"""Offline JSON Schema and fixture gate; never resolves references over the network."""

import copy
import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]


def main():
    schemas = {}
    registry = Registry()
    for path in sorted((ROOT / "contracts/json-schema").rglob("*.schema.json")):
        schema = json.loads(path.read_text())
        Draft202012Validator.check_schema(schema)
        schemas[path.name.removesuffix(".schema.json")] = schema
        registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
    assert len(schemas) == 8, "Schema inventory changed; review the gate"

    def validator(name):
        return Draft202012Validator(schemas[name], registry=registry, format_checker=FormatChecker())

    count = 0
    for path in sorted((ROOT / "conformance/fixtures/v1").rglob("*.json")):
        data = json.loads(path.read_text())
        name = path.name.split(".")[0] + ".v1"
        errors = list(validator(name).iter_errors(data))
        assert bool(errors) == (path.parent.name == "invalid"), f"Unexpected fixture outcome: {path} {errors}"
        count += 1

    for directory, schema_name in [("capability-packs", "capability-pack.v1"), ("domain-packs", "domain-pack.v1")]:
        for path in sorted((ROOT / directory).rglob("pack.yaml")):
            validator(schema_name).validate(yaml.safe_load(path.read_text()))
            count += 1
    for path in sorted((ROOT / "examples/solutions").rglob("*")):
        if path.name in {"solution.yaml", "solution-without-domain.yaml", "solution-build-input.v1.json"}:
            validator("solution-build-input.v1").validate(yaml.safe_load(path.read_text()))
            count += 1

    result_schema = validator("capability-result.v1")
    original = json.loads((ROOT / "conformance/fixtures/v1/valid/capability-result.need-confirmation.json").read_text())
    for status in ["NEED_CONFIRMATION", "PROCESSING", "UNKNOWN"]:
        probe = copy.deepcopy(original)
        probe["status"] = status
        probe.pop("task")
        assert not result_schema.is_valid(probe), f"Missing task accepted: {status}"
        probe["task"] = None
        assert not result_schema.is_valid(probe), f"Null task accepted: {status}"
        count += 2
    probe = copy.deepcopy(original)
    probe["task"]["confirmationRequired"] = False
    assert not result_schema.is_valid(probe), "Missing confirmation flag accepted"
    print(f"PASS: {len(schemas)} schemas, {count + 1} fixtures/assets/state probes")


if __name__ == "__main__":
    main()
