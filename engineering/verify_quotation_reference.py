"""Offline H1 component verification. This never qualifies a Host, SDK or public output."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "conformance/fixtures/quotation/v0.1"
MODULES = ("packages/java/quotation", "providers/quotation-reference")
SUITES = {
    "packages/java/quotation": {
        "io.github.bitlhk.finance.harness.quotation.QuoteFactsV01Test": {
            "rejectsDuplicateTrailingMissingAndCoercedFields", "valuesAreImmutableAndCoreNullPolicyIsUntouched"},
        "io.github.bitlhk.finance.harness.quotation.QuoteDisclosureV01Test": None,
    },
    "providers/quotation-reference": {
        "io.github.bitlhk.finance.harness.providers.quotation.QuotationReferenceConfigTest": {
            "freezesActualBytesAndDoesNotReloadChangedSource",
            "rejectsDigestSizeDuplicateUnknownAndAmbiguousRecords", "rejectsFileAndParentSymlinks",
            "actualCoreRegistryAcceptsIdentityAndRejectsMissingOrWrongLock"},
        "io.github.bitlhk.finance.harness.providers.quotation.QuotationReferenceProviderTest": {
            "returnsImmutableVersionedFactsWithNoBusinessWrite", "revocationBeforeAndAfterReadDoesNotReturnFacts",
            "refusesUntrustedSelectorsAndAllTaskSemantics", "expiredAndFutureQuotesNeverReturnOldPrice",
            "factoryIsLazyPreinstalledAndRejectsNetworkOrDependencies"},
    },
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def identity(root=ROOT):
    """Pin implementation, tests, source inputs and contracts; never generated target/cache."""
    paths = {root / "pom.xml"}
    for name in ("packages/java/core", *MODULES, "contracts/json-schema/quotation",
                 "conformance/fixtures/quotation", "capability-packs/quotation-consultation-readonly"):
        paths.update(p for p in (root / name).rglob("*")
                     if p.is_file() and "target" not in p.relative_to(root / name).parts)
    paths.update(root / p for p in (
        "catalog/finance-assets.v1.json", "observation-packs/readonly/quotation-consultation.json",
        "engineering/verify_quotation_reference.py", "engineering/test_quotation_reference.py",
        "engineering/test_quotation_contracts.py"))
    values = {p.relative_to(root).as_posix(): digest(p) for p in sorted(paths)}
    return {"files": values, "sha256": hashlib.sha256(
        json.dumps(values, sort_keys=True, separators=(",", ":")).encode()).hexdigest()}


def require_same_identity(before, after):
    if before != after:
        raise ValueError("SOURCE_OR_INPUT_CHANGED")


FROZEN = {
    "conformance/fixtures/quotation/v0.1/cases.v1.json": "d0e4d8ca39bf0da308735f01ea33acf5e0b5a5d5303d1f569ab746e71b52d828",
    "conformance/fixtures/quotation/v0.1/reference-config.v0.1.json": "281e86c2f0c3245062da73401c35f16a6ff235b0903d8f148cbeb9befe8ea462",
    "contracts/json-schema/quotation/quote-facts.v0.1.schema.json": "28091dc2e81357a9794c6d20c85cb1d5678796bdb559947a88b5f7dde9625c84",
    "contracts/json-schema/quotation/quotation-reference-config.v0.1.schema.json": "007b2f9838439e1776673202f2a8fcfa610e75e17fc817695c4481870f4d21ad",
}


def require_frozen(root=ROOT):
    if any(digest(root / path) != expected for path, expected in FROZEN.items()):
        raise ValueError("FROZEN_INPUT_MISMATCH")


def junit_results(root, manifest):
    """Fresh Maven execution is required by verify(); XML alone is not trusted attestation."""
    results = []
    for module, suites in SUITES.items():
        for classname, expected in suites.items():
            path = root / module / "target/surefire-reports" / ("TEST-" + classname + ".xml")
            suite = ET.parse(path).getroot()
            rows = suite.findall("testcase")
            names = [row.attrib["name"] for row in rows]
            if expected is None:
                expected = {"frozenIndependentCases()[" + str(i + 1) + "]"
                            for i in range(len(manifest["cases"]))}
            if (suite.tag != "testsuite" or suite.get("name") != classname
                    or len(names) != len(set(names)) or set(names) != expected
                    or int(suite.get("tests", "-1")) != len(rows)
                    or any(int(suite.get(k, "-1")) != 0 for k in ("failures", "errors", "skipped"))
                    or any(row.get("classname") != classname or list(row) for row in rows)):
                raise ValueError("INCOMPLETE_OR_FAILED_JAVA_SUITE")
            results.append({"class": classname, "tests": len(rows), "status": "PASS",
                            "reportSha256": digest(path)})
    return results


def verify(report):
    report = Path(report).resolve()
    report.parent.mkdir(parents=True, exist_ok=True)
    evidence = {"schemaVersion": "quotation-component-evidence.v0.1", "stage": "H1",
                "status": "FAIL", "hostSdkQualification": "NOT_RUN", "publicDisclosure": "NOT_RUN",
                "builderQualification": "NOT_RUN", "businessNetwork": "NOT_RUN",
                "fixtureClock": "2026-10-19T12:00:00Z"}
    try:
        from engineering.test_quotation_contracts import load, validators
        from engineering.verify_component_assembly import verify as verify_assembly
        before = identity()
        require_frozen()
        manifest = load(FIXTURES / "cases.v1.json")
        if (manifest.get("frozenBeforeImplementation") is not True or len(manifest["cases"]) < 25
                or len({r["id"] for r in manifest["cases"]}) != len(manifest["cases"])):
            raise ValueError("INVALID_CASE_MANIFEST")
        validator = validators()["quote-facts.v0.1.schema.json"]
        for row in manifest["cases"]:
            if validator.is_valid(row["payload"]) != row["schemaValid"]:
                raise ValueError("SCHEMA_ORACLE_MISMATCH")
        validators()["quotation-reference-config.v0.1.schema.json"].validate(
            load(FIXTURES / "reference-config.v0.1.json"))
        evidence["structure"] = {"status": "PASS", "cases": len(manifest["cases"])}
        evidence["assembly"] = verify_assembly(ROOT)
        mvn = shutil.which("mvn")
        cache = os.environ.get("H1_MAVEN_REPO")
        if not mvn or not cache or not Path(cache).is_dir():
            raise RuntimeError("OFFLINE_TOOLCHAIN_NOT_CONFIGURED")
        # Remove only the known reports. Stale green XML cannot satisfy this invocation.
        for module, suites in SUITES.items():
            for classname in suites:
                (ROOT / module / "target/surefire-reports" / ("TEST-" + classname + ".xml")).unlink(missing_ok=True)
        with tempfile.TemporaryDirectory(prefix="h1-", dir=report.parent) as private:
            settings = Path(private) / "settings.xml"
            settings.write_text('<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">'
                                '<offline>true</offline></settings>', encoding="utf-8")
            cmd = [mvn, "-o", "-B", "-ntp", "-s", str(settings), "-gs", str(settings),
                   "-Dmaven.repo.local=" + str(Path(cache).resolve()), "-DargLine=-Djava.io.tmpdir=" + private,
                   "-pl", ",".join(MODULES), "-am", "package"]
            result = subprocess.run(cmd, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    timeout=300, check=False)
            log = report.with_suffix(".maven.log")
            log.write_bytes(result.stdout)
            evidence["maven"] = {"exitCode": result.returncode, "logSha256": digest(log)}
            if result.returncode:
                raise ValueError("OFFLINE_REACTOR_FAILED")
        evidence["java"] = junit_results(ROOT, manifest)
        require_same_identity(before, identity())
        evidence["identity"] = before
        evidence["cases"] = [{"id": row["id"], "schemaValid": row["schemaValid"],
                              "disclosureReason": row["disclosureReason"], "status": "PASS"}
                             for row in manifest["cases"]]
        evidence["artifacts"] = {p.relative_to(ROOT).as_posix(): digest(p)
                                 for module in MODULES for p in (ROOT / module / "target").glob("*.jar")}
        if len(evidence["artifacts"]) != 2:
            raise ValueError("MISSING_COMPONENT_JARS")
        evidence["status"] = "PASS"
    except Exception as error:
        # Diagnostic class/code only: no config bodies, credentials or arbitrary exception detail.
        evidence["error"] = str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__
    report.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    result = verify(args.report)
    print(json.dumps({k: result[k] for k in ("stage", "status", "hostSdkQualification", "publicDisclosure")}))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    # Direct-file and module invocation resolve the same repository-local implementation.
    sys.path.insert(0, str(ROOT))
    raise SystemExit(main())
