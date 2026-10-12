"""Store/API integration using controlled reports in the existing runner format."""
import copy
import json
import os
from pathlib import Path
from threading import RLock
from types import SimpleNamespace
from urllib.parse import quote

import pytest

from finance_solution_builder.business_assets import check_source, component_development_support
from finance_solution_builder.experience_contracts import ApiError
from finance_solution_builder.method_api import MethodApi
from finance_solution_builder.method_assets import MethodAssets, canonical, installation_digest, runtime_files, runtime_reference, sha
from finance_solution_builder.method_verification import owner_method_verifications
from finance_solution_builder.unified_catalog import unified_catalog
from tests.test_business_assets import registered
from tests.test_business_packages import _fixture_anchor

PROJECT = Path(__file__).resolve().parents[1]
SDK = "maven://com.openjiuwen:agent-core-java:0.1.15"
INTEGRATOR = "maven://io.github.bitlhk.finance:finance-harness-openjiuwen-java:0.1.0-SNAPSHOT"
COMPONENT = "account.balance.query"


@pytest.fixture
def context(registered, tmp_path):
    contract = registered / "contracts/json-schema/capability/capability-result.v1.schema.json"
    contract.parent.mkdir(parents=True, exist_ok=True)
    contract.write_text('{"type":"object"}')
    anchor = _fixture_anchor(registered)
    store = MethodAssets(tmp_path / "store", source_check=lambda v: check_source(PROJECT, registered, v))
    jobs = SimpleNamespace(project=PROJECT, builder=SimpleNamespace(harness_repo=registered),
                           method_assets=store, business_package_anchor=anchor, lock=RLock(), jobs={})
    revision = unified_catalog(PROJECT, registered)["directory"]["revision"]
    return jobs, MethodApi(jobs), {"componentId": COMPONENT, "directoryRevision": revision, "name": "Owner method"}


def controlled_report(draft):
    version = "0.0.0"
    digest = installation_digest(runtime_files(draft["assetId"], version, draft["data"]))
    ref = runtime_reference({"assetId": draft["assetId"], "version": version, "installationDigest": digest})
    runtime = {"family": "openjiuwen", "language": "java", "templateRef": SDK, "integrationRef": INTEGRATOR}
    lock = {"runtime": runtime, "assets": [
        {"kind": "RUNTIME_TEMPLATE", "id": SDK, "version": "0.1.15", "digest": "sha256:" + "a" * 64},
        {"kind": "RUNTIME_INTEGRATION", "id": INTEGRATOR, "version": "0.1.0-SNAPSHOT", "digest": "sha256:" + "b" * 64},
        {"kind": "DOMAIN_PACK", **ref}],
        "domainInstallations": [{"packId": draft["assetId"], "packVersion": version,
                                 "profile": "local-read-only", "capabilities": draft["data"]["capabilityIds"]}]}
    return {"schemaVersion": "private-method-trial.v0.1", "status": "PASS", "runtimeVerified": True,
            "model": "CONTROLLED_TEST", "source": "CONTROLLED_HTTP",
            "trialRuntime": {"family": "openjiuwen", "language": "java", "source": "GENERATED_JAVA_HOST"},
            "contentDigest": draft["contentDigest"], "revision": draft["revision"],
            "baseSourceDigest": sha(canonical(draft["source"])),
            "trialInstallationVersion": version, "trialInstallationDigest": digest,
            "comparison": {"candidate": {"runtime": runtime, "businessWriteAttempts": 0,
                "cases": [{"queryDigest": sha(c["query"].encode()), "loadedMethodObserved": True,
                           "modelRequests": 2, "businessReadCount": 1, "toolCount": 1,
                           "runtimeLock": copy.deepcopy(lock)} for c in draft["data"]["cases"]]}}}


def publish(context, owner="alice", *, version="1.0.0", change=None, name="Owner method"):
    jobs, api, body = context
    _, draft = api.post("/api/v1/assets/customize", {**body, "data": {"name": name,
        "content": "# Changed SOP\nOnly the authorized owner sees this method.",
        "capabilityIds": [COMPONENT]}}, owner, None)
    report = controlled_report(draft)
    if change:
        change(report)
    trial = jobs.method_assets.record_trial(owner, draft["assetId"], draft, report)
    reference = jobs.method_assets.publish(owner, draft["assetId"], draft["revision"], version,
        trial["trialId"], binding_confirmations=[COMPONENT])
    return {k: reference[k] for k in ("assetId", "version", "releaseDigest")}


def support(context, owner="alice", scope=None):
    jobs, _, body = context
    return component_development_support(PROJECT, jobs.builder.harness_repo, COMPONENT,
        body["directoryRevision"], store=jobs.method_assets, owner=owner, scope=scope)


