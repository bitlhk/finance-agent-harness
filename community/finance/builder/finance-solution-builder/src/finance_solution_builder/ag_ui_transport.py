"""AG-UI transport adapter; identity, history and tools remain owned by the Host."""
from __future__ import annotations

import re
import uuid

from ag_ui.core import (
    CustomEvent, RunAgentInput, RunStartedEvent, RunFinishedEvent, RunErrorEvent,
    StepStartedEvent, StepFinishedEvent, TextMessageStartEvent,
    TextMessageContentEvent, TextMessageEndEvent,
)
from ag_ui.encoder import EventEncoder

from .workbench_contracts import ApiError


def parse_input(body):
    allowed = {"threadId", "runId", "state", "messages", "tools", "context", "forwardedProps"}
    try:
        if not isinstance(body, dict) or set(body) != allowed:
            raise ValueError()
        request = RunAgentInput.model_validate(body)
        if (body["state"] not in ({}, None) or body["tools"] != [] or body["context"] != []
                or len(body["messages"]) != 1
                or set(body["messages"][0]) != {"id", "role", "content"}
                or body["messages"][0]["role"] != "user"
                or not isinstance(body["messages"][0]["content"], str)
                or not 1 <= len(body["messages"][0]["content"].strip()) <= 4000
                or not isinstance(body["forwardedProps"], dict)
                or set(body["forwardedProps"]) != {"instanceId"}):
            raise ValueError()
        for value in (request.thread_id, request.run_id, request.messages[0].id,
                      body["forwardedProps"]["instanceId"]):
            if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
                raise ValueError()
        return request
    except (ValueError, TypeError, KeyError):
        raise ApiError("AG_UI_INPUT_REJECTED", 400) from None


def response_text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("output", "answer", "content", "response", "result"):
            text = response_text(value.get(key))
            if text:
                return text
    return ""


def _text_chunks(value: str, size: int = 120):
    return [value[offset:offset + size] for offset in range(0, len(value), size) if value[offset:offset + size]]


def _review_team():
    return {"members": [{"id": "case-orchestrator", "name": "审核案件编排器", "status": "COMPLETED"}]}


def stream_run(api, build_id, owner, request, send):
    """Real lifecycle SSE, with a buffered final answer (not token streaming)."""
    route = "/api/v1/builds/" + build_id
    identity = {"instanceId": request.forwarded_props["instanceId"], "sessionId": request.thread_id}
    encoder = EventEncoder()

    def emit(event):
        send(encoder.encode(event).encode("utf-8"))

    def authorize():
        code, envelope = api.post(route + "/authorization", identity, owner)
        if code != 200:
            raise ApiError(envelope.get("error", {}).get("code", "AUTHORIZATION_REJECTED"), code)
        return envelope

    # No stream headers or lifecycle success before owner/instance/session validation.
    authorize()
    emit(RunStartedEvent(thread_id=request.thread_id, run_id=request.run_id))
    emit(StepStartedEvent(step_name="governed_execution"))
    try:
        code, envelope = api.post(route + "/chat", {**identity, "query": request.messages[0].content}, owner)
        if code != 200:
            raise ApiError(envelope.get("error", {}).get("code", "MODEL_OR_RUNTIME_FAILED"), code)
        authorize()
        result = envelope["result"]
        text = response_text(result.get("response"))
        if not text:
            raise ApiError("EMPTY_MODEL_RESPONSE", 502)
        message_id = "message-" + uuid.uuid4().hex
        events = [StepFinishedEvent(step_name="governed_execution"),
                  CustomEvent(name="finance.reviewTeam", value=_review_team()),
                  TextMessageStartEvent(message_id=message_id, role="assistant"),
                  *(TextMessageContentEvent(message_id=message_id, delta=chunk) for chunk in _text_chunks(text)),
                  TextMessageEndEvent(message_id=message_id),
                  CustomEvent(name="finance.evidence", value={**envelope, "runId": request.run_id}),
                  RunFinishedEvent(thread_id=request.thread_id, run_id=request.run_id)]
        # Serialize against stop/restart and prevent an old response reaching a new instance.
        with api.jobs.lock:
            current = api.inspect(build_id, owner, expected_instance=identity["instanceId"], expected_status="RUNNING")
            if current["instance"]["sessionId"] != request.thread_id:
                raise ApiError("INSTANCE_CHANGED", 409)
            send(b"".join(encoder.encode(event).encode("utf-8") for event in events))
    except (BrokenPipeError, ConnectionResetError):
        # A disconnected read-only run is not replayed automatically.
        raise
    except Exception as error:
        code = error.code if isinstance(error, ApiError) else "MODEL_OR_RUNTIME_FAILED"
        emit(CustomEvent(name="finance.clearConversation", value={"clearConversation": True}))
        emit(RunErrorEvent(message=code, code=code))
