import json
from pathlib import Path
import threading
from types import SimpleNamespace

import pytest

from finance_solution_builder.experience_contracts import ApiError
from finance_solution_builder.method_assets import MethodAssets
from finance_solution_builder.method_api import MethodApi


class Queue:
    def __init__(self): self.pending = []
    def submit(self, callback, *args): self.pending.append((callback, args))
    def finish(self):
        callback, args = self.pending.pop(0)
        callback(*args)


@pytest.fixture
def api(tmp_path):
    harness = Path(__file__).resolve().parents[1] / "tests/fixtures/business-assets"
    jobs = SimpleNamespace(method_assets=MethodAssets(tmp_path / "private"), lock=threading.RLock(),
        closing=False, queue=Queue(), jobs={}, project=Path(__file__).resolve().parents[1],
        builder=SimpleNamespace(harness_repo=harness))
    return MethodApi(jobs)


def draft(api):
    code, item = api.post("/api/v1/assets/copy-default", {"capabilityId": "account.balance.query", "name": "My method"}, "alice", None)
    assert code == 201
    return item


def test_api_owner_save_validate_and_revision(api):
    item = draft(api)
    path = "/api/v1/assets/" + item["assetId"]
    assert api.get("/api/v1/assets", "bob")[1]["assets"] == []
    with pytest.raises(ApiError, match="ASSET_NOT_AVAILABLE"):
        api.get(path, "bob")
    data = {**item["data"], "content": "New method"}
    assert api.post(path + "/save", {"revision": 1, "data": data}, "alice", None)[1]["revision"] == 2
    with pytest.raises(ApiError, match="ASSET_REVISION_CONFLICT"):
        api.post(path + "/validate", {"revision": 1}, "alice", None)
    assert api.post(path + "/validate", {"revision": 2}, "alice", None)[1]["status"] == "VALID"


@pytest.mark.parametrize("extra", [{"runtimeVerified": True}, {"report": {"status": "PASS"}}, {"trusted": True}])
def test_client_cannot_submit_trial_report(api, extra):
    item = draft(api)
    with pytest.raises(ApiError, match="REQUEST_INVALID"):
        api.post("/api/v1/assets/" + item["assetId"] + "/trial", {"revision": 1, **extra}, "alice", None)
    assert not api.jobs.queue.pending


def test_server_trial_publish_and_explicit_apply(api, monkeypatch):
    from finance_solution_builder import method_trials
    called = []
    def run(jobs, owner, snapshot):
        called.append((owner, snapshot["contentDigest"]))
        return {"status": "PASS", "runtimeVerified": True, "model": "CONTROLLED_TEST"}
    monkeypatch.setattr(method_trials, "run_trial", run)
    item = draft(api)
    path = "/api/v1/assets/" + item["assetId"]
    assert api.post(path + "/trial", {"revision": 1}, "alice", None)[0] == 202
    assert api.get(path + "/trial", "alice")[1]["status"] == "QUEUED"
    with pytest.raises(ApiError, match="ASSET_TRIAL_QUEUE_FULL"):
        api.post(path + "/trial", {"revision": 1}, "alice", None)
    api.jobs.queue.finish()
    result = api.get(path + "/trial", "alice")[1]
    assert result["status"] == "COMPLETED" and called == [("alice", item["contentDigest"])]
    trial = result["trial"]
    assert trial["report"]["professionalQuality"] == "NOT_EVALUATED"
    code, release = api.post(path + "/publish", {"revision": 1, "version": "1.0.0", "trialId": trial["trialId"]}, "alice", None)
    assert code == 201 and not api.jobs.queue.pending
    calls = []
    workbench = SimpleNamespace(post=lambda *args: calls.append(args) or (202, {"buildId": "new-agent"}))
    body = {"version": "1.0.0", "releaseDigest": release["releaseDigest"], "selection": {"schema_version": "0.6"}}
    assert api.post(path + "/apply", body, "alice", workbench)[0] == 202
    assert calls[0][0] == "/api/v1/builds" and calls[0][1]["schema_version"] == "0.7"
    assert calls[0][1]["methodRef"]["assetId"] == item["assetId"]


