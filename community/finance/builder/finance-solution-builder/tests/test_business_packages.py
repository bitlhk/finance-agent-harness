import base64
import copy
import hashlib
import io
import json
import shutil
import subprocess
import threading
import zipfile
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import quote

import pytest

from finance_solution_builder.business_packages import (
    FINANCE_HARNESS_BUSINESS_PACKAGE_V01,
    HarnessPackageAnchor,
    dependency_status,
    inspect_archive,
    verify_harness_package_anchor,
)
from finance_solution_builder.experience_contracts import ApiError
from finance_solution_builder.method_api import MethodApi
from finance_solution_builder.method_assets import MethodAssets


def test_shipped_packager_anchor_matches_qualified_harness_release():
    assert FINANCE_HARNESS_BUSINESS_PACKAGE_V01 == HarnessPackageAnchor(
        source="https://github.com/bitlhk/finance-agent-harness",
        revision="564c9869a42464670a7eed4b802979a76790c1c4",
        packager_sha256="8eae31d01f7914e4ea8fc3ddb1b61b6df0196faffababdad814b2df3be33f0c3",
        schema_sha256="446de7e61c8313c377986ec0bf425a1eb508a45c3bb6c090cca0a566f538617d",
    )


class Queue:
    def __init__(self):
        self.pending = []

    def submit(self, callback, *args):
        self.pending.append((callback, args))

    def finish(self):
        callback, args = self.pending.pop(0)
        callback(*args)


def _git(root, *arguments):
    return subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=True,
        text=True,
        encoding="utf-8",
        capture_output=True,
    ).stdout.strip()


def _fixture_anchor(harness):
    source = "https://example.test/finance-harness-fixture"
    _git(harness, "init")
    _git(harness, "config", "user.name", "Fixture")
    _git(harness, "config", "user.email", "fixture@example.test")
    _git(harness, "remote", "add", "origin", source)
    _git(harness, "add", ".")
    _git(harness, "commit", "-m", "fixture")
    packager = harness / "engineering/business_package.py"
    schema = (
        harness
        / "contracts/json-schema/package/finance-business-component-package.v0.1.schema.json"
    )
    return HarnessPackageAnchor(
        source=source,
        revision=_git(harness, "rev-parse", "HEAD"),
        packager_sha256=hashlib.sha256(packager.read_bytes().replace(b"\r\n", b"\n")).hexdigest(),
        schema_sha256=hashlib.sha256(schema.read_bytes().replace(b"\r\n", b"\n")).hexdigest(),
    )


@pytest.fixture
def context(tmp_path):
    project = Path(__file__).resolve().parents[1]
    harness = tmp_path / "harness"
    shutil.copytree(project / "tests/fixtures/business-assets", harness)
    anchor = _fixture_anchor(harness)
    jobs = SimpleNamespace(
        method_assets=MethodAssets(tmp_path / "private"),
        lock=threading.RLock(),
        closing=False,
        queue=Queue(),
        jobs={},
        project=project,
        builder=SimpleNamespace(harness_repo=harness),
        business_package_anchor=anchor,
    )
    return MethodApi(jobs), harness, anchor


def published(api, monkeypatch):
    from finance_solution_builder import method_trials

    monkeypatch.setattr(
        method_trials,
        "run_trial",
        lambda *args: {
            "status": "PASS",
            "runtimeVerified": True,
            "model": "CONTROLLED_TEST",
            "professionalQuality": "NOT_EVALUATED",
            "trialRuntime": {
                "family": "openjiuwen",
                "language": "java",
                "source": "GENERATED_JAVA_HOST",
            },
        },
    )
    data = {
        "name": "资产核验",
        "maintainer": "test-owner",
        "content": "# 核验\n只使用工具事实。",
        "capabilityIds": ["wealth.facts.describe"],
        "cases": [{"name": "正常", "query": "核对资产", "expectedTerms": []}],
    }
    draft = api.post("/api/v1/assets", {"data": data}, "alice", None)[1]
    path = "/api/v1/assets/" + draft["assetId"]
    api.post(path + "/trial", {"revision": 1}, "alice", None)
    api.jobs.queue.finish()
    trial = api.get(path + "/trial", "alice")[1]["trial"]
    plan = api.post(
        path + "/package-plan", {"revision": 1, "trialId": trial["trialId"]}, "alice", None
    )[1]
    assert plan["requirements"]["runtimeFamilies"] == ["openjiuwen"]
    assert plan["requirements"]["runtimeLanguages"] == ["java"]
    assert plan["bindings"][0]["protocol"] == "finance-provider-spi.v1"
    assert plan["bindings"][0]["inputSchemaRef"].endswith("/wealth.facts.describe/input")
    assert plan["evidence"]["runtimeVerified"] is True
    release = api.post(
        path + "/publish",
        {
            "revision": 1,
            "version": "1.0.0",
            "trialId": trial["trialId"],
            "bindingConfirmations": ["wealth.facts.describe"],
        },
        "alice",
        None,
    )[1]
    return path, release


