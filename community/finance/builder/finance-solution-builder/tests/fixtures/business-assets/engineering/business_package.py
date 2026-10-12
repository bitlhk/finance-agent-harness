"""Test double for the trusted Harness Packager boundary; production loads the Harness implementation."""
import hashlib
import json
import stat
import zipfile
from pathlib import PurePosixPath


class PackageError(ValueError):
    pass


def safe_name(name):
    path = PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts or name.startswith("/"):
        raise PackageError("PACKAGE_PATH_INVALID")
    return name


def _digest(raw):
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def build(root, output):
    source = json.loads((root / "package-source.json").read_text())
    paths = source.pop("files")
    files = {name: (root / safe_name(name)).read_bytes() for name in paths}
    source["schemaVersion"] = "finance-business-component-package.v0.1"
    source["files"] = [{"path": name,
        "role": "SKILL" if name == "skill/SKILL.md" else "VALIDATION_SUMMARY" if name == "validation-summary.json" else "CASE" if name.startswith("cases/") else "REFERENCE",
        "sha256": _digest(raw), "size": len(raw)} for name, raw in sorted(files.items())]
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr(zipfile.ZipInfo("finance-business-package.json"), json.dumps(source, sort_keys=True))
        for name, raw in sorted(files.items()): archive.writestr(zipfile.ZipInfo(name), raw)
    raw = output.read_bytes()
    return {"manifest": source, "archiveSha256": _digest(raw), "archiveSize": len(raw)}


def read(package):
    raw = package.read_bytes()
    try:
        with zipfile.ZipFile(package) as archive:
            files = {}
            for item in archive.infolist():
                safe_name(item.filename)
                if item.is_dir() or stat.S_ISLNK(item.external_attr >> 16): raise PackageError("PACKAGE_ARCHIVE_ENTRY_INVALID")
                files[item.filename] = archive.read(item)
        manifest = json.loads(files.pop("finance-business-package.json"))
    except (KeyError, ValueError, zipfile.BadZipFile) as error:
        if isinstance(error, PackageError): raise
        raise PackageError("PACKAGE_ARCHIVE_INVALID") from error
    return manifest, files, raw