def test_component_contents_reads_owner_store_without_promoting_base_pass(context):
    jobs, api, body = context
    alice = publish(context)
    bob = publish(context, "bob", name="BOB_PRIVATE_TEXT")
    store_bytes = {p: p.read_bytes() for p in jobs.method_assets.root.rglob("*.json")}
    path = f"/api/v1/assets/components/{COMPONENT}/{body['directoryRevision']}/contents"
    result = api.get(path, "alice")[1]["developmentSupport"]
    assert result["validation"]["status"] == "NOT_RUN"
    assert result["reportScan"]["status"] == "COMPLETE"
    assert len(result["derivedMethodVerifications"]) == 1
    record = result["derivedMethodVerifications"][0]
    assert record["assetRef"] == alice and record["validation"]["status"] == "RUNTIME_CHECK_PASSED"
    assert record["name"] == "Owner method"
    assert record["validation"]["kind"] == "METHOD_RUNTIME_CHECK"
    assert record["validation"]["environmentStatus"] == "NOT_FULLY_RECORDED"
    assert record["label"] == "派生方法版本的验证" and record["baseMatch"] == "MATCHED"
    assert record["testedMethodRef"]["version"] == "0.0.0"
    assert record["binding"] == "SAME_CONTENT_PUBLISHED_VERSION"
    assert record["actualRuntime"]["version"] == "0.1.15"
    assert record["actualRuntime"]["integrator"]["id"] == INTEGRATOR
    assert record["scopeMatch"] == "NOT_SELECTED"
    assert "scope.hostId" in record["missingFields"]
    assert record["timeSource"] == "TRIAL_RECORDED_AT"
    serialized = json.dumps(result)
    assert bob["assetId"] not in serialized and "BOB_PRIVATE_TEXT" not in serialized
    assert str(jobs.method_assets.root) not in serialized and "Changed SOP" not in serialized
    assert {p: p.read_bytes() for p in jobs.method_assets.root.rglob("*.json")} == store_bytes
    with pytest.raises(ApiError, match="ASSET_NOT_AVAILABLE"):
        jobs.method_assets.release_trial("bob", alice)


def test_release_contents_augments_original_verification_from_same_trial(context):
    jobs, api, _ = context
    ref = publish(context)
    release, trial = jobs.method_assets.release_trial("alice", ref)
    result = support(context)["derivedMethodVerifications"][0]
    view = api.get(result["reportRef"]["url"], "alice")[1]
    verification = view["verification"]
    assert verification["trialId"] == trial["trialId"] and verification["runtime"] == trial["report"]["trialRuntime"]
    assert verification["reportDigest"] == sha(canonical(trial["report"]))
    assert verification["casesDigest"] == sha(canonical(release["data"]["cases"]))
    assert verification["actualRuntime"] == result["actualRuntime"]
    assert verification["reportRef"] == result["reportRef"]
    assert "developmentSupport" not in view
    with pytest.raises(ApiError, match="ASSET_NOT_AVAILABLE"):
        api.get(result["reportRef"]["url"], "bob")


@pytest.mark.parametrize("colon", [":", "%3A", "%3a"])
def test_component_report_browser_route_keeps_frozen_release_identity(context, colon):
    jobs, api, body = context
    ref = publish(context)
    draft = jobs.method_assets.get("alice", ref["assetId"])
    jobs.method_assets.update("alice", ref["assetId"], draft["revision"],
                             {**draft["data"], "name": "Changed draft", "content": "Later SOP"})
    before = {p: p.read_bytes() for p in jobs.method_assets.root.rglob("*.json")}
    component_route = f"/api/v1/assets/components/{COMPONENT}/{quote(body['directoryRevision'], safe='')}/contents"
    component = api.get(component_route, "alice")[1]
    support_view = component["developmentSupport"]
    assert support_view["validation"]["status"] == "NOT_RUN"
    record = support_view["derivedMethodVerifications"][0]
    assert record["assetRef"] == ref
    assert record["validation"]["status"] == "RUNTIME_CHECK_PASSED"
    # verification-version uses encodeURIComponent on the button's version and digest.
    digest = quote(record["assetRef"]["releaseDigest"], safe="").replace("%3A", colon)
    route = f"/api/v1/assets/{ref['assetId']}/packages/{quote(ref['version'], safe='')}/{digest}"
    code, view = api.get(route + "/contents", "alice")
    assert code == 200 and view["name"] == "Owner method"
    assert {k: view["identity"][k] for k in ref} == ref
    assert view["verification"]["assetRef"] == ref
    assert view["verification"]["validation"]["status"] == "RUNTIME_CHECK_PASSED"
    assert view == api.get(record["reportRef"]["url"], "alice")[1]
    assert view == api.get(route + "/contents", "alice")[1]
    assert all(f["path"] and f["fileId"] for f in view["files"])
    assert api.get(route, "alice") == api.get(record["reportRef"]["url"].removesuffix("/contents"), "alice")
    assert {p: p.read_bytes() for p in jobs.method_assets.root.rglob("*.json")} == before