def test_published_contents_match_zip_and_ignore_draft_changes(context, monkeypatch):
    api, harness, anchor = context
    path, release = published(api, monkeypatch)
    route = path + "/packages/1.0.0/" + release["releaseDigest"]
    archive = api.get(route, "alice")[1]
    view = api.get(route + "/contents", "alice")[1]
    with zipfile.ZipFile(io.BytesIO(base64.b64decode(archive["base64"]))) as package:
        shown = {f["path"]: f for f in view["files"] if f["origin"] == "PACKAGE_FILE"}
        assert set(shown) == set(package.namelist())
        for name, item in shown.items():
            assert item["content"].encode() == package.read(name)
            assert item["sha256"] == "sha256:" + hashlib.sha256(package.read(name)).hexdigest()
    assert any(f["origin"] == "REFERENCED_ASSET" for f in view["files"])
    assert view["bindings"][0]["inputFile"].endswith("pack.yaml")
    draft = api.get(path, "alice")[1]
    api.post(path + "/save", {"revision": draft["revision"], "data": {**draft["data"], "content": "NEW DRAFT"}}, "alice", None)
    after = api.get(route + "/contents", "alice")[1]
    assert next(f for f in after["files"] if f["path"] == "skill/SKILL.md")["content"] != "NEW DRAFT"
    with pytest.raises(ApiError, match="ASSET_NOT_AVAILABLE"):
        api.get(route + "/contents", "bob")
    with pytest.raises(ApiError, match="ASSET_RELEASE_CHANGED"):
        api.get(path + "/packages/1.0.0/sha256:" + "0" * 64 + "/contents", "alice")
    with pytest.raises(ApiError, match="NOT_FOUND"):
        api.get(route + "/contents/../../secret", "alice")
    assert not api.jobs.queue.pending


@pytest.mark.parametrize("suffix", ["", "/contents"])
@pytest.mark.parametrize("change,code", [
    ("owner", "ASSET_NOT_AVAILABLE"),
    ("digest", "ASSET_RELEASE_CHANGED"),
    ("double_encoding", "ASSET_RELEASE_CHANGED"),
    ("version_path", "ASSET_REFERENCE_INVALID"),
    ("asset_path", "ASSET_NOT_AVAILABLE"),
    ("release", "ASSET_RELEASE_CHANGED"),
    ("trial", "ASSET_RELEASE_CHANGED"),
])
def test_encoded_package_routes_preserve_immutable_and_owner_gates(context, monkeypatch, suffix, change, code):
    api, _, _ = context
    _, release = published(api, monkeypatch)
    ref = {k: quote(release[k], safe="") for k in ("assetId", "version", "releaseDigest")}
    owner = "bob" if change == "owner" else "alice"
    if change == "digest":
        ref["releaseDigest"] = quote("sha256:" + "0" * 64, safe="")
    elif change == "double_encoding":
        ref["releaseDigest"] = quote(ref["releaseDigest"], safe="")
    elif change in {"version_path", "asset_path"}:
        ref["version" if change == "version_path" else "assetId"] += "%2F.."
    elif change in {"release", "trial"}:
        file = api.store._folder("alice", release["assetId"]) / "releases/1.0.0" / (change + ".json")
        value = json.loads(file.read_text())
        if change == "release":
            value["data"]["name"] = "Tampered release"
        else:
            value["report"]["runtimeVerified"] = False
        file.write_text(json.dumps(value))
    route = "/api/v1/assets/{assetId}/packages/{version}/{releaseDigest}".format(**ref)
    with pytest.raises(ApiError, match=code):
        api.get(route + suffix, owner)
    assert not api.jobs.queue.pending


def test_asset_usage_is_owner_scoped_and_version_specific(context, monkeypatch):
    api, _, _ = context
    path, release = published(api, monkeypatch)
    ref = {k: release[k] for k in ("assetId", "version", "releaseDigest")}
    api.jobs.jobs = {
        "one": {"id": "agent-one", "owner": "alice", "status": "BUILT", "methodRef": ref},
        "two": {"id": "agent-two", "owner": "alice", "status": "STOPPED", "methodRef": ref},
        "foreign": {"id": "agent-foreign", "owner": "bob", "status": "BUILT", "methodRef": ref},
        "old": {"id": "agent-old", "owner": "alice", "status": "BUILT", "methodRef": {**ref, "version": "0.9.0"}},
    }
    view = api.get(path + "/packages/1.0.0/" + release["releaseDigest"] + "/contents", "alice")[1]
    assert [r["buildId"] for r in view["usages"]] == ["agent-one", "agent-two"]
    assert all(r["association"] == "DECLARED_SELECTION" for r in view["usages"])
    assert not any("lock" in f["path"].lower() for f in view["files"])


