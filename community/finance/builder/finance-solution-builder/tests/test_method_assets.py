import base64
import io
import json
import os
import stat
import zipfile

import pytest

from finance_solution_builder.experience_contracts import ApiError
from finance_solution_builder.method_assets import MethodAssets, runtime_files


def content():
    return {"name": "Asset check", "content": "# Method\nUse [policy](references/policy.md).",
            "resources": {"references/policy.md": "Never mix currencies. SOURCE-MARKER-ONE"},
            "requiredResources": ["references/policy.md"], "capabilityIds": ["wealth.facts.describe"],
            "cases": [{"name": "normal", "query": "Describe my assets", "expectedTerms": []}]}


@pytest.fixture
def store(tmp_path):
    return MethodAssets(tmp_path / "private")


def make_tested(store):
    draft = store.create("alice", content())
    report = store.record_trial("alice", draft["assetId"], draft,
                                {"status": "PASS", "runtimeVerified": True, "model": "CONTROLLED_TEST"})
    return draft, report


def test_revision_and_owner_boundary(store):
    draft = store.create("alice", content())
    assert store.list("bob") == []
    with pytest.raises(ApiError, match="ASSET_NOT_AVAILABLE"):
        store.get("bob", draft["assetId"])
    updated = store.update("alice", draft["assetId"], 1, content())
    assert updated["revision"] == 2
    with pytest.raises(ApiError, match="ASSET_REVISION_CONFLICT"):
        store.update("alice", draft["assetId"], 1, content())


def test_publish_pins_old_content_and_materializes_only_selected_resources(store, tmp_path):
    draft, report = make_tested(store)
    release = store.publish("alice", draft["assetId"], 1, "1.0.0", report["trialId"])
    ref = {k: release[k] for k in ("assetId", "version", "releaseDigest")}
    with pytest.raises(ApiError, match="ASSET_NOT_AVAILABLE"):
        store.release("bob", ref)
    changed = content()
    changed["resources"]["references/policy.md"] = "SOURCE-MARKER-TWO"
    store.update("alice", draft["assetId"], 1, changed)
    assets = tmp_path / "candidate" / "assets"
    mapped = store.materialize("alice", ref, assets)
    entry = next(assets.rglob("SKILL.md")).read_text()
    assert "SOURCE-MARKER-ONE" in entry and "SOURCE-MARKER-TWO" not in entry
    assert "expectedTerms" not in entry
    assert mapped["digest"] == release["installationDigest"]
    with pytest.raises(ApiError, match="ASSET_FRESH_TRIAL_REQUIRED"):
        store.publish("alice", draft["assetId"], 2, "1.1.0", report["trialId"])


def test_expired_or_unexecuted_report_cannot_publish(store):
    draft, report = make_tested(store)
    store.clock = lambda: report["createdAt"] + 86401
    with pytest.raises(ApiError, match="ASSET_FRESH_TRIAL_REQUIRED"):
        store.publish("alice", draft["assetId"], 1, "1.0.0", report["trialId"])
    store.clock = lambda: report["createdAt"]
    invalid = store.record_trial("alice", draft["assetId"], draft, {"status": "PASS", "runtimeVerified": False})
    with pytest.raises(ApiError, match="ASSET_FRESH_TRIAL_REQUIRED"):
        store.publish("alice", draft["assetId"], 1, "1.0.0", invalid["trialId"])


def test_immutable_version_and_tamper_rejected(store):
    draft, report = make_tested(store)
    release = store.publish("alice", draft["assetId"], 1, "1.0.0", report["trialId"])
    ref = {k: release[k] for k in ("assetId", "version", "releaseDigest")}
    with pytest.raises(ApiError, match="ASSET_VERSION_EXISTS"):
        store.publish("alice", draft["assetId"], 1, "1.0.0", report["trialId"])
    path = next(store.root.rglob("release.json"))
    raw = json.loads(path.read_text())
    raw["data"]["content"] = "Changed"
    path.write_text(json.dumps(raw))
    with pytest.raises(ApiError, match="ASSET_RELEASE_CHANGED"):
        store.release("alice", ref)


@pytest.mark.parametrize("mutation,code", [
    (lambda d: d["resources"].clear(), "ASSET_REFERENCE_MISSING"),
    (lambda d: d["requiredResources"].clear(), "ASSET_REFERENCE_NOT_SELECTED"),
    (lambda d: d.update(capabilityIds=["transfer.submit"]), "ASSET_CAPABILITY_UNSUPPORTED"),
    (lambda d: d.update(content="Run scripts/analysis.py"), "ASSET_SCRIPT_EXECUTION_UNSUPPORTED"),
    (lambda d: d.update(kind="TERMINOLOGY"), "ASSET_TYPE_PENDING_INTEGRATION"),
])
def test_dependency_failures(store, mutation, code):
    data = content()
    mutation(data)
    draft = store.create("alice", data)
    assert code in {i["code"] for i in draft["validation"]["issues"]}
    with pytest.raises(ApiError, match="ASSET_VALIDATION_REQUIRED"):
        store.trial_input("alice", draft["assetId"], 1)


