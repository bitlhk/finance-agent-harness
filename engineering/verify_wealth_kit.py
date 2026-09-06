"""Validate the five controlled CLI reports against artifacts and shared business contracts."""
import hashlib
import json
import re
import argparse
from datetime import datetime
from pathlib import Path

from jsonschema import Draft202012Validator
from verify_contracts import FORMATS, ROOT


def read(path):
    return json.loads(path.read_text())


def instant_key(value):
    """Compare Java instants losslessly on Python 3.10+, including nanoseconds."""
    if not isinstance(value, str):
        raise ValueError("Expected an ISO timestamp string")
    match = re.fullmatch(
        r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?(Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)", value
    )
    if not match:
        raise ValueError("Expected ISO timestamp with timezone and at most nine fractional digits")
    seconds, fraction, offset = match.groups()
    whole = datetime.fromisoformat(seconds + ("+00:00" if offset == "Z" else offset))
    return whole, int((fraction or "").ljust(9, "0"))


DEFAULT_CASES = ["BANK_HTTP-NORMAL", "SNAPSHOT-NORMAL", "EA_HTTP-NORMAL", "BANK_HTTP-DENIED", "EA_HTTP-DENIED"]
READONLY_CASES = ["BANK_HTTP-NORMAL", "SNAPSHOT-NORMAL", "BANK_HTTP-DENIED", "BANK_HTTP-EXPIRED_GRANT",
                 "BANK_HTTP-WRONG_SUBJECT", "BANK_HTTP-MODEL_SELECTOR", "BANK_HTTP-MISSING_RISK",
                 "BANK_HTTP-MISSING_METADATA", "BANK_HTTP-ZERO", "BANK_HTTP-BAD_RESPONSE"]