@pytest.mark.parametrize("field,value,status", [("runtimeId", "openjiuwen-python-0.1.18", "SCOPE_MISMATCH"),
    ("integratorId", "another-integrator", "SCOPE_MISMATCH"), ("profileId", "another-profile", "SCOPE_MISMATCH"),
    ("hostId", "unrecorded-host", "SCOPE_NOT_RECORDED"), ("testDataId", "unrecorded-data", "SCOPE_NOT_RECORDED")])
def test_selected_scope_needs_real_matching_evidence(context, field, value, status):
    publish(context)
    result = support(context, scope={field: value})
    assert result["validation"]["status"] == "NOT_RUN"
    assert result["derivedMethodVerifications"][0]["validation"]["status"] == status


def test_matching_recorded_runtime_scope_is_explicit(context):
    publish(context)
    record = support(context, scope={"runtimeId": "openjiuwen-java-0.1.15", "integratorId": INTEGRATOR})["derivedMethodVerifications"][0]
    assert record["scopeMatch"] == "MATCHED" and record["validation"]["status"] == "RUNTIME_CHECK_PASSED"


@pytest.mark.parametrize("mutation,status", [("content", "CONTENT_MISMATCH"), ("source", "SOURCE_MISMATCH"),
    ("method_digest", "METHOD_DIGEST_MISMATCH"), ("case", "CASE_MISMATCH"),
    ("sdk", "RUNTIME_MISMATCH"), ("python_report", "RUNTIME_MISMATCH"),
    ("old_method_lock", "EVIDENCE_INCOMPLETE"), ("platform", "UNSCOPED_REPORT")])
def test_store_backed_invalid_report_cannot_become_method_pass(context, mutation, status):
    def change(report):
        if mutation == "content": report["contentDigest"] = "sha256:" + "0" * 64
        if mutation == "source": report["baseSourceDigest"] = "sha256:" + "0" * 64
        if mutation == "method_digest": report["trialInstallationDigest"] = "sha256:" + "0" * 64
        case = report["comparison"]["candidate"]["cases"][0]
        if mutation == "case": case["queryDigest"] = "sha256:" + "0" * 64
        if mutation == "sdk": case["runtimeLock"]["assets"][0]["version"] = "0.0.1"
        if mutation == "python_report": report["trialRuntime"]["language"] = "python"
        if mutation == "old_method_lock": case["runtimeLock"]["assets"][2]["version"] = "0.0.1"
        if mutation == "platform":
            report.clear()
            report.update(status="PASS", runtimeVerified=True, testsPassed=1000)
    publish(context, change=change)
    record = support(context)["derivedMethodVerifications"][0]
    assert record["validation"]["status"] == status


def test_archived_multiple_releases_use_immutable_cases_not_current_draft(context):
    jobs, api, _ = context
    ref = publish(context)
    store = jobs.method_assets
    draft = store.get("alice", ref["assetId"])
    changed = {**draft["data"], "name": "Later frozen method name", "content": "A later SOP",
               "cases": [{"name": "new", "query": "New question", "expectedTerms": []}]}
    draft = store.update("alice", ref["assetId"], draft["revision"], changed)
    trial = store.record_trial("alice", ref["assetId"], draft, controlled_report(draft))
    store.publish("alice", ref["assetId"], draft["revision"], "2.0.0", trial["trialId"], [COMPONENT])
    store.lifecycle("alice", ref["assetId"], draft["revision"], "archive")
    reports = support(context)["derivedMethodVerifications"]
    assert {r["assetRef"]["version"] for r in reports} == {"1.0.0", "2.0.0"}
    assert {r["assetRef"]["version"]: r["name"] for r in reports} == {
        "1.0.0": "Owner method", "2.0.0": "Later frozen method name"}
    assert len({r["casesDigest"] for r in reports}) == 2
    assert all(r["validation"]["status"] == "RUNTIME_CHECK_PASSED" for r in reports)
    assert store.release("alice", ref)["data"]["cases"] != changed["cases"]


