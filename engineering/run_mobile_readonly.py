"""Cross-repository controlled host-adapter checks; not a live banking acceptance."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
from uuid import uuid4

from run_onboarding import ROOT, git, preflight, run_steps

UPSTREAM = "f89c9fb2215dc4ebca169f676f96fac2d18b0e8c"
MOBILE_MODULE = "framework/finance-harness-openjiuwen"
SCENARIOS = {
    "NORMAL", "ZERO", "MISSING_RISK", "UNAVAILABLE_RISK", "MISSING_CURRENCY", "PARTIAL_COVERAGE",
    "BAD_RESPONSE", "WRONG_BANK_IDENTITY", "NO_GRANT", "WRONG_PRINCIPAL", "WRONG_SUBJECT",
    "WRONG_SESSION", "EXPIRED", "INSUFFICIENT_OUTER_SCOPE", "INSUFFICIENT_SOURCE_SCOPE",
    "MODEL_CUSTOMER", "MODEL_PROFILE", "REVOKED",
}
DENIED = SCENARIOS - {"NORMAL", "ZERO", "MISSING_RISK", "UNAVAILABLE_RISK", "MISSING_CURRENCY", "PARTIAL_COVERAGE"}


def validate_mobile_records(records):
    if set(records) != SCENARIOS:
        raise ValueError("Missing or unexpected mobile scenarios")
    for name, record in records.items():
        if record["scenario"] != name or record["path"] != "MOBILE_ADAPTER_HARNESS_CONTROLLED_HTTP":
            raise ValueError("Wrong evidence identity: " + name)
        if record["realAuthorization"] != "NOT_RUN" or record["agentConversation"] != "NOT_RUN":
            raise ValueError("Controlled evidence cannot certify live or conversation acceptance")
        if record["sourceWriteAttempts"] != 0:
            raise ValueError("Unexpected write attempt")
        result = record["result"]
        if result["status"] != ("REJECTED" if name in DENIED else "COMPLETED"):
            raise ValueError("Wrong result: " + name)
        if not result["executionEvidence"]:
            raise ValueError("Missing execution evidence")
        if name in DENIED:
            if "facts" in result["data"]:
                raise ValueError("Facts exposed on denied request")
            if name not in {"BAD_RESPONSE", "REVOKED"} and record["sourceReads"] != 0:
                raise ValueError("Denied request reached business source")
            if name == "REVOKED" and record["sourceReads"] != 2:
                raise ValueError("Revoked request read again")
        else:
            if record["sourceReads"] != 2 or result["data"]["description"]["suitability"] != "NOT_EVALUATED":
                raise ValueError("Incomplete read or unexpected suitability result")


def main():
    import os
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mobile-root", type=Path, required=True)
    args = parser.parse_args()
    mobile = args.mobile_root.resolve()
    env = preflight(ROOT, os.environ)
    preflight(mobile, env)
    if not (mobile / MOBILE_MODULE / "src/main/java/com/openjiuwen/intelligence/finance/harness/FinanceHarnessReadOnlySupport.java").is_file():
        raise ValueError("Use the readonly integration branch, not the old demo branch")
    if git(mobile, "merge-base", UPSTREAM, "HEAD") != UPSTREAM:
        raise ValueError("Selected upstream main is not included")
    harness_commit = git(ROOT, "rev-parse", "HEAD")
    mobile_commit = git(mobile, "rev-parse", "HEAD")
    mobile_tree = git(mobile, "rev-parse", "HEAD^{tree}")
    output = ROOT / "target" / "mobile-readonly" / uuid4().hex
    output.mkdir(parents=True, mode=0o700)
    commands = [
        ("java-version", ["java", "-version"]),
        ("maven-version", ["mvn", "-version"]),
        ("harness-contracts", [sys.executable, "engineering/verify_contracts.py"]),
        ("harness-install-and-tests", ["mvn", "-B", "-ntp", "install"]),
        ("mobile-readonly-regressions", ["mvn", "-B", "-ntp", "-f", str(mobile / "pom.xml"),
                                        "-pl", MOBILE_MODULE, "-am", "clean", "test"]),
    ]
    code = run_steps(ROOT, output, commands, env)
    path = output / "report.json"
    report = json.loads(path.read_text(encoding="utf-8"))
    report.update(evidenceKind="CROSS_REPO_CONTROLLED_HOST_ADAPTER",
                  mobileCommit=mobile_commit, mobileUpstreamCommit=UPSTREAM,
                  mobileTree=mobile_tree,
                  fullMobileConversation="NOT_RUN", bankSimulator="NOT_RUN",
                  builderManualReproduction="PENDING_REVIEW", builderProductIntegration="NOT_RUN", eaConsumer="NOT_RUN")
    try:
        if code == 0:
            source = mobile / MOBILE_MODULE / "target/readonly-evidence"
            records = {file.stem: json.loads(file.read_text(encoding="utf-8")) for file in source.glob("*.json")}
            validate_mobile_records(records)
            shutil.copytree(source, output / "mobile-evidence")
            report["mobileEvidence"] = {file.name: hashlib.sha256(file.read_bytes()).hexdigest()
                                        for file in sorted((output / "mobile-evidence").glob("*.json"))}
            report["artifactCollection"] = "PASS"
            report["testedArtifacts"] = {
                str(file.relative_to(ROOT)): hashlib.sha256(file.read_bytes()).hexdigest()
                for directory in ("packages/java/core", "packages/java/wealth", "integrations/openjiuwen-java", "providers/non-ai-banking")
                for file in sorted((ROOT / directory / "target").glob("*.jar"))
            }
            if len(report["testedArtifacts"]) != 4:
                raise ValueError("Expected four local Harness artifacts")
            built = {Path(name).name: digest for name, digest in report["testedArtifacts"].items()}
            if any(record["loadedArtifacts"] != built for record in records.values()):
                raise ValueError("Loaded Harness JARs differ from this checkout's build")
            if (git(ROOT, "status", "--porcelain") or git(mobile, "status", "--porcelain")
                    or git(ROOT, "rev-parse", "HEAD") != harness_commit
                    or git(mobile, "rev-parse", "HEAD") != mobile_commit):
                raise ValueError("Source changed during reproduction")
    except (OSError, ValueError, KeyError) as failure:
        report.update(automatedStatus="FAIL", artifactCollection="FAIL", failureType=type(failure).__name__)
        code = 1
    path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("Report: " + str(path))
    print("Controlled adapter checks only; no human, Builder product, live authorization or full UI acceptance.")
    return code


if __name__ == "__main__":
    sys.exit(main())
