"""Validate bounded report semantics and fail-closed negative fixtures."""
from copy import deepcopy
from pathlib import Path
import json
import pytest
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "contracts/json-schema/professional-report"

def validator():
    documents = [json.loads(p.read_text(encoding="utf-8")) for p in SCHEMAS.glob("*.json")]
    registry = Registry().with_resources((d["$id"], Resource.from_contents(d)) for d in documents)
    for document in documents:
        Draft202012Validator.check_schema(document)
    return Draft202012Validator(next(d for d in documents if d["$id"] == "urn:finance:report:result:v1"), registry=registry, format_checker=FormatChecker())

def test_original_and_negative_report_contract():
    cases = json.loads((ROOT / "conformance/professional-report-readonly/schema-cases.v1.json").read_text(encoding="utf-8"))
    validate = validator()
    validate.validate(cases["valid"])
    for case in cases["negativePatches"]:
        value = deepcopy(cases["valid"])
        value.update(case["patch"])
        assert list(validate.iter_errors(value)), case["reason"]

def test_no_unknown_or_external_file_fields():
    artifact = json.loads((SCHEMAS / "artifact.v1.schema.json").read_text(encoding="utf-8"))
    validate = Draft202012Validator(artifact)
    value = dict(id="a1", runId="run1", sourceResultId="s1", name="result.json", mediaType="application/json", bytes=1, sha256="sha256:"+"a"*64)
    validate.validate(value)
    for name in ["../secret", "http://evil/result", "a/b.png", "result.html"]:
        changed = {**value, "name": name}
        if name == "result.html": changed["mediaType"] = "text/html"
        assert list(validate.iter_errors(changed))
    assert list(validate.iter_errors({**value, "path": "/private/file"}))
