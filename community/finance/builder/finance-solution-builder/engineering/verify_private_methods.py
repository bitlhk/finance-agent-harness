"""Opt-in offline acceptance: real generated Java Host, synthetic bank and explicit test model."""
import argparse
import json
from pathlib import Path
import time
import uuid

from finance_solution_builder.method_assets import write_private, runtime_reference
from finance_solution_builder.method_trials import reusable_seed, fixture_module, probe
from finance_solution_builder.service import solution_for
from finance_solution_builder.workbench import Jobs
from finance_solution_builder.workbench_api import WorkbenchApi


def verify(project, harness, data):
    data.mkdir(parents=True, exist_ok=True, mode=0o700)
    if any(data.iterdir()): raise ValueError("FRESH_ACCEPTANCE_DIRECTORY_REQUIRED")
    jobs = Jobs(project, harness, data_dir=data)
    api = WorkbenchApi(jobs)
    try:
        _, asset = api.post("/api/v1/assets", {"data": {
            "name": "Synthetic wealth method", "content": "# SOURCE-METHOD-V1\nCheck references/coverage.md before explaining facts.",
            "resources": {"references/coverage.md": "SOURCE-REFERENCE-V1: never treat missing assets as zero."},
            "requiredResources": ["references/coverage.md"], "capabilityIds": ["wealth.facts.describe"],
            "cases": [{"name": "wealth query", "query": "wealth-probe: describe my wealth facts",
                       "expectedTerms": ["SOURCE-METHOD-V1", "SOURCE-REFERENCE-V1"]}]}}, "acceptance-owner")
        path = "/api/v1/assets/" + asset["assetId"]
        assert api.post(path + "/validate", {"revision": 1}, "acceptance-owner")[1]["status"] == "VALID"
        assert api.post(path + "/trial", {"revision": 1}, "acceptance-owner")[0] == 202
        deadline = time.monotonic() + 1800
        while True:
            trial_job = api.get(path + "/trial", "acceptance-owner")[1]
            if trial_job["status"] not in {"QUEUED", "RUNNING"}: break
            if time.monotonic() > deadline: raise RuntimeError("TRIAL_TIMEOUT")
            time.sleep(.5)
        if trial_job["status"] != "COMPLETED": raise RuntimeError(trial_job.get("error", "TRIAL_FAILED"))
        trial = trial_job["trial"]
        assert trial["report"]["runtimeVerified"] and trial["report"]["professionalQuality"] == "NOT_EVALUATED"
        _, released = api.post(path + "/publish", {"revision": 1, "version": "1.0.0", "trialId": trial["trialId"]}, "acceptance-owner")
        ref = {k: released[k] for k in ("assetId", "version", "releaseDigest")}
        frozen = jobs.method_assets.release("acceptance-owner", ref)
        # Applying a release through the direct Builder API uses the same immutable snapshot as Workbench /apply.
        source = data / "released-input.json"
        write_private(source, solution_for(["wealth.facts.describe"], "agent-" + uuid.uuid4().hex[:16], runtime_reference(frozen), frozen["data"]["capabilityIds"]))
        built = jobs.builder.build(source, data / "released-agent", method_snapshot=frozen,
                                   reuse_candidate=reusable_seed(jobs, "acceptance-owner"))
        helper = fixture_module(project)
        observed = probe(built.candidate_dir, frozen["data"]["cases"], helper, snapshot=frozen)
        assert observed["cases"][0]["loadedMethodObserved"]
        assert any(a["id"] == asset["assetId"] and a["version"] == "1.0.0"
                   for a in observed["cases"][0]["runtimeLock"]["assets"])
        helper.verify(built.candidate_dir)
        report = {"schemaVersion": "private-method-acceptance.v0.1", "trialId": trial["trialId"],
            "trialReport": trial["report"], "release": released, "releasedInstallation": observed,
            "regression": json.loads((built.candidate_dir / "controlled-service-report.json").read_text()),
            "realModel": "NOT_RUN", "fullSimulator": "NOT_RUN", "ui": "PENDING_PHASE_B",
            "humanAcceptance": "NOT_RUN"}
        write_private(data / "acceptance-report.json", report)
        print(json.dumps({"report": str(data / "acceptance-report.json"), "controlledRuntime": "PASS",
                          "releaseVersion": "1.0.0", "realModel": "NOT_RUN", "humanAcceptance": "NOT_RUN"}))
    finally:
        jobs.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    args = parser.parse_args()
    verify(Path(__file__).resolve().parents[1], args.harness.resolve(), args.data.resolve())
