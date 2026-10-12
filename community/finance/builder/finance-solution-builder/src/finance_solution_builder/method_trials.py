"""Explicit controlled Java/HTTP trials, never a live-model fallback or uploaded runner."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import secrets
import shutil
import subprocess
import time
import uuid
import urllib.request

from .experience_contracts import ApiError
from .method_assets import canonical, sha, runtime_files, installation_digest, runtime_reference, write_private
from .readonly import revision
from .service import MAIN, CAPABILITIES, installation_for_method, solution_for, template_digest, stop_owned_process


def reusable_seed(jobs, owner):
    """One current compatible host per owner; called only by the serialized build queue."""
    key = sha(canonical({"template": template_digest(jobs.project), "harness": revision(jobs.builder.harness_repo)}))[7:23]
    space = jobs.data_dir / "method-trials" / hashlib.sha256(owner.encode()).hexdigest()
    space.mkdir(parents=True, exist_ok=True, mode=0o700)
    root = space / ("seed-" + key)
    if root.exists():
        report = root / "build-report.json"
        if report.is_file() and json.loads(report.read_text()).get("status") == "BUILT":
            return root / "candidate"
        shutil.rmtree(root)
    capabilities = (sorted(CAPABILITIES) if (jobs.builder.harness_repo / "capability-packs/cashflow-readonly/pack.yaml").is_file()
                    else ["wealth.facts.describe", "account.balance.query"])
    selection = solution_for(capabilities, "agent-" + uuid.uuid4().hex[:16])
    input_file = space / "seed-input.json"
    write_private(input_file, selection)
    jobs.builder.build(input_file, root)
    return root / "candidate"


def fixture_module(project, harness=None):
    # Load only this shipped, fixed test helper. No asset path or uploaded code can select a runner.
    path = project / "engineering/verify_local_service.py"
    spec = importlib.util.spec_from_file_location("finance_method_trial_fixture", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    if harness is not None:
        source = harness / "conformance/fixtures/cashflow/source"
        if source.is_dir():
            module.cashflow_data = {name: json.loads((source / (name + ".json")).read_text())
                                   for name in ("summary", "analysis", "summary-policy", "analysis-policy")}
    return module


def probe(candidate, cases, helper, *, snapshot=None, cancel=None, live_model=None, bank_url=None):
    fixture = helper.Endpoints()
    fixture.cashflow = getattr(helper, "cashflow_data", None)
    recorder = helper.ReadOnlyRecorder(bank_url) if bank_url else None
    if recorder:
        fixture.customer = "customer-demo-001"
    bank = recorder.base if recorder else fixture.base
    def reads(): return helper.business_calls(fixture, recorder)
    token = secrets.token_urlsafe(40)
    policy = candidate / "method-trial-policy.json"
    write_private(policy, {"schemaVersion": "local-simulator-access.v0.1", "bankUrl": bank,
        "subjects": {fixture.customer: {"enabled": True, "expiresAt": helper.expiry(),
            "scopes": ["wealth:facts:read", "wealth:customer:read", "account:read", "cashflow:read"]}}})
    env = {k: os.environ[k] for k in ("PATH", "HOME", "JAVA_HOME", "TMPDIR", "LANG") if k in os.environ}
    env.update(FH_BANK_URL=bank, FH_BANK_LOGIN="demo", FH_BANK_SECRET="demo123",
        FH_ACCESS_POLICY=str(policy), FH_HOST_TOKEN=token, FH_MODEL_URL=fixture.base + "/v1",
        FH_MODEL_KEY="explicit-test-not-a-key", FH_MODEL_NAME="explicit-boundary-test",
        FH_MODEL_MODE="CONTROLLED_TEST", FH_PORT="0")
    if live_model:
        from .model_registry import ModelRegistry
        binding, frozen_version = live_model
        def respond(body):
            try:
                registry = ModelRegistry()
                ref = binding["modelRef"]
                if registry.status(ref) != "CONFIGURED_UNTESTED" or registry.version(registry.bindings[ref]) != frozen_version:
                    return 503, {"error": "MODEL_CONFIGURATION_CHANGED"}
                url = registry.env[binding["urlEnvironment"]].rstrip("/") + "/chat/completions"
                request = urllib.request.Request(url, data=canonical(body), headers={"Content-Type": "application/json",
                    "Authorization": "Bearer " + registry.env[binding["keyEnvironment"]]})
                with urllib.request.urlopen(request, timeout=35) as response:
                    raw = response.read(2 * 1024 * 1024 + 1)
                    if len(raw) > 2 * 1024 * 1024:
                        raise ValueError()
                    return response.status, json.loads(raw)
            except Exception:
                return 502, {"error": "MODEL_PREVIEW_UNAVAILABLE"}
        fixture.model_responder = respond
        env.update(FH_MODEL_MODE="LIVE", FH_MODEL_NAME=binding["modelName"])
    info = candidate / "instance.json"
    info.unlink(missing_ok=True)
    log = candidate / "method-trial-host.log"
    child = None
    evidence = {"model": "CONFIGURED_MODEL" if live_model else "CONTROLLED_TEST",
                "source": "FULL_SYNTHETIC_SIMULATOR" if recorder else "CONTROLLED_HTTP", "cases": []}
    try:
        jar = next((candidate / "target").glob("*.jar"))
        with log.open("wb") as stream:
            log.chmod(0o600)
            child = subprocess.Popen(["java", "-Xmx512m", "-cp", str(jar) + os.pathsep + str(candidate / "target/dependency/*"),
                MAIN, str(candidate)], cwd=candidate, env=env, stdout=stream, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 40
        while not info.is_file():
            if child.poll() is not None or time.monotonic() > deadline or cancel and cancel.is_set():
                raise ApiError("ASSET_TRIAL_HOST_FAILED", 409)
            time.sleep(.1)
        instance = json.loads(info.read_text())
        base = "http://127.0.0.1:" + str(instance["port"])
        code, health = helper.request(base, token, "/health")
        lock = json.loads((candidate / "service-lock.json").read_text())
        if (code != 200 or instance["pid"] != child.pid or health.get("artifactLock") != lock
                or health.get("instanceId") != instance["instanceId"]):
            raise ApiError("ASSET_TRIAL_INSTANCE_MISMATCH", 409)
        evidence.update(buildId=lock["buildId"], lockDigest=sha((candidate / "service-lock.json").read_bytes()),
                        runtime=health["artifactLock"]["runtime"], sources=lock["sources"])
        expected = None
        if snapshot:
            expected = runtime_files(snapshot["assetId"], snapshot["version"], snapshot["data"])[
                "skills/" + snapshot["assetId"] + "/SKILL.md"].decode()
        for case in cases:
            if cancel and cancel.is_set():
                raise ApiError("ASSET_TRIAL_CANCELLED", 409)
            code, session = helper.request(base, token, "/sessions", {})
            if code != 200:
                raise ApiError("ASSET_TRIAL_SESSION_REJECTED", 409)
            installed = session["installations"]
            selected = {c["capabilityId"] for pack in installed for c in pack["capabilities"]}
            if selected != set(lock["capabilities"]) or any(c["operation"] != "QUERY" for p in installed for c in p["capabilities"]):
                raise ApiError("ASSET_TRIAL_TOOL_MISMATCH", 409)
            start, read_count = len(fixture.models), len(reads())
            code, result = helper.request(base, token, "/sessions/" + session["sessionId"] + "/chat", {"query": case["query"]})
            requests = fixture.models[start:]
            if (code != 200 or not requests or not result.get("toolResults") or len(reads()) <= read_count
                    or result.get("evidenceKind") != ("CONFIGURED_MODEL_WITH_LOCAL_BANK_SIMULATOR" if live_model else "EXPLICIT_TEST_MODEL")):
                raise ApiError("ASSET_TRIAL_TOOL_CALL_REQUIRED", 409)
            system = "\n".join(str(m.get("content", "")) for m in requests[0]["messages"] if m["role"] == "system")
            runtime_lock = result["runtimeLock"]
            method_observed = None
            if snapshot:
                ref = runtime_reference(snapshot)
                method_observed = (expected in system and any(a.get("id") == ref["id"] and a.get("version") == ref["version"]
                    and a.get("digest") == ref["digest"] for a in runtime_lock["assets"]))
                if not method_observed:
                    raise ApiError("ASSET_TRIAL_METHOD_NOT_LOADED", 409)
            evidence["cases"].append({"name": case["name"], "queryDigest": sha(case["query"].encode()),
                "modelRequests": len(requests), "businessReadCount": len(reads()) - read_count,
                "toolResultsDigest": sha(canonical(result["toolResults"])), "toolCount": len(result["toolResults"]),
                "runtimeLock": runtime_lock, "systemPromptDigest": sha(system.encode()),
                "loadedMethodObserved": method_observed,
                "expectedTermsInLoadedInstructions": {term: term in system for term in case["expectedTerms"]},
                "responseDigest": sha(canonical(result["response"]))})
            if live_model:
                evidence["cases"][-1].update(response=result["response"], facts=result["toolResults"],
                                            sourceCalls=result.get("sourceCalls", []))
            # Private synthetic evidence permits an owner/reviewer to inspect the actual sent text and tool/result pairing.
            write_private(candidate / "method-evidence" / (str(len(evidence["cases"])) + ".json"),
                          {"requests": requests, "runtimeLock": runtime_lock, "toolResults": result["toolResults"]})
            helper.request(base, token, "/sessions/" + session["sessionId"] + "/revoke", {})
        if fixture.writes or recorder and any(not call["identity"] and call["method"] != "GET" for call in recorder.calls):
            raise ApiError("ASSET_TRIAL_WRITE_ATTEMPT", 409)
        evidence["businessWriteAttempts"] = len(fixture.writes)
        if recorder:
            evidence["sourceTransport"] = recorder.calls
        evidence["evidenceFiles"] = {p.name: sha(p.read_bytes()) for p in sorted((candidate / "method-evidence").glob("*.json"))}
        return evidence
    finally:
        stop_owned_process(child)
        fixture.close()
        if recorder:
            recorder.close()
        policy.unlink(missing_ok=True)


def run_trial(jobs, owner, draft):
    began = time.monotonic()
    seed = reusable_seed(jobs, owner)
    root = seed.parent.parent / ("trial-" + uuid.uuid4().hex[:16])
    root.mkdir(mode=0o700)
    version = "0.0.0"
    snapshot = {"assetId": draft["assetId"], "version": version, "data": draft["data"],
        "installationDigest": installation_digest(runtime_files(draft["assetId"], version, draft["data"])),
        "releaseDigest": draft["contentDigest"]}
    helper = fixture_module(jobs.project, jobs.builder.harness_repo)
    candidates = []
    outcomes = {}
    try:
        for label, custom in (("baseline", None), ("candidate", snapshot)):
            input_file = root / (label + "-input.json")
            write_private(input_file, solution_for(installation_for_method(draft["data"]["capabilityIds"]), "agent-" + uuid.uuid4().hex[:16],
                                                  runtime_reference(custom) if custom else None,
                                                  custom["data"]["capabilityIds"] if custom else None))
            candidates.append(root / label / "candidate")
            built = jobs.builder.build(input_file, root / label, reuse_candidate=seed, method_snapshot=custom)
            outcomes[label] = probe(built.candidate_dir, draft["data"]["cases"], helper, snapshot=custom,
                                   cancel=getattr(jobs.builder, "service_cancel", None))
        after = outcomes["candidate"]
        checks = [c["loadedMethodObserved"] is True for c in after["cases"]]
        runtime_verified = bool(checks) and all(checks)
        report = {"schemaVersion": "private-method-trial.v0.1", "status": "PASS" if runtime_verified else "FAIL",
            "runtimeVerified": runtime_verified, "model": "CONTROLLED_TEST", "source": "CONTROLLED_HTTP",
            "professionalQuality": "NOT_EVALUATED", "liveModelValidation": "NOT_RUN", "humanReview": "PENDING",
            "contentDigest": draft["contentDigest"], "revision": draft["revision"],
            "trialInstallationVersion": version, "trialInstallationDigest": snapshot["installationDigest"],
            "trialRuntime": {"family": "openjiuwen", "language": "java", "source": "GENERATED_JAVA_HOST"},
            "baseSourceDigest": sha(canonical(draft["source"])),
            "comparison": outcomes, "comparisonKind": "DEFAULT_VS_DRAFT_LOADING_AND_EXECUTION_NOT_QUALITY",
            "elapsedMillis": round((time.monotonic() - began) * 1000)}
        write_private(root / "report.json", report)
        return report
    finally:
        # Retain small private inputs/evidence/locks, not a dependency copy for every experimental revision.
        for candidate in candidates:
            shutil.rmtree(candidate / "target", ignore_errors=True)


def run_preview(jobs, owner, draft, model_ref, query):
    from .model_registry import ModelRegistry
    registry = ModelRegistry()
    if registry.status(model_ref) != "CONFIGURED_UNTESTED":
        raise ApiError("MODEL_CONNECTION_UNAVAILABLE", 409)
    binding = registry.bindings[model_ref]
    version = registry.version(binding)
    seed = reusable_seed(jobs, owner)
    root = seed.parent.parent / ("preview-" + uuid.uuid4().hex[:16])
    root.mkdir(mode=0o700)
    snapshot = {"assetId": draft["assetId"], "version": "0.0.0", "data": draft["data"],
        "installationDigest": installation_digest(runtime_files(draft["assetId"], "0.0.0", draft["data"])),
        "releaseDigest": draft["contentDigest"]}
    comparison = {}
    try:
        for label, custom in (("baseline", None), ("candidate", snapshot)):
            current = jobs.method_assets.trial_input(owner, draft["assetId"], draft["revision"])
            if current["contentDigest"] != draft["contentDigest"]:
                raise ApiError("ASSET_REVISION_CONFLICT", 409)
            source = root / (label + "-input.json")
            write_private(source, solution_for(installation_for_method(draft["data"]["capabilityIds"]), "agent-" + uuid.uuid4().hex[:16],
                runtime_reference(custom) if custom else None, custom["data"]["capabilityIds"] if custom else None))
            built = jobs.builder.build(source, root / label, reuse_candidate=seed, method_snapshot=custom)
            comparison[label] = probe(built.candidate_dir, [{"name": "Model preview", "query": query, "expectedTerms": []}],
                fixture_module(jobs.project, jobs.builder.harness_repo), snapshot=custom, cancel=getattr(jobs.builder, "service_cancel", None),
                live_model=(binding, version))
        current = jobs.method_assets.trial_input(owner, draft["assetId"], draft["revision"])
        if current["contentDigest"] != draft["contentDigest"]:
            raise ApiError("ASSET_REVISION_CONFLICT", 409)
        result = {"schemaVersion": "private-method-preview.v0.1", "revision": draft["revision"],
            "contentDigest": draft["contentDigest"], "modelRef": model_ref, "modelVersion": version,
            "source": "CONTROLLED_HTTP", "professionalQuality": "REQUIRES_HUMAN_REVIEW",
            "comparison": comparison, "query": query}
        write_private(root / "preview.json", result)
        return result
    finally:
        for label in ("baseline", "candidate"):
            shutil.rmtree(root / label / "candidate/target", ignore_errors=True)
