from __future__ import annotations

import os
from pathlib import Path

import pytest

from finance_builder_base.runtime import (
    UnsafeWorkspacePath,
    copy_directory_tree,
    copy_workspace_tree,
    io_path,
    require_path_within,
    resolve_directory,
    resolve_workspace_path,
)


def test_copy_directory_tree_between_trusted_roots(tmp_path: Path) -> None:
    source_root = tmp_path / "source-root"
    target_root = tmp_path / "target-root"
    source = source_root / "solution"
    source.mkdir(parents=True)
    target_root.mkdir()
    (source / "agent.py").write_text("VALUE = 1\n", encoding="utf-8")

    copied = copy_directory_tree(source_root, "solution", target_root, "adapted")

    assert copied == (target_root / "adapted").resolve()
    assert (copied / "agent.py").read_text(encoding="utf-8") == "VALUE = 1\n"


def test_resolve_and_copy_safe_workspace_tree(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    (source / "keep.txt").write_text("ok", encoding="utf-8")
    (source / ".git").mkdir()
    (source / ".git" / "config").write_text("ignored", encoding="utf-8")
    (source / "__pycache__").mkdir()
    (source / "__pycache__" / "x.pyc").write_bytes(b"ignored")

    assert resolve_workspace_path(tmp_path, "source/keep.txt", required_type="file").is_file()
    target = copy_workspace_tree(tmp_path, "source", "output")
    assert (target / "keep.txt").read_text(encoding="utf-8") == "ok"
    assert not (target / ".git").exists()
    assert not (target / "__pycache__").exists()


def test_directory_and_containment_helpers_are_repeatable(tmp_path: Path) -> None:
    root = tmp_path / "workspace"
    nested = root / "artifacts" / "candidate"
    nested.mkdir(parents=True)

    first_root = resolve_directory(root)
    first_path = require_path_within(nested, root)

    assert resolve_directory(root) == first_root == root.resolve()
    assert require_path_within(nested, root) == first_path == nested.resolve()
    assert sorted(root.rglob("*")) == [root / "artifacts", nested]


def test_containment_helper_rejects_outside_without_leaking_root(tmp_path: Path) -> None:
    root = tmp_path / "workspace"
    root.mkdir()

    with pytest.raises(UnsafeWorkspacePath) as caught:
        require_path_within(tmp_path / "outside", root)

    assert caught.value.code == "workspace.path_outside_root"
    assert str(tmp_path) not in str(caught.value)


@pytest.mark.parametrize("value", ["../outside", r"C:\outside", "/outside"])
def test_rejects_outside_paths_without_leaking_root(tmp_path: Path, value: str) -> None:
    with pytest.raises(UnsafeWorkspacePath) as caught:
        resolve_workspace_path(tmp_path, value)
    assert caught.value.code == "workspace.path_outside_root"
    assert str(tmp_path) not in str(caught.value)
    assert str(tmp_path) not in caught.value.logical_path


@pytest.mark.parametrize(
    ("root_name", "create_file", "expected_code"),
    [
        ("missing", False, "workspace.root_missing"),
        ("root.txt", True, "workspace.root_not_directory"),
    ],
)
def test_rejects_invalid_workspace_root_without_leaking_host_path(
    tmp_path: Path,
    root_name: str,
    create_file: bool,
    expected_code: str,
) -> None:
    root = tmp_path / root_name
    if create_file:
        root.write_text("not a directory", encoding="utf-8")

    with pytest.raises(UnsafeWorkspacePath) as caught:
        resolve_workspace_path(root, ".")

    assert caught.value.code == expected_code
    assert caught.value.logical_path == "."
    assert str(tmp_path) not in str(caught.value)


def test_copy_rejects_overlap_and_existing_target(tmp_path: Path) -> None:
    (tmp_path / "source" / "child").mkdir(parents=True)
    (tmp_path / "existing").mkdir()
    for target in ("source", "source/child/new", "existing"):
        with pytest.raises(UnsafeWorkspacePath):
            copy_workspace_tree(tmp_path, "source", target)


def test_rejects_symlink_that_can_escape(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    outside = tmp_path.parent / "outside.txt"
    outside.write_text("private", encoding="utf-8")
    link = source / "link.txt"
    try:
        link.symlink_to(outside)
    except OSError:
        pytest.skip("symlink creation is unavailable on this platform")
    with pytest.raises(UnsafeWorkspacePath) as caught:
        copy_workspace_tree(tmp_path, "source", "output")
    assert caught.value.code == "workspace.symlink_not_allowed"

def test_ignores_symlink_inside_excluded_transient_directory(tmp_path: Path) -> None:
    source = tmp_path / "source"
    transient = source / ".pytest-tmp"
    transient.mkdir(parents=True)
    outside = tmp_path.parent / "outside.txt"
    outside.write_text("private", encoding="utf-8")
    link = transient / "current"
    try:
        link.symlink_to(outside)
    except OSError:
        pytest.skip("symlink creation is unavailable on this platform")

    copied = copy_workspace_tree(tmp_path, "source", "output")

    assert not (copied / ".pytest-tmp").exists()


@pytest.mark.skipif(os.name != "nt", reason="Windows extended path contract")
def test_resolve_and_copy_support_paths_beyond_legacy_max_path(tmp_path: Path) -> None:
    deep = tmp_path
    while len(str(deep)) < 275:
        deep /= "long-workspace-segment"
    source_root = deep / "source-root"
    target_root = deep / "target-root"
    source = source_root / "solution"
    io_path(source).mkdir(parents=True)
    io_path(target_root).mkdir()
    io_path(source / "agent.py").write_text("VALUE = 1\n", encoding="utf-8")

    copied = copy_directory_tree(source_root, "solution", target_root, "adapted")

    assert len(str(copied / "agent.py")) > 260
    assert io_path(copied / "agent.py").read_text(encoding="utf-8") == "VALUE = 1\n"
    with pytest.raises(UnsafeWorkspacePath) as caught:
        copy_directory_tree(source_root, "solution", target_root, "adapted")
    assert caught.value.code == "workspace.target_exists"
