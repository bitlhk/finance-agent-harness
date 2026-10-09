"""Build and inspect inert Finance Harness business component ZIPs."""
from __future__ import annotations

import argparse
import hashlib
import json
import stat
import zipfile
from pathlib import Path, PurePosixPath

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "contracts/json-schema/package/finance-business-component-package.v0.1.schema.json"
MANIFEST = "finance-business-package.json"
MAX_FILES = 128
MAX_FILE = 256 * 1024
MAX_TOTAL = 512 * 1024


class PackageError(ValueError):
    pass


def canonical(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def digest(raw: bytes) -> str:
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def safe_name(value: str) -> str:
    if not isinstance(value, str) or not value or len(value) > 360 or "\\" in value or "\x00" in value:
        raise PackageError("PACKAGE_PATH_INVALID")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise PackageError("PACKAGE_PATH_INVALID")
    allowed = value == "skill/SKILL.md" or value == "validation-summary.json" or (
        value.startswith("references/") and path.suffix.lower() in {".md", ".txt", ".json", ".yaml", ".yml"}
    ) or (value.startswith("templates/") and path.suffix.lower() in {".md", ".txt"}
    ) or (value.startswith("cases/") and path.suffix.lower() == ".json")
    if not allowed:
        raise PackageError("PACKAGE_FILE_TYPE_UNSUPPORTED")
    return value


def validator() -> Draft202012Validator:
    schema = json.loads(SCHEMA.read_text())
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


def load_source(root: Path) -> tuple[dict, dict[str, bytes]]:
    definition_path = root / "package-source.json"
    try:
        definition = json.loads(definition_path.read_text())
    except (OSError, ValueError) as error:
        raise PackageError("PACKAGE_SOURCE_INVALID") from error
    required = {"schemaVersion", "package", "skill", "bindings", "requirements", "cases", "validation", "files"}
    if set(definition) != required or definition["schemaVersion"] != "finance-business-component-package-source.v0.1":
        raise PackageError("PACKAGE_SOURCE_INVALID")
    paths = definition.pop("files")
    if not isinstance(paths, list) or not 3 <= len(paths) <= MAX_FILES or len(paths) != len(set(paths)):
        raise PackageError("PACKAGE_FILES_INVALID")
    files: dict[str, bytes] = {}
    total = 0
    for name in sorted(paths):
        safe_name(name)
        file = root / name
        resolved = file.resolve()
        if not resolved.is_relative_to(root.resolve()) or not file.is_file() or file.is_symlink():
            raise PackageError("PACKAGE_FILE_INVALID")
        raw = file.read_bytes()
        total += len(raw)
        if len(raw) > MAX_FILE or total > MAX_TOTAL:
            raise PackageError("PACKAGE_SIZE_EXCEEDED")
        files[name] = raw
    roles = {"skill/SKILL.md": "SKILL", "validation-summary.json": "VALIDATION_SUMMARY"}
    manifest = {**definition, "schemaVersion": "finance-business-component-package.v0.1",
        "files": [{"path": name, "role": roles.get(name, "CASE" if name.startswith("cases/") else "REFERENCE"),
                   "sha256": digest(raw), "size": len(raw)} for name, raw in files.items()]}
    validate_manifest(manifest, files)
    return manifest, files


def validate_manifest(manifest: dict, files: dict[str, bytes]) -> None:
    errors = sorted(validator().iter_errors(manifest), key=lambda item: list(item.path))
    if errors:
        raise PackageError("PACKAGE_MANIFEST_INVALID: " + errors[0].message)
    declared = {item["path"]: item for item in manifest["files"]}
    if len(declared) != len(manifest["files"]) or set(declared) != set(files):
        raise PackageError("PACKAGE_FILE_SET_MISMATCH")
    for name, raw in files.items():
        safe_name(name)
        if declared[name]["size"] != len(raw) or declared[name]["sha256"] != digest(raw):
            raise PackageError("PACKAGE_FILE_DIGEST_MISMATCH")
        expected_role = ("SKILL" if name == "skill/SKILL.md" else
                         "VALIDATION_SUMMARY" if name == "validation-summary.json" else
                         "CASE" if name.startswith("cases/") else "REFERENCE")
        if declared[name]["role"] != expected_role:
            raise PackageError("PACKAGE_FILE_ROLE_MISMATCH")
    if manifest["skill"]["entry"] not in files or manifest["validation"]["summaryPath"] not in files:
        raise PackageError("PACKAGE_REQUIRED_FILE_MISSING")
    case_paths = {case["path"] for case in manifest["cases"]}
    if not case_paths <= set(files):
        raise PackageError("PACKAGE_CASE_MISSING")
    binding_ids = [item["capabilityId"] for item in manifest["bindings"]]
    if len(binding_ids) != len(set(binding_ids)):
        raise PackageError("PACKAGE_BINDING_DUPLICATE")
    parsed = {}
    for name in case_paths | {manifest["validation"]["summaryPath"]}:
        try:
            parsed[name] = json.loads(files[name])
        except ValueError as error:
            raise PackageError("PACKAGE_JSON_INVALID") from error
    summary = parsed[manifest["validation"]["summaryPath"]]
    if (not isinstance(summary, dict)
            or summary.get("status") != manifest["validation"]["status"]
            or summary.get("professionalQuality", "NOT_EVALUATED") != manifest["validation"]["professionalQuality"]):
        raise PackageError("PACKAGE_VALIDATION_SUMMARY_MISMATCH")


def build(source: Path, output: Path) -> dict:
    manifest, files = load_source(source.resolve())
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, raw in [(MANIFEST, canonical(manifest)), *files.items()]:
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(info, raw)
    raw = output.read_bytes()
    return {"manifest": manifest, "archiveSha256": digest(raw), "archiveSize": len(raw)}


def read(package: Path) -> tuple[dict, dict[str, bytes], bytes]:
    raw = package.read_bytes()
    if len(raw) > MAX_TOTAL:
        raise PackageError("PACKAGE_SIZE_EXCEEDED")
    files: dict[str, bytes] = {}
    try:
        with zipfile.ZipFile(package) as archive:
            if len(archive.infolist()) > MAX_FILES + 1:
                raise PackageError("PACKAGE_FILES_INVALID")
            total = 0
            for item in archive.infolist():
                if item.is_dir() or item.flag_bits & 1 or stat.S_ISLNK(item.external_attr >> 16):
                    raise PackageError("PACKAGE_ARCHIVE_ENTRY_INVALID")
                name = item.filename
                if name != MANIFEST:
                    safe_name(name)
                if name in files or item.file_size > MAX_FILE or item.file_size > max(item.compress_size, 1) * 100:
                    raise PackageError("PACKAGE_ARCHIVE_ENTRY_INVALID")
                total += item.file_size
                if total > MAX_TOTAL:
                    raise PackageError("PACKAGE_SIZE_EXCEEDED")
                files[name] = archive.read(item)
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError) as error:
        raise PackageError("PACKAGE_ARCHIVE_INVALID") from error
    if MANIFEST not in files:
        raise PackageError("PACKAGE_MANIFEST_MISSING")
    try:
        manifest = json.loads(files.pop(MANIFEST))
    except ValueError as error:
        raise PackageError("PACKAGE_MANIFEST_INVALID") from error
    validate_manifest(manifest, files)
    return manifest, files, raw


def inspect(package: Path) -> dict:
    manifest, _, raw = read(package)
    return {"manifest": manifest, "archiveSha256": digest(raw), "archiveSize": len(raw)}


def main() -> None:
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("build")
    create.add_argument("--source", type=Path, required=True)
    create.add_argument("--output", type=Path, required=True)
    check = commands.add_parser("inspect")
    check.add_argument("--package", type=Path, required=True)
    args = parser.parse_args()
    result = build(args.source, args.output) if args.command == "build" else inspect(args.package)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
