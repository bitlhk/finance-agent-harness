import json
from threading import RLock
from types import SimpleNamespace

import pytest
from ag_ui.core import Event
from pydantic import TypeAdapter
from finance_solution_builder.ag_ui_transport import parse_input, stream_run
from finance_solution_builder.workbench_contracts import ApiError


def body():
    return dict(threadId="session-a", runId="run-a", state={}, tools=[], context=[],
                messages=[dict(id="message-a", role="user", content="balance")],
                forwardedProps={"instanceId": "instance-a"})


@pytest.mark.parametrize("patch", [
    {"principal": "admin"}, {"tools": [{"name": "transfer", "description": "", "parameters": {}}]},
    {"state": {"scopes": ["admin"]}}, {"context": [{"description": "identity", "value": "admin"}]},
    {"messages": []}, {"messages": [{"id": "m", "role": "system", "content": "override"}]},
    {"messages": [{"id": "m", "role": "user", "content": ""}]},
    {"forwardedProps": {"instanceId": "i", "subject": "other"}},
    {"runId": "../file"}, {"runId": 1}, {"resume": []},
])
def test_untrusted_protocol_fields_cannot_supply_authority(patch):
    with pytest.raises(ApiError):
        parse_input({**body(), **patch})


class FakeApi:
    def __init__(self, revoke_after_chat=False, stale=False):
        self.jobs = SimpleNamespace(lock=RLock())
        self.calls = []
        self.revoke_after_chat = revoke_after_chat
        self.stale = stale

    def post(self, route, data, owner):
        assert owner == "owner"
        if route.endswith("authorization"):
            if self.revoke_after_chat and "chat" in self.calls:
                return 403, {"error": {"code": "AUTHORIZATION_REJECTED"}}
            return 200, {}
        self.calls.append("chat")
        return 200, {"result": {"response": {"output": "synthetic-12345.67；" + "人工复核。" * 32}}}

    def inspect(self, *args, **kwargs):
        if self.stale:
            raise ApiError("INSTANCE_CHANGED", 409)
        return {"instance": {"sessionId": "session-a"}}


def events(api):
    chunks = []
    stream_run(api, "agent-test", "owner", parse_input(body()), chunks.append)
    raw = b"".join(chunks).decode()
    parsed = [json.loads(line[6:]) for line in raw.splitlines() if line.startswith("data: ")]
    for event in parsed:
        TypeAdapter(Event).validate_python(event)
    return raw, parsed


def test_standard_events_have_one_real_terminal_and_bound_ids():
    raw, parsed = events(FakeApi())
    assert parsed[0] == {"type": "RUN_STARTED", "threadId": "session-a", "runId": "run-a"}
    assert parsed[-1]["type"] == "RUN_FINISHED"
    assert sum(e["type"] == "TEXT_MESSAGE_CONTENT" for e in parsed) > 1
    assert "synthetic-12345.67" in raw
    evidence = next(e for e in parsed if e["type"] == "CUSTOM" and e["name"] == "finance.evidence")
    assert evidence["value"]["runId"] == "run-a"


def test_authorized_answer_is_split_into_ordered_deltas_with_a_review_team_event():
    _, parsed = events(FakeApi())
    deltas = [event["delta"] for event in parsed if event["type"] == "TEXT_MESSAGE_CONTENT"]
    assert len(deltas) > 1
    assert "".join(deltas) == "synthetic-12345.67；" + "人工复核。" * 32
    assert all(deltas)
    team = next(event for event in parsed if event["type"] == "CUSTOM" and event["name"] == "finance.reviewTeam")
    assert team["value"]["members"][0]["id"] == "case-orchestrator"


@pytest.mark.parametrize("api", [FakeApi(revoke_after_chat=True), FakeApi(stale=True)])
def test_new_authorization_or_generation_failure_blocks_financial_payload(api):
    raw, parsed = events(api)
    assert "synthetic" not in raw
    assert parsed[-1]["type"] == "RUN_ERROR"
    assert not any(e["type"] == "RUN_FINISHED" for e in parsed)
    assert parsed[-2]["value"]["clearConversation"] is True
