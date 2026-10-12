"""Opt-in V0.1 acceptance: uploaded Skill -> package -> target release -> Agent."""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import time
import uuid

from finance_solution_builder.business_packages import verify_harness_package_anchor
from finance_solution_builder.method_assets import runtime_reference, write_private
from finance_solution_builder.method_trials import fixture_module, probe, reusable_seed, run_preview
from finance_solution_builder.service import solution_for
from finance_solution_builder.workbench import Jobs
from finance_solution_builder.workbench_api import WorkbenchApi


def git_state(root):
    def run(*args):
        return subprocess.run(["git", *args], cwd=root, check=True, text=True,
                              capture_output=True).stdout.strip()
    return {"commit": run("rev-parse", "HEAD"), "dirty": bool(run("status", "--porcelain"))}


def await_trial(api, path, owner, timeout=1800):
    assert api.post(path + "/trial", {"revision": 1}, owner)[0] == 202
    deadline = time.monotonic() + timeout
    while True:
        job = api.get(path + "/trial", owner)[1]
        if job["status"] not in {"QUEUED", "RUNNING"}:
            break
        if time.monotonic() > deadline:
            raise RuntimeError("TRIAL_TIMEOUT")
        time.sleep(.5)
    if job["status"] != "COMPLETED":
        raise RuntimeError(job.get("error", "TRIAL_FAILED"))
    return job["trial"]


def publish(api, path, owner, version):
    trial = await_trial(api, path, owner)
    plan = api.post(path + "/package-plan", {"revision": 1, "trialId": trial["trialId"]}, owner)[1]
    release = api.post(path + "/publish", {"revision": 1, "version": version,
        "trialId": trial["trialId"], "bindingConfirmations": ["wealth.facts.describe"]}, owner)[1]
    return trial, plan, release


def verify(project, harness, data, model_ref=None):
    package_anchor = verify_harness_package_anchor(harness)
    data.mkdir(parents=True, exist_ok=True, mode=0o700)
    if any(data.iterdir()):
        raise ValueError("FRESH_ACCEPTANCE_DIRECTORY_REQUIRED")
    jobs = Jobs(project, harness, data_dir=data)
    api = WorkbenchApi(jobs)
    source_owner, target_owner = "package-source-owner", "package-target-owner"
    try:
        skill_path = harness / "domain-packs/wealth/shared-facts/skills/wealth-facts-description/SKILL.md"
        skill_raw = skill_path.read_bytes()
        upload = {"filename": "SKILL.md", "base64": base64.b64encode(skill_raw).decode(),
                  "name": "财富事实核验组件"}
        preview = api.post("/api/v1/assets/import-preview", upload, source_owner)[1]
        assert jobs.method_assets.list(source_owner) == []
        source_data = {**preview["data"], "name": "财富事实核验组件",
            "capabilityIds": ["wealth.facts.describe"],
            "cases": [{"name": "财富事实核验", "query": "请查询我的资产概览", "expectedTerms": []}]}
        source = api.post("/api/v1/assets/import", {**upload, "data": source_data}, source_owner)[1]
        source_path = "/api/v1/assets/" + source["assetId"]
        assert api.post(source_path + "/validate", {"revision": 1}, source_owner)[1]["status"] == "VALID"
        source_trial, source_plan, source_release = publish(api, source_path, source_owner, "1.0.0")

        route = source_path + "/packages/1.0.0/" + source_release["releaseDigest"]
        exported = api.get(route, source_owner)[1]
        (data / exported["filename"]).write_bytes(base64.b64decode(exported["base64"]))

        import_body = {"filename": exported["filename"], "base64": exported["base64"],
                       "name": "目标环境财富事实核验"}
        target_preview = api.post("/api/v1/assets/package-preview", import_body, target_owner)[1]
        assert target_preview["dependencyStatus"]["status"] == "ADMISSIBLE"
        assert jobs.method_assets.list(target_owner) == []
        target_data = {**target_preview["data"], "name": "目标环境财富事实核验",
                       "content": target_preview["data"]["content"] + "\n\n目标环境已重新核验。"}
        target = api.post("/api/v1/assets/package-import", {**import_body, "data": target_data}, target_owner)[1]
        target_path = "/api/v1/assets/" + target["assetId"]
        assert api.post(target_path + "/validate", {"revision": 1}, target_owner)[1]["status"] == "VALID"
        target_trial, target_plan, target_release = publish(api, target_path, target_owner, "1.0.0")
        model_preview = run_preview(jobs, target_owner, target, model_ref,
            target["data"]["cases"][0]["query"]) if model_ref else None

        target_ref = {key: target_release[key] for key in ("assetId", "version", "releaseDigest")}
        frozen = jobs.method_assets.release(target_owner, target_ref)
        source_input = data / "target-release-input.json"
        write_private(source_input, solution_for(["wealth.facts.describe"],
            "agent-" + uuid.uuid4().hex[:16], runtime_reference(frozen), frozen["data"]["capabilityIds"]))
        built = jobs.builder.build(source_input, data / "target-release-agent", method_snapshot=frozen,
                                   reuse_candidate=reusable_seed(jobs, target_owner))
        helper = fixture_module(project)
        observed = probe(built.candidate_dir, frozen["data"]["cases"], helper, snapshot=frozen)
        helper.verify(built.candidate_dir)
        assert observed["cases"][0]["loadedMethodObserved"]

        report = {"schemaVersion": "finance-business-package-acceptance.v0.1",
            "sources": {"builder": git_state(project), "harness": git_state(harness),
                        "businessPackageAnchor": package_anchor,
                        "skill": "repo://domain-packs/wealth/shared-facts/skills/wealth-facts-description/SKILL.md"},
            "source": {"uploadPreview": "PASS", "trial": source_trial, "plan": source_plan,
                       "release": source_release},
            "package": {"filename": exported["filename"], "sha256": exported["sha256"],
                        "manifest": exported["manifest"]},
            "target": {"importPreview": "PASS", "editedImportSaved": "PASS", "owner": target_owner,
                       "trial": target_trial, "plan": target_plan, "release": target_release},
            "agent": {"status": "PASS", "usesTargetRelease": target_ref,
                      "candidate": str(built.candidate_dir), "observed": observed},
            "realModel": ({"status": "PASS", "modelRef": model_preview["modelRef"],
                           "modelVersion": model_preview["modelVersion"],
                           "source": model_preview["source"],
                           "professionalQuality": model_preview["professionalQuality"]}
                          if model_preview else "NOT_RUN"),
            "realBankAuthorization": "NOT_RUN", "humanAcceptance": "NOT_RUN"}
        write_private(data / "business-package-acceptance.json", report)
        print(json.dumps({"status": "PASS", "report": str(data / "business-package-acceptance.json"),
                          "package": str(data / exported["filename"]), "targetRelease": target_ref,
                          "realModel": report["realModel"], "humanAcceptance": "NOT_RUN"}))
    finally:
        jobs.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--model-ref")
    args = parser.parse_args()
    verify(Path(__file__).resolve().parents[1], args.harness.resolve(), args.data.resolve(), args.model_ref)
