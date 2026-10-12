"""Generate interface samples only, never verification evidence or connection discovery."""
import json
from pathlib import Path
import tempfile

from finance_solution_builder.service import digest, write_json
from finance_solution_builder.workbench import Jobs
from finance_solution_builder.workbench_api import WorkbenchApi
from finance_solution_builder.workbench_contracts import Selection, catalog, connection_status, default_selection


def main():
    destination = Path(__file__).resolve().parents[1] / "examples/workbench-api-v02"
    destination.mkdir(exist_ok=True)
    samples = {"catalog": catalog(), "selection.schema": Selection.model_json_schema(),
               "selection": default_selection(["wealth.facts.describe", "account.balance.query"]),
               "connections-missing": connection_status({})}
    with tempfile.TemporaryDirectory() as directory:
        jobs = Jobs(Path(directory), Path(directory))
        try:
            key = "agent-interface-example"
            jobs.jobs[key] = {"id": key, "owner": "example-owner", "status": "BUILT",
                              "capabilities": samples["selection"]["capabilityIds"], "selection": samples["selection"],
                              "createdAt": 1788692400}
            candidate = jobs.path(key) / "candidate"; candidate.mkdir(parents=True)
            write_json(candidate / "service-lock.json", {"buildId": key, "verification": "COMPILED", "files": {}})
            write_json(candidate.parent / "build-report.json", {"status": "BUILT", "lockSha256": digest(candidate / "service-lock.json"),
                       "stages": [{"name": name, "status": "PASS", "elapsedMillis": 100} for name in
                                  ("harness_contracts", "harness_tests_install", "candidate_package")]})
            api = WorkbenchApi(jobs)
            samples["build-succeeded"] = api.inspect(key, "example-owner")
            jobs.jobs[key].update(status="FAILED", error="INSTANCE_START_FAILED")
            samples["instance-failed-build-preserved"] = api.inspect(key, "example-owner")
            samples["start-request"] = {"artifactId": samples["build-succeeded"]["artifact"]["artifactId"]}
        finally: jobs.close()
    samples["chat-request"] = {"instanceId": "example-instance", "sessionId": "example-session", "query": "Describe my known assets"}
    samples["authorization-rejected"] = {"schemaVersion": "finance-workbench-api.v0.2", "buildId": "agent-interface-example",
        "instanceId": "example-instance", "sessionId": "example-session", "runId": "run-example",
        "error": {"code": "AUTHORIZATION_REJECTED", "retryable": False}, "clearConversation": True}
    for name, sample in samples.items():
        (destination / (name + ".json")).write_text(json.dumps(sample, indent=2) + "\n")


if __name__ == "__main__": main()
