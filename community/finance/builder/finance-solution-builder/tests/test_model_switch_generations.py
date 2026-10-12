from concurrent.futures import ThreadPoolExecutor
import threading

import pytest

from finance_solution_builder.workbench import Jobs


class Child:
    def poll(self): return None


@pytest.fixture
def running(tmp_path):
    jobs = Jobs(tmp_path, tmp_path)
    jobs.jobs["agent-test"] = dict(id="agent-test", owner="owner", status="RUNNING", instanceId="instance-a", port=12345,
                                   modelSelection={"selectedModelRefs": ["model-a", "model-b"]})
    jobs.processes["agent-test"] = Child()
    jobs.tokens["agent-test"] = "old-token"
    jobs.sessions["agent-test"] = "session-a"
    yield jobs
    jobs.processes.clear()
    jobs.close()


def test_old_chat_cannot_publish_into_switched_model_session(running, monkeypatch):
    arrived, release = threading.Event(), threading.Event()
    def request(_id, path, _body, **_kwargs):
        if path.endswith("/chat"):
            arrived.set()
            assert release.wait(5)
            return 200, {"response": "private old-model result"}
        return 200, {"sessionId": "session-b", "modelRef": "model-b"}
    monkeypatch.setattr(running, "_request", request)
    with ThreadPoolExecutor(max_workers=1) as pool:
        old = pool.submit(running.talk, "agent-test", "owner", "chat", {"query": "old"}, expected_session="session-a")
        try:
            assert arrived.wait(5)
            code, _ = running.talk("agent-test", "owner", "switch-model", {"modelRef": "model-b"}, expected_session="session-a")
            assert code == 200 and running.sessions["agent-test"] == "session-b"
        finally:
            release.set()
        code, result = old.result(timeout=5)
    assert code == 409 and "private" not in str(result)
    assert running.sessions["agent-test"] == "session-b"


@pytest.mark.parametrize("failed", [True, False])
def test_old_switch_completion_cannot_overwrite_or_kill_restarted_instance(running, monkeypatch, failed):
    arrived, release = threading.Event(), threading.Event()
    def request(*_args, **_kwargs):
        arrived.set()
        assert release.wait(5)
        if failed:
            raise ConnectionResetError()
        return 200, {"sessionId": "stale-session", "modelRef": "model-b"}
    monkeypatch.setattr(running, "_request", request)
    monkeypatch.setattr("finance_solution_builder.workbench.stop_owned_process", lambda *_: pytest.fail("replacement killed"))
    replacement = Child()
    with ThreadPoolExecutor(max_workers=1) as pool:
        old = pool.submit(running.talk, "agent-test", "owner", "switch-model", {"modelRef": "model-b"}, expected_session="session-a")
        try:
            assert arrived.wait(5)
            with running.lock:
                running.processes["agent-test"] = replacement
                running.tokens["agent-test"] = "replacement"
                running.sessions["agent-test"] = "replacement-session"
                running.jobs["agent-test"]["instanceId"] = "replacement"
        finally:
            release.set()
        code, result = old.result(timeout=5)
    assert code == 409 and result["error"] == "INSTANCE_CHANGED"
    assert running.processes["agent-test"] is replacement
    assert running.sessions["agent-test"] == "replacement-session"
