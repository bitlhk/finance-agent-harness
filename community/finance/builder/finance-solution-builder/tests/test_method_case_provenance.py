import copy
import json

import pytest

from finance_solution_builder.asset_identity import AssetIdentityError
from finance_solution_builder.business_assets import validate_binding
from finance_solution_builder.development_support import INDEX
from finance_solution_builder.directory import describe_directory
from finance_solution_builder.experience_contracts import ApiError
from finance_solution_builder.method_assets import canonical, sha, write_private
from finance_solution_builder.workbench import Jobs
from finance_solution_builder.workbench_api import WorkbenchApi
from tests.development_support_fixture import write_declarations
from tests.test_business_assets import PROJECT, registered


@pytest.fixture
def editor(registered, tmp_path):
    jobs = Jobs(PROJECT, registered, data_dir=tmp_path / "jobs")
    api = WorkbenchApi(jobs)
    body = {"componentId": "finance.cashflow.readonly",
            "directoryRevision": describe_directory(registered)["revision"], "name": "Editor method"}
    try:
        yield registered, jobs.method_assets, api, body
    finally:
        jobs.close()


def page_save(api, body, preview):
    # browser/method-assets.js submits creation.body + structuredClone(editor.data).
    payload = json.loads(json.dumps({**body, "data": preview["data"]}))
    return api.post("/api/v1/assets/customize", payload, "alice")[1]


def preview_for(api, body):
    return api.post("/api/v1/assets/customize-preview", body, "alice")[1]


def update(api, draft, data):
    return api.post("/api/v1/assets/" + draft["assetId"] + "/save",
                    {"revision": draft["revision"], "data": data}, "alice")[1]


def publish(api, store, draft, version):
    trial = store.record_trial("alice", draft["assetId"], draft,
                               {"status": "PASS", "runtimeVerified": True, "model": "CONTROLLED_TEST"})
    result = api.post("/api/v1/assets/" + draft["assetId"] + "/publish",
                     {"revision": draft["revision"], "version": version, "trialId": trial["trialId"]}, "alice")[1]
    return {key: result[key] for key in ("assetId", "version", "releaseDigest")}


def declarations(root):
    index = json.loads((root / INDEX).read_text())
    return [json.loads((root / ref["source"].removeprefix("repo://")).read_text())
            for ref in index["declarations"]]


@pytest.mark.parametrize("component", ["account.balance.query", "wealth.facts.describe", "finance.cashflow.readonly"])
def test_page_roundtrip_retains_reverified_defaults_outside_strict_binding(editor, component):
    root, store, api, body = editor
    body = {**body, "componentId": component}
    preview = preview_for(api, body)
    draft = page_save(api, body, preview)
    assert draft["data"] == preview["data"]
    assert draft["source"] == preview["source"]
    assert draft["source"]["caseProvenance"]["kind"] == "COMPONENT_DECLARATION"
    assert draft["source"]["caseProvenance"]["casesDigest"] == sha(canonical(draft["data"]["cases"]))
    assert "caseProvenance" not in draft["source"]["binding"]
    validate_binding(root, draft["source"]["binding"])
    unchanged = update(api, draft, draft["data"])
    assert unchanged["source"] == preview["source"]
    assert store.get("alice", draft["assetId"])["source"] == preview["source"]
    assert store.list("bob") == []


@pytest.mark.parametrize("change", ["name", "query", "expectedTerms", "order", "empty"])
def test_explicit_changed_cases_are_not_misattributed_on_first_save(editor, change):
    _, _, api, body = editor
    preview = preview_for(api, body)
    cases = preview["data"]["cases"]
    if change == "order":
        cases.reverse()
    elif change == "empty":
        cases.clear()
    elif change == "expectedTerms":
        cases[0][change] = ["USER_EXPECTATION"]
    else:
        cases[0][change] = "User edit"
    draft = page_save(api, body, preview)
    assert draft["data"]["cases"] == cases
    assert draft["source"]["caseProvenance"] == {
        "schemaVersion": "finance-method-case-provenance.v0.1", "kind": "USER_PROVIDED",
        "casesDigest": sha(canonical(cases))}
    if not cases:
        assert draft["validation"]["status"] == "INVALID"
        assert draft["warnings"][0]["code"] == "ASSET_CASE_REQUIRED"