def test_async_failure_and_stale_revision_not_publishable(api, monkeypatch):
    from finance_solution_builder import method_trials
    monkeypatch.setattr(method_trials, "run_trial", lambda *a: {"status": "PASS", "runtimeVerified": True})
    item = draft(api)
    path = "/api/v1/assets/" + item["assetId"]
    api.post(path + "/trial", {"revision": 1}, "alice", None)
    api.post(path + "/save", {"revision": 1, "data": {**item["data"], "content": "changed"}}, "alice", None)
    api.jobs.queue.finish()
    assert api.get(path + "/trial", "alice")[1]["error"] == "ASSET_REVISION_CONFLICT"
    assert api.get(path, "alice")[1]["trial"] is None


def test_restart_marks_unfinished_trial_interrupted(api):
    item = draft(api)
    path = "/api/v1/assets/" + item["assetId"]
    api.post(path + "/trial", {"revision": 1}, "alice", None)
    restarted = MethodApi(api.jobs)
    assert restarted.get(path + "/trial", "alice")[1]["status"] == "INTERRUPTED"


def test_published_report_tampering_rejected(api, monkeypatch):
    from finance_solution_builder import method_trials
    monkeypatch.setattr(method_trials, "run_trial", lambda *a: {"status": "PASS", "runtimeVerified": True})
    item = draft(api)
    path = "/api/v1/assets/" + item["assetId"]
    api.post(path + "/trial", {"revision": 1}, "alice", None)
    api.jobs.queue.finish()
    trial = api.get(path + "/trial", "alice")[1]["trial"]
    release = api.post(path + "/publish", {"revision": 1, "version": "1.0.0", "trialId": trial["trialId"]}, "alice", None)[1]
    file = next(api.store.root.rglob("releases/1.0.0/trial.json"))
    data = json.loads(file.read_text())
    data["report"]["runtimeVerified"] = False
    file.write_text(json.dumps(data))
    with pytest.raises(ApiError, match="ASSET_RELEASE_CHANGED"):
        api.store.release("alice", {k: release[k] for k in ("assetId", "version", "releaseDigest")})


def test_live_preview_uses_selected_connection_and_cannot_authorize_publication(api, monkeypatch):
    from finance_solution_builder import method_trials, model_registry
    monkeypatch.setattr(model_registry, "ModelRegistry", lambda: SimpleNamespace(status=lambda ref:
        "CONFIGURED_UNTESTED" if ref == "model-test" else "UNBOUND"))
    calls = []
    monkeypatch.setattr(method_trials, "run_preview", lambda jobs, owner, snap, ref, query:
        calls.append((owner, snap["revision"], ref, query)) or {"revision": snap["revision"], "modelRef": ref})
    item = draft(api)
    path = "/api/v1/assets/" + item["assetId"]
    with pytest.raises(ApiError, match="MODEL_CONNECTION_UNAVAILABLE"):
        api.post(path + "/preview", {"revision": 1, "modelRef": "model-absent", "query": "My facts"}, "alice", None)
    assert not api.jobs.queue.pending
    api.post(path + "/preview", {"revision": 1, "modelRef": "model-test", "query": "My facts"}, "alice", None)
    api.jobs.queue.finish()
    assert calls == [("alice", 1, "model-test", "My facts")]
    assert api.get(path + "/preview", "alice")[1]["status"] == "COMPLETED"
    assert api.get(path, "alice")[1]["trial"] is None
    with pytest.raises(ApiError, match="ASSET_FRESH_TRIAL_REQUIRED"):
        api.post(path + "/publish", {"revision": 1, "version": "1.0.0", "trialId": "preview"}, "alice", None)


def test_example_and_import_preview_write_nothing_then_explicit_save(api):
    import base64
    body = {"capabilityId": "account.balance.query", "name": "Preview"}
    code, preview = api.post("/api/v1/assets/example-preview", body, "alice", None)
    assert code == 200 and preview["data"]["capabilityIds"] == ["account.balance.query"]
    assert list(api.store.root.iterdir()) == []
    imported = {"filename": "SKILL.md", "base64": base64.b64encode(b"# Imported").decode(), "name": "Import"}
    code, value = api.post("/api/v1/assets/import-preview", imported, "alice", None)
    assert code == 200 and value["validation"]["status"] == "INVALID"
    assert list(api.store.root.iterdir()) == []
    edited = {**value["data"], "content": "# Edited after preview"}
    saved = api.post("/api/v1/assets/import", {**imported, "data": edited}, "alice", None)[1]
    assert saved["revision"] == 1 and saved["data"]["content"] == edited["content"]
    assert saved["source"]["kind"] == "IMPORTED"
    original = json.loads(next(api.store.root.rglob("original.json")).read_text())
    assert base64.b64decode(original["base64"]) == b"# Imported"


