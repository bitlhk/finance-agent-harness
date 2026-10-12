import hashlib
import json
import uuid
from pathlib import Path
import subprocess
import pytest
from test_execution_history import saved


def snapshot_fixture(tmp_path, legacy=False):
    store, directory, request = saved(tmp_path)
    call = str(uuid.uuid4())
    body = dict(recordType="model-snapshot", requestId=request, callId=call, spanId="model-1", part="request",
                boundary="CLIENT_REQUEST_PREPARED", state="CAPTURED", filter="ALLOWLIST_AND_REDACTION_V01",
                dispatch="NOT_OBSERVED", ownerRef=hashlib.sha256(b"owner-a").hexdigest(), body='<script>synthetic</script>')
    if not legacy:
        body.update(changes=[{'reason':'COLLECTION_LIMIT','path':'/messages','originalCount':129,'retainedCount':128}],changesOmitted=0)
    raw = json.dumps(body).encode()
    ref = {k: v for k, v in body.items() if k not in {"ownerRef", "body"}}
    ref["digest"] = hashlib.sha256(raw).hexdigest()
    with (directory / (request + ".jsonl")).open("a") as output:
        output.write(json.dumps(ref) + "\n")
    folder = directory / "model-snapshots"
    folder.mkdir()
    path = folder / f"{request}.{call}.request.json"
    path.write_bytes(raw)
    return store, request, call, path


def test_snapshot_requires_separate_grant_and_never_executes(tmp_path, monkeypatch):
    store, request, call, path = snapshot_fixture(tmp_path)
    assert "<script>" not in json.dumps(store.get("owner-a", request))
    assert store.get("owner-a", request)["snapshots"][0]["state"] == "BODY_NOT_VERIFIED"
    with pytest.raises(PermissionError):
        store.snapshot("owner-a", request, call, "request")
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-a").hexdigest())
    monkeypatch.setattr("urllib.request.urlopen", lambda *a, **kw: pytest.fail("History must not execute HTTP"))
    assert store.snapshot("owner-a", request, call, "request")["boundary"] == "CLIENT_REQUEST_PREPARED"
    assert store.snapshot("owner-a", request, call, "request")["changes"][0]['retainedCount']==128
    with pytest.raises(PermissionError):
        store.snapshot("owner-b", request, call, "request")
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-b").hexdigest())
    with pytest.raises(PermissionError):
        store.snapshot("owner-b", request, call, "request")


def test_body_failure_or_tampering_is_not_complete(tmp_path, monkeypatch):
    store, request, call, path = snapshot_fixture(tmp_path)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-a").hexdigest())
    path.write_text("{}")
    assert store.snapshot("owner-a", request, call, "request")["reason"] == "BODY_DIGEST_MISMATCH"
    path.unlink()
    assert store.snapshot("owner-a", request, call, "request")["state"] == "NOT_CAPTURED"
    assert store.snapshot("owner-a", request, str(uuid.uuid4()), "response")["reason"] == "NO_SNAPSHOT_REFERENCE"
    with pytest.raises(ValueError):
        store.snapshot("owner-a", request, "../../x", "request")


def test_legacy_snapshot_does_not_invent_loss_metadata(tmp_path, monkeypatch):
    store, request, call, _ = snapshot_fixture(tmp_path, legacy=True)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-a").hexdigest())
    result = store.snapshot("owner-a", request, call, "request")
    assert result['body'] == '<script>synthetic</script>'
    assert result['changes'] is None and result['changesOmitted'] is None


