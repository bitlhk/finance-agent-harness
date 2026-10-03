"""Package native method-only test evidence; the Host still verifies exact loaded artifacts.

This is an operator test utility, not evidence accepted from Skill uploads.
Use a fresh evidence directory from MethodOnlyNativeTest, then verify in a fresh JVM.
"""
import argparse
import hashlib
import json
from pathlib import Path

CHECKS = frozenset({"method-native", "method-isolation", "method-revocation", "method-unknown-tool",
                    "method-cancel", "method-model-error", "method-parameters", "method-admission",
                    "method-qualification", "method-observation", "method-sink-degradation", "method-case-workspace"})


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n")


def package(evidence, output):
    records = []
    for path in sorted(evidence.glob("*.json")):
        if path.is_symlink() or path.stat().st_size > 16_777_216:
            raise ValueError("EVIDENCE_FILE_INVALID")
        record = json.loads(path.read_text())
        if not record.get("test") or record.get("status") != "PASS":
            raise ValueError("NATIVE_TEST_NOT_PASSED")
        records.append((path, record))
    if not records:
        raise ValueError("NATIVE_EVIDENCE_MISSING")
    first = records[0][1]
    identity_fields = ("runtimeArtifacts", "collectorDeclaration", "collectorIdentity", "qualificationIdentity")
    if any(any(row.get(key) != first.get(key) for key in identity_fields) for _, row in records):
        raise ValueError("MIXED_NATIVE_ARTIFACTS")
    if any(item["role"].startswith("PROVIDER:") for item in first["runtimeArtifacts"]):
        raise ValueError("METHOD_PROOF_HAS_PROVIDER")
    if CHECKS - {tag for _, row in records for tag in row["checkIds"]}:
        raise ValueError("NATIVE_CHECKS_MISSING")
    output.mkdir(parents=True, exist_ok=False)
    raw = output / "raw"
    raw.mkdir()
    checks = []
    for path, _ in records:
        (raw / path.name).write_bytes(path.read_bytes())
    for check in sorted(CHECKS):
        refs = [{"file": "raw/" + path.name, "sha256": digest(raw / path.name)}
                for path, row in records if check in row["checkIds"]]
        checks.append({"id": check, "passed": True, "evidence": refs})
    proof = output / "proof.json"
    write(proof, {"schemaVersion": "agentscope-method-qualification-evidence.v0.1", "family": "agentscope",
                  "sdkVersion": "2.0.3", "status": "PASS", "collectorDeclaration": first["collectorDeclaration"],
                  "artifacts": first["runtimeArtifacts"], "checks": checks})
    declaration = first["collectorDeclaration"]
    report = output / "qualification.json"
    write(report, {"schemaVersion": "finance-observation-qualification.v0.1", "collectors": [{
        "collectorId": declaration["collectorId"], "identity": first["qualificationIdentity"],
        "semantics": sorted(item["semanticId"] for item in declaration["capabilities"]), "passed": True,
        "evidence": [{"file": "proof.json", "sha256": digest(proof)}]}]})
    return {"qualificationFile": str(report), "qualificationDigest": "sha256:" + digest(report),
            "checks": len(checks), "nativeRecords": len(records), "freshJvmAdmission": "NOT_RUN",
            "generatedHostQualification": "NOT_RUN", "businessQuality": "NOT_EVALUATED"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(package(args.evidence, args.output), indent=2))
