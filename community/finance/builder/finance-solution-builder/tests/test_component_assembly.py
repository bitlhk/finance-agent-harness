from copy import deepcopy
import itertools
import json
from pathlib import Path
import shutil

import pytest
import yaml

from finance_solution_builder.component_assembly import AssemblyRejected, ComponentAssembly
from finance_solution_builder.service import solution_for

PROJECT = Path(__file__).resolve().parents[1]
FIXTURE = Path(__file__).parent / "fixtures/component-assembly"
UNITS = [["wealth.facts.describe"], ["account.balance.query"],
         ["finance.transaction-summary.query", "finance.spending-analysis.query"]]
COMBINATIONS = [sum(items, []) for n in range(1, 4) for items in itertools.combinations(UNITS, n)]


@pytest.fixture
def root(tmp_path):
    shutil.copytree(FIXTURE, tmp_path / "harness")
    return tmp_path / "harness"


def host():
    return json.loads((PROJECT / "templates/local-readonly-service/host-support.v0.1.json").read_text())


def run(root, solution, support=None):
    support = host() if support is None else support
    return ComponentAssembly(root).resolve(solution["capabilityPackRefs"], solution["providerRefs"],
        solution["capabilityBindings"], host=support, runtime=solution["runtime"],
        connection_kind="synthetic-bank-http.v1")


def edit(root, file, change):
    path = root / file
    value = json.loads(path.read_text())
    change(value)
    path.write_text(json.dumps(value))


@pytest.mark.parametrize("capabilities", COMBINATIONS)
def test_seven_legacy_combinations_unchanged_and_pure(root, capabilities):
    solution = solution_for(capabilities, "baseline")
    original = deepcopy(solution)
    before = {str(p.relative_to(root)): p.read_bytes() for p in root.rglob("*") if p.is_file()}
    resolved = run(root, solution)
    for key in ("capabilityPackRefs", "providerRefs", "domainPackRefs", "capabilityBindings"):
        assert resolved[key] == solution[key]
    assert solution == original
    assert before == {str(p.relative_to(root)): p.read_bytes() for p in root.rglob("*") if p.is_file()}
    assert resolved == run(root, solution)
    assert resolved["evidence"]["authorization"] == "RUNTIME_CHECK_REQUIRED"
    assert resolved["evidence"]["installation"] == "NOT_OBSERVED"
    assert all(c["operation"] == "QUERY" for c in resolved["capabilities"])


