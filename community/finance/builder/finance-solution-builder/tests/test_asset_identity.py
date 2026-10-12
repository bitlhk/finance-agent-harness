from copy import deepcopy

import pytest

from finance_solution_builder.asset_contents import file_view
from finance_solution_builder.asset_identity import (
    AssetIdentityError, checked_files, prepare_contents, resolve_file,
)
from finance_solution_builder.workbench_api import public_error


def entry(content=b"# First", **reference):
    return file_view("SKILL.md", content, "PACKAGE_FILE", "SKILL", {
        "assetType": "BUSINESS_PACKAGE", "id": "example", "version": "1.0.0",
        "source": "package://example", "workspaceId": "owned-workspace", **reference})


def test_identical_references_deduplicate_without_losing_relationships_or_mutation():
    a, b = entry(via="first"), entry(via="second")
    original = deepcopy([a, b])
    files = checked_files([a, b])
    assert [a, b] == original
    assert len(files) == 1
    assert [ref["via"] for ref in files[0]["references"]] == ["first", "second"]
    assert files == checked_files(files)
    assert files[0]["fileId"].startswith("asset-file:")
    assert files[0]["path"] == "SKILL.md"


@pytest.mark.parametrize("change", ["content", "role", "origin"])
def test_same_identity_conflicts_before_dictionary_overwrite(change):
    a = entry()
    b = entry(b"# Changed") if change == "content" else entry()
    if change == "role": b["role"] = "CASE"
    if change == "origin": b["origin"] = "REFERENCED_ASSET"
    with pytest.raises(AssetIdentityError, match="ASSET_IDENTITY_CONFLICT"):
        checked_files([a, b])
    with pytest.raises(AssetIdentityError, match="ASSET_IDENTITY_CONFLICT"):
        checked_files([b, a])


@pytest.mark.parametrize("different", [
    {"source": "package://other"}, {"version": "2.0.0"},
    {"workspaceId": "other-workspace"}, {"assetType": "DOMAIN_PACK"}, {"id": "other"},
])
def test_identity_dimensions_keep_same_path_separate(different):
    files = checked_files([entry(), entry(b"# Other", **different)])
    assert len(files) == len({f["fileId"] for f in files}) == 2
    assert len({f["path"] for f in files}) == 1
    for file in files:
        assert resolve_file(files, file["fileId"]) == file
    with pytest.raises(AssetIdentityError, match="ASSET_FILE_AMBIGUOUS"):
        resolve_file(files, "SKILL.md")


def test_same_bytes_different_sources_remain_independently_addressable():
    files = checked_files([entry(), entry(source="package://other")])
    assert len(files) == 2 and files[0]["sha256"] == files[1]["sha256"]
    assert files[0]["fileId"] != files[1]["fileId"]


def test_old_bare_path_works_only_with_unique_match():
    files = checked_files([entry()])
    assert resolve_file(files, "SKILL.md") == files[0]
    assert resolve_file(files, "missing") is None


def test_file_id_cannot_retrieve_another_authorized_context():
    alice = checked_files([entry(workspaceId="alice")])
    bob = checked_files([entry(workspaceId="bob")])
    assert resolve_file(bob, alice[0]["fileId"]) is None


def test_historical_records_are_not_rewritten_or_given_content_digests():
    old = {"path": "SKILL.md", "role": "SKILL", "origin": "PACKAGE_FILE", "content": "old"}
    original = deepcopy(old)
    files = checked_files([old, old])
    assert old == original and len(files) == 2
    assert all("sha256" not in f and f["identityStatus"] == "SOURCE_UNVERIFIED" for f in files)
    assert all(f["identityMessage"] == "来源待核对" for f in files)
    assert files == checked_files(files)
    with pytest.raises(AssetIdentityError, match="ASSET_FILE_AMBIGUOUS"):
        resolve_file(files, "SKILL.md")


def test_missing_digest_does_not_mask_conflict_between_verified_entries():
    old = entry(); old.pop("sha256")
    with pytest.raises(AssetIdentityError, match="ASSET_IDENTITY_CONFLICT"):
        checked_files([entry(), old, entry(b"different")])