def test_single_file_import_keeps_original_and_requires_explicit_bindings(store):
    raw = b"---\nname: sample\nallowed-tools: wealth_facts_describe\n---\n# Check\n[missing](references/missing.md)"
    draft = store.import_asset("alice", "SKILL.md", base64.b64encode(raw).decode(), "Imported")
    assert draft["data"]["capabilityIds"] == []
    assert draft["validation"]["status"] == "INVALID"
    original = json.loads(next(store.root.rglob("original.json")).read_text())
    assert base64.b64decode(original["base64"]) == raw


@pytest.mark.parametrize(
    "name",
    [
        "../outside.md",
        "/etc/secret",
        "references/../../outside.md",
        pytest.param(
            "references\\outside.md",
            marks=pytest.mark.skipif(
                os.name == "nt",
                reason="zipfile normalizes backslashes before archive validation on Windows",
            ),
        ),
    ],
)
def test_archive_path_rejected(store, name):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("SKILL.md", "Test")
        archive.writestr(name, "secret")
    with pytest.raises(ApiError):
        store.import_asset("alice", "method.zip", base64.b64encode(output.getvalue()).decode(), "Invalid")
    assert not store.list("alice")


def test_archive_links_and_expansion_rejected(store):
    for symlink in (True, False):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("SKILL.md", "Test")
            info = zipfile.ZipInfo("references/a.txt")
            if symlink:
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                archive.writestr(info, "/etc/passwd")
            else:
                archive.writestr("references/a.txt", "a" * 64000)
        with pytest.raises(ApiError):
            store.import_asset("alice", "method.zip", base64.b64encode(output.getvalue()).decode(), "Invalid")


def test_trial_cannot_finish_over_new_revision(store):
    draft = store.create("alice", content())
    store.update("alice", draft["assetId"], 1, content())
    with pytest.raises(ApiError, match="ASSET_REVISION_CONFLICT"):
        store.record_trial("alice", draft["assetId"], draft, {"status": "PASS", "runtimeVerified": True})


@pytest.mark.parametrize("prefix", ["", "pu-fa-analysis/"])
def test_named_zip_import_with_optional_single_wrapper(store, prefix):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        if prefix:
            archive.writestr(prefix, "")
        archive.writestr(prefix + "SKILL.md", "Check references/policy.md")
        archive.writestr(prefix + "references/policy.md", "Do not infer missing facts.")
    draft = store.import_asset("alice", "pu-fa-analysis.zip", base64.b64encode(output.getvalue()).decode(), "Imported")
    assert draft["source"]["filename"] == "pu-fa-analysis.zip"
    assert draft["data"]["resources"] == {"references/policy.md": "Do not infer missing facts."}


@pytest.mark.parametrize("filename", ["../method.zip", "folder/method.zip", "a\\method.zip", ".zip", "a\x00.zip"])
def test_upload_display_name_cannot_be_a_path(store, filename):
    with pytest.raises(ApiError):
        store.import_asset("alice", filename, "", "Invalid")


def test_release_and_modified_draft_have_distinct_status(store):
    draft, report = make_tested(store)
    store.publish("alice", draft["assetId"], 1, "1.0.0", report["trialId"])
    published = store.list("alice")[0]
    assert published["status"] == "PUBLISHED"
    assert published["hasUnpublishedChanges"] is False
    changed = content()
    changed["content"] += "\nNew method step."
    changed["capabilityIds"] = ["account.balance.query"]
    updated = store.update("alice", draft["assetId"], 1, changed)
    assert updated["hasUnpublishedChanges"] is True
    pending = store.list("alice")[0]
    assert pending["status"] == "UNPUBLISHED_CHANGES"
    assert pending["versions"][0]["version"] == "1.0.0"
    assert pending["versions"][0]["capabilityIds"] == ["wealth.facts.describe"]


def test_no_expectations_cannot_be_reported_as_professional_quality_pass(store):
    draft = store.create("alice", content())
    trial = store.record_trial("alice", draft["assetId"], draft,
        {"status": "PASS", "runtimeVerified": True, "professionalQuality": "PASS"})
    assert trial["report"]["professionalQuality"] == "NOT_EVALUATED"


