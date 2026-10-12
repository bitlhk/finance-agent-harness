import subprocess

import pytest

from finance_solution_builder.asset_contents import RepositorySnapshot, file_view, _usage
from finance_solution_builder.experience_contracts import ApiError


def git(root, *args):
    subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True)


@pytest.fixture
def repository(tmp_path):
    root = tmp_path / "repo"
    root.mkdir()
    folder = root / "capability-packs/example"
    folder.mkdir(parents=True)
    (folder / "pack.yaml").write_text("packId: example\nversion: 1.0.0\n")
    git(root, "init")
    git(root, "add", ".")
    git(root, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "-m", "fixture")
    return root


def test_snapshot_exact_commit_and_drift_rejected(repository):
    snapshot = RepositorySnapshot(repository)
    files = snapshot.files("repo://capability-packs/example", "capability-packs", {"version": "1.0.0"})
    assert files[0]["content"] == "packId: example\nversion: 1.0.0\n"
    (repository / "capability-packs/example/pack.yaml").write_text("changed working tree")
    with pytest.raises(ApiError, match="ASSET_CONTENT_SOURCE_CHANGED"):
        snapshot.files("repo://capability-packs/example", "capability-packs", {})


def test_snapshot_accepts_clean_crlf_checkout_but_rejects_real_drift(repository):
    path = repository / "capability-packs/example/pack.yaml"
    path.write_bytes(b"packId: example\r\nversion: 1.0.0\r\n")
    git(repository, "config", "core.autocrlf", "true")
    name = "capability-packs/example/pack.yaml"
    committed_oid = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", f"HEAD:{name}"],
        check=True, capture_output=True,
    ).stdout.strip()
    working_oid = subprocess.run(
        ["git", "-C", str(repository), "hash-object", f"--path={name}", "--", name],
        check=True, capture_output=True,
    ).stdout.strip()
    assert working_oid == committed_oid
    assert b"\r\n" in path.read_bytes()
    snapshot = RepositorySnapshot(repository)
    files = snapshot.files("repo://capability-packs/example", "capability-packs", {})
    assert files[0]["content"] == "packId: example\nversion: 1.0.0\n"

    path.write_bytes(b"packId: changed\r\nversion: 1.0.0\r\n")
    with pytest.raises(ApiError, match="ASSET_CONTENT_SOURCE_CHANGED"):
        snapshot.files("repo://capability-packs/example", "capability-packs", {})


def test_snapshot_missing_tracked_file_is_source_changed(repository):
    (repository / "capability-packs/example/pack.yaml").unlink()
    with pytest.raises(ApiError, match="ASSET_CONTENT_SOURCE_CHANGED"):
        RepositorySnapshot(repository).files("repo://capability-packs/example", "capability-packs", {})


@pytest.mark.parametrize("source", ["repo:///etc/passwd", "repo://capability-packs/../../etc/passwd",
                                    "file:///etc/passwd", "repo://providers/example", "repo://capability-packs/..\\secret"])
def test_snapshot_rejects_paths_outside_catalog_type(repository, source):
    with pytest.raises(ApiError, match="ASSET_CONTENT_REFERENCE_UNAVAILABLE"):
        RepositorySnapshot(repository).files(source, "capability-packs", {})


def test_snapshot_never_follows_symlink(repository, tmp_path):
    secret = tmp_path / "secret.json"
    secret.write_text("do not expose")
    (repository / "capability-packs/example/secret.json").symlink_to(secret)
    git(repository, "add", ".")
    git(repository, "-c", "user.name=Test", "-c", "user.email=test@example.test", "commit", "-m", "symlink")
    with pytest.raises(ApiError, match="ASSET_CONTENT_UNSAFE_ENTRY"):
        RepositorySnapshot(repository).files("repo://capability-packs/example", "capability-packs", {})


def test_preview_limits_are_explicit():
    assert file_view("x", b"x" * 65537, "PACKAGE_FILE", "REFERENCE")["previewStatus"] == "TOO_LARGE"
    assert file_view("x", b"\xff", "PACKAGE_FILE", "REFERENCE")["previewStatus"] == "BINARY"


def test_usage_name_comes_from_owned_build_selection():
    from types import SimpleNamespace
    from threading import RLock
    ref={'assetId':'method','version':'1','releaseDigest':'digest'}
    alice={'id':'a','owner':'alice','status':'STOPPED','methodRef':ref,'selection':{'interfaceConfig':{'assistantName':'核验助手'}}}
    jobs=SimpleNamespace(lock=RLock(),jobs={'a':alice,'b':{**alice,'id':'b','owner':'bob'}})
    usages=_usage(jobs,'alice',reference=ref)
    assert len(usages)==1 and usages[0]['displayName']=='核验助手'
    assert usages[0]['nameSource']=='BUILD_SELECTION'
    assert usages[0]['association']=='DECLARED_SELECTION'


