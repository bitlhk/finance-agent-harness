import copy
import itertools
import json
import shutil
from pathlib import Path

import pytest
import yaml

from finance_solution_builder.business_assets import ancestry, catalog_bindings, check_source
from finance_solution_builder.directory import describe_directory
from finance_solution_builder.experience_contracts import ApiError
from finance_solution_builder.method_assets import MethodAssets
from finance_solution_builder.service import CAPABILITIES, CASHFLOW, solution_for

PROJECT = Path(__file__).resolve().parents[1]


@pytest.fixture
def registered(tmp_path):
    root = tmp_path / "harness"
    shutil.copytree(PROJECT / "tests/fixtures/business-assets", root)
    from tests.component_fixture import copy_admission_metadata
    copy_admission_metadata(root)
    solution = solution_for(sorted(CAPABILITIES), "fixture")
    for ref in solution["capabilityPackRefs"]:
        path = root / ref["source"].removeprefix("repo://")
        path.mkdir(parents=True, exist_ok=True)
        caps = [c for c in CAPABILITIES if
                (c in CASHFLOW if ref["id"] == "cashflow-readonly" else
                 c == ("wealth.facts.describe" if ref["id"] == "wealth-facts-description" else "account.balance.query"))]
        (path / "pack.yaml").write_text(yaml.safe_dump({"schemaVersion": "finance-capability-pack.v1",
            "systemPrompt": "Controlled source fixture", "packId": ref["id"], "version": ref["version"],
            "capabilities": [{"capabilityId": c, "toolName": c.replace(".", "_").replace("-", "_"), "name": "Controlled query",
                              "description": "Controlled query", "operation": "QUERY",
                              "inputSchema": {"type": "object"}, "requiredScopes": ["fixture:read"]} for c in sorted(caps)]}))
    for ref in solution["domainPackRefs"]:
        path = root / ref["source"].removeprefix("repo://")
        path.mkdir(parents=True)
        (path / "SKILL.md").write_text("# Fixture method\nReport missing data. Never grant access.")
        (path / "pack.yaml").write_text(yaml.safe_dump({"schemaVersion": "finance-domain-pack.v1",
            "packId": ref["id"], "version": ref["version"], "profile": "local-read-only",
            "capabilities": sorted(CASHFLOW) if ref["id"] == "cashflow-customer-self-service" else ["wealth.facts.describe"],
            "scope": ["Describe facts"], "outOfScope": ["Writes"], "skills": [{"directory": ".", "entry": "SKILL.md"}]}))
    profile = root / solution["profileRef"]["source"].removeprefix("repo://")
    profile.parent.mkdir(parents=True)
    profile.write_text("Synthetic customer only. Not a grant.")
    from tests.development_support_fixture import install_support
    install_support(root)
    return root


def derived(root, component="account.balance.query"):
    directory = describe_directory(root)
    binding, text = ancestry(PROJECT, root, component, directory["revision"])
    return {"name": "Bound method", "content": text, "capabilityIds": binding["capabilityIds"],
            "cases": [{"name": "normal", "query": "Known facts", "expectedTerms": ["facts"]}]}, {
                "kind": "BUSINESS_DERIVED", "binding": binding}


def test_seven_combinations_have_unique_readonly_packs_and_explicit_providers(registered):
    units = [{"account.balance.query"}, {"wealth.facts.describe"}, CASHFLOW]
    tested = []
    for n in range(1, 4):
        for combo in itertools.combinations(units, n):
            caps = sorted(set.union(*combo))
            solution = solution_for(caps, "test")
            snapshot = catalog_bindings(registered, solution)
            assert snapshot["digest"].startswith("sha256:")
            assert len({r["id"] for r in solution["capabilityPackRefs"]}) == len(combo)
            assert set(solution["capabilityBindings"]) == set(caps)
            assert all("retail-banking-basics" != r["id"] for r in solution["capabilityPackRefs"])
            tested.append(caps)
    assert len(tested) == 7
    assert len(solution_for(sorted(CAPABILITIES), "test")["providerRefs"]) == 2


@pytest.mark.parametrize("component", ["account.balance.query", "wealth.facts.describe", "finance.cashflow.readonly"])
def test_same_component_projection_and_bound_source_for_customization(registered, component):
    directory = describe_directory(registered)
    part = next(p for p in directory["components"] if p["id"] == component)
    data, source = derived(registered, component)
    assert data["capabilityIds"] == sorted(part["capabilityIds"])
    assert source["binding"]["basePackages"][0]["id"] == part["capabilityPackRefs"][0]["id"]
    assert source["binding"]["basePackages"][0]["digest"] == part["capabilityPackRefs"][0]["digest"]
    assert part["verification"]["installation"] == "NOT_OBSERVED"
    assert part["verification"]["environment"] == "PREFLIGHT_REQUIRED"
    assert part["verification"]["methodTrialRuntime"] == "JAVA_ONLY"
    assert part["methodKind"] == ("HOST_METHOD" if component == "account.balance.query" else "DOMAIN_PACK")