@pytest.mark.parametrize("mutation,code", [
    ("missing-declaration", "ASSEMBLY_SOURCE_UNAVAILABLE"),
    ("unknown-version", "ASSEMBLY_ASSET_VERSION_UNAVAILABLE"),
    ("missing-feature", "ASSEMBLY_HOST_FEATURE_MISSING"),
    ("runtime", "ASSEMBLY_RUNTIME_UNSUPPORTED"),
    ("connection", "ASSEMBLY_CONNECTION_UNSUPPORTED"),
    ("missing-binding", "ASSEMBLY_BINDING_MISSING"),
    ("extra-binding", "ASSEMBLY_BINDING_SET_MISMATCH"),
    ("provider-version", "ASSEMBLY_PROVIDER_SUPPORT_MISMATCH"),
    ("unknown-field", "ASSEMBLY_SCHEMA_INVALID"),
    ("owner", "ASSEMBLY_OWNER_MISMATCH"),
    ("profile", "ASSEMBLY_ASSET_INCOMPATIBLE"),
    ("dependency", "ASSEMBLY_DEPENDENCY_MISSING"),
    ("source", "ASSEMBLY_ASSET_SOURCE_MISMATCH"),
    ("artifact", "ASSEMBLY_PROVIDER_ARTIFACT_MISMATCH"),
    ("extra-ref-field", "ASSEMBLY_REFERENCE_INVALID"),
])
def test_admission_rejects_before_any_installation(root, mutation, code):
    solution = solution_for(UNITS[1], "negative")
    support = host()
    declaration = "capability-packs/account-balance-readonly/assembly.v0.1.json"
    if mutation == "missing-declaration": (root / declaration).unlink()
    if mutation == "unknown-version": solution["capabilityPackRefs"][0]["version"] = "99"
    if mutation == "missing-feature": support["features"].remove("trusted-grants.v1")
    if mutation == "runtime": solution["runtime"]["templateRef"] = "maven://fake:sdk:99"
    if mutation == "connection": support["connectionKinds"] = ["other"]
    if mutation == "missing-binding": solution["capabilityBindings"] = {}
    if mutation == "extra-binding": solution["capabilityBindings"]["unknown.capability"] = "non-ai-banking-reference"
    if mutation == "provider-version":
        edit(root, "providers/non-ai-banking/assembly.v0.1.json", lambda d: d["supports"][0]["pack"].update(version="99"))
    if mutation == "unknown-field": edit(root, declaration, lambda d: d.update(grantedScopes=["all"]))
    if mutation == "owner": edit(root, declaration, lambda d: d["owner"].update(version="99"))
    if mutation == "profile": support["profile"] = "admin"
    if mutation == "source": solution["capabilityPackRefs"][0]["source"] = "repo://not-the-selected-pack"
    if mutation == "artifact": solution["providerRefs"][0]["source"] = "maven://fake:provider:99"
    if mutation == "extra-ref-field": solution["providerRefs"][0]["trusted"] = True
    if mutation == "dependency":
        def change(catalog):
            asset = next(a for a in catalog["capabilityPacks"] if a["id"] == "account-balance-readonly")
            asset["dependencies"] = [{"kind": "capability-pack", "id": "missing", "version": "1"}]
        edit(root, "catalog/finance-assets.v1.json", change)
    with pytest.raises(AssemblyRejected, match=code): run(root, solution, support)


def test_partial_atomic_pack_not_silently_installed(root):
    solution = solution_for(UNITS[2], "partial")
    solution["capabilityBindings"].pop("finance.spending-analysis.query")
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_BINDING_MISSING"):
        run(root, solution)


@pytest.mark.parametrize('same_pack_id', [True, False])
@pytest.mark.parametrize('changed_definition', [True, False])
def test_versioned_duplicate_capability_remains_existing_admission_rejection(root, same_pack_id, changed_definition):
    solution = solution_for(UNITS[1], 'version-conflict')
    original = solution['capabilityPackRefs'][0]
    path = root / 'capability-packs/second-version'
    shutil.copytree(root / original['source'][7:], path)
    identity = {'id': original['id'] if same_pack_id else 'second-pack', 'version': '0.2.0'}
    pack = yaml.safe_load((path / 'pack.yaml').read_text())
    pack.update(packId=identity['id'], version=identity['version'])
    if changed_definition:
        pack['capabilities'][0]['toolName'] = 'other_balance_query'
    (path / 'pack.yaml').write_text(yaml.safe_dump(pack))
    edit(root, 'capability-packs/second-version/assembly.v0.1.json', lambda value: value.update(owner=identity))
    def add_version(catalog):
        asset = deepcopy(next(a for a in catalog['capabilityPacks'] if a['id'] == original['id']))
        asset.update(**identity, source='repo://capability-packs/second-version')
        catalog['capabilityPacks'].append(asset)
    edit(root, 'catalog/finance-assets.v1.json', add_version)
    solution['capabilityPackRefs'].append({**identity, 'source': 'repo://capability-packs/second-version'})
    before = {str(p.relative_to(root)): p.read_bytes() for p in root.rglob('*') if p.is_file()}
    with pytest.raises(AssemblyRejected, match='ASSEMBLY_DUPLICATE_CAPABILITY'):
        run(root, solution)
    assert before == {str(p.relative_to(root)): p.read_bytes() for p in root.rglob('*') if p.is_file()}