@pytest.mark.parametrize("change", ["invalid", "digest", "missing", "cases", "scope", "conflict"])
def test_page_save_reloads_current_declarations_instead_of_trusting_preview(editor, change):
    root, _, api, body = editor
    preview = preview_for(api, body)
    values = declarations(root)
    if change == "invalid":
        (root / INDEX).write_text("invalid")
    elif change == "digest":
        ref = preview["source"]["caseProvenance"]["sources"][0]
        (root / ref["source"].removeprefix("repo://")).write_bytes(b"{}")
    elif change == "missing":
        (root / INDEX).unlink()
    else:
        subjects = preview["source"]["caseProvenance"]["subjects"]
        value = next(value for value in values if value["subject"] in subjects)
        if change == "cases":
            value["methodCases"][0]["query"] = "New current default"
        elif change == "scope":
            value["scope"]["runtimeId"] = "another-runtime"
        else:
            conflicting = copy.deepcopy(value)
            conflicting["methodCases"][0]["query"] = "Conflicting default"
            values.append(conflicting)
        write_declarations(root, values)
    draft = page_save(api, body, preview)
    assert draft["data"] == preview["data"]
    assert draft["source"]["caseProvenance"]["kind"] == "USER_PROVIDED"
    assert "sources" not in draft["source"]["caseProvenance"]
    assert draft["source"]["caseProvenance"]["casesDigest"] == sha(canonical(draft["data"]["cases"]))


def test_same_cases_with_new_valid_declaration_record_current_server_reference(editor):
    root, _, api, body = editor
    preview = preview_for(api, body)
    values = declarations(root)
    subjects = preview["source"]["caseProvenance"]["subjects"]
    value = next(value for value in values if value["subject"] in subjects)
    value["commands"] = []
    write_declarations(root, values)
    current = preview_for(api, body)
    assert current["data"]["cases"] == preview["data"]["cases"]
    assert current["source"]["caseProvenance"]["sources"] != preview["source"]["caseProvenance"]["sources"]
    draft = page_save(api, body, preview)
    assert draft["source"]["caseProvenance"] == current["source"]["caseProvenance"]


@pytest.mark.parametrize("field", ["source", "caseProvenance"])
def test_page_cannot_submit_forged_provenance_at_either_payload_level(editor, field):
    _, store, api, body = editor
    preview = preview_for(api, body)
    forged = {"kind": "COMPONENT_DECLARATION", "casesDigest": "sha256:" + "0" * 64}
    with pytest.raises(ApiError, match="REQUEST_INVALID"):
        api.post("/api/v1/assets/customize", {**body, "data": preview["data"], field: forged}, "alice")
    with pytest.raises(ApiError, match="ASSET_CONTENT_INVALID"):
        page_save(api, body, {"data": {**preview["data"], field: forged}})
    assert store.list("alice") == []
    draft = page_save(api, body, preview)
    with pytest.raises(ApiError, match="REQUEST_INVALID"):
        api.post("/api/v1/assets/" + draft["assetId"] + "/save",
                 {"revision": 1, "data": draft["data"], field: forged}, "alice")
    assert store.get("alice", draft["assetId"])["source"] == preview["source"]


def test_identity_errors_are_not_swallowed_by_optional_default_recognition(editor, monkeypatch):
    from finance_solution_builder import business_assets

    _, store, api, body = editor
    preview = preview_for(api, body)

    def conflict(*args, **kwargs):
        raise AssetIdentityError("ASSET_IDENTITY_CONFLICT", "Conflicting source")

    monkeypatch.setattr(business_assets, "development_support_for_binding", conflict)
    with pytest.raises(AssetIdentityError, match="ASSET_IDENTITY_CONFLICT"):
        page_save(api, body, preview)
    assert store.list("alice") == []


def test_modifications_keep_first_origin_and_publish_current_digest_without_rewriting_history(editor):
    root, store, api, body = editor
    preview = preview_for(api, body)
    draft = page_save(api, body, preview)
    original = copy.deepcopy(draft["source"]["caseProvenance"])
    old_ref = publish(api, store, draft, "1.0.0")
    old_release, old_trial = store.release_trial("alice", old_ref)
    before = {str(path): path.read_bytes() for path in store.root.rglob("releases/1.0.0/*.json")}
    assert before

    content_only = copy.deepcopy(draft["data"])
    content_only["content"] += "\nOnly the method text changed."
    draft = update(api, draft, content_only)
    assert draft["source"]["caseProvenance"] == original
    assert draft["trial"] is None

    # Saved origins are frozen evidence, independent of later directory metadata.
    (root / INDEX).unlink()
    for change in ("query", "expectedTerms", "restore"):
        data = copy.deepcopy(draft["data"])
        if change == "restore":
            data["cases"] = copy.deepcopy(preview["data"]["cases"])
        elif change == "query":
            data["cases"][0]["query"] = "Updated user question"
        else:
            data["cases"][0]["expectedTerms"] = ["UPDATED_EXPECTATION"]
        draft = update(api, draft, data)
        provenance = draft["source"]["caseProvenance"]
        assert provenance == {"schemaVersion": "finance-method-case-provenance.v0.2",
                              "kind": "USER_MODIFIED", "casesDigest": sha(canonical(data["cases"])),
                              "original": original}
        assert draft["source"]["binding"] == preview["source"]["binding"]
        validate_binding(root, draft["source"]["binding"])
        assert draft["trial"] is None
        with pytest.raises(ApiError, match="ASSET_FRESH_TRIAL_REQUIRED"):
            store.publish("alice", draft["assetId"], draft["revision"], "1.1.0", old_trial["trialId"])
        if change == "query":
            modified_ref = publish(api, store, draft, "1.0.1")
            modified_release = store.release("alice", modified_ref)
            assert modified_release["source"]["caseProvenance"] == provenance
            assert provenance["casesDigest"] != original["casesDigest"]
            assert modified_release["data"]["cases"] != old_release["data"]["cases"]
    unchanged = update(api, draft, draft["data"])
    assert unchanged["source"] == draft["source"]
    new_ref = publish(api, store, unchanged, "1.1.0")
    release, trial = store.release_trial("alice", new_ref)
    assert release["source"] == unchanged["source"]
    assert release["source"]["caseProvenance"]["casesDigest"] == sha(canonical(release["data"]["cases"]))
    assert trial["contentDigest"] == release["contentDigest"]
    assert release["source"]["caseProvenance"]["original"] == old_release["source"]["caseProvenance"]
    assert store.release("alice", modified_ref) == modified_release
    assert store.release_trial("alice", old_ref) == (old_release, old_trial)
    assert before == {str(path): path.read_bytes() for path in store.root.rglob("releases/1.0.0/*.json")}
    with pytest.raises(ApiError, match="ASSET_NOT_AVAILABLE"):
        store.release("bob", new_ref)