def test_snapshot_ui_loss_summary():
    result = subprocess.run(['node','engineering/test_snapshot_summary.mjs'],
        cwd=Path(__file__).resolve().parents[1], capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stdout + result.stderr


def test_expired_body_requires_matching_original_digest(tmp_path, monkeypatch):
    store, request, call, path = snapshot_fixture(tmp_path)
    monkeypatch.setenv('FH_SNAPSHOT_VIEW_OWNERS',hashlib.sha256(b'owner-a').hexdigest())
    digest=hashlib.sha256(path.read_bytes()).hexdigest()
    path.unlink()
    marker=path.with_name(path.name+'.expired')
    marker.write_text(json.dumps({'state':'EXPIRED','digest':'wrong','expiredAt':'2026-01-01T00:00:00Z'}))
    assert store.snapshot('owner-a',request,call,'request')['state']=='NOT_CAPTURED'
    marker.write_text(json.dumps({'state':'EXPIRED','digest':digest,'expiredAt':'2026-01-01T00:00:00Z'}))
    assert store.snapshot('owner-a',request,call,'request')['state']=='EXPIRED'
    assert store.get('owner-a',request)['requestId']==request


def storage_receipt(store, request, **changes):
    path = store.directory("owner-a") / (request + ".jsonl")
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    ref = next(row for row in rows if row.get("recordType") == "model-snapshot")
    row = {key: ref[key] for key in ("requestId", "callId", "part", "spanId", "digest")}
    row.update(recordType="model-snapshot-storage", state="WRITE_FAILED", reason="BODY_WRITE_FAILED",
               at="2026-09-14T12:00:00Z")
    row.update(changes)
    with path.open("a") as output: output.write(json.dumps(row) + "\n")
    return row


@pytest.mark.parametrize("state,reason,expected", [
    ("WRITE_FAILED", "BODY_WRITE_FAILED", "BODY_WRITE_FAILED"),
    ("WRITE_FAILED", "BODY_STORAGE_QUOTA", "BODY_STORAGE_QUOTA"),
    ("WRITTEN", None, "FILE_MISSING"),
])
def test_missing_body_classified_only_by_digest_bound_storage_receipt(tmp_path, monkeypatch, state, reason, expected):
    store, request, call, path = snapshot_fixture(tmp_path)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-a").hexdigest())
    receipt = storage_receipt(store, request, state=state, reason=reason)
    path.unlink()
    result = store.snapshot("owner-a", request, call, "request")
    assert result["reason"] == expected
    assert result["storageResult"]["state"] == state
    assert result["gapReason"] == ("WRITE_FAILED" if state == "WRITE_FAILED" else "MISSING")
    metadata = store.get("owner-a", request)["snapshots"][0]["storageResult"]
    assert set(metadata) <= {"state", "reason", "at"}
    assert metadata["at"] == receipt["at"]


@pytest.mark.parametrize("field", ["requestId", "callId", "part", "spanId", "digest"])
def test_storage_receipt_requires_all_binding_fields(tmp_path, monkeypatch, field):
    store, request, call, path = snapshot_fixture(tmp_path)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-a").hexdigest())
    storage_receipt(store, request, **{field: "mismatched"})
    path.unlink()
    result = store.snapshot("owner-a", request, call, "request")
    assert result["reason"] == "BODY_UNAVAILABLE_OR_NOT_FLUSHED"
    assert result["gapReason"] == "UNAVAILABLE"
    assert store.get("owner-a", request)["snapshots"][0]["storageResult"] is None


def test_last_matching_storage_receipt_never_skips_body_digest_verification(tmp_path, monkeypatch):
    store, request, call, path = snapshot_fixture(tmp_path)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-a").hexdigest())
    storage_receipt(store, request)
    storage_receipt(store, request, state="WRITTEN", reason=None)
    storage_receipt(store, request, digest="f" * 64)
    assert store.snapshot("owner-a", request, call, "request")["body"] == "<script>synthetic</script>"
    assert store.get("owner-a", request)["snapshots"][0]["storageResult"]["state"] == "WRITTEN"
    path.write_bytes(b"tampered")
    result = store.snapshot("owner-a", request, call, "request")
    assert result["reason"] == "BODY_DIGEST_MISMATCH"
    assert result["gapReason"] == "DIGEST_MISMATCH" and "body" not in result


@pytest.mark.parametrize("reason", ["PRIVATE /secret/token", [], {"private": "secret"}])
def test_storage_result_projects_only_whitelisted_fields(tmp_path, monkeypatch, reason):
    store, request, call, path = snapshot_fixture(tmp_path)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-a").hexdigest())
    storage_receipt(store, request, reason=reason, at="PRIVATE /secret/token", body="PRIVATE BODY",
                    exceptionMessage="PRIVATE STACK", storageResult={"private": "PRIVATE"})
    path.unlink()
    view = store.get("owner-a", request)
    assert view["snapshots"][0]["storageResult"] == {"state": "WRITE_FAILED"}
    result = store.snapshot("owner-a", request, call, "request")
    assert result["reason"] == "BODY_WRITE_FAILED"
    assert "PRIVATE" not in json.dumps([view, result])


@pytest.mark.parametrize("reason", ["BODY_QUEUE_REJECTED", "BODY_PREPARATION_FAILED", None, "PRIVATE /path"])
def test_preparation_failure_reasons_require_safe_reference_evidence(tmp_path, monkeypatch, reason):
    store, request, call, path = snapshot_fixture(tmp_path)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-a").hexdigest())
    storage_receipt(store, request)
    history = store.directory("owner-a") / (request + ".jsonl")
    rows = [json.loads(line) for line in history.read_text().splitlines()]
    ref = next(row for row in rows if row.get("recordType") == "model-snapshot")
    ref.update(storage="UNAVAILABLE", storageReason=reason)
    ref.pop("digest")
    history.write_text("\n".join(map(json.dumps, rows)))
    path.unlink()
    result = store.snapshot("owner-a", request, call, "request")
    assert result["gapReason"] == "STORAGE_UNAVAILABLE"
    assert result["reason"] == (reason if reason in ("BODY_QUEUE_REJECTED", "BODY_PREPARATION_FAILED")
                                else "BODY_UNAVAILABLE_OR_NOT_FLUSHED")
    metadata = store.get("owner-a", request)["snapshots"][0]
    assert metadata["storageResult"] is None
    assert metadata["storageReason"] == (reason if reason in ("BODY_QUEUE_REJECTED", "BODY_PREPARATION_FAILED") else None)
    assert "PRIVATE" not in json.dumps([result, metadata])


def test_queued_missing_is_not_guessed_pending_expired_or_failed(tmp_path, monkeypatch):
    store, request, call, path = snapshot_fixture(tmp_path)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-a").hexdigest())
    history = store.directory("owner-a") / (request + ".jsonl")
    rows = [json.loads(line) for line in history.read_text().splitlines()]
    ref = next(row for row in rows if row.get("recordType") == "model-snapshot")
    ref.update(storage="QUEUED", state="FAILED")
    history.write_text("\n".join(map(json.dumps, rows)))
    path.unlink()
    result = store.snapshot("owner-a", request, call, "request")
    assert result == {"state": "NOT_CAPTURED", "reason": "BODY_UNAVAILABLE_OR_NOT_FLUSHED",
                      "gapReason": "UNAVAILABLE", "evidence": "BODY_FILE_NOT_FOUND"}


def test_missing_digest_does_not_match_expiry_marker(tmp_path, monkeypatch):
    store, request, call, path = snapshot_fixture(tmp_path)
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-a").hexdigest())
    history = store.directory("owner-a") / (request + ".jsonl")
    rows = [json.loads(line) for line in history.read_text().splitlines()]
    next(row for row in rows if row.get("recordType") == "model-snapshot").pop("digest")
    history.write_text("\n".join(map(json.dumps, rows)))
    path.unlink()
    path.with_name(path.name + ".expired").write_text(json.dumps({"state": "EXPIRED"}))
    assert store.snapshot("owner-a", request, call, "request")["gapReason"] == "UNAVAILABLE"


def test_access_denial_and_symlinks_are_not_reported_as_missing(tmp_path, monkeypatch):
    store, request, call, path = snapshot_fixture(tmp_path)
    with pytest.raises(PermissionError, match="SNAPSHOT_VIEW_NOT_GRANTED"):
        store.snapshot("owner-a", request, call, "request")
    monkeypatch.setenv("FH_SNAPSHOT_VIEW_OWNERS", hashlib.sha256(b"owner-a").hexdigest())
    original = store._snapshot_read
    def denied(file):
        if file == path: raise PermissionError("SNAPSHOT_UNAVAILABLE")
        return original(file)
    monkeypatch.setattr(store, "_snapshot_read", denied)
    with pytest.raises(PermissionError, match="SNAPSHOT_UNAVAILABLE"):
        store.snapshot("owner-a", request, call, "request")
    monkeypatch.setattr(store, "_snapshot_read", original)
    outside = tmp_path / "private.json"
    outside.write_bytes(path.read_bytes())
    path.unlink(); path.symlink_to(outside)
    with pytest.raises(PermissionError, match="SNAPSHOT_UNAVAILABLE"):
        store.snapshot("owner-a", request, call, "request")


def test_storage_receipt_after_terminal_is_not_a_late_business_event(tmp_path):
    store, request, _, _ = snapshot_fixture(tmp_path)
    history = store.directory("owner-a") / (request + ".jsonl")
    rows = [json.loads(line) for line in history.read_text().splitlines()]
    terminal = next(row for row in rows if row.get("recordType") == "terminal")
    rows.remove(terminal); rows.append(terminal)
    history.write_text("\n".join(map(json.dumps, rows)) + "\n")
    storage_receipt(store, request, state="WRITTEN", reason=None)
    result = store.get("owner-a", request)
    assert "LATE_EVENT_AFTER_TERMINAL" not in result["gaps"]
    assert result["complete"] is True