def test_old_missing_fields_are_not_inferred_or_rewritten(context):
    def old(report):
        report.pop("trialInstallationVersion")
        report.pop("trialInstallationDigest")
        report.pop("comparison")
    ref = publish(context, change=old)
    jobs, _, _ = context
    before = {p: p.read_bytes() for p in jobs.method_assets.root.rglob("*.json")}
    record = support(context)["derivedMethodVerifications"][0]
    assert record["testedMethodRef"] is None and record["actualRuntime"] is None
    assert "report.trialInstallationVersion" in record["missingFields"]
    assert record["validation"]["status"] == "EVIDENCE_INCOMPLETE"
    assert {p: p.read_bytes() for p in jobs.method_assets.root.rglob("*.json")} == before


def test_corrupt_release_trial_is_skipped_without_exposing_contents(context):
    jobs, _, _ = context
    ref = publish(context)
    file = jobs.method_assets._folder("alice", ref["assetId"]) / "releases/1.0.0/trial.json"
    file.write_text('{"secret":"DO_NOT_EXPOSE"}')
    result = support(context)
    assert result["derivedMethodVerifications"] == [] and result["reportScan"]["status"] == "PARTIAL"
    assert "DO_NOT_EXPOSE" not in json.dumps(result)


def test_scan_is_bounded_before_store_reads_and_never_calls_list(context, monkeypatch):
    from finance_solution_builder import method_verification
    jobs, _, _ = context
    for _ in range(3): publish(context)
    monkeypatch.setattr(method_verification, "MAX_SCAN_RELEASES", 1)
    monkeypatch.setattr(jobs.method_assets, "list", lambda *args: pytest.fail("unbounded list"))
    original = jobs.method_assets.release_trial
    calls = []
    def read(owner, ref):
        calls.append((owner, ref))
        return original(owner, ref)
    monkeypatch.setattr(jobs.method_assets, "release_trial", read)
    result = support(context)
    assert result["reportScan"]["truncated"] is True
    assert result["reportScan"]["scannedReleases"] == 1 and len(calls) == 1
    assert len(result["derivedMethodVerifications"]) == 1


def test_cross_owner_symlink_is_not_followed(context):
    jobs, _, _ = context
    bob = publish(context, "bob")
    alice = jobs.method_assets._space("alice")
    alice.mkdir()
    (alice / bob["assetId"]).symlink_to(jobs.method_assets._folder("bob", bob["assetId"]), target_is_directory=True)
    result = support(context)
    assert result["derivedMethodVerifications"] == []
    assert bob["assetId"] not in json.dumps(result)


def test_old_base_version_is_labelled_but_never_changes_base_validation(context):
    jobs, _, _ = context
    publish(context)
    subjects = copy.deepcopy(support(context)["subjects"])
    subjects[0]["version"] = "99.0.0"
    reports, _ = owner_method_verifications(jobs.method_assets, "alice", COMPONENT, subjects)
    assert reports[0]["baseMatch"] == "VERSION_MISMATCH"
    subjects[0]["version"] = "0.1.0"
    subjects[0]["digest"] = "sha256:" + "0" * 64
    reports, _ = owner_method_verifications(jobs.method_assets, "alice", COMPONENT, subjects)
    assert reports[0]["baseMatch"] == "DIGEST_MISMATCH"


def test_missing_current_declaration_does_not_hide_owner_history(context):
    jobs, _, _ = context
    publish(context)
    (jobs.builder.harness_repo / "metadata/development-support/index.v0.1.json").unlink()
    result = support(context)
    assert result["status"] == "NOT_DECLARED" and result["methodCases"] == []
    assert result["validation"]["status"] == "NOT_RUN"
    assert result["derivedMethodVerifications"][0]["validation"]["status"] == "RUNTIME_CHECK_PASSED"


@pytest.mark.parametrize("limit", ["MAX_FILE_BYTES", "MAX_SCAN_BYTES", "MAX_SCAN_ENTRIES", "MAX_REPORTS"])
def test_each_scan_limit_prevents_unbounded_release_reads(context, monkeypatch, limit):
    from finance_solution_builder import method_verification
    jobs, _, _ = context
    publish(context)
    monkeypatch.setattr(method_verification, limit, 0)
    monkeypatch.setattr(jobs.method_assets, "release_trial", lambda *args: pytest.fail("read beyond budget"))
    result = support(context)
    assert result["derivedMethodVerifications"] == []
    assert result["reportScan"]["status"] in {"PARTIAL", "TRUNCATED"}


def test_nonregular_report_file_is_skipped_before_open(context):
    jobs, _, _ = context
    ref = publish(context)
    path = jobs.method_assets._folder("alice", ref["assetId"]) / "releases/1.0.0/trial.json"
    path.unlink()
    os.mkfifo(path)
    result = support(context)
    assert result["derivedMethodVerifications"] == []
    assert result["reportScan"]["status"] == "PARTIAL"