def test_duplicate_definition_within_pack_rejected_by_existing_admission(root):
    solution = solution_for(UNITS[1], 'duplicate-definition')
    path = root / solution['capabilityPackRefs'][0]['source'][7:] / 'pack.yaml'
    pack = yaml.safe_load(path.read_text())
    pack['capabilities'].append(deepcopy(pack['capabilities'][0]))
    path.write_text(yaml.safe_dump(pack))
    with pytest.raises(AssemblyRejected, match='ASSEMBLY_CATALOG_CAPABILITY_MISMATCH'):
        run(root, solution)


def test_multiple_provider_versions_cannot_enter_single_assembly(root):
    solution = solution_for(UNITS[1], 'provider-ambiguity')
    solution['providerRefs'].append({**solution['providerRefs'][0], 'version': '0.2.0'})
    with pytest.raises(AssemblyRejected, match='ASSEMBLY_SELECTION_INVALID'):
        run(root, solution)


def test_catalog_change_cannot_change_old_evidence(root):
    solution = solution_for(UNITS[1], "immutable")
    old = run(root, solution)
    snapshot = deepcopy(old)
    edit(root, "catalog/finance-assets.v1.json", lambda d: d.update(catalogRevision="new-revision"))
    new = run(root, solution)
    assert old == snapshot
    assert old["evidence"]["files"] != new["evidence"]["files"]


def test_duplicate_json_rejected(root):
    path = root / "capability-packs/account-balance-readonly/assembly.v0.1.json"
    path.write_text(path.read_text().replace('"kind": "capability-pack",', '"kind": "capability-pack", "kind": "provider",'))
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_DUPLICATE_FIELD"):
        run(root, solution_for(UNITS[1], "duplicate"))


def test_reference_escape_rejected(root):
    def change(catalog):
        next(a for a in catalog["capabilityPacks"] if a["id"] == "account-balance-readonly")["source"] = "repo://../../outside"
    edit(root, "catalog/finance-assets.v1.json", change)
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_ASSET_SOURCE_MISMATCH"):
        run(root, solution_for(UNITS[1], "escape"))


def test_new_controlled_capability_does_not_require_resolver_business_branch(root):
    # This proves resolver extension, not a new running Provider or human onboarding.
    cid, pid = "fixture.document-status.query", "fixture-document-status"
    solution = solution_for(UNITS[1], "extension")
    path = root / "capability-packs/account-balance-readonly"
    replacement = root / "capability-packs" / pid
    shutil.copytree(path, replacement)
    pack = yaml.safe_load((replacement / "pack.yaml").read_text())
    pack["packId"] = pid
    pack["capabilities"][0].update(capabilityId=cid, toolName="fixture_document_status", requiredScopes=["document:read"])
    (replacement / "pack.yaml").write_text(yaml.safe_dump(pack))
    edit(root, f"capability-packs/{pid}/assembly.v0.1.json", lambda d: d["owner"].update(id=pid))
    def catalog_change(catalog):
        asset = deepcopy(next(a for a in catalog["capabilityPacks"] if a["id"] == "account-balance-readonly"))
        asset.update(id=pid, source=f"repo://capability-packs/{pid}", capabilities=[cid])
        catalog["capabilityPacks"].append(asset)
        next(a for a in catalog["providers"] if a["id"] == "non-ai-banking-reference")["capabilities"].append(cid)
    edit(root, "catalog/finance-assets.v1.json", catalog_change)
    edit(root, "providers/non-ai-banking/assembly.v0.1.json", lambda d: d["supports"].append(
        {"pack": {"id": pid, "version": "0.1.0"}, "capabilityIds": [cid], "operation": "QUERY"}))
    solution["capabilityPackRefs"] = [{"id": pid, "version": "0.1.0", "source": f"repo://capability-packs/{pid}"}]
    solution["capabilityBindings"] = {cid: "non-ai-banking-reference"}
    result = run(root, solution)
    assert result["requiredScopes"] == ["document:read"]
    assert [c["capabilityId"] for c in result["capabilities"]] == [cid]
    assert "grantedScopes" not in result