def test_method_only_replaces_its_declared_scope(store):
    from finance_solution_builder.method_assets import runtime_reference
    from finance_solution_builder.service import solution_for, validate_service
    data = content()
    data["capabilityIds"] = ["account.balance.query"]
    asset = store.create("alice", data)
    trial = store.record_trial("alice", asset["assetId"], asset, {"status": "PASS", "runtimeVerified": True})
    release = store.publish("alice", asset["assetId"], 1, "1.0.0", trial["trialId"])
    ref = {k: release[k] for k in ("assetId", "version", "releaseDigest")}
    frozen = store.release("alice", ref)
    caps = ["account.balance.query", "wealth.facts.describe"]
    solution = solution_for(caps, "test-build", runtime_reference(frozen), data["capabilityIds"])
    assert {r["id"] for r in solution["domainPackRefs"]} == {"wealth-shared-facts", asset["assetId"]}
    assert set(validate_service(solution)) == set(caps)


@pytest.mark.parametrize("scope", [["finance.transaction-summary.query"],
                                  ["finance.spending-analysis.query"],
                                  ["finance.transaction-summary.query", "finance.spending-analysis.query"]])
def test_cashflow_method_scope_is_not_the_installation_unit(store, scope):
    from finance_solution_builder.method_assets import runtime_reference
    from finance_solution_builder.service import CASHFLOW, solution_for, validate_service
    data = content()
    data["capabilityIds"] = scope
    asset = store.create("alice", data)
    trial = store.record_trial("alice", asset["assetId"], asset, {"status": "PASS", "runtimeVerified": True})
    release = store.publish("alice", asset["assetId"], 1, "1.0.0", trial["trialId"])
    frozen = store.release("alice", {k: release[k] for k in ("assetId", "version", "releaseDigest")})
    caps = sorted(CASHFLOW | {"wealth.facts.describe"})
    solution = solution_for(caps, "test-build", runtime_reference(frozen), scope)
    assert {r["id"] for r in solution["domainPackRefs"]} == {"wealth-shared-facts", asset["assetId"]}
    assert set(validate_service(solution)) == set(caps)
    pack = json.loads(runtime_files(asset["assetId"], "1.0.0", frozen["data"])["pack.yaml"])
    assert pack["capabilities"] == sorted(scope)
    assert set(solution["capabilityBindings"]) == set(caps)
    with pytest.raises(ValueError, match="READONLY_SELECTION_INVALID"):
        solution_for(["finance.transaction-summary.query"], "test-invalid")
    with pytest.raises(ValueError, match="SERVICE_METHOD_SCOPE_INVALID"):
        solution_for(["account.balance.query"], "test-invalid", runtime_reference(frozen), scope)


@pytest.mark.parametrize("entry", ["other/secret.txt", "wrapped/../SKILL.md", "wrapped/nested/SKILL.md"])
def test_archive_wrapper_cannot_hide_unrelated_or_unsafe_entries(store, entry):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("wrapped/SKILL.md", "Test")
        archive.writestr(entry, "Other")
    with pytest.raises(ApiError):
        store.import_asset("alice", "customer-package.zip", base64.b64encode(output.getvalue()).decode(), "Invalid")


def test_archiving_does_not_change_immutable_release_or_existing_materialization(store, tmp_path):
    draft, report = make_tested(store)
    release = store.publish("alice", draft["assetId"], 1, "1.0.0", report["trialId"])
    ref = {k: release[k] for k in ("assetId", "version", "releaseDigest")}
    before = store.release("alice", ref)
    with pytest.raises(ApiError, match="ASSET_REQUIRES_ARCHIVE"):
        store.lifecycle("alice", draft["assetId"], 1, "delete")
    store.lifecycle("alice", draft["assetId"], 1, "archive")
    assert store.list("alice")[0]["status"] == "ARCHIVED"
    assert store.release("alice", ref) == before
    assert store.materialize("alice", ref, tmp_path / "old-build")["digest"] == release["installationDigest"]
    with pytest.raises(ApiError, match="ASSET_ARCHIVED"):
        store.require_active("alice", draft["assetId"])
    store.lifecycle("alice", draft["assetId"], 2, "restore")
    assert store.get("alice", draft["assetId"])["trial"] is None


def test_import_preview_uses_identical_archive_rejection_without_disk_writes(store):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("SKILL.md", "Test")
        archive.writestr("../escape", "bad")
    for preview in (True, False):
        with pytest.raises(ApiError):
            store.import_asset("alice", "bad.zip", base64.b64encode(output.getvalue()).decode(), "Bad", preview=preview)
    assert list(store.root.iterdir()) == []