@pytest.mark.parametrize('change,code', [('role', 'ASSET_IDENTITY_CONFLICT'),
                                       ('digest', 'ASSET_CONTENT_DIGEST_MISMATCH')])
def test_published_manifest_entries_are_checked_before_role_index(context, monkeypatch, change, code):
    from finance_solution_builder import business_packages
    api, _, _ = context
    path, release = published(api, monkeypatch)
    inspect = business_packages.inspect_archive
    def conflicting(*args):
        manifest, files = inspect(*args)
        entry = dict(manifest['files'][0])
        if change == 'role':
            entry['role'] = 'SKILL' if entry['role'] != 'SKILL' else 'CASE'
        else:
            entry['sha256'] = 'sha256:' + '0' * 64
        manifest['files'].append(entry)
        return manifest, files
    monkeypatch.setattr(business_packages, 'inspect_archive', conflicting)
    with pytest.raises(ApiError, match=code):
        api.get(path + '/packages/1.0.0/' + release['releaseDigest'] + '/contents', 'alice')


def test_published_contents_do_not_load_current_component_development_defaults(context, monkeypatch):
    from finance_solution_builder import business_assets
    api, _, _ = context
    path, release = published(api, monkeypatch)
    def unavailable(*args):
        raise AssertionError('published package must not load current defaults')
    monkeypatch.setattr(business_assets, 'component_development_support', unavailable)
    view = api.get(path + '/packages/1.0.0/' + release['releaseDigest'] + '/contents', 'alice')[1]
    assert 'developmentSupport' not in view
    assert any(f['role'] == 'CASE' and f['origin'] == 'PACKAGE_FILE' for f in view['files'])
    assert view['verification']['trialId']
    for binding in view['bindings']:
        assert binding['inputFileId'] in {f['fileId'] for f in view['files']}
        if binding.get('outputFile'):
            assert binding['outputFileId'] in {f['fileId'] for f in view['files']}
        else:
            assert 'outputFileId' not in binding


def test_release_exports_and_reimports_without_writing_on_preview(context, monkeypatch):
    api, harness, anchor = context
    path, release = published(api, monkeypatch)
    route = path + "/packages/1.0.0/" + release["releaseDigest"]
    value = api.get(route, "alice")[1]
    raw = base64.b64decode(value["base64"])
    manifest, _ = inspect_archive(raw, harness, anchor)
    assert manifest["bindings"][0]["toolName"] == "wealth_facts_describe"
    assert manifest["requirements"]["requiredScopes"] == ["wealth:facts:read"]
    before = api.store.list("bob")
    body = {"filename": value["filename"], "base64": value["base64"], "name": "导入资产核验"}
    preview = api.post("/api/v1/assets/package-preview", body, "bob", None)[1]
    assert preview["dependencyStatus"]["status"] == "ADMISSIBLE" and api.store.list("bob") == before
    imported = api.post("/api/v1/assets/package-import", body, "bob", None)[1]
    assert imported["data"]["capabilityIds"] == ["wealth.facts.describe"]
    assert imported["source"]["archiveSha256"] == value["sha256"]

    edited = {
        **preview["data"],
        "name": "目标环境名称",
        "content": preview["data"]["content"] + "\n补充核验。",
    }
    saved = api.post("/api/v1/assets/package-import", {**body, "data": edited}, "carol", None)[1]
    assert saved["data"]["name"] == "目标环境名称" and saved["data"]["content"].endswith(
        "补充核验。"
    )


def test_package_export_requires_explicit_binding_confirmation(context, monkeypatch):
    api, _, _ = context
    from finance_solution_builder import method_trials

    monkeypatch.setattr(
        method_trials,
        "run_trial",
        lambda *args: {
            "status": "PASS",
            "runtimeVerified": True,
            "trialRuntime": {
                "family": "openjiuwen",
                "language": "java",
                "source": "GENERATED_JAVA_HOST",
            },
        },
    )
    draft = api.post(
        "/api/v1/assets",
        {
            "data": {
                "name": "old",
                "content": "# old",
                "capabilityIds": ["wealth.facts.describe"],
                "cases": [{"name": "n", "query": "q"}],
            }
        },
        "alice",
        None,
    )[1]
    path = "/api/v1/assets/" + draft["assetId"]
    api.post(path + "/trial", {"revision": 1}, "alice", None)
    api.jobs.queue.finish()
    trial = api.get(path + "/trial", "alice")[1]["trial"]
    release = api.post(
        path + "/publish",
        {"revision": 1, "version": "1.0.0", "trialId": trial["trialId"]},
        "alice",
        None,
    )[1]
    with pytest.raises(ApiError, match="BINDING_CONFIRMATION_REQUIRED"):
        api.get(path + "/packages/1.0.0/" + release["releaseDigest"], "alice")


