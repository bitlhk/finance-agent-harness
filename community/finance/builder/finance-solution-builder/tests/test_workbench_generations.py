"""Paused completions must never mutate a replacement process or session."""
import json
from pathlib import Path
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from finance_solution_builder.service import digest, write_json
from finance_solution_builder.workbench import ENV_KEYS, Jobs


PROJECT = Path(__file__).resolve().parents[1]


@pytest.fixture
def rig(tmp_path):
    jobs = Jobs(tmp_path, tmp_path)
    children = []

    def install():
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(120)"])
        children.append(child)
        with jobs.lock:
            jobs.jobs.setdefault("agent-race", {"id": "agent-race", "owner": "owner"}).update(
                status="RUNNING", instanceId=str(child.pid), port=1, error=None)
            jobs.processes["agent-race"] = child
            jobs.tokens["agent-race"] = str(child.pid)
            jobs.sessions["agent-race"] = "session-" + str(child.pid)
        return child

    yield jobs, install
    jobs.close()
    for child in children:
        if child.poll() is None:
            child.terminate()
        child.wait(timeout=5)


@pytest.mark.parametrize("action,completion", [
    ("chat", "error"), ("chat", "success"), ("chat", "clear"),
    ("session", "success"), ("session", "error"), ("revoke", "clear"),
    ("tasks", "success"), ("task-confirm", "error"), ("task-resume", "clear"),
])
def test_old_completion_cannot_change_replacement(rig, monkeypatch, action, completion):
    jobs, install = rig
    install()
    if action == "session":
        jobs.sessions.clear()
    ready, release = threading.Event(), threading.Event()

    def delayed(*args, **kwargs):
        ready.set()
        assert release.wait(10)
        if completion == "error":
            raise ConnectionResetError("old connection")
        return 200, {"sessionId": "stale-session", "response": "stale-private-data", "clearConversation": completion == "clear"}

    monkeypatch.setattr(jobs, "_request", delayed)
    with ThreadPoolExecutor(1) as pool:
        body = {"query": "hello"} if action == "chat" else {"handle": "test-handle", "nonce": "test-nonce"} if action == "task-confirm" else {"handle": "test-handle"} if action == "task-resume" else {}
        future = pool.submit(jobs.talk, "agent-race", "owner", action, body)
        try:
            assert ready.wait(5)
            jobs.stop("agent-race", "owner")
            new = install()
        finally:
            release.set()
        code, result = future.result(timeout=10)
    assert code == 409 and result["error"] == "INSTANCE_CHANGED"
    assert "stale" not in json.dumps(result)
    assert new.poll() is None
    assert jobs.processes["agent-race"] is new
    assert jobs.sessions["agent-race"] == "session-" + str(new.pid)
    assert jobs.jobs["agent-race"]["status"] == "RUNNING"


@pytest.mark.parametrize("completion", ["success", "error"])
def test_start_completion_cannot_replace_new_instance(rig, monkeypatch, completion):
    jobs, install = rig
    jobs.jobs["agent-race"] = {"id": "agent-race", "owner": "owner", "status": "BUILT"}
    candidate = jobs.path("agent-race") / "candidate"
    (candidate / "target").mkdir(parents=True)
    (candidate / "target/service.jar").write_bytes(b"test-only")
    manifest = {"files": {}, "runtime": {"language": "java"}}
    write_json(candidate / "service-lock.json", manifest)
    write_json(candidate.parent / "build-report.json", {"lockSha256": digest(candidate / "service-lock.json")})
    for key in ENV_KEYS:
        monkeypatch.setenv(key, "test-only")
    ready, release = threading.Event(), threading.Event()

    def spawn(*args, **kwargs):
        child = install()
        write_json(candidate / "instance.json", {"pid": child.pid, "buildId": "agent-race", "port": 1, "instanceId": "old"})
        return child

    def health(*args, **kwargs):
        ready.set()
        assert release.wait(10)
        if completion == "error":
            raise ConnectionResetError()
        return 200, {"artifactLock": manifest, "instanceId": "old"}

    monkeypatch.setattr("finance_solution_builder.workbench.spawn_process", spawn)
    monkeypatch.setattr(jobs, "_request", health)
    with ThreadPoolExecutor(1) as pool:
        future = pool.submit(jobs.start, "agent-race", "owner")
        try:
            assert ready.wait(5)
            jobs.stop("agent-race", "owner")
            new = install()
        finally:
            release.set()
        with pytest.raises(RuntimeError, match="INSTANCE_CHANGED"):
            future.result(timeout=10)
    assert new.poll() is None and jobs.processes["agent-race"] is new
    assert jobs.jobs["agent-race"]["status"] == "RUNNING"
    assert jobs.jobs["agent-race"]["instanceId"] == str(new.pid)
    assert jobs.sessions["agent-race"] == "session-" + str(new.pid)