@pytest.mark.parametrize("digest,code", [("invalid", "ASSET_CONTENT_DIGEST_INVALID"),
                                        ("sha256:" + "0" * 64, "ASSET_CONTENT_DIGEST_MISMATCH")])
def test_false_content_digest_rejected(digest, code):
    file = entry(); file["sha256"] = digest
    with pytest.raises(AssetIdentityError, match=code):
        checked_files([file])


def test_asset_level_digest_claim_checked_even_for_nonoverlapping_paths():
    a, b = entry(digest="sha256:" + "1" * 64), entry(digest="sha256:" + "2" * 64)
    b["path"] = "other.md"
    with pytest.raises(AssetIdentityError, match="ASSET_IDENTITY_CONFLICT"):
        checked_files([a, b])


def test_asset_digest_claim_alone_is_not_verified_by_file_digest():
    file = entry(digest='sha256:' + '1' * 64)
    result = checked_files([file])[0]
    assert result['sha256'] != result['reference']['digest']
    assert result['identityStatus'] == 'SOURCE_UNVERIFIED'
    assert 'verifiedAssetDigest' not in result
    assert checked_files([result]) == [result]


def test_separate_verified_asset_digest_does_not_replace_file_digest():
    file = entry(digest='sha256:' + '1' * 64)
    file['verifiedAssetDigest'] = file['reference']['digest']
    result = checked_files([file])[0]
    assert result['identityStatus'] == 'VERIFIED'
    assert result['sha256'] == file['sha256'] != result['verifiedAssetDigest']
    file['verifiedAssetDigest'] = 'sha256:' + '2' * 64
    with pytest.raises(AssetIdentityError, match='ASSET_CONTENT_DIGEST_MISMATCH'):
        checked_files([file])


@pytest.mark.parametrize("path", ["/etc/secret", "../secret", "a/../secret", "a\\secret", "a//b", "a/./b", "a\nsecret"])
def test_unsafe_file_paths_are_rejected_with_redacted_locations(path):
    file = entry(); file["path"] = path
    with pytest.raises(AssetIdentityError) as caught:
        checked_files([file])
    status, detail = public_error(caught.value)
    assert status == 409 and detail["code"] == "ASSET_CONTENT_UNSAFE_ENTRY"
    assert detail["locations"][0]["path"] == "[来源待核对]"
    assert "message" in detail and not detail["retryable"]


def test_repo_file_must_be_inside_declared_asset_root():
    file = entry(source="repo://capability-packs/example")
    file["path"] = "capability-packs/example-other/pack.yaml"
    with pytest.raises(AssetIdentityError, match="ASSET_CONTENT_SOURCE_MISMATCH"):
        checked_files([file])


def test_binding_resolves_source_and_version_not_first_path():
    first, second = entry(), entry(b"second", version="2.0.0")
    result = prepare_contents({"files": [first, second], "dependencies": [], "bindings": [
        {"capabilityId": "lookup", "inputFile": "SKILL.md", "capabilityPack": second["reference"]}]})
    assert result["bindings"][0]["inputFileId"] == result["files"][1]["fileId"]


def test_conflict_public_response_keeps_safe_message_and_locations():
    with pytest.raises(AssetIdentityError) as caught:
        checked_files([entry(), entry(b"different")])
    status, detail = public_error(caught.value)
    assert status == 409 and detail["code"] == "ASSET_IDENTITY_CONFLICT"
    assert "不能覆盖" in detail["message"]
    assert detail["locations"] == [{"path": "SKILL.md", "source": "package://example"}] * 2


def test_preparation_is_idempotent_after_mixed_legacy_and_verified_deduplication():
    legacy = {'path': 'old.md', 'role': 'REFERENCE', 'origin': 'PACKAGE_FILE', 'content': 'old'}
    value = {'files': [entry(), entry(), legacy, legacy], 'bindings': [], 'dependencies': []}
    first = prepare_contents(value)
    assert len(first['files']) == 3
    assert first == prepare_contents(first) == prepare_contents(prepare_contents(first))
