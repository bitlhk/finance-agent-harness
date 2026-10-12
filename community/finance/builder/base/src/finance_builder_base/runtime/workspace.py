"""Safe local-workspace path and copy operations."""

from __future__ import annotations

import os
import shutil
from fnmatch import fnmatch
from pathlib import Path, PurePosixPath, PureWindowsPath

DEFAULT_EXCLUDES = frozenset(
    {
        ".git",
        ".mypy_cache",
        ".pytest_cache",
        ".pytest-*",
        ".ruff_cache",
        ".tox",
        ".venv",
        "*.pyc",
        "__pycache__",
        "htmlcov",
        "venv",
    }
)


class UnsafeWorkspacePath(ValueError):
    """Public-safe workspace failure with a stable machine code."""

    def __init__(self, code: str, logical_path: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.logical_path = logical_path
        self.message = message


def io_path(path: Path) -> Path:
    """Return an extended-length Windows path when the host requires one."""

    value = str(path)
    is_windows = os.name == "nt"
    is_absolute = path.is_absolute()
    lacks_extended_prefix = not value.startswith("\\\\?\\")
    if all((is_windows, is_absolute, lacks_extended_prefix)):
        if value.startswith("\\\\"):
            unc_parts = value.removeprefix("\\\\").split("\\")
            return Path(PureWindowsPath("\\\\?\\UNC").joinpath(*unc_parts))
        return Path(f"\\\\?\\{PureWindowsPath(value)}")
    return path


def _logical(value: str | Path) -> str:
    raw = str(value)
    if PurePosixPath(raw).is_absolute() or PureWindowsPath(raw).is_absolute():
        return "<absolute>"
    return raw.replace("\\", "/")


def _is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
    except ValueError:
        return False
    return True


def resolve_workspace_path(
    root: Path,
    relative_path: str | Path,
    *,
    required_type: str | None = None,
    must_exist: bool = True,
) -> Path:
    """Resolve a logical path under ``root`` without exposing host paths."""

    raw_path = str(relative_path)
    logical = _logical(relative_path)
    posix_path = PurePosixPath(raw_path)
    windows_path = PureWindowsPath(raw_path)
    has_unsafe_path_component = any(
        (
            posix_path.is_absolute(),
            windows_path.is_absolute(),
            ".." in posix_path.parts,
            ".." in windows_path.parts,
        )
    )
    if has_unsafe_path_component:
        raise UnsafeWorkspacePath("workspace.path_outside_root", logical, "path leaves workspace")

    trusted_root = root.resolve(strict=False)
    trusted_root_io = io_path(trusted_root)
    if not trusted_root_io.exists():
        raise UnsafeWorkspacePath(
            "workspace.root_missing", ".", "workspace root is missing"
        )
    if not trusted_root_io.is_dir():
        raise UnsafeWorkspacePath(
            "workspace.root_not_directory", ".", "workspace root is not a directory"
        )
    candidate = trusted_root.joinpath(relative_path).resolve(strict=False)
    if not _is_relative_to(candidate, trusted_root):
        raise UnsafeWorkspacePath("workspace.path_outside_root", logical, "path leaves workspace")
    candidate_io = io_path(candidate)
    if must_exist and not candidate_io.exists():
        raise UnsafeWorkspacePath("workspace.path_missing", logical, "required path is missing")
    if required_type == "file" and not candidate_io.is_file():
        raise UnsafeWorkspacePath("workspace.path_not_file", logical, "required path is not a file")
    if required_type == "directory" and not candidate_io.is_dir():
        raise UnsafeWorkspacePath(
            "workspace.path_not_directory", logical, "required path is not a directory"
        )
    return candidate


def resolve_directory(path: str | Path, *, must_exist: bool = True) -> Path:
    """Resolve a host-side directory through the shared workspace checks."""

    candidate = Path(path)
    try:
        parent = candidate.parent.resolve(strict=False)
        return resolve_workspace_path(
            parent,
            candidate.name,
            required_type="directory" if must_exist else None,
            must_exist=must_exist,
        )
    except OSError as exc:
        raise UnsafeWorkspacePath(
            "workspace.path_unavailable",
            _logical(path),
            "directory is missing or unavailable",
        ) from exc


def require_path_within(path: str | Path, root: str | Path) -> Path:
    """Resolve ``path`` and require it to remain under an existing root."""

    trusted_root = resolve_directory(root)
    try:
        candidate = Path(path).resolve(strict=False)
    except OSError as exc:
        raise UnsafeWorkspacePath(
            "workspace.path_unavailable",
            _logical(path),
            "path is unavailable",
        ) from exc
    try:
        relative = candidate.relative_to(trusted_root)
    except ValueError as exc:
        raise UnsafeWorkspacePath(
            "workspace.path_outside_root",
            _logical(path),
            "path leaves workspace",
        ) from exc
    return resolve_workspace_path(trusted_root, relative, must_exist=False)


def _reject_symlinks(
    source: Path,
    logical_source: str,
    excludes: frozenset[str],
) -> None:
    source_io = io_path(source)
    for item in source_io.rglob("*"):
        relative = item.relative_to(source_io)
        is_excluded = False
        for part in relative.parts:
            for pattern in excludes:
                if fnmatch(part, pattern):
                    is_excluded = True
                    break
            if is_excluded:
                break
        if is_excluded:
            continue
        if item.is_symlink():
            raise UnsafeWorkspacePath(
                "workspace.symlink_not_allowed",
                logical_source,
                "source tree contains a symbolic link",
            )


def copy_directory_tree(
    source_root: Path,
    source: str | Path,
    target_root: Path,
    target: str | Path,
    *,
    excludes: frozenset[str] = DEFAULT_EXCLUDES,
) -> Path:
    """Copy between two trusted roots after validating both logical paths."""

    source_path = resolve_workspace_path(source_root, source, required_type="directory")
    target_path = resolve_workspace_path(target_root, target, must_exist=False)
    logical_target = _logical(target)
    if io_path(target_path).exists():
        raise UnsafeWorkspacePath(
            "workspace.target_exists", logical_target, "copy target already exists"
        )
    paths_match = source_path == target_path
    target_is_nested = _is_relative_to(target_path, source_path)
    source_is_nested = _is_relative_to(source_path, target_path)
    if any((paths_match, target_is_nested, source_is_nested)):
        raise UnsafeWorkspacePath(
            "workspace.path_overlap", logical_target, "source and target overlap"
        )
    _reject_symlinks(source_path, _logical(source), excludes)
    shutil.copytree(
        io_path(source_path),
        io_path(target_path),
        ignore=shutil.ignore_patterns(*sorted(excludes)),
    )
    return target_path


def copy_workspace_tree(
    root: Path,
    source: str | Path,
    target: str | Path,
    *,
    excludes: frozenset[str] = DEFAULT_EXCLUDES,
) -> Path:
    """Copy a verified directory tree within one trusted workspace root."""

    return copy_directory_tree(root, source, root, target, excludes=excludes)