@pytest.mark.parametrize("change", ["version", "profile", "dependency", "provider"])
def test_catalog_mismatch_cannot_become_available(registered, change):
    path = registered / "catalog/finance-assets.v1.json"
    value = json.loads(path.read_text())
    if change == "version":
        value["capabilityPacks"][-1]["version"] = "9.9.9"
    if change == "profile":
        value["capabilityPacks"][-1]["compatibility"]["profiles"] = ["employee-assisted"]
    if change == "dependency":
        value["domainPacks"][0]["dependencies"][0]["version"] = "9.9.9"
    if change == "provider":
        value["providers"][0]["capabilities"] = []
    path.write_text(json.dumps(value))
    with pytest.raises(ValueError):
        catalog_bindings(registered, solution_for(sorted(CAPABILITIES), "test"))


def test_pins_base_and_keeps_legacy_release_bytes(registered, tmp_path):
    store = MethodAssets(tmp_path / "store", source_check=lambda v: check_source(PROJECT, registered, v))
    data, source = derived(registered)
    legacy = store.create("alice", data)
    draft = store.create("alice", data, source=source)
    refs = []
    for d in (legacy, draft):
        trial = store.record_trial("alice", d["assetId"], d, {"status": "PASS", "runtimeVerified": True})
        ref = store.publish("alice", d["assetId"], 1, "1.0.0", trial["trialId"])
        refs.append({k: ref[k] for k in ("assetId", "version", "releaseDigest")})
    before = {str(p): p.read_bytes() for p in store.root.rglob("release.json")}
    old = store.release("alice", refs[0])
    new = store.release("alice", refs[1])
    assert old["schemaVersion"] == "private-method-release.v0.1"
    assert new["schemaVersion"] == "private-method-release.v0.2"
    assert store.get("alice", legacy["assetId"])["baseRelationship"] == "NOT_RECORDED"
    assert new["source"] == source
    changed = copy.deepcopy(data)
    changed["content"] += "\nNew requirement"
    store.update("alice", draft["assetId"], 1, changed)
    with pytest.raises(ApiError, match="ASSET_FRESH_TRIAL_REQUIRED"):
        store.publish("alice", draft["assetId"], 2, "1.0.1", trial["trialId"])
    assert before == {str(p): p.read_bytes() for p in store.root.rglob("release.json")}
    with pytest.raises(ApiError, match="ASSET_NOT_AVAILABLE"):
        store.release("bob", refs[1])
    store.lifecycle("alice", draft["assetId"], 2, "archive")
    assert store.release("alice", refs[1]) == new
    with pytest.raises(ApiError, match="ASSET_ARCHIVED"):
        store.require_active("alice", draft["assetId"])


@pytest.mark.parametrize("change", ["digest", "scope", "revision", "unknown"])
def test_client_cannot_forge_base_binding(registered, tmp_path, change):
    store = MethodAssets(tmp_path / "store", source_check=lambda v: check_source(PROJECT, registered, v))
    data, source = derived(registered)
    if change == "digest":
        source["binding"]["basePackages"][0]["digest"] = "sha256:" + "0" * 64
    if change == "scope":
        data["capabilityIds"] = ["wealth.facts.describe"]
    if change == "revision":
        source["binding"]["directoryRevision"] = "0" * 64
    if change == "unknown":
        source["binding"]["grantedScopes"] = ["*"]
    with pytest.raises(ApiError):
        store.create("alice", data, source=source)
    assert store.list("alice") == []


def test_base_change_invalidates_trial_but_does_not_rewrite_old_release(registered, tmp_path):
    store = MethodAssets(tmp_path / "store", source_check=lambda v: check_source(PROJECT, registered, v))
    data, source = derived(registered, "wealth.facts.describe")
    draft = store.create("alice", data, source=source)
    trial = store.record_trial("alice", draft["assetId"], draft, {"status": "PASS", "runtimeVerified": True})
    ref = store.publish("alice", draft["assetId"], 1, "1.0.0", trial["trialId"])
    ref = {k: ref[k] for k in ("assetId", "version", "releaseDigest")}
    snapshot = store.release("alice", ref)
    (registered / "domain-packs/wealth/shared-facts/SKILL.md").write_text("Changed method")
    with pytest.raises(ApiError, match="ASSET_BASE_CHANGED"):
        store.trial_input("alice", draft["assetId"], 1)
    with pytest.raises(ApiError, match="ASSET_BASE_CHANGED"):
        store.check_source(snapshot)
    assert store.release("alice", ref) == snapshot
    assert store.materialize("alice", ref, tmp_path / "old-artifact")["digest"] == snapshot["installationDigest"]


