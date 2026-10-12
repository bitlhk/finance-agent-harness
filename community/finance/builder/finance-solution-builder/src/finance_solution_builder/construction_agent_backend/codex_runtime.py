"""Private Codex App Server JSON-RPC runtime used by the construction adapter."""

from __future__ import annotations

import json
import queue
import re
import subprocess
import tempfile
import threading
import time
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol


CODEX_VERSION = "0.153.4"
_APPROVAL_METHODS = frozenset(
    {
        "item/commandExecution/requestApproval",
        "item/fileChange/requestApproval",
        "item/permissions/requestApproval",
    }
)
_V2_SCHEMA_MARKERS = (
    '"thread/start"',
    '"turn/start"',
    '"turn/interrupt"',
    '"turn/completed"',
    '"turn/diff/updated"',
)
_SERVER_SCHEMA_MARKERS = (
    '"item/commandExecution/requestApproval"',
    '"item/fileChange/requestApproval"',
    '"item/permissions/requestApproval"',
)


class CodexRuntimeError(RuntimeError):
    """Raised when the pinned Codex runtime cannot satisfy its private contract."""


@dataclass(frozen=True)
class CodexProcessConfig:
    """Process-local configuration; it is never exposed through the public SPI."""

    executable: str
    expected_version: str
    workspace: Path
    model: str | None
    startup_timeout_seconds: float
    request_timeout_seconds: float


class CodexRuntime(Protocol):
    """Small seam implemented by the real process client and deterministic doubles."""

    def start(self) -> None: ...
    def start_turn(self, prompt: str) -> tuple[str, str]: ...
    def stream_messages(self, thread_id: str, turn_id: str) -> Iterable[Mapping[str, Any]]: ...
    def reply_approval(self, message: Mapping[str, Any], approve: bool) -> None: ...
    def interrupt(self, thread_id: str, turn_id: str) -> None: ...
    def alive(self) -> bool: ...
    def stop(self) -> None: ...


