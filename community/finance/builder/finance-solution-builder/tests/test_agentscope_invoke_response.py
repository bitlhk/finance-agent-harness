"""Restricted-delivery unit tests; native acceptance is a separate opt-in test."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import stat
import threading

import pytest

from finance_builder_base import ResourceKind
from finance_solution_builder.candidate_backend import restricted_response as delivery
from finance_solution_builder.candidate_backend.models import CandidateResourceRef, InvocationRequest, LifecycleStatus
from finance_solution_builder.service import digest, write_json
from tests.test_agentscope_backend import native, owned_backend
from tests.test_component_assembly import root
from tests.test_managed_runtime_selection import candidate


@pytest.fixture
def running(owned_backend):
    rig = owned_backend
    rig.handle = rig.adapter.start(rig.request).handle
    assert rig.handle is not None
    return rig


def request(rig, identity="query-1", body=None):
    path = rig.adapter.output / (identity + ".input.json")
    write_json(path, {"query": "synthetic query"} if body is None else body)
    return InvocationRequest(handle=rig.handle, request_id=identity, protocol="finance-chat",
        payload_ref=CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator=path.name, sha256=digest(path)))


def invoke(rig, identity="query-1", body=None):
    return rig.adapter.invoke(request(rig, identity, body))


def protected_read(rig, req, ref, *, owner="owner-a"):
    return delivery.read_response(rig.adapter.output, ref, owner=owner, request=req,
        instance_id=rig.adapter._instance_id, verify_identity=lambda: rig.adapter._owned(rig.handle))


@pytest.mark.parametrize("enabled,allowed", [(None, None), ("false", None), ("TRUE", "correct"),
    ("true", "*"), ("true", "owner-a"), ("true", "wrong")])
def test_capture_grant_exact_owner_hash_before_any_dispatch(running, monkeypatch, enabled, allowed):
    rig = running
    monkeypatch.setenv("FH_SNAPSHOTS_ENABLED", enabled or "")
    monkeypatch.setenv("FH_SNAPSHOT_CAPTURE_OWNERS", delivery.owner_ref("owner-a") if allowed == "correct" else allowed or "")
    monkeypatch.setenv("FH_HISTORY_OWNER", "*")
    before = list(rig.routes)
    result = invoke(rig, body={"query": "test", "capture": True, "owner": "owner-a"})
    assert result.outcome.status == LifecycleStatus.BLOCKED and result.response_ref is None
    assert result.outcome.error.code.endswith("capture_denied") and not result.outcome.error.retryable
    assert rig.routes == before and rig.identity not in rig.jobs.sessions
    assert not (rig.adapter.output / "runtime/responses").exists()


@pytest.mark.parametrize("field,value", [("MAX_FILES", ""), ("MAX_FILES", "0"), ("MAX_FILES", "4"),
    ("MAX_BYTES", "0"), ("MAX_BYTES", str(2 * delivery.META_LIMIT)),
    ("RETENTION_SECONDS", "0"), ("RETENTION_SECONDS", "inf"), ("RETENTION_SECONDS", "999999999")])
def test_explicit_finite_policy_required_before_dispatch(running, monkeypatch, field, value):
    rig = running
    monkeypatch.setenv("FH_CANDIDATE_RESPONSE_" + field, value)
    before = list(rig.routes)
    result = invoke(rig)
    assert result.outcome.status == LifecycleStatus.BLOCKED
    assert rig.routes == before and rig.identity not in rig.jobs.sessions


def test_wrong_owned_handle_rejected_before_storage_or_business(running):
    rig = running
    rig.adapter.owner = "other-owner"
    before = list(rig.routes)
    assert invoke(rig).outcome.status == LifecycleStatus.BLOCKED
    assert rig.routes == before and not (rig.adapter.output / "runtime/responses").exists()


@pytest.mark.parametrize("body", [{"query": 2}, {"query": "x", "capture": True}, {"query": "x", "runId": "../bad"}])
def test_payload_validation_precedes_session(running, body):
    before = list(running.routes)
    assert invoke(running, body=body).outcome.status == LifecycleStatus.BLOCKED
    assert running.routes == before


def test_actual_reply_and_executions_separate_private_immutable_receipt(running, monkeypatch):
    rig = running
    req = request(rig)
    result = rig.adapter.invoke(req)
    assert result.outcome.status == LifecycleStatus.PASSED
    ref = result.response_ref
    actual = json.loads((rig.adapter.output / ref.locator).read_bytes())
    assert actual == {"response": "unit response", "requestId": "host-request", "executions": [
        {"result": {"status": "COMPLETED", "data": {"unit": "private tool result"}}}]}
    assert digest(rig.adapter.output / ref.locator) == ref.sha256
    assert ref.media_type == "application/json"
    receipt = json.loads((rig.adapter.output / result.outcome.evidence_refs[0].locator).read_bytes())
    assert receipt["responseSha256"] == ref.sha256 and receipt["payloadSha256"] == req.payload_ref.sha256
    assert receipt["handleDigest"] == rig.handle.sha256() and receipt["instanceId"] == rig.adapter._instance_id
    assert receipt["ownerRef"] == delivery.owner_ref("owner-a")
    assert receipt["facts"]["chatAttempted"] and receipt["facts"]["businessOutcome"] == "NOT_ASSESSED"
    assert "unit response" not in json.dumps(receipt) and "private tool result" not in json.dumps(receipt)
    space = rig.adapter.output / "runtime/responses"
    assert stat.S_IMODE(space.stat().st_mode) == 0o700
    assert all(stat.S_IMODE(path.stat().st_mode) == 0o600 for path in space.iterdir())
    assert sum(b"unit response" in path.read_bytes() for path in space.iterdir()) == 1
    assert not (rig.adapter.output / "backend-evidence").exists()
    assert not list((rig.adapter.output / "candidate").rglob("*.response.json"))
    with pytest.raises(delivery.DeliveryRejected, match="view_denied"):
        protected_read(rig, req, ref)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", delivery.owner_ref("owner-a"))
    assert protected_read(rig, req, ref) == actual
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", delivery.owner_ref("other-owner"))
    with pytest.raises(delivery.DeliveryRejected, match="identity_changed"):
        protected_read(rig, req, ref, owner="other-owner")


@pytest.mark.parametrize("where", ["payload", "runtime", "responses", "lock"])
def test_symlink_path_rejected_without_dispatch(running, tmp_path, where):
    rig = running
    req = request(rig)
    outside = tmp_path / "outside"
    outside.mkdir()
    if where == "payload":
        path = rig.adapter.output / req.payload_ref.locator
        target = outside / "input.json"
        path.rename(target)
        path.symlink_to(target)
    elif where == "runtime":
        (rig.adapter.output / "runtime").symlink_to(outside, target_is_directory=True)
    else:
        (rig.adapter.output / "runtime").mkdir()
        if where == "responses":
            (rig.adapter.output / "runtime/responses").symlink_to(outside, target_is_directory=True)
        else:
            (rig.adapter.output / "runtime/responses").mkdir(mode=0o700)
            (rig.adapter.output / "runtime/responses/.lock").symlink_to(outside / "lock")
    original = {path.name: path.read_bytes() for path in outside.iterdir()}
    before = list(rig.routes)
    assert rig.adapter.invoke(req).outcome.status == LifecycleStatus.BLOCKED
    assert rig.routes == before and {path.name: path.read_bytes() for path in outside.iterdir()} == original


@pytest.mark.parametrize("budget", ["files", "bytes"])
def test_completed_reply_consumes_reserved_budget(running, monkeypatch, budget):
    rig = running
    if budget == "files":
        monkeypatch.setenv("FH_CANDIDATE_RESPONSE_MAX_FILES", "5")
    else:
        monkeypatch.setenv("FH_CANDIDATE_RESPONSE_MAX_BYTES", str(delivery.BODY_LIMIT + 2 * delivery.META_LIMIT))
    first = invoke(rig)
    assert first.outcome.status == LifecycleStatus.PASSED
    before = list(rig.routes)
    assert invoke(rig, "query-2").outcome.status == LifecycleStatus.BLOCKED
    assert rig.routes == before
    assert json.loads((rig.adapter.output / first.response_ref.locator).read_bytes())["response"] == "unit response"


def test_concurrent_reservations_account_for_inflight_under_os_lock(running, monkeypatch):
    rig = running
    monkeypatch.setenv("FH_CANDIDATE_RESPONSE_MAX_FILES", "5")
    requests = [request(rig, "concurrent-" + str(index)) for index in range(2)]
    barrier = threading.Barrier(2)
    def reserve(req):
        store = delivery.ResponseStore(rig.adapter.output, "owner-a")
        try:
            barrier.wait(timeout=5)
            try:
                store.reserve(req, rig.adapter._instance_id, lambda: rig.adapter._owned(rig.handle))
                return "RESERVED"
            except delivery.DeliveryRejected as exc:
                return exc.code
        finally:
            store.close()
    before = list(rig.routes)
    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(reserve, requests))
    assert sorted(results) == ["RESERVED", "quota_exhausted"]
    assert rig.routes == before


@pytest.mark.parametrize("fault", ["size", "disk", "fsync", "directory-fsync", "link", "revoke", "generation", "owner", "path-race"])
def test_post_dispatch_failure_never_replays_or_rewrites_host_fact(running, monkeypatch, tmp_path, fault):
    rig = running
    history = rig.adapter.output / "existing-host-history.json"
    write_json(history, {"business": "COMPLETED", "source": "unit-history-fixture"})
    original_history = history.read_bytes()
    original = rig.jobs.talk
    business = []
    def talk(*args, **kwargs):
        code, result = original(*args, **kwargs)
        if args[2] == "chat":
            business.append(result)
            if fault == "size":
                result = dict(result, response="x" * (delivery.BODY_LIMIT + 1))
            elif fault == "revoke":
                monkeypatch.setenv("FH_SNAPSHOT_CAPTURE_OWNERS", "")
            elif fault == "generation":
                rig.jobs.jobs[rig.identity]["instanceId"] = "replacement-generation"
                rig.adapter._instance_id = "replacement-generation"
                monkeypatch.setattr(rig.jobs, "_same_instance", lambda *_: False)
            elif fault == "owner":
                rig.jobs.jobs[rig.identity]["owner"] = "other-owner"
            elif fault == "path-race":
                space = rig.adapter.output / "runtime/responses"
                space.rename(rig.adapter.output / "runtime/moved")
                outside = tmp_path / "outside-race"
                outside.mkdir()
                space.symlink_to(outside, target_is_directory=True)
        return code, result
    monkeypatch.setattr(rig.jobs, "talk", talk)
    publish = delivery.ResponseStore._publish
    def publish_fault(self, name, data):
        if fault == "disk" and name.endswith(".response.json"):
            raise OSError("private filesystem details must not escape")
        return publish(self, name, data)
    monkeypatch.setattr(delivery.ResponseStore, "_publish", publish_fault)
    fsync = os.fsync
    def fsync_fault(fd):
        if business and (fault == "fsync" or fault == "directory-fsync" and stat.S_ISDIR(os.fstat(fd).st_mode)):
            raise OSError("private fsync error")
        return fsync(fd)
    monkeypatch.setattr(delivery.os, "fsync", fsync_fault)
    link = os.link
    def link_fault(source, destination, **kwargs):
        if fault == "link" and destination.endswith(".response.json"):
            raise OSError("private link error")
        return link(source, destination, **kwargs)
    monkeypatch.setattr(delivery.os, "link", link_fault)
    result = invoke(rig)
    assert result.outcome.status == LifecycleStatus.FAILED and result.response_ref is None
    assert not result.outcome.error.retryable and "private" not in result.outcome.error.message
    assert len(business) == 1 and history.read_bytes() == original_history
    before = list(rig.routes)
    assert invoke(rig).outcome.status == LifecycleStatus.BLOCKED
    assert rig.routes == before and len(business) == 1
    if fault == "path-race":
        assert not list((tmp_path / "outside-race").iterdir())
    else:
        assert not list((rig.adapter.output / "runtime/responses").glob("*.response.json"))
        assert result.outcome.evidence_refs
        metadata = json.loads((rig.adapter.output / result.outcome.evidence_refs[0].locator).read_bytes())
        assert metadata["automaticRetry"] is False
        if metadata.get("facts"):
            assert metadata["facts"]["chatAttempted"] and metadata["facts"]["chatResultCode"] == 200


@pytest.mark.parametrize("fault", ["expired", "missing", "tampered", "view-revoked", "wrong-handle"])
def test_restricted_delayed_reader_rechecks_identity_policy_and_content(running, monkeypatch, fault):
    rig = running
    req = request(rig)
    result = rig.adapter.invoke(req)
    assert result.outcome.status == LifecycleStatus.PASSED
    body = rig.adapter.output / result.response_ref.locator
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", delivery.owner_ref("owner-a"))
    receipt_path = rig.adapter.output / result.outcome.evidence_refs[0].locator
    receipt = receipt_path.read_bytes()
    if fault == "expired":
        monkeypatch.setattr(delivery.time, "time", lambda: json.loads(receipt)["expiresAt"] + 1)
    elif fault == "missing":
        body.unlink()
    elif fault == "tampered":
        body.write_bytes(b'{"response":"tampered"}')
    elif fault == "view-revoked":
        monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", "*")
    else:
        req = req.model_copy(update={"handle": req.handle.model_copy(update={"descriptor_digest": "c" * 64})})
    with pytest.raises((ValueError, OSError)):
        protected_read(rig, req, result.response_ref)
    assert receipt_path.read_bytes() == receipt
    if fault == "expired":
        assert not body.exists()
        before = list(rig.routes)
        assert rig.adapter.invoke(req).outcome.status == LifecycleStatus.BLOCKED
        assert rig.routes == before


def test_expired_body_reclaimed_but_idempotency_metadata_stays_bounded(running, monkeypatch):
    rig = running
    monkeypatch.setenv("FH_CANDIDATE_RESPONSE_MAX_BYTES", str(delivery.BODY_LIMIT + 4 * delivery.META_LIMIT))
    first = invoke(rig)
    receipt = json.loads((rig.adapter.output / first.outcome.evidence_refs[0].locator).read_bytes())
    monkeypatch.setattr(delivery.time, "time", lambda: receipt["expiresAt"] + 1)
    second = invoke(rig, "query-2")
    assert second.outcome.status == LifecycleStatus.PASSED
    assert not (rig.adapter.output / first.response_ref.locator).exists()
    assert (rig.adapter.output / first.outcome.evidence_refs[0].locator).exists()
    assert len(list((rig.adapter.output / "runtime/responses").glob("*.reserved.json"))) == 2
    assert invoke(rig).outcome.status == LifecycleStatus.BLOCKED


def test_grant_revoked_after_publication_removes_body_and_never_returns_pass(running, monkeypatch):
    original = delivery.Reservation._receipt
    def receipt(self, *args, **kwargs):
        original(self, *args, **kwargs)
        monkeypatch.setenv("FH_SNAPSHOT_CAPTURE_OWNERS", "")
    monkeypatch.setattr(delivery.Reservation, "_receipt", receipt)
    result = invoke(running)
    assert result.outcome.status == LifecycleStatus.FAILED and result.response_ref is None
    assert not list((running.adapter.output / "runtime/responses").glob("*.response.json"))
    assert result.outcome.evidence_refs and not result.outcome.error.retryable


def test_reservation_fsync_failure_blocks_before_session(running, monkeypatch):
    before = list(running.routes)
    monkeypatch.setattr(delivery.os, "fsync", lambda *_: (_ for _ in ()).throw(OSError("unit fsync")))
    result = invoke(running)
    assert result.outcome.status == LifecycleStatus.BLOCKED and running.routes == before


def test_complete_partial_business_response_is_delivered_without_business_pass_claim(running, monkeypatch):
    original = running.jobs.talk
    partial = {"response": "Tool result unavailable", "executions": [{"result": {"status": "FAILED"}}]}
    def talk(*args, **kwargs):
        status, reply = original(*args, **kwargs)
        return (status, partial) if args[2] == "chat" else (status, reply)
    monkeypatch.setattr(running.jobs, "talk", talk)
    result = invoke(running)
    assert result.outcome.status == LifecycleStatus.PASSED
    assert json.loads((running.adapter.output / result.response_ref.locator).read_bytes()) == partial
    receipt = json.loads((running.adapter.output / result.outcome.evidence_refs[0].locator).read_bytes())
    assert receipt["facts"]["businessOutcome"] == "NOT_ASSESSED"


def test_other_owner_reader_does_not_read_response_bytes(running, monkeypatch):
    req = request(running)
    result = running.adapter.invoke(req)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", delivery.owner_ref("other-owner"))
    original = delivery.ResponseStore._read
    def read(self, name, limit):
        assert not name.endswith(".response.json"), "Body read before owner verification"
        return original(self, name, limit)
    monkeypatch.setattr(delivery.ResponseStore, "_read", read)
    with pytest.raises(delivery.DeliveryRejected, match="identity_changed"):
        protected_read(running, req, result.response_ref, owner="other-owner")


@pytest.mark.parametrize("linked", [False, True])
def test_interrupted_publication_temp_is_cleaned_before_expiry_check(running, monkeypatch, linked):
    req = request(running)
    result = running.adapter.invoke(req)
    body = running.adapter.output / result.response_ref.locator
    orphan = body.parent / (".pending-" + "a" * 32)
    if linked:
        os.link(body, orphan)
    else:
        orphan.write_bytes(b'{"response":"interrupted private body"}')
        orphan.chmod(0o600)
    receipt = json.loads((running.adapter.output / result.outcome.evidence_refs[0].locator).read_bytes())
    monkeypatch.setattr(delivery.time, "time", lambda: receipt["expiresAt"] + 1)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", delivery.owner_ref("owner-a"))
    with pytest.raises(delivery.DeliveryRejected, match="response_expired"):
        protected_read(running, req, result.response_ref)
    assert not orphan.exists() and not body.exists()
    assert (running.adapter.output / result.outcome.evidence_refs[0].locator).exists()


@pytest.mark.parametrize("case,action,code", [
    ("wrong-owner", "session", None),
    ("generation-changed", "session", 409),
    ("chat-without-session", "chat", 403),
    ("session-already-active", "session", None),
])
def test_jobs_local_denial_after_last_identity_check_records_attempt_not_http(running, monkeypatch, case, action, code):
    rig = running
    if action == "chat":
        rig.jobs.sessions[rig.identity] = "owned-session"
    actual_talk = rig.jobs.talk
    attempts, http_boundary_calls = [], []
    def raced_talk(*args, **kwargs):
        assert args[2] == action
        attempts.append(action)
        # This boundary is after the Backend's last _current check. Execute
        # real Jobs.talk guards; do not synthesize its rejection result.
        with rig.jobs.lock:
            if case == "wrong-owner":
                rig.jobs.jobs[rig.identity]["owner"] = "other-owner"
            elif case == "generation-changed":
                rig.jobs.jobs[rig.identity]["instanceId"] = "replacement-generation"
            elif case == "chat-without-session":
                rig.jobs.sessions.pop(rig.identity)
            else:
                rig.jobs.sessions[rig.identity] = "raced-session"
        return actual_talk(*args, **kwargs)
    def forbidden_http(*args, **kwargs):
        http_boundary_calls.append(True)
        pytest.fail("A local Jobs rejection reached the HTTP boundary")
    monkeypatch.setattr(rig.jobs, "talk", raced_talk)
    monkeypatch.setattr(rig.jobs, "_request", forbidden_http)
    result = invoke(rig)
    assert attempts == [action] and http_boundary_calls == []
    assert result.outcome.status == LifecycleStatus.FAILED and result.response_ref is None
    assert not result.outcome.error.retryable and "after a Jobs call attempt" in result.outcome.summary
    receipt = json.loads((rig.adapter.output / result.outcome.evidence_refs[0].locator).read_bytes())
    facts = receipt["facts"]
    assert receipt["dispatch"] == "ATTEMPTED" and receipt["delivery"] == "FAILED"
    assert facts["sessionAttempted"] is (action == "session")
    assert facts["chatAttempted"] is (action == "chat")
    assert facts[action + "ResultCode"] == code and facts["businessOutcome"] == "NOT_ASSESSED"
    assert not {"sessionDispatched", "chatDispatched", "sessionHttpStatus", "chatHttpStatus", "networkSent"} & facts.keys()
    assert invoke(rig).outcome.status == LifecycleStatus.BLOCKED
    assert attempts == [action] and http_boundary_calls == []


def test_jobs_409_after_http_boundary_does_not_infer_zero_business(running, monkeypatch):
    rig = running
    rig.jobs.sessions[rig.identity] = "owned-session"
    actual_request = rig.jobs._request
    completed_results = []
    def replaced_after_request(*args, **kwargs):
        code, reply = actual_request(*args, **kwargs)
        completed_results.append(reply)
        with rig.jobs.lock:
            rig.jobs.tokens[rig.identity] = "replacement-token"
        return code, reply
    monkeypatch.setattr(rig.jobs, "_request", replaced_after_request)
    result = invoke(rig)
    assert len(completed_results) == 1 and completed_results[0]["executions"][0]["result"]["status"] == "COMPLETED"
    assert result.outcome.status == LifecycleStatus.FAILED and result.response_ref is None
    assert not result.outcome.error.retryable
    receipt = json.loads((rig.adapter.output / result.outcome.evidence_refs[0].locator).read_bytes())
    assert receipt["dispatch"] == "ATTEMPTED" and receipt["facts"]["chatAttempted"]
    assert receipt["facts"]["chatResultCode"] == 409 and receipt["facts"]["businessOutcome"] == "NOT_ASSESSED"
    assert invoke(rig).outcome.status == LifecycleStatus.BLOCKED and len(completed_results) == 1
