import json
from threading import RLock

from finance_solution_builder.experience import Experience, _subagents


def _decode(chunks):
    return [json.loads(line[6:]) for line in b"".join(chunks).decode().splitlines() if line.startswith("data: ")]


def test_smart_audit_stream_forwards_candidate_events_without_batching():
    experience = object.__new__(Experience)
    experience.lock = RLock()
    entry = {
        "session": "session-a",
        "generation": 0,
        "capabilities": [
            "smartaudit.personal.borrower-qualification.query",
            "smartaudit.personal.income-verification.query",
            "smartaudit.personal.asset-liability.query",
            "smartaudit.personal.repayment-capacity.query",
        ],
    }
    experience.current = lambda build, token: entry

    events = [
        {"type": "RUN_STARTED", "threadId": "session-a", "runId": "run-a"},
        {"type": "CUSTOM", "name": "finance.subagents", "value": {"members": [{"id": "case-orchestrator", "status": "RUNNING"}]}},
        {"type": "TEXT_MESSAGE_START", "messageId": "message-a", "role": "assistant"},
        {"type": "TEXT_MESSAGE_CONTENT", "messageId": "message-a", "delta": "审核工作"},
        {"type": "TEXT_MESSAGE_CONTENT", "messageId": "message-a", "delta": "底稿。"},
        {"type": "TEXT_MESSAGE_END", "messageId": "message-a"},
        {"type": "RUN_FINISHED", "threadId": "session-a", "runId": "run-a"},
    ]
    experience.call = lambda build, token, action, body: {} if action == "status" else (_ for _ in ()).throw(AssertionError(action))
    experience._stream_candidate_agui = lambda build, value, body: iter(events)
    chunks = []
    experience.stream("agent-a", "ticket-a", {"query": "材料包", "runId": "run-a"}, chunks.append)
    assert _decode(chunks) == events


def test_smart_audit_subagents_include_real_completion_details():
    completed = _subagents([
        "smartaudit.personal.borrower-qualification.query",
        "smartaudit.personal.income-verification.query",
        "smartaudit.personal.asset-liability.query",
        "smartaudit.personal.repayment-capacity.query",
    ], {"executedCapabilities": [
        {"capabilityId": "smartaudit.personal.borrower-qualification.query", "resultStatus": "COMPLETED"},
        {"capabilityId": "smartaudit.personal.income-verification.query", "resultStatus": "COMPLETED"},
        {"capabilityId": "smartaudit.personal.asset-liability.query", "resultStatus": "COMPLETED"},
        {"capabilityId": "smartaudit.personal.repayment-capacity.query", "resultStatus": "COMPLETED"},
    ], "auditTimings": {"smartaudit.personal.borrower-qualification.query": 84}}, "COMPLETED")["members"]
    assert completed[0]["status"] == "COMPLETED"
    assert completed[0]["summary"] == "已汇总 4 项受控核验回执，待人工复核交接"
    assert completed[1]["status"] == "COMPLETED"
    assert completed[1]["materials"] == 4
    assert completed[1]["rules"] == 2
    assert completed[1]["scope"] == "核验借款人身份、家庭关系与申请主体信息"
    assert "身份证" in completed[1]["evidence"]
    assert completed[1]["elapsedMs"] == 84
    assert all(member["status"] == "COMPLETED" for member in completed[2:])