def test_user_case_edit_does_not_gain_component_origin(editor):
    _, _, api, body = editor
    preview = preview_for(api, body)
    preview["data"]["cases"][0]["query"] = "User-authored question"
    draft = page_save(api, body, preview)
    original = copy.deepcopy(draft["source"]["caseProvenance"])
    data = copy.deepcopy(draft["data"])
    data["cases"] = []
    changed = update(api, draft, data)
    assert changed["source"]["caseProvenance"]["kind"] == "USER_MODIFIED"
    assert changed["source"]["caseProvenance"]["original"] == original
    assert original["kind"] == "USER_PROVIDED" and "sources" not in original
    assert changed["source"]["caseProvenance"]["casesDigest"] == sha(canonical([]))
    assert changed["validation"]["status"] == "INVALID"


def test_legacy_stale_provenance_cannot_publish_until_save_marks_it_modified(editor):
    _, store, api, body = editor
    draft = page_save(api, body, preview_for(api, body))
    origin = copy.deepcopy(draft["source"]["caseProvenance"])
    data = copy.deepcopy(draft["data"])
    data["cases"][0]["query"] = "Changed by the old save implementation"
    changed = update(api, draft, data)
    trial = store.record_trial("alice", changed["assetId"], changed,
                               {"status": "PASS", "runtimeVerified": True})
    file = store._folder("alice", draft["assetId"]) / "draft.json"
    old_format = json.loads(file.read_text())
    old_format["source"]["caseProvenance"] = origin
    write_private(file, old_format)
    before = file.read_bytes()
    assert store.get("alice", draft["assetId"])["source"]["caseProvenance"] == origin
    with pytest.raises(ApiError, match="ASSET_CASE_PROVENANCE_MISMATCH"):
        store.publish("alice", changed["assetId"], changed["revision"], "1.0.0", trial["trialId"])
    assert file.read_bytes() == before
    fixed = update(api, changed, changed["data"])
    assert fixed["source"]["caseProvenance"]["kind"] == "USER_MODIFIED"
    assert fixed["source"]["caseProvenance"]["original"] == origin
    assert fixed["source"]["caseProvenance"]["casesDigest"] == sha(canonical(data["cases"]))
    assert fixed["trial"] is None
    ref = publish(api, store, fixed, "1.0.0")
    assert store.release("alice", ref)["source"] == fixed["source"]


def test_legacy_draft_without_provenance_is_not_backfilled(editor):
    _, store, api, body = editor
    preview = preview_for(api, body)
    source = {key: value for key, value in preview["source"].items() if key != "caseProvenance"}
    draft = store.create("alice", preview["data"], source=source)
    data = copy.deepcopy(draft["data"])
    data["cases"][0]["query"] = "Legacy user edit"
    changed = update(api, draft, data)
    assert changed["source"] == source
    ref = publish(api, store, changed, "1.0.0")
    assert "caseProvenance" not in store.release("alice", ref)["source"]


def test_new_draft_cannot_record_provenance_for_different_cases(editor):
    _, store, api, body = editor
    preview = preview_for(api, body)
    preview["data"]["cases"][0]["query"] = "Different from the claimed provenance"
    with pytest.raises(ApiError, match="ASSET_CASE_PROVENANCE_MISMATCH"):
        store.create("alice", preview["data"], source=preview["source"])
    assert store.list("alice") == []
