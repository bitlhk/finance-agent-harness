"""Deterministic Codex App Server double for adapter tests."""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from pathlib import Path
from threading import Event
from typing import Any

from finance_solution_builder.construction_agent_backend.codex_runtime import CodexProcessConfig


@dataclass
class CodexProtocolDouble:
    config: CodexProcessConfig
    approval_method: str | None = "item/commandExecution/requestApproval"
    fail_start: bool = False
    turn_status: str = "completed"
    changed_path: str = "candidate/src/generated.py"
    diff_path: str | None = None
    include_usage: bool = True
    started: bool = False
    stopped: int = 0
    interrupted: list[tuple[str, str]] = field(default_factory=list)
    replies: list[tuple[int, bool]] = field(default_factory=list)
    prompt: str = ""
    stream_count: int = 0
    approval_return_gate: Event | None = None
    terminal_error: bool = False
    approval_params: Mapping[str, Any] | None = None

    def start(self) -> None:
        if self.fail_start:
            raise RuntimeError("private startup failure")
        self.started = True

    def start_turn(self, prompt: str) -> tuple[str, str]:
        self.prompt = prompt
        if self.approval_method is None:
            self._mutate()
        return "thread-1", "turn-1"

    def stream_messages(self, thread_id: str, turn_id: str) -> Iterable[Mapping[str, Any]]:
        assert (thread_id, turn_id) == ("thread-1", "turn-1")
        self.stream_count += 1
        if self.approval_method is not None and self.stream_count == 1:
            params: dict[str, Any] = {
                "threadId": thread_id,
                "turnId": turn_id,
                "itemId": "item-approval",
                "startedAtMs": 1,
            }
            if self.approval_method == "item/permissions/requestApproval":
                params.update(
                    {
                        "cwd": str(self.config.workspace),
                        "permissions": {"network": {"enabled": True}},
                    }
                )
            if self.approval_params is not None:
                params.update(self.approval_params)
            yield {"id": 41, "method": self.approval_method, "params": params}
            if self.approval_return_gate is not None:
                self.approval_return_gate.wait(timeout=2)
            return
        if self.include_usage:
            yield {
                "method": "thread/tokenUsage/updated",
                "params": {
                    "threadId": thread_id,
                    "turnId": turn_id,
                    "tokenUsage": {"total": {"inputTokens": 10, "outputTokens": 5}},
                },
            }
        if self.terminal_error:
            yield {
                "method": "error",
                "params": {
                    "threadId": thread_id,
                    "turnId": turn_id,
                    "willRetry": False,
                    "error": {"message": "private provider error"},
                },
            }
            return
        path = self.diff_path or self.changed_path
        yield {
            "method": "turn/diff/updated",
            "params": {
                "threadId": thread_id,
                "turnId": turn_id,
                "diff": f"--- a/{path}\n+++ b/{path}\n@@ -0,0 +1 @@\n+generated\n",
            },
        }
        yield {
            "method": "item/completed",
            "params": {
                "threadId": thread_id,
                "turnId": turn_id,
                "completedAtMs": 2,
                "item": {
                    "id": "item-file",
                    "type": "fileChange",
                    "status": "completed",
                    "changes": [],
                },
            },
        }
        yield {
            "method": "turn/completed",
            "params": {
                "threadId": thread_id,
                "turn": {"id": turn_id, "items": [], "status": self.turn_status},
            },
        }

    def reply_approval(self, message: Mapping[str, Any], approve: bool) -> None:
        assert message.get("id") == 41
        self.replies.append((41, approve))
        if approve:
            self._mutate()

    def interrupt(self, thread_id: str, turn_id: str) -> None:
        self.interrupted.append((thread_id, turn_id))

    def alive(self) -> bool:
        return self.started and self.stopped == 0

    def stop(self) -> None:
        self.stopped += 1

    def _mutate(self) -> None:
        path = self.config.workspace / Path(self.changed_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("generated = True\n", encoding="utf-8")
