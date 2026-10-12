from __future__ import annotations

import hashlib
import json
import re
import shutil
import subprocess
import tarfile
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


GIT_REF = re.compile(r"^git\+(?P<url>.+?\.git)@(?P<revision>[^#]+)#(?P<subpath>.+)$")
ARCHIVE_INTEGRITY_FILE = ".asset-integrity.json"
_ARCHIVE_METADATA_FILES = {
    ".asset-revision",
    ".asset-source",
    ARCHIVE_INTEGRITY_FILE,
}


def tree_digest(path: Path) -> str:
    """Return a stable digest for a file or directory tree."""
    digest = hashlib.sha256()
    if path.is_file():
        digest.update(path.name.encode())
        digest.update(path.read_bytes())
        return f"sha256:{digest.hexdigest()}"
    for item in sorted(p for p in path.rglob("*") if p.is_file()):
        if any(part in {".git", "target", "__pycache__"} for part in item.parts):
            continue
        digest.update(item.relative_to(path).as_posix().encode())
        digest.update(b"\0")
        digest.update(item.read_bytes())
        digest.update(b"\0")
    return f"sha256:{digest.hexdigest()}"


def parse_git_source(source: str) -> tuple[str, str, str]:
    match = GIT_REF.fullmatch(source)
    if not match:
        raise ValueError(f"unsupported git asset reference: {source}")
    return match["url"], match["revision"], match["subpath"].strip("/")


def parse_github_repo(url: str) -> tuple[str, str]:
    parsed = urlparse(url)
    parts = parsed.path.strip("/").removesuffix(".git").split("/")
    if parsed.scheme != "https" or parsed.hostname != "github.com" or len(parts) != 2:
        raise ValueError(f"not a GitHub repository URL: {url}")
    return parts[0], parts[1]


def archive_tree_digest(path: Path) -> str:
    """Return a stable digest for a downloaded repository archive."""

    digest = hashlib.sha256()
    for item in sorted(candidate for candidate in path.rglob("*") if candidate.is_file()):
        relative = item.relative_to(path)
        if relative.name in _ARCHIVE_METADATA_FILES:
            continue
        if any(
            part in {".git", "target", "node_modules", "__pycache__"} for part in relative.parts
        ):
            continue
        digest.update(relative.as_posix().encode())
        digest.update(b"\0")
        digest.update(item.read_bytes())
        digest.update(b"\0")
    return f"sha256:{digest.hexdigest()}"


@dataclass(frozen=True)
class HarnessArchiveAnchor:
    """Trusted identity supplied outside a non-Git Harness archive."""

    source: str
    revision: str
    tree_digest: str

    def __post_init__(self) -> None:
        parse_github_repo(self.source)
        if re.fullmatch(r"[a-fA-F0-9]{40}", self.revision) is None:
            raise ValueError("asset.archive.anchor_revision_invalid")
        if re.fullmatch(r"sha256:[a-fA-F0-9]{64}", self.tree_digest) is None:
            raise ValueError("asset.archive.anchor_tree_digest_invalid")


def optional_archive_anchor(
    *, source: str | None, revision: str | None, tree_digest: str | None
) -> HarnessArchiveAnchor | None:
    """Build one atomic archive anchor from executable configuration."""

    values = (source, revision, tree_digest)
    if not any(values):
        return None
    if not all(values):
        raise ValueError("asset.archive.anchor_incomplete")
    return HarnessArchiveAnchor(
        source=str(source), revision=str(revision), tree_digest=str(tree_digest)
    )


