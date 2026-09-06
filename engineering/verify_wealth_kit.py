"""Validate the five controlled CLI reports against artifacts and shared business contracts."""
import hashlib
import json
from datetime import datetime

from jsonschema import Draft202012Validator
from verify_contracts import FORMATS, ROOT


def read(path):
    return json.loads(path.read_text())


def verify(directory):
    report = read(directory / "report.json")
    for name, field in [("solution-lock.json", "lockSha256"), ("controlled-model-trace.json", "traceSha256")]:
        assert hashlib.sha256((directory / name).read_bytes()).hexdigest() == report[field], name
    assert report["evidenceKind"] == "CONTROLLED_HTTP_AND_SCRIPTED_MODEL"
    assert report["modelQuality"] == "NOT_EVALUATED_SCRIPTED_MODEL"
    assert report["liveAuthorization"] == "NOT_RUN"
    assert report["realProviderSubstitution"] == report["independentHumanOnboarding"] == "PENDING"
    assert report["buildInputSha256"] == hashlib.sha256((ROOT / "examples/solutions/wealth-facts-kit/solution.yaml").read_bytes()).hexdigest()
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
    if report["configuration"]["scenario"] == "DENIED":
        assert tool["status"] != "COMPLETED" and report["sourceReadCount"] == 0
        assert not tool["data"].get("result") and "facts" not in tool["data"]
    else:
        assert tool["status"] == "COMPLETED"
        schema = read(ROOT / "contracts/json-schema/wealth/wealth-facts.v0.1.schema.json")
        Draft202012Validator(schema, format_checker=FORMATS).validate(tool["data"]["facts"])
        assert tool["data"]["description"]["suitability"] == "NOT_EVALUATED"
        assert tool["data"]["description"]["taskReadiness"] == "COMPLETE_FOR_DESCRIPTION"
        assert datetime.fromisoformat(tool["data"]["facts"]["source"]["observedAt"]) >= datetime.fromisoformat(report["startedAt"])
        assert tool["data"]["facts"]["assets"]["amounts"][0]["money"]["value"] == "1000000.12345678"
    lock = read(directory / "solution-lock.json")
    for kind, field in [("RUNTIME_TEMPLATE", "templateRef"), ("RUNTIME_INTEGRATION", "integrationRef")]:
        observed = [a for a in lock["assets"] if a["kind"] == kind]
        assert len(observed) == 1 and observed[0]["id"] == lock["runtime"][field]
        assert observed[0]["digest"].startswith("sha256:") and len(observed[0]["digest"]) == 71
    assert "WealthFactsProvider" in json.dumps(lock) and "BoundFinanceProvider" in json.dumps(lock)
    print(f"{directory.name}: {tool['status']}, sourceReads={report['sourceReadCount']}, "
          f"sourceWrites=0, elapsedMillis={report['elapsedMillis']}")
    return report


def main():
    directory = ROOT / "examples/solutions/wealth-facts-kit/target/runs"
    names = ["BANK_HTTP-NORMAL", "SNAPSHOT-NORMAL", "EA_HTTP-NORMAL", "BANK_HTTP-DENIED", "EA_HTTP-DENIED"]
    reports = {name: verify(directory / name) for name in names}
    bank, snapshot = (reports[name] for name in names[:2])
    assert {k: v for k, v in bank["configuration"].items() if k != "binding"} == {
        k: v for k, v in snapshot["configuration"].items() if k != "binding"}
    for field in ["assets", "risk"]:
        assert bank["toolResults"][0]["data"]["facts"][field] == snapshot["toolResults"][0]["data"]["facts"][field]
    assert bank["toolResults"][0]["data"]["description"] == snapshot["toolResults"][0]["data"]["description"]
    print("PASS: controlled CLI path, shared semantics and denial checks; live/human acceptance remains pending")


if __name__ == "__main__":
    main()