def test_delete_checks_owner_revision_and_actual_references(api):
    item = draft(api)
    path = "/api/v1/assets/" + item["assetId"]
    with pytest.raises(ApiError, match="ASSET_NOT_AVAILABLE"):
        api.post(path + "/delete", {"revision": 1}, "bob", None)
    with pytest.raises(ApiError, match="ASSET_REVISION_CONFLICT"):
        api.post(path + "/delete", {"revision": 0}, "alice", None)
    api.jobs.jobs["existing-agent"] = {"owner": "alice", "methodRef": {"assetId": item["assetId"]}}
    with pytest.raises(ApiError, match="ASSET_REQUIRES_ARCHIVE"):
        api.post(path + "/delete", {"revision": 1}, "alice", None)
    api.jobs.jobs.clear()
    assert api.post(path + "/delete", {"revision": 1}, "alice", None)[1]["status"] == "DELETED"
    assert api.store.list("alice") == []


def test_active_trial_blocks_removal_then_evidence_requires_archive(api, monkeypatch):
    from finance_solution_builder import method_trials
    monkeypatch.setattr(method_trials, "run_trial", lambda *a: {"status": "PASS", "runtimeVerified": True})
    item = draft(api)
    path = "/api/v1/assets/" + item["assetId"]
    api.post(path + "/trial", {"revision": 1}, "alice", None)
    for action in ("delete", "archive"):
        with pytest.raises(ApiError, match="ASSET_TRIAL_ACTIVE"):
            api.post(path + "/" + action, {"revision": 1}, "alice", None)
    api.jobs.queue.finish()
    trial = api.get(path + "/trial", "alice")[1]
    with pytest.raises(ApiError, match="ASSET_REQUIRES_ARCHIVE"):
        api.post(path + "/delete", {"revision": 1}, "alice", None)
    archived = api.post(path + "/archive", {"revision": 1}, "alice", None)[1]
    assert archived["status"] == "ARCHIVED" and archived["revision"] == 2
    assert api.get(path + "/trial", "alice")[1] == trial
    for action, body in (("save", {"revision": 2, "data": item["data"]}), ("trial", {"revision": 2})):
        with pytest.raises(ApiError, match="ASSET_ARCHIVED"):
            api.post(path + "/" + action, body, "alice", None)
    restored = api.post(path + "/restore", {"revision": 2}, "alice", None)[1]
    assert restored["status"] == "DRAFT" and restored["revision"] == 3


@pytest.mark.parametrize("runtime", ["openjiuwen-java-0.1.15", "openjiuwen-python-0.1.18"])
def test_build_admission_rechecks_archive_after_snapshot_was_read(tmp_path, runtime):
    from finance_solution_builder.workbench import Jobs
    jobs = Jobs(tmp_path, tmp_path, data_dir=tmp_path / "jobs")
    try:
        data = {"name":"Method", "content":"Verify scope", "capabilityIds":["account.balance.query"],
                "cases":[{"name":"normal", "query":"balance"}]}
        draft = jobs.method_assets.create("alice", data)
        trial = jobs.method_assets.record_trial("alice", draft["assetId"], draft, {"status":"PASS", "runtimeVerified":True})
        release = jobs.method_assets.publish("alice", draft["assetId"], 1, "1.0.0", trial["trialId"])
        snapshot = jobs.method_assets.release("alice", {k:release[k] for k in ("assetId","version","releaseDigest")})
        with jobs.lock:
            jobs.method_assets.lifecycle("alice", draft["assetId"], 1, "archive")
        with pytest.raises(ApiError, match="ASSET_ARCHIVED"):
            jobs.create({"runtime":runtime, "capabilities":["account.balance.query"],
                         "model":"configured-model", "connection":"local-simulator"}, "alice", method_snapshot=snapshot)
        assert jobs.jobs == {} and list(jobs.inputs.iterdir()) == []
    finally:
        jobs.close()