def write_archive_integrity(path: Path, *, revision: str, source: str) -> None:
    """Persist download metadata; callers must supply an external trust anchor."""

    parse_github_repo(source)
    if re.fullmatch(r"[a-fA-F0-9]{40}", revision) is None:
        raise ValueError("asset.archive.revision_not_full_sha")
    (path / ".asset-revision").write_text(revision + "\n", encoding="utf-8")
    (path / ".asset-source").write_text(source + "\n", encoding="utf-8")
    document = {
        "schemaVersion": "finance-asset-archive-integrity.v1",
        "revision": revision,
        "source": source,
        "treeDigest": archive_tree_digest(path),
    }
    (path / ARCHIVE_INTEGRITY_FILE).write_text(
        json.dumps(document, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def verify_archive_integrity(
    path: Path,
    *,
    anchor: HarnessArchiveAnchor,
) -> None:
    """Reject an archive that differs from an externally trusted anchor."""

    try:
        revision = (path / ".asset-revision").read_text(encoding="utf-8").strip()
        source = (path / ".asset-source").read_text(encoding="utf-8").strip()
        document = json.loads((path / ARCHIVE_INTEGRITY_FILE).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("asset.archive.integrity_metadata_missing_or_invalid") from exc
    if revision != anchor.revision:
        raise ValueError("asset.archive.revision_mismatch")
    try:
        parse_github_repo(source)
    except ValueError as exc:
        raise ValueError("asset.archive.untrusted_source") from exc
    if source != anchor.source:
        raise ValueError("asset.archive.source_mismatch")
    if not isinstance(document, dict) or document.get("schemaVersion") != (
        "finance-asset-archive-integrity.v1"
    ):
        raise ValueError("asset.archive.integrity_schema_invalid")
    if document.get("revision") != revision or document.get("source") != source:
        raise ValueError("asset.archive.integrity_identity_mismatch")
    if document.get("treeDigest") != anchor.tree_digest:
        raise ValueError("asset.archive.anchor_digest_mismatch")
    if archive_tree_digest(path) != anchor.tree_digest:
        raise ValueError("asset.archive.content_digest_mismatch")


@dataclass(frozen=True)
class ResolvedAsset:
    id: str
    version: str
    source: str
    status: str
    digest: str | None = None
    revision: str | None = None
    path: Path | None = None
    error: str | None = None
    root_path: Path | None = None

    def lock_record(self) -> dict[str, Any]:
        record: dict[str, Any] = {
            "id": self.id,
            "version": self.version,
            "source": self.source,
            "status": self.status,
        }
        for key, value in (
            ("digest", self.digest),
            ("revision", self.revision),
            ("error", self.error),
        ):
            if value:
                record[key] = value
        return record


class AssetResolver:
    def __init__(self, harness_repo: Path, cache_dir: Path, git: str = "git") -> None:
        self.harness_repo = harness_repo.resolve()
        self.cache_dir = cache_dir.resolve()
        self.git = git
        self._verified_archives: dict[Path, HarnessArchiveAnchor] = {}
        self.cache_dir.mkdir(parents=True, exist_ok=True)

    def resolve(self, ref: dict[str, Any]) -> ResolvedAsset:
        source = str(ref["source"])
        if source.startswith("repo://"):
            path = (self.harness_repo / source.removeprefix("repo://")).resolve()
            if not path.is_relative_to(self.harness_repo):
                raise ValueError(f"repo asset escapes harness root: {source}")
            if not path.exists():
                return ResolvedAsset(
                    ref["id"], ref["version"], source, "unresolved", error="path not found"
                )
            return ResolvedAsset(
                ref["id"],
                ref["version"],
                source,
                "resolved",
                tree_digest(path),
                path=path,
                root_path=self.harness_repo,
            )
        if source.startswith("git+"):
            return self._resolve_git(ref)
        return ResolvedAsset(
            ref["id"], ref["version"], source, "unresolved", error="unsupported source"
        )

    def _resolve_git(self, ref: dict[str, Any]) -> ResolvedAsset:
        source = str(ref["source"])
        try:
            url, revision, subpath = parse_git_source(source)
        except ValueError as exc:
            return ResolvedAsset(ref["id"], ref["version"], source, "unresolved", error=str(exc))
        cache_key = hashlib.sha256(f"{url}@{revision}".encode()).hexdigest()[:16]
        checkout = self.cache_dir / cache_key
        cached_asset_path = (checkout / subpath).resolve()
        cached_anchor = self._verified_archives.get(checkout)
        if cached_anchor is not None and cached_asset_path.exists():
            if re.fullmatch(r"[a-fA-F0-9]{7,40}", revision) and cached_anchor.revision.startswith(
                revision
            ):
                try:
                    verify_archive_integrity(checkout, anchor=cached_anchor)
                except ValueError:
                    pass
                else:
                    return ResolvedAsset(
                        ref["id"],
                        ref["version"],
                        source,
                        "resolved",
                        tree_digest(cached_asset_path),
                        revision=cached_anchor.revision,
                        path=cached_asset_path,
                        root_path=checkout,
                    )
        try:
            if not (checkout / ".git").exists():
                shutil.rmtree(checkout, ignore_errors=True)
                checkout.mkdir(parents=True)
                self._run([self.git, "init", "-q"], checkout)
                self._run([self.git, "remote", "add", "origin", url], checkout)
            self._run([self.git, "fetch", "-q", "--depth=1", "origin", revision], checkout)
            self._run([self.git, "checkout", "-q", "--detach", "FETCH_HEAD"], checkout)
            locked_revision = self._run([self.git, "rev-parse", "HEAD"], checkout).strip()
            asset_path = (checkout / subpath).resolve()
            if not asset_path.is_relative_to(checkout.resolve()) or not asset_path.exists():
                raise RuntimeError(f"subpath not found: {subpath}")
            return ResolvedAsset(
                ref["id"],
                ref["version"],
                source,
                "resolved",
                tree_digest(asset_path),
                revision=locked_revision,
                path=asset_path,
                root_path=checkout,
            )
        except (
            OSError,
            subprocess.CalledProcessError,
            subprocess.TimeoutExpired,
            RuntimeError,
        ) as exc:
            try:
                archive_anchor = self._download_github_archive(url, revision, checkout)
                asset_path = (checkout / subpath).resolve()
                if not asset_path.is_relative_to(checkout.resolve()) or not asset_path.exists():
                    raise RuntimeError(f"subpath not found: {subpath}")
                return ResolvedAsset(
                    ref["id"],
                    ref["version"],
                    source,
                    "resolved",
                    tree_digest(asset_path),
                    revision=archive_anchor.revision,
                    path=asset_path,
                    root_path=checkout,
                )
            except (
                OSError,
                subprocess.CalledProcessError,
                subprocess.TimeoutExpired,
                RuntimeError,
                ValueError,
            ):
                pass
            message = str(exc)
            if isinstance(exc, subprocess.CalledProcessError) and exc.stderr:
                message = exc.stderr.strip().splitlines()[-1]
            return ResolvedAsset(
                ref["id"], ref["version"], source, "unresolved", revision=revision, error=message
            )

    def _download_github_archive(
        self, url: str, revision: str, checkout: Path
    ) -> HarnessArchiveAnchor:
        owner, repo = parse_github_repo(url)
        locked_revision = self._run(
            ["gh", "api", f"repos/{owner}/{repo}/commits/{revision}", "--jq", ".sha"],
            self.cache_dir,
        ).strip()
        with tempfile.TemporaryDirectory(dir=self.cache_dir) as temporary:
            temporary_root = Path(temporary)
            archive_path = temporary_root / "asset.tar.gz"
            with archive_path.open("wb") as archive_file:
                subprocess.run(
                    ["gh", "api", f"repos/{owner}/{repo}/tarball/{locked_revision}"],
                    cwd=self.cache_dir,
                    stdout=archive_file,
                    stderr=subprocess.PIPE,
                    check=True,
                    timeout=60,
                )
            extract_root = temporary_root / "extract"
            extract_root.mkdir()
            with tarfile.open(archive_path, "r:gz") as archive:
                self._safe_extract(archive, extract_root)
            roots = [item for item in extract_root.iterdir() if item.is_dir()]
            if len(roots) != 1:
                raise RuntimeError("GitHub archive has an unexpected root layout")
            shutil.rmtree(checkout, ignore_errors=True)
            shutil.copytree(roots[0], checkout)
            write_archive_integrity(checkout, revision=locked_revision, source=url)
        anchor = HarnessArchiveAnchor(
            source=url,
            revision=locked_revision,
            tree_digest=archive_tree_digest(checkout),
        )
        self._verified_archives[checkout] = anchor
        return anchor

    @staticmethod
    def _safe_extract(archive: tarfile.TarFile, destination: Path) -> None:
        for member in archive.getmembers():
            target = (destination / member.name).resolve()
            if not target.is_relative_to(destination.resolve()):
                raise RuntimeError("archive member escapes extraction root")
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                target.parent.mkdir(parents=True, exist_ok=True)
                source = archive.extractfile(member)
                if source is None:
                    raise RuntimeError(f"could not read archive member: {member.name}")
                with source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)

    @staticmethod
    def _run(command: list[str], cwd: Path) -> str:
        result = subprocess.run(
            command,
            cwd=cwd,
            text=True,
            capture_output=True,
            check=True,
            timeout=30,
        )
        return result.stdout