def test_package_owner_and_digest_boundaries(context, monkeypatch):
    api, _, _ = context
    path, release = published(api, monkeypatch)
    route = path + "/packages/1.0.0/" + release["releaseDigest"]
    with pytest.raises(ApiError, match="ASSET_NOT_AVAILABLE"):
        api.get(route, "bob")
    with pytest.raises(ApiError):
        api.get(route[:-1] + ("0" if route[-1] != "0" else "1"), "alice")


def test_dependency_admission_reports_version_runtime_and_pending_binding(context, monkeypatch):
    api, harness, anchor = context
    path, release = published(api, monkeypatch)
    exported = api.get(path + "/packages/1.0.0/" + release["releaseDigest"], "alice")[1]
    manifest = exported["manifest"]

    wrong_version = copy.deepcopy(manifest)
    wrong_version["bindings"][0]["capabilityPack"]["version"] = "999.0.0"
    status = dependency_status(wrong_version, harness, anchor)
    assert status["status"] == "MISSING_DEPENDENCIES"
    assert status["missing"] == ["wealth.facts.describe"]

    unsupported = copy.deepcopy(manifest)
    unsupported["requirements"]["runtimeLanguages"] = ["python"]
    assert dependency_status(unsupported, harness, anchor)["unsupportedRuntime"] == ["python"]

    unknown_family = copy.deepcopy(manifest)
    unknown_family["requirements"]["runtimeFamilies"] = ["unknown-runtime"]
    assert (
        "runtimeFamilies"
        in dependency_status(unknown_family, harness, anchor)["requirementMismatches"]
    )

    wrong_protocol = copy.deepcopy(manifest)
    wrong_protocol["bindings"][0]["protocol"] = "unknown-provider-protocol.v9"
    assert dependency_status(wrong_protocol, harness, anchor)["mismatched"] == [
        "wealth.facts.describe"
    ]

    wrong_contract = copy.deepcopy(manifest)
    wrong_contract["bindings"][0]["inputSchemaRef"] = "schema://missing-input.v9"
    assert dependency_status(wrong_contract, harness, anchor)["mismatched"] == [
        "wealth.facts.describe"
    ]

    unsupported_execution = copy.deepcopy(manifest)
    unsupported_execution["requirements"]["execution"] = ["QUERY", "CONFIRMATION", "RECOVERY"]
    assert (
        "execution"
        in dependency_status(unsupported_execution, harness, anchor)["requirementMismatches"]
    )

    pending = copy.deepcopy(manifest)
    pending["bindings"][0]["confirmation"] = "PENDING"
    assert dependency_status(pending, harness, anchor)["pendingBindings"] == [
        "wealth.facts.describe"
    ]


def test_import_rejects_archive_traversal(context):
    _, harness, anchor = context
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr("../SKILL.md", "unsafe")
    with pytest.raises(ApiError, match="BUSINESS_PACKAGE_PATH_INVALID"):
        inspect_archive(output.getvalue(), harness, anchor)


def test_packager_anchor_rejects_modified_tracked_code(context):
    _, harness, anchor = context
    path = harness / "engineering/business_package.py"
    path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")

    with pytest.raises(ApiError, match="BUSINESS_PACKAGE_HARNESS_UNTRUSTED"):
        verify_harness_package_anchor(harness, anchor)


def test_packager_anchor_rejects_wrong_revision_or_source(context):
    _, harness, anchor = context
    wrong_revision = HarnessPackageAnchor(
        source=anchor.source,
        revision="0" * 40,
        packager_sha256=anchor.packager_sha256,
        schema_sha256=anchor.schema_sha256,
    )
    wrong_source = HarnessPackageAnchor(
        source="https://example.test/another-harness",
        revision=anchor.revision,
        packager_sha256=anchor.packager_sha256,
        schema_sha256=anchor.schema_sha256,
    )

    for candidate in (wrong_revision, wrong_source):
        with pytest.raises(ApiError, match="BUSINESS_PACKAGE_HARNESS_UNTRUSTED"):
            verify_harness_package_anchor(harness, candidate)
