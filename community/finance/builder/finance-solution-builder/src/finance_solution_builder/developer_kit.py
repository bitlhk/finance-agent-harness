"""Authenticated source kit, separate from the Harness business ZIP format."""
import base64
import io
import json
from pathlib import Path, PurePosixPath
import subprocess
import zipfile

from .asset_contents import _git
from .experience_contracts import ApiError
from .method_assets import sha

ROOTS = ("pom.xml", "LICENSE", "README.md", "packages", "providers", "integrations", "conformance",
         "examples", "capability-packs", "domain-packs", "contracts", "catalog", "engineering", "docs", "evaluation", "profiles", "observation-packs", "routing", ".github")
MAX_ARCHIVE = 20 * 1024 * 1024


def developer_kit(harness):
    revision = _git(harness, "rev-parse", "HEAD").decode().strip()
    process = subprocess.run(["git", "-C", str(harness), "archive", "--format=zip", revision,
                              *[p for p in ROOTS if _git(harness, "ls-tree", revision, "--", p)]],
                             capture_output=True, timeout=30)
    if process.returncode or len(process.stdout) > MAX_ARCHIVE:
        raise ApiError("DEVELOPER_KIT_UNAVAILABLE", 409)
    files = {}
    with zipfile.ZipFile(io.BytesIO(process.stdout)) as source:
        for entry in source.infolist():
            if entry.is_dir():
                continue
            path = PurePosixPath(entry.filename)
            mode = entry.external_attr >> 16
            if path.is_absolute() or ".." in path.parts or mode & 0o170000 == 0o120000:
                raise ApiError("DEVELOPER_KIT_UNSAFE_ENTRY", 409)
            if entry.file_size > MAX_ARCHIVE or sum(map(len, files.values())) + entry.file_size > MAX_ARCHIVE:
                raise ApiError("DEVELOPER_KIT_LIMIT_EXCEEDED", 409)
            files["harness/" + entry.filename] = source.read(entry)
    readme = Path(__file__).with_name("developer-kit-readme.md").read_bytes()
    files["README.md"] = readme
    inventory = {"schemaVersion": "finance-developer-kit.v0.2", "sourceCommit": revision,
                 "kind": "SOURCE_REFERENCE_KIT_NOT_BUSINESS_PACKAGE", "runtime": "Declared in harness/pom.xml",
                 "files": {name: sha(raw) for name, raw in files.items()},
                 "humanIndependentAcceptance": "NOT_RUN"}
    files["source-lock.json"] = json.dumps(inventory, ensure_ascii=False, indent=2).encode()
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, raw in sorted(files.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, raw)
    raw = buffer.getvalue()
    return {"filename": "finance-developer-kit-" + revision[:12] + ".zip", "base64": base64.b64encode(raw).decode(),
            "sha256": sha(raw), "sourceCommit": revision, "kind": inventory["kind"]}