class StdioCodexRuntime:
    """One managed, stdio-only Codex App Server process for one Builder task."""

    def __init__(self, config: CodexProcessConfig) -> None:
        self._config = config
        self._process: subprocess.Popen[str] | None = None
        self._next_id = 1
        self._condition = threading.Condition()
        self._responses: dict[int | str, Mapping[str, Any]] = {}
        self._messages: queue.Queue[Mapping[str, Any] | None] = queue.Queue()
        self._write_lock = threading.Lock()
        self._reader: threading.Thread | None = None

    def _command(
        self, *args: str, timeout: float | None = None
    ) -> subprocess.CompletedProcess[str]:
        try:
            return subprocess.run(
                (self._config.executable, *args),
                cwd=self._config.workspace,
                capture_output=True,
                check=False,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout or self._config.startup_timeout_seconds,
            )
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise CodexRuntimeError("construction.codex.preflight_failed") from exc

    def _verify_version_and_schema(self) -> None:
        version = self._command("--version")
        match = re.search(r"codex-cli\s+([^\s]+)", version.stdout)
        if (
            version.returncode != 0
            or match is None
            or match.group(1) != self._config.expected_version
        ):
            raise CodexRuntimeError("construction.codex.version_mismatch")
        with tempfile.TemporaryDirectory(prefix="finance-builder-codex-schema-") as raw:
            target = Path(raw)
            generated = self._command("app-server", "generate-json-schema", "--out", str(target))
            v2_path = target / "codex_app_server_protocol.v2.schemas.json"
            server_path = target / "codex_app_server_protocol.schemas.json"
            if generated.returncode != 0 or not v2_path.is_file() or not server_path.is_file():
                raise CodexRuntimeError("construction.codex.schema_unavailable")
            v2_text = v2_path.read_text(encoding="utf-8")
            server_text = server_path.read_text(encoding="utf-8")
            if any(marker not in v2_text for marker in _V2_SCHEMA_MARKERS) or any(
                marker not in server_text for marker in _SERVER_SCHEMA_MARKERS
            ):
                raise CodexRuntimeError("construction.codex.schema_incompatible")

    def start(self) -> None:
        self._verify_version_and_schema()
        try:
            self._process = subprocess.Popen(
                (self._config.executable, "app-server", "--stdio"),
                cwd=self._config.workspace,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                encoding="utf-8",
                errors="replace",
                bufsize=1,
            )
        except OSError as exc:
            raise CodexRuntimeError("construction.codex.startup_failed") from exc
        self._reader = threading.Thread(
            target=self._read_loop,
            daemon=True,
            name="finance-builder-codex-app-server",
        )
        self._reader.start()
        initialized = self._request(
            "initialize",
            {
                "clientInfo": {
                    "name": "finance-solution-builder",
                    "title": "Finance Solution Builder",
                    "version": "0.2.0",
                }
            },
        )
        if not isinstance(initialized.get("userAgent"), str):
            raise CodexRuntimeError("construction.codex.initialize_invalid")
        self._notify("initialized", {})
        account = self._request("account/read", {"refreshToken": False})
        if account.get("requiresOpenaiAuth") is True and not isinstance(
            account.get("account"), Mapping
        ):
            raise CodexRuntimeError("construction.codex.account_unavailable")
        models = self._request("model/list", {"limit": 100, "includeHidden": False})
        available = {
            item.get("id")
            for item in models.get("data", ())
            if isinstance(item, Mapping) and isinstance(item.get("id"), str)
        }
        if self._config.model is not None and self._config.model not in available:
            raise CodexRuntimeError("construction.codex.model_unavailable")
        if self._config.model is None and not any(
            isinstance(item, Mapping) and item.get("isDefault") is True
            for item in models.get("data", ())
        ):
            raise CodexRuntimeError("construction.codex.default_model_unavailable")

    def start_turn(self, prompt: str) -> tuple[str, str]:
        thread_params: dict[str, Any] = {
            "cwd": str(self._config.workspace),
            "approvalPolicy": "on-request",
            "approvalsReviewer": "user",
            "sandbox": "workspace-write",
            "ephemeral": True,
            "developerInstructions": (
                "Work only in the current Candidate workspace. Do not access external "
                "systems or broaden write scope without an explicit approval request."
            ),
        }
        if self._config.model is not None:
            thread_params["model"] = self._config.model
        thread_result = self._request("thread/start", thread_params)
        thread = thread_result.get("thread")
        thread_id = thread.get("id") if isinstance(thread, Mapping) else None
        if not isinstance(thread_id, str) or not thread_id:
            raise CodexRuntimeError("construction.codex.thread_invalid")
        turn_result = self._request(
            "turn/start",
            {
                "threadId": thread_id,
                "input": [{"type": "text", "text": prompt}],
                "cwd": str(self._config.workspace),
            },
        )
        turn = turn_result.get("turn")
        turn_id = turn.get("id") if isinstance(turn, Mapping) else None
        if not isinstance(turn_id, str) or not turn_id:
            raise CodexRuntimeError("construction.codex.turn_invalid")
        return thread_id, turn_id

    def stream_messages(self, thread_id: str, turn_id: str) -> Iterable[Mapping[str, Any]]:
        while True:
            try:
                message = self._messages.get(timeout=self._config.request_timeout_seconds)
            except queue.Empty as exc:
                raise CodexRuntimeError("construction.codex.event_timeout") from exc
            if message is None:
                raise CodexRuntimeError("construction.codex.event_stream_closed")
            params = message.get("params")
            values = params if isinstance(params, Mapping) else {}
            method = message.get("method")
            if not isinstance(method, str):
                continue
            if method in _APPROVAL_METHODS and (
                values.get("threadId") != thread_id or values.get("turnId") != turn_id
            ):
                raise CodexRuntimeError("construction.codex.approval_identity_invalid")
            if values.get("threadId") not in {None, thread_id}:
                continue
            if values.get("turnId") not in {None, turn_id}:
                continue
            yield message
            if method in _APPROVAL_METHODS or method == "turn/completed":
                return

    def reply_approval(self, message: Mapping[str, Any], approve: bool) -> None:
        request_id = message.get("id")
        method = message.get("method")
        params = message.get("params")
        if not isinstance(request_id, (int, str)) or not isinstance(method, str):
            raise CodexRuntimeError("construction.codex.approval_invalid")
        values = params if isinstance(params, Mapping) else {}
        if method in {
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
        }:
            result: Mapping[str, Any] = {"decision": "accept" if approve else "cancel"}
        elif method == "item/permissions/requestApproval":
            permissions = values.get("permissions")
            if approve and isinstance(permissions, Mapping):
                result = {"permissions": dict(permissions), "scope": "turn"}
            else:
                result = {"permissions": {}, "scope": "turn"}
        else:
            raise CodexRuntimeError("construction.codex.approval_method_unknown")
        self._respond(request_id, result=result)

    def interrupt(self, thread_id: str, turn_id: str) -> None:
        self._request("turn/interrupt", {"threadId": thread_id, "turnId": turn_id})

    def alive(self) -> bool:
        return self._process is not None and self._process.poll() is None

    def stop(self) -> None:
        process = self._process
        self._process = None
        if process is None:
            return
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        self._messages.put(None)

    def _read_loop(self) -> None:
        assert self._process is not None and self._process.stdout is not None
        try:
            for line in self._process.stdout:
                try:
                    message = json.loads(line)
                except (json.JSONDecodeError, TypeError):
                    continue
                if not isinstance(message, Mapping):
                    continue
                response_id = message.get("id")
                if isinstance(response_id, (int, str)) and "method" not in message:
                    with self._condition:
                        self._responses[response_id] = message
                        self._condition.notify_all()
                elif isinstance(message.get("method"), str):
                    self._messages.put(message)
        finally:
            with self._condition:
                self._condition.notify_all()
            self._messages.put(None)

    def _write(self, message: Mapping[str, Any]) -> None:
        process = self._process
        if process is None or process.poll() is not None or process.stdin is None:
            raise CodexRuntimeError("construction.codex.process_unavailable")
        payload = json.dumps(message, ensure_ascii=True, separators=(",", ":"))
        with self._write_lock:
            try:
                process.stdin.write(payload + "\n")
                process.stdin.flush()
            except OSError as exc:
                raise CodexRuntimeError("construction.codex.write_failed") from exc

    def _request(self, method: str, params: Mapping[str, Any]) -> Mapping[str, Any]:
        with self._condition:
            request_id = self._next_id
            self._next_id += 1
        self._write({"id": request_id, "method": method, "params": dict(params)})
        deadline = time.monotonic() + self._config.request_timeout_seconds
        with self._condition:
            while request_id not in self._responses:
                process = self._process
                if process is None or process.poll() is not None:
                    raise CodexRuntimeError("construction.codex.process_exited")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise CodexRuntimeError("construction.codex.request_timeout")
                self._condition.wait(timeout=remaining)
            response = self._responses.pop(request_id)
        if "error" in response:
            raise CodexRuntimeError(f"construction.codex.rpc_failed:{method}")
        result = response.get("result")
        if not isinstance(result, Mapping):
            raise CodexRuntimeError(f"construction.codex.rpc_invalid:{method}")
        return result

    def _notify(self, method: str, params: Mapping[str, Any]) -> None:
        self._write({"method": method, "params": dict(params)})

    def _respond(self, request_id: int | str, *, result: Mapping[str, Any]) -> None:
        self._write({"id": request_id, "result": dict(result)})
