"""Fixed offline checks; metadata commands are displayed, never executed."""
import copy
import json
from pathlib import Path

from jsonschema import Draft202012Validator

from observation_assembly import (
    AssemblyError, local_path, read_json, source_digest, validate_assembly,
)

ROOT = Path(__file__).resolve().parents[1]


def verify(root=ROOT):
    root = Path(root).resolve()
    manifest = read_json(local_path(root, "repo://observation-packs/native-analysis/assembly.v0.1.json"))
    expected_compatibility = {
        "runtimeId": "openjiuwen-java", "sdkVersion": "0.1.16",
        "integrationId": "finance-harness-openjiuwen-java-otel",
        "ruleVersion": "finance-single-run.v1",
    }
    result = validate_assembly(root, manifest, compatibility=expected_compatibility)
    cases = read_json(local_path(root, "repo://contracts/fixtures/observation/assembly-cases.v0.1.json"))
    assembly_count = 0
    for case in cases["cases"]:
        probe = copy.deepcopy(manifest)
        if case.get("operation") != "none":
            target = probe
            for part in case["path"][:-1]:
                target = target[part]
            target[case["path"][-1]] = case["value"]
        try:
            status = validate_assembly(root, probe, compatibility=expected_compatibility)["status"]
        except AssemblyError as exc:
            status = str(exc)
        if status != case["expected"]:
            raise AssemblyError("FIXTURE_MISMATCH")
        assembly_count += 1
    counts = {}
    # These three inputs are structurally valid but rejected by the Java semantic gate.
    semantic_only = {"foreign-evidence", "duplicate-step", "duplicate-evidence"}
    for name, schema_name, key in (
            ("native", "native-observation", "value"),
            ("analysis", "analysis-input", "input")):
        filename = name + "-cases.v1.json"
        fixtures = read_json(local_path(root, "repo://contracts/fixtures/observation/" + filename))
        schema = read_json(local_path(
            root, "repo://contracts/json-schema/observation/" + schema_name + ".v1.schema.json"))
        validator = Draft202012Validator(schema)
        for case in fixtures["cases"]:
            structural = case["valid"] or (name == "analysis" and case["name"] in semantic_only)
            if validator.is_valid(case[key]) != structural:
                raise AssemblyError("FIXTURE_MISMATCH")
        counts[name] = len(fixtures["cases"])
    support_schema = read_json(local_path(
        root, "repo://contracts/json-schema/catalog/finance-component-development-support.v0.1.schema.json"))
    index = read_json(local_path(root, "repo://metadata/development-support/index.v0.1.json"))
    for reference in manifest["developmentSupport"]:
        support = read_json(local_path(root, reference["source"]))
        Draft202012Validator(support_schema).validate(support)
        subject = support["subject"]
        component = next((c for c in manifest["components"] if c["id"] == subject["id"]), None)
        if not component or any(subject[k] != component[k] for k in ("source", "digest")):
            raise AssemblyError("SUPPORT_SUBJECT_MISMATCH")
        if not any(entry == {k: reference[k] for k in ("source", "digest")}
                   for entry in index["declarations"]):
            raise AssemblyError("SUPPORT_NOT_INDEXED")
        for case in support["caseRefs"]:
            if source_digest(root, dict(case, kind="file")) != case["digest"]:
                raise AssemblyError("DIGEST_MISMATCH")
    return dict(result, assemblyCases=assembly_count, structuralCases=counts,
                javaSemanticTests="SEPARATE_REQUIRED_CHECK",
                executionQualification="NOT_RUN", businessAuthorization="NOT_CHECKED",
                metadataCommandsExecuted=0)


if __name__ == "__main__":
    try:
        print(json.dumps(verify(), indent=2))
    except AssemblyError as exc:
        print(json.dumps({"status": "FAILED", "code": str(exc)}))
        raise SystemExit(1)
