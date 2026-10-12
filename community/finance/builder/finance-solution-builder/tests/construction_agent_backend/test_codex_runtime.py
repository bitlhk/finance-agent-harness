from __future__ import annotations

import subprocess

import pytest

from finance_solution_builder.construction_agent_backend.codex_runtime import (
    CODEX_VERSION,
    CodexProcessConfig,
    CodexRuntimeError,
    StdioCodexRuntime,
    _SERVER_SCHEMA_MARKERS,
    _V2_SCHEMA_MARKERS,
)


def _config(tmp_path, **values):
    defaults = {
        "executable": "codex",
        "expected_version": CODEX_VERSION,
        "workspace": tmp_path,
        "model": "gpt-test",
        "startup_timeout_seconds": 1.0,
        "request_timeout_seconds": 0.01,
    }
    defaults.update(values)
    return CodexProcessConfig(**defaults)


def test_preflight_requires_exact_version_and_protocol_schema(tmp_path, monkeypatch) -> None:
    runtime = StdioCodexRuntime(_config(tmp_path))

    def command(*args, timeout=None):
        if args == ("--version",):
            return subprocess.CompletedProcess(args, 0, f"codex-cli {CODEX_VERSION}\n", "")
        target = tmp_path.__class__(args[-1])
        (target / "codex_app_server_protocol.v2.schemas.json").write_text(
            "\n".join(_V2_SCHEMA_MARKERS), encoding="utf-8"
        )
        (target / "codex_app_server_protocol.schemas.json").write_text(
            "\n".join(_SERVER_SCHEMA_MARKERS), encoding="utf-8"
        )
        return subprocess.CompletedProcess(args, 0, "", "")

    monkeypatch.setattr(runtime, "_command", command)
    runtime._verify_version_and_schema()


def test_preflight_rejects_version_or_schema_drift(tmp_path, monkeypatch) -> None:
    runtime = StdioCodexRuntime(_config(tmp_path))
    monkeypatch.setattr(
        runtime,
        "_command",
        lambda *args, **_: subprocess.CompletedProcess(args, 0, "codex-cli 0.1.0\n", ""),
    )
    with pytest.raises(CodexRuntimeError, match="version_mismatch"):
        runtime._verify_version_and_schema()


def test_stream_ignores_another_turn_and_stops_at_approval(tmp_path) -> None:
    runtime = StdioCodexRuntime(_config(tmp_path))
    runtime._messages.put(
        {"method": "turn/diff/updated", "params": {"threadId": "other", "turnId": "x"}}
    )
    approval = {
        "id": 7,
        "method": "item/commandExecution/requestApproval",
        "params": {"threadId": "thread-1", "turnId": "turn-1"},
    }
    runtime._messages.put(approval)
    assert list(runtime.stream_messages("thread-1", "turn-1")) == [approval]


@pytest.mark.parametrize(
    "params",
    (
        {"turnId": "turn-1"},
        {"threadId": "thread-1"},
        {"threadId": "thread-1", "turnId": "another-turn"},
    ),
)
def test_stream_rejects_approval_without_exact_turn_identity(tmp_path, params) -> None:
    runtime = StdioCodexRuntime(_config(tmp_path))
    runtime._messages.put(
        {
            "id": "approval-7",
            "method": "item/fileChange/requestApproval",
            "params": params,
        }
    )
    with pytest.raises(CodexRuntimeError, match="approval_identity_invalid"):
        list(runtime.stream_messages("thread-1", "turn-1"))


def test_stream_eof_and_timeout_fail_closed(tmp_path) -> None:
    runtime = StdioCodexRuntime(_config(tmp_path))
    runtime._messages.put(None)
    with pytest.raises(CodexRuntimeError, match="event_stream_closed"):
        list(runtime.stream_messages("thread-1", "turn-1"))
    with pytest.raises(CodexRuntimeError, match="event_timeout"):
        list(runtime.stream_messages("thread-1", "turn-1"))


@pytest.mark.parametrize(
    ("method", "approve", "expected"),
    (
        ("item/commandExecution/requestApproval", True, {"decision": "accept"}),
        ("item/fileChange/requestApproval", False, {"decision": "cancel"}),
        (
            "item/permissions/requestApproval",
            True,
            {"permissions": {"network": {"enabled": True}}, "scope": "turn"},
        ),
        ("item/permissions/requestApproval", False, {"permissions": {}, "scope": "turn"}),
    ),
)
def test_approval_reply_is_one_request_or_turn_only(
    tmp_path, monkeypatch, method, approve, expected
) -> None:
    runtime = StdioCodexRuntime(_config(tmp_path))
    captured = []
    monkeypatch.setattr(
        runtime,
        "_respond",
        lambda request_id, *, result: captured.append((request_id, result)),
    )
    runtime.reply_approval(
        {
            "id": 7,
            "method": method,
            "params": {"permissions": {"network": {"enabled": True}}},
        },
        approve,
    )
    assert captured == [(7, expected)]
    assert "session" not in str(captured)


def test_approval_reply_accepts_string_request_id(tmp_path, monkeypatch) -> None:
    runtime = StdioCodexRuntime(_config(tmp_path))
    captured = []
    monkeypatch.setattr(
        runtime,
        "_respond",
        lambda request_id, *, result: captured.append((request_id, result)),
    )
    runtime.reply_approval(
        {
            "id": "approval-7",
            "method": "item/commandExecution/requestApproval",
            "params": {},
        },
        True,
    )
    assert captured == [("approval-7", {"decision": "accept"})]


def test_unknown_approval_method_is_rejected(tmp_path) -> None:
    runtime = StdioCodexRuntime(_config(tmp_path))
    with pytest.raises(CodexRuntimeError, match="approval_method_unknown"):
        runtime.reply_approval({"id": 1, "method": "unknown", "params": {}}, True)