def verify(directory, root=ROOT):
    report = read(directory / "report.json")
    for name, field in [("solution-lock.json", "lockSha256"), ("controlled-model-trace.json", "traceSha256")]:
        assert hashlib.sha256((directory / name).read_bytes()).hexdigest() == report[field], name
    assert report["evidenceKind"] == "CONTROLLED_HTTP_AND_SCRIPTED_MODEL"
    assert report["modelQuality"] == "NOT_EVALUATED_SCRIPTED_MODEL"
    assert report["liveAuthorization"] == "NOT_RUN"
    assert report["realProviderSubstitution"] == report["independentHumanOnboarding"] == "PENDING"
    assert report["buildInputSha256"] == hashlib.sha256((root / "examples/solutions/wealth-facts-kit/solution.yaml").read_bytes()).hexdigest()
    config = report["configuration"]
    assert directory.name == config["binding"] + "-" + config["scenario"], "Case identity mismatch"
    assert config["mode"] == "CONTROLLED"
    assert config["profile"] == ("EMPLOYEE" if config["binding"] == "EA_HTTP" else "CUSTOMER")
    assert report["sourceSemantics"]["basisReference"] == "fixture:dictionary-v1"
    assert report["sourceProvider"]["capabilities"], "Source capability identity must be observed"
    assert report["modelRequestCount"] == 2 and report["modelCompletionCount"] == 1
    assert report["sourceWriteCount"] == 0 and report["privateDataObservedByModel"] is False
    assert report["sourceWriteCountSemantics"] == "POSSIBLE_MUTATION_ATTEMPTS_INCLUDING_REJECTED"
    assert report["businessAsOf"] == "2026-09-05T00:00:00Z"
    attempts = report["sourceCallAttempts"]
    assert all(a["decision"] == "ACCEPTED" and not a["possibleMutation"] for a in attempts)
    assert len([a for a in attempts if a["reason"].endswith("_READ")]) == report["sourceReadCount"]
    events = report["nativeEvents"]
    assert [e["hook"] for e in events] == ["AFTER_TOOL_CALL", "AFTER_INVOKE"]
    assert all(e["agentId"] == report["agentId"] for e in events)
    trace = read(directory / "controlled-model-trace.json")
    assert len(trace) == report["modelRequestCount"] and "PRIVATE_" not in json.dumps(trace)
    tools = report["toolResults"]
    assert len(tools) == 1 and tools[0]["evidence"], "Expected native tool result and bridge evidence"
    tool = tools[0]
    scenario = config["scenario"]
    denied = {"DENIED", "EXPIRED_GRANT", "WRONG_SUBJECT", "MODEL_SELECTOR", "BAD_RESPONSE"}
    if scenario in denied:
        assert tool["status"] == "REJECTED"
        if scenario != "BAD_RESPONSE" and not (scenario == "WRONG_SUBJECT" and config["binding"] == "EA_HTTP"):
            assert report["sourceReadCount"] == 0
        assert not tool["data"].get("result") and "facts" not in tool["data"]
    else:
        assert tool["status"] == "COMPLETED"
        schema = read(root / "contracts/json-schema/wealth/wealth-facts.v0.1.schema.json")
        Draft202012Validator(schema, format_checker=FORMATS).validate(tool["data"]["facts"])
        assert tool["data"]["description"]["suitability"] == "NOT_EVALUATED"
        partial = scenario in {"MISSING_RISK", "MISSING_METADATA"}
        assert tool["data"]["description"]["taskReadiness"] == ("PARTIAL_FOR_DESCRIPTION" if partial else "COMPLETE_FOR_DESCRIPTION")
        assert instant_key(tool["data"]["facts"]["source"]["observedAt"]) >= instant_key(report["startedAt"])
        facts = tool["data"]["facts"]
        if scenario == "MISSING_METADATA" and config["binding"] == "EA_HTTP":
            assert not facts["assets"]["amounts"]
        else:
            assert facts["assets"]["amounts"][0]["money"]["value"] == ("0" if scenario == "ZERO" else "1000000.12345678")
            assert facts["assets"]["amounts"][0]["money"]["currency"] == "CNY"
        if scenario == "MISSING_METADATA":
            codes = {gap["code"] for gap in facts["gaps"]}
            assert {"ASSET_SCOPE_NOT_ESTABLISHED", "RISK_SCHEME_NOT_ESTABLISHED"} <= codes
            assert not facts["risk"].get("scheme")
            assert all(a["scope"] == "SOURCE_REPORTED_UNSPECIFIED" for a in facts["assets"]["amounts"])
        if scenario == "MISSING_RISK":
            assert facts["risk"]["availability"] == "MISSING" and facts["gaps"]
    lock = read(directory / "solution-lock.json")
    runtime = lock["runtime"].get("resolvedArtifacts", lock["runtime"])
    for kind, field in [("RUNTIME_TEMPLATE", "templateRef"), ("RUNTIME_INTEGRATION", "integrationRef")]:
        observed = [a for a in lock["assets"] if a["kind"] == kind]
        assert len(observed) == 1 and observed[0]["id"] == runtime[field]
        assert observed[0]["digest"].startswith("sha256:") and len(observed[0]["digest"]) == 71
    assert "WealthFactsProvider" in json.dumps(lock) and "BoundFinanceProvider" in json.dumps(lock)
    print(f"{directory.name}: {tool['status']}, sourceReads={report['sourceReadCount']}, "
          f"sourceWrites=0, elapsedMillis={report['elapsedMillis']}")
    return report


def verify_suite(root, directory, names):
    assert names in (DEFAULT_CASES, READONLY_CASES), "Use a frozen, non-empty acceptance matrix"
    reports = {name: verify(directory / name, root) for name in names}
    bank, snapshot = (reports[name] for name in names[:2])
    assert {k: v for k, v in bank["configuration"].items() if k != "binding"} == {
        k: v for k, v in snapshot["configuration"].items() if k != "binding"}
    for field in ["assets", "risk"]:
        assert bank["toolResults"][0]["data"]["facts"][field] == snapshot["toolResults"][0]["data"]["facts"][field]
    assert bank["toolResults"][0]["data"]["description"] == snapshot["toolResults"][0]["data"]["description"]
    print("PASS: controlled CLI path, shared semantics and denial checks; live/human acceptance remains pending")
    return reports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT, help="Standalone candidate asset root or Harness checkout")
    parser.add_argument("--runs", type=Path)
    parser.add_argument("--matrix", choices=["kit", "customer-readonly"], default="kit")
    args = parser.parse_args()
    verify_suite(args.root, args.runs or args.root / "examples/solutions/wealth-facts-kit/target/runs",
                 DEFAULT_CASES if args.matrix == "kit" else READONLY_CASES)


if __name__ == "__main__":
    main()