@pytest.mark.parametrize("failure", [False, True])
def test_stop_cleanup_cannot_change_replacement(rig, monkeypatch, failure):
    jobs, install = rig
    old = install()
    ready, release = threading.Event(), threading.Event()
    wait = old.wait

    def delayed_wait(*args, **kwargs):
        result = wait(*args, **kwargs)
        ready.set()
        assert release.wait(10)
        if failure:
            raise OSError("old stop observation failed")
        return result

    monkeypatch.setattr(old, "wait", delayed_wait)
    with ThreadPoolExecutor(1) as pool:
        future = pool.submit(jobs.stop, "agent-race", "owner")
        try:
            assert ready.wait(5)
            new = install()
        finally:
            release.set()
        with pytest.raises(RuntimeError, match="INSTANCE_CHANGED"):
            future.result(timeout=10)
    assert new.poll() is None and jobs.processes["agent-race"] is new
    assert jobs.jobs["agent-race"]["status"] == "RUNNING"
    assert jobs.sessions["agent-race"] == "session-" + str(new.pid)


def test_only_one_session_creation_in_flight(rig, monkeypatch):
    jobs, install = rig
    install()
    jobs.sessions.clear()
    ready, release = threading.Event(), threading.Event()

    def delayed(*args, **kwargs):
        ready.set()
        assert release.wait(10)
        return 200, {"sessionId": "accepted-session"}

    monkeypatch.setattr(jobs, "_request", delayed)
    with ThreadPoolExecutor(1) as pool:
        future = pool.submit(jobs.talk, "agent-race", "owner", "session", {})
        try:
            assert ready.wait(5)
            with pytest.raises(ValueError, match="SESSION_ALREADY_ACTIVE"):
                jobs.talk("agent-race", "owner", "session", {})
        finally:
            release.set()
        assert future.result(timeout=10)[0] == 200
    assert jobs.sessions["agent-race"] == "accepted-session"


def test_current_session_failure_still_terminates_own_instance(rig, monkeypatch):
    jobs, install = rig
    child = install()
    def fail(*args, **kwargs): raise ConnectionResetError()
    monkeypatch.setattr(jobs, "_request", fail)
    code, result = jobs.talk("agent-race", "owner", "chat", {"query": "hello"})
    assert code == 504 and result["clearConversation"]
    assert child.poll() is not None and "agent-race" not in jobs.processes
    assert jobs.jobs["agent-race"]["status"] == "FAILED"


def test_actual_workbench_contains_read_only_optimization_storyline():
    source = (PROJECT / "browser/workbench.js").read_text(encoding="utf-8")
    bundle = (PROJECT / "examples/local-workbench/app.js").read_text(
        encoding="utf-8"
    )
    styles = (PROJECT / "examples/local-workbench/style.css").read_text(
        encoding="utf-8"
    )

    for marker in (
        "/optimization-view",
        "Builder 怎样把 Candidate 优化到可交付",
        "逐条执行冻结测试集",
        "问题归因与受控修正",
        "实际变更摘要",
        "审批 / 治理事实",
        "需要外部资产负责人处理",
        "源记录未提供阶段时间",
        "data-optimization-node",
    ):
        assert marker in source
    assert "optimization-workbench" in styles
    assert "sample-log" in styles
    assert "optimizationNodes.set" in source
    assert "optimizationViews.delete(id)" in source
    assert "optimizationNodes.delete(id)" in source
    assert "optimization-view`);" in source
    assert "/optimization-view`, {" not in source
    assert "/optimization-view" in bundle
    assert "data-optimization-node" in bundle
    assert r"Builder \u600E\u6837\u628A Candidate" in bundle