def test_customize_api_preview_cancel_and_source_are_server_owned(registered, tmp_path):
    from finance_solution_builder.workbench import Jobs
    from finance_solution_builder.workbench_api import WorkbenchApi
    jobs = Jobs(PROJECT, registered, data_dir=tmp_path / "jobs")
    api = WorkbenchApi(jobs)
    try:
        body = {"componentId": "finance.cashflow.readonly",
                "directoryRevision": describe_directory(registered)["revision"], "name": "Cashflow method"}
        code, preview = api.post("/api/v1/assets/customize-preview", body, "alice")
        assert code == 200 and jobs.method_assets.list("alice") == []
        assert [c["query"] for c in preview["data"]["cases"]] == ["请查询 2026-08 收支汇总", "请查询 2026-08 收支分类"]
        assert len(preview["source"]["binding"]["capabilityIds"]) == 2
        with pytest.raises(ApiError):
            api.post("/api/v1/assets/customize", {**body, "data": preview["data"], "source": {"kind": "trusted"}}, "alice")
        code, draft = api.post("/api/v1/assets/customize", {**body, "data": preview["data"]}, "alice")
        assert code == 201 and draft["source"]["binding"] == preview["source"]["binding"]
        assert draft["source"]["caseProvenance"] == preview["source"]["caseProvenance"]
        assert preview["source"]["caseProvenance"]["kind"] == "COMPONENT_DECLARATION"
        assert jobs.method_assets.list("alice")[0]["componentId"] == body["componentId"]
        assert jobs.method_assets.list("bob") == []
        api.post("/api/v1/assets/" + draft["assetId"] + "/validate", {"revision": 1}, "alice")
        method = registered / "domain-packs/cashflow/customer-self-service/SKILL.md"
        method.write_text("Changed base method")
        with pytest.raises(ApiError, match="ASSET_BASE_CHANGED"):
            api.post("/api/v1/assets/" + draft["assetId"] + "/validate", {"revision": 1}, "alice")
        with pytest.raises(ApiError, match="DIRECTORY_CHANGED"):
            api.post("/api/v1/assets/customize-preview", {**body, "directoryRevision": "0" * 64}, "alice")
    finally:
        jobs.close()


@pytest.mark.parametrize('component',['account.balance.query','wealth.facts.describe','finance.cashflow.readonly'])
def test_customization_accepts_exact_unified_revision_and_preserves_legacy(registered,component):
    from finance_solution_builder.unified_catalog import unified_catalog
    directory=unified_catalog(PROJECT,registered)['directory']
    assert directory['revision']!=describe_directory(registered)['revision']
    binding,text=ancestry(PROJECT,registered,component,directory['revision'])
    assert binding['directoryRevision']==directory['revision'] and text
    check_source(PROJECT,registered,{'data':{'capabilityIds':binding['capabilityIds']},'source':{'kind':'BUSINESS_DERIVED','binding':binding}})
    old_data,old_source=derived(registered,component)
    check_source(PROJECT,registered,{'data':old_data,'source':old_source})
    assert old_data['capabilityIds']==binding['capabilityIds']
    with pytest.raises(ApiError,match='DIRECTORY_CHANGED'):
        ancestry(PROJECT,registered,component,'0'*64)


def test_reading_guide_requires_matching_source(tmp_path):
    from finance_solution_builder.method_guides import describe_guide
    guide = describe_guide("account.balance.query", tmp_path, PROJECT)
    assert guide["status"] == "SOURCE_MATCHED" and len(guide["steps"]) == 3
    assert guide["kind"] == "READING_GUIDE_NOT_EXECUTABLE_TRANSLATION"
    changed = tmp_path / "project"
    path = changed / "templates/local-readonly-service/methods/account-balance.md"
    path.parent.mkdir(parents=True)
    path.write_text("A different method")
    stale = describe_guide("account.balance.query", tmp_path, changed)
    assert stale["status"] == "REVIEW_REQUIRED" and stale["steps"] == []


def test_business_import_retains_source_and_rejects_forgery(registered, tmp_path):
    import base64
    from finance_solution_builder.workbench import Jobs
    from finance_solution_builder.workbench_api import WorkbenchApi
    jobs = Jobs(PROJECT, registered, data_dir=tmp_path / "jobs")
    api = WorkbenchApi(jobs)
    try:
        body = {"componentId": "account.balance.query", "directoryRevision": describe_directory(registered)["revision"],
                "name": "Imported account method", "filename": "SKILL.md",
                "base64": base64.b64encode(b"# Method\nExplain currency and missing data.").decode()}
        _, preview = api.post("/api/v1/assets/customize-import-preview", body, "alice")
        assert jobs.method_assets.list("alice") == []
        assert preview["source"]["importedFrom"]["kind"] == "IMPORTED"
        assert preview["data"]["capabilityIds"] == ["account.balance.query"]
        _, saved = api.post("/api/v1/assets/customize-import", {**body, "data": preview["data"]}, "alice")
        assert saved["source"] == preview["source"]
        assert jobs.method_assets.list("bob") == []
        wrong = copy.deepcopy(preview["data"])
        wrong["capabilityIds"] = ["wealth.facts.describe"]
        with pytest.raises(ApiError, match="ASSET_BASE_CHANGED"):
            api.post("/api/v1/assets/customize-import", {**body, "data": wrong}, "alice")
        with pytest.raises(ApiError, match="REQUEST_INVALID"):
            api.post("/api/v1/assets/customize-import", {**body, "data": preview["data"], "source": {}}, "alice")
    finally:
        jobs.close()