def test_snapshot_pins_complete_identity_and_keeps_file_id_stable(repository):
    snapshot = RepositorySnapshot(repository)
    reference = {'id': 'example', 'version': '1.0.0'}
    files = snapshot.files('repo://capability-packs/example', 'capability-packs', reference)
    assert files == snapshot.files('repo://capability-packs/example', 'capability-packs', reference)
    file = files[0]
    assert file['identityStatus'] == 'VERIFIED'
    assert file['reference']['assetType'] == 'CAPABILITY_PACK'
    assert file['reference']['source'] == 'repo://capability-packs/example'
    assert str(repository) not in str(file)


@pytest.mark.parametrize('reference', [{'id': 'other', 'version': '1.0.0'}, {'id': 'example', 'version': '2.0.0'}])
def test_snapshot_does_not_label_different_pack_identity_as_selected_version(repository, reference):
    with pytest.raises(ApiError, match='ASSET_CONTENT_SOURCE_MISMATCH'):
        RepositorySnapshot(repository).files('repo://capability-packs/example', 'capability-packs', reference)


def test_snapshot_rejects_missing_or_symlink_parent_worktree(repository, tmp_path):
    snapshot = RepositorySnapshot(repository)
    source = repository / 'capability-packs/example'
    moved = tmp_path / 'outside'
    source.rename(moved)
    source.symlink_to(moved, target_is_directory=True)
    with pytest.raises(ApiError, match='ASSET_CONTENT_SOURCE_CHANGED'):
        snapshot.files('repo://capability-packs/example', 'capability-packs', {'id': 'example', 'version': '1.0.0'})


@pytest.mark.parametrize('support_state', ['NOT_APPLICABLE', 'AVAILABLE', 'UNAVAILABLE', 'IDENTITY_CONFLICT'])
def test_component_retains_all_provider_candidates_without_selecting_first(repository, monkeypatch, support_state):
    from finance_solution_builder import unified_catalog
    from finance_solution_builder import business_assets
    from finance_solution_builder.asset_identity import AssetIdentityError
    from finance_solution_builder.asset_contents import component_contents, development_relations
    from finance_solution_builder.asset_composition import composition
    from threading import RLock
    from types import SimpleNamespace

    (repository / 'capability-packs/example/pack.yaml').write_text('''packId: example
version: 1.0.0
capabilities:
  - capabilityId: example.query
    toolName: example_query
    operation: QUERY
''')
    contract = repository / 'contracts/json-schema/capability/capability-result.v1.schema.json'
    contract.parent.mkdir(parents=True)
    contract.write_text('{"type":"object"}')
    git(repository, 'add', '.')
    git(repository, '-c', 'user.name=Test', '-c', 'user.email=test@example.test', 'commit', '-m', 'component')
    providers = [{'id': 'provider', 'version': v, 'source': f'maven://test:provider:{v}'} for v in ('1', '2')]
    part = {'id': 'example', 'version': '1.0.0', 'label': 'Example', 'status': 'AVAILABLE',
            'capabilityPackRefs': [{'id': 'example', 'version': '1.0.0', 'source': 'repo://capability-packs/example'}],
            'domainPackRefs': [], 'providerRefs': [], 'capabilityBindings': {},
            'providerOptions': [{'providerRefs': [ref], 'capabilityBindings': {'example.query': 'provider'}} for ref in providers],
            'runtime': {}, 'runtimeIds': [], 'profileRef': {}, 'methodCustomizationSupported': support_state != 'NOT_APPLICABLE'}
    monkeypatch.setattr(unified_catalog, 'unified_catalog', lambda *args: {'directory': {'revision': 'revision', 'components': [part]}})
    jobs = SimpleNamespace(project=repository, builder=SimpleNamespace(harness_repo=repository), jobs={}, lock=RLock(), method_assets=object())
    support_calls = []
    support = {'status': 'AVAILABLE', 'methodCases': [{'name': 'test', 'query': 'example'}]}
    def load_support(*args, store, owner):
        assert store is jobs.method_assets and owner == 'alice'
        support_calls.append(args)
        if support_state == 'UNAVAILABLE':
            raise ApiError('DEVELOPMENT_SUPPORT_UNAVAILABLE', 409)
        if support_state == 'IDENTITY_CONFLICT':
            raise AssetIdentityError('ASSET_IDENTITY_CONFLICT', '版本摘要冲突')
        return support
    monkeypatch.setattr(business_assets, 'component_development_support', load_support)
    if support_state == 'IDENTITY_CONFLICT':
        with pytest.raises(AssetIdentityError, match='ASSET_IDENTITY_CONFLICT'):
            component_contents(jobs, 'alice', 'example', 'revision')
        return
    value = composition(development_relations(component_contents(jobs, 'alice', 'example', 'revision')))
    binding = value['bindings'][0]
    assert binding['providerId'] is None and binding['providerCandidates'] == providers
    assert binding['inputFileId'] in {f['fileId'] for f in value['files']}
    pending = next(n for n in value['composition']['nodes'] if n['id'] == 'provider-reference:provider')
    assert pending['label'] == '待确定版本' and len(pending['reference']['candidates']) == 2
    if support_state == 'NOT_APPLICABLE':
        assert value['developmentSupport'] is None and support_calls == []
    else:
        assert support_calls == [(repository, repository, 'example', 'revision')]
        assert value['developmentSupport']['status'] == support_state
        if support_state == 'AVAILABLE':
            assert value['developmentSupport'] == support
