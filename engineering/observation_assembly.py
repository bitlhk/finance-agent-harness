"""Offline observation assembly validation. Declarations never grant authority."""
from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

from jsonschema import Draft202012Validator, ValidationError

LIMIT = 256 * 1024
REFERENCE_LIMIT = 16
SCHEMAS = Path("contracts/json-schema/observation")


class AssemblyError(ValueError):
    """Safe stable failure code without source paths or payloads."""


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise AssemblyError("DUPLICATE_KEY")
        result[key] = value
    return result


def read_json(path):
    """Read a bounded JSON object, rejecting duplicate and non-finite values."""
    try:
        with Path(path).open("rb") as stream:
            raw = stream.read(LIMIT + 1)
        if len(raw) > LIMIT:
            raise AssemblyError("RESOURCE_LIMIT")
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_pairs,
                          parse_constant=lambda _: _invalid_number())
    except (OSError, UnicodeError, json.JSONDecodeError, RecursionError) as exc:
        raise AssemblyError("INVALID_DOCUMENT") from exc


def _invalid_number():
    raise AssemblyError("INVALID_DOCUMENT")


def local_path(root, source):
    """Resolve a canonical repository source without following links/reparse points."""
    if not isinstance(source, str) or not re.fullmatch(
            r"repo://[A-Za-z0-9_-][A-Za-z0-9_./-]*", source):
        raise AssemblyError("INVALID_SOURCE")
    relative = source[7:]
    parts = relative.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise AssemblyError("INVALID_SOURCE")
    root = Path(root).resolve(strict=True)
    current = root
    try:
        for part in parts:
            current = current / part
            stat = current.lstat()
            if current.is_symlink() or getattr(stat, "st_file_attributes", 0) & 0x400:
                raise AssemblyError("UNSAFE_SOURCE")
        current.resolve(strict=True).relative_to(root)
    except AssemblyError:
        raise
    except (OSError, ValueError) as exc:
        raise AssemblyError("INVALID_SOURCE") from exc
    return current


def source_digest(root, reference):
    """Raw file or sorted relative-path/NUL/content/NUL tree SHA256."""
    path = local_path(root, reference["source"])
    kind = reference["kind"]
    if kind == "file":
        paths = [path]
        if not path.is_file():
            raise AssemblyError("SOURCE_KIND")
    elif kind == "tree":
        if not path.is_dir():
            raise AssemblyError("SOURCE_KIND")
        paths = sorted(path.rglob("*"), key=lambda p: p.relative_to(path).as_posix())
        if len(paths) > 4096:
            raise AssemblyError("RESOURCE_LIMIT")
    else:
        raise AssemblyError("SOURCE_KIND")
    digest = hashlib.sha256()
    total = 0
    for item in paths:
        checked = local_path(root, "repo://" + item.relative_to(Path(root).resolve()).as_posix())
        if checked.is_dir():
            continue
        if not checked.is_file():
            raise AssemblyError("SOURCE_KIND")
        with checked.open("rb") as stream:
            raw = stream.read(LIMIT + 1)
        total += len(raw)
        if len(raw) > LIMIT or total > 16 * 1024 * 1024:
            raise AssemblyError("RESOURCE_LIMIT")
        if kind == "tree":
            digest.update(checked.relative_to(path).as_posix().encode("utf-8") + b"\0")
        digest.update(raw)
        if kind == "tree":
            digest.update(b"\0")
    return "sha256:" + digest.hexdigest()


def _validate(root, document, name):
    try:
        encoded = json.dumps(document, allow_nan=False).encode("utf-8")
    except (TypeError, ValueError, RecursionError) as exc:
        raise AssemblyError("INVALID_DOCUMENT") from exc
    if len(encoded) > LIMIT:
        raise AssemblyError("RESOURCE_LIMIT")
    schema = read_json(local_path(root, "repo://" + (SCHEMAS / name).as_posix()))
    Draft202012Validator.check_schema(schema)
    try:
        Draft202012Validator(schema).validate(document)
    except ValidationError:
        # Never echo validation errors containing caller-supplied metadata.
        raise AssemblyError("SCHEMA_MISMATCH") from None


def validate_assembly(root, document, *, compatibility=None):
    """Check declared bytes and exact compatibility; does not execute or authorize."""
    _validate(root, document, "observation-assembly.v0.1.schema.json")
    if set(document["hostBindings"]) != {
            "requestId", "instanceId", "processBootId", "runtimeLockDigest",
            "authorization", "storage"}:
        raise AssemblyError("MISSING_HOST_BINDING")
    references = []
    for field in ("components", "contracts", "requirements", "conformance",
                  "developmentSupport"):
        entries = document[field]
        expected_kind = "tree" if field == "components" else "file"
        if any(entry["kind"] != expected_kind for entry in entries):
            raise AssemblyError("SOURCE_KIND")
        if len({entry["id"] for entry in entries}) != len(entries):
            raise AssemblyError("DUPLICATE_REFERENCE")
        references.extend(entries)
    references.append(document["policyContract"])
    if document["policyContract"]["kind"] != "file":
        raise AssemblyError("SOURCE_KIND")
    for reference in references:
        if source_digest(root, reference) != reference["digest"]:
            raise AssemblyError("DIGEST_MISMATCH")
    if compatibility is not None and document["compatibility"] != compatibility:
        raise AssemblyError("INCOMPATIBLE_RUNTIME")
    return {"status": "DECLARATION_VALID", "referencesChecked": len(references)}


def validate_context(root, document):
    """Validate finite metadata only; the Host must separately authorize every read."""
    _validate(root, document, "financial-evidence-context.v0.1.schema.json")
    samples = document["samples"]
    if len({sample["sampleId"] for sample in samples}) != len(samples):
        raise AssemblyError("DUPLICATE_SAMPLE")
    if sum(len(sample["evidenceRefs"]) for sample in samples) > 256:
        raise AssemblyError("RESOURCE_LIMIT")
    for sample in samples:
        refs = sample["evidenceRefs"]
        if len({ref["id"] for ref in refs}) != len(refs):
            raise AssemblyError("DUPLICATE_REFERENCE")
    return {"status": "CONTEXT_STRUCTURE_VALID", "samplesChecked": len(samples)}
