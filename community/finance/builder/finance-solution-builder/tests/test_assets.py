import hashlib
from pathlib import Path

import pytest

from finance_solution_builder.assets import (
    AssetResolver,
    HarnessArchiveAnchor,
    archive_tree_digest,
    optional_archive_anchor,
    parse_git_source,
    parse_github_repo,
    tree_digest,
    write_archive_integrity,
)


def test_tree_digest_is_stable(tmp_path: Path) -> None:
    (tmp_path / "b.txt").write_text("b", encoding="utf-8")
    (tmp_path / "a.txt").write_text("a", encoding="utf-8")
    first = tree_digest(tmp_path)
    second = tree_digest(tmp_path)
    assert first == second
    assert first.startswith("sha256:")


def test_tree_digest_changes_with_content(tmp_path: Path) -> None:
    file = tmp_path / "asset.txt"
    file.write_text("a", encoding="utf-8")
    first = tree_digest(tmp_path)
    file.write_text("b", encoding="utf-8")
    assert tree_digest(tmp_path) != first


def test_parse_git_source() -> None:
    assert parse_git_source("git+https://example/a.git@abc#path/to/asset") == (
        "https://example/a.git",
        "abc",
        "path/to/asset",
    )


def test_parse_private_github_repo() -> None:
    assert parse_github_repo("https://github.com/example/private-repo.git") == (
        "example",
        "private-repo",
    )


def test_repo_source_cannot_escape_root(tmp_path: Path) -> None:
    resolver = AssetResolver(tmp_path, tmp_path / "cache")
    with pytest.raises(ValueError, match="escapes"):
        resolver.resolve({"id": "x", "version": "1", "source": "repo://../outside"})


def test_repo_asset_is_resolved(tmp_path: Path) -> None:
    asset = tmp_path / "packs/test"
    asset.mkdir(parents=True)
    (asset / "pack.yaml").write_text("version: 1\n", encoding="utf-8")
    resolved = AssetResolver(tmp_path, tmp_path / "cache").resolve(
        {"id": "test", "version": "1", "source": "repo://packs/test"}
    )
    assert resolved.status == "resolved"
    assert resolved.digest.startswith("sha256:")


def test_pinned_github_archive_is_reused_without_git_fetch(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    revision = "a" * 40
    repository = "https://github.com/example/private-repo.git"
    source = f"git+{repository}@{revision}#packs/demo"
    cache_key = hashlib.sha256(f"{repository}@{revision}".encode()).hexdigest()[:16]
    checkout = tmp_path / "cache" / cache_key
    asset = checkout / "packs/demo"
    asset.mkdir(parents=True)
    (asset / "pack.yaml").write_text("version: 1\n", encoding="utf-8")
    write_archive_integrity(checkout, revision=revision, source=repository)

    resolver = AssetResolver(tmp_path, tmp_path / "cache")
    resolver._verified_archives[checkout] = HarnessArchiveAnchor(
        source=repository,
        revision=revision,
        tree_digest=archive_tree_digest(checkout),
    )
    monkeypatch.setattr(resolver, "_run", lambda *_args: pytest.fail("cache miss"))
    resolved = resolver.resolve({"id": "demo", "version": "1", "source": source})

    assert resolved.status == "resolved"
    assert resolved.revision == revision


def test_archive_anchor_configuration_is_atomic() -> None:
    with pytest.raises(ValueError, match="asset.archive.anchor_incomplete"):
        optional_archive_anchor(
            source="https://github.com/example/private-repo.git",
            revision=None,
            tree_digest=None,
        )


def test_archive_anchor_rejects_invalid_digest() -> None:
    with pytest.raises(ValueError, match="asset.archive.anchor_tree_digest_invalid"):
        HarnessArchiveAnchor(
            source="https://github.com/example/private-repo.git",
            revision="a" * 40,
            tree_digest="sha256:not-a-digest",
        )
