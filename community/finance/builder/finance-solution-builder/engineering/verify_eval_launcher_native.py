"""Opt-in native Launcher verification with an isolated synthetic bank.

Uses existing admission, trial/publication, build and Host lifecycle. No production
config is read. Supply existing qualification environment before running. The
default model is explicitly controlled, never claimed as a real-model evaluation.
"""
import argparse
import base64
import copy
import hashlib
import io
import json
import os
import secrets
import signal
import shutil
import subprocess
from pathlib import Path
from urllib.parse import urlsplit
import sys
import time
import threading
import zipfile

project = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(project / "src"), str(project.parent / "base/src")]
from finance_solution_builder.eval_launcher_backend import BuilderBackend, Handle
from finance_solution_builder.eval_launcher_service import Launcher, validate_skill
from finance_solution_builder.eval_launcher_http import make_server
from finance_solution_builder.method_assets import write_private
from finance_solution_builder.method_trials import run_trial
from finance_solution_builder.model_registry import ModelRegistry
from finance_solution_builder.model_generation import validate_generation, generation_digest
from finance_solution_builder.workbench import trusted_business_time_environment
from finance_solution_builder.workbench_contracts import InterfaceConfig, catalog
from verify_local_service import Endpoints, expiry


def live_model_options(base, name, serve_info):
    if base is None and name is None:
        return None
    if base is None or name is None or serve_info is None:
        raise ValueError("LIVE_MODEL_REQUIRES_SERVE_AND_PAIRED_OPTIONS")
    try:
        parsed = urlsplit(base)
        port = parsed.port
        valid = (parsed.scheme in {"http", "https"} and parsed.hostname in {"127.0.0.1", "localhost"}
                 and parsed.username is None and parsed.password is None and "?" not in base and "#" not in base
                 and "\\" not in base and not any(c.isspace() or ord(c) < 32 for c in base)
                 and (port is None or 1 <= port <= 65535))
    except ValueError:
        valid = False
    if not valid:
        raise ValueError("LIVE_MODEL_LOOPBACK_URL_REQUIRED")
    if (not isinstance(name, str) or not 1 <= len(name) <= 180 or name != name.strip()
            or any(ord(c) < 32 for c in name) or any(value in name for value in ("http://", "https://", "sk-"))):
        raise ValueError("LIVE_MODEL_NAME_INVALID")
    return {"base": base.rstrip("/"), "name": name}


def fixture_model_environment(bank_base, live_model):
    base = live_model["base"] if live_model else bank_base + "/v1"
    name = live_model["name"] if live_model else "controlled-eval-launcher"
    key = "loopback-relay-placeholder" if live_model else "controlled-test-not-a-secret"
    return {"FH_CONN_LAUNCHER_TEST_URL": base, "FH_CONN_LAUNCHER_TEST_KEY": key,
            "FH_MODEL_URL": base, "FH_MODEL_NAME": name, "FH_MODEL_KEY": key,
            "FH_MODEL_MODE": "LIVE" if live_model else "CONTROLLED_TEST"}


def serve_fixture(launcher, presets, skill, owner, info_path, seconds, stop_file=None, generation=None, preset_root=None, live_model=None):
    if type(seconds) is not int or not 1 <= seconds <= 1200:
        raise ValueError("SERVE_SECONDS_INVALID")
    if info_path.exists() or info_path.is_symlink():
        raise ValueError("SERVE_INFO_ALREADY_EXISTS")
    if stop_file is not None and (stop_file.exists() or stop_file.is_symlink()):
        raise ValueError("STOP_FILE_ALREADY_EXISTS")
    token = secrets.token_urlsafe(40)
    server = make_server(launcher, {hashlib.sha256(token.encode()).hexdigest(): {
        "owner": owner, "runtimes": list(presets)}})
    stopped = threading.Event()
    thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=.1), daemon=True)
    handlers = {}
    published = False
    info = {"schemaVersion": "finance.eval-native-fixture.v1", "status": "READY",
            "baseUrl": f"http://127.0.0.1:{server.server_port}/", "token": token,
            "skill": copy.deepcopy(skill), "modelId": "launcher-model",
            "runtimes": {key: {"path": key + "/", "runtime": p["runtime"], "model": p["model"],
                "requiredToolNames": p["toolNames"],
                "presetRef": str((preset_root or info_path.parent) / (key + "-preset.json")),
                "allowDrafts": p.get("allowDrafts", False)} for key, p in presets.items()},
            "serveSeconds": seconds, "mode": "CONTROLLED_NATIVE_FIXTURE", "realModel": "NOT_RUN"}
    if generation is not None:
        info["generation"] = copy.deepcopy(generation)
    if live_model is not None:
        info.update(mode="LIVE_NATIVE_FIXTURE", realModel="LIVE", modelHttpEvidence="EXTERNAL_LOOPBACK_RELAY",
                    modelInvocationStatus="NOT_OBSERVED_BY_FIXTURE")
    try:
        if threading.current_thread() is threading.main_thread():
            for name in (signal.SIGTERM, signal.SIGINT):
                handlers[name] = signal.signal(name, lambda *_: stopped.set())
        thread.start()
        write_private(info_path, info)
        published = True
        print("READY: native Launcher fixture; private connection info written.", flush=True)
        deadline = time.monotonic() + seconds
        while not stopped.wait(min(.2, max(0, deadline - time.monotonic()))):
            if time.monotonic() >= deadline or stop_file is not None and stop_file.exists():
                break
            launcher.reap()
    finally:
        if thread.ident is not None:
            server.shutdown()
        server.server_close()
        if thread.ident is not None:
            thread.join(timeout=3)
        for name, handler in handlers.items():
            signal.signal(name, handler)
        if published and info_path.is_file() and not info_path.is_symlink():
            write_private(info_path, {**info, "status": "STOPPED"})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--resume-admission", type=Path, help="Reuse a real prior isolated trial/release; admission is rechecked")
    parser.add_argument("--seed-cache", type=Path, help="Reuse a prior probe's checked binary seed, not its method release")
    parser.add_argument("--draft", action="store_true", help="Import changed text as an execution candidate without publishing it")
    parser.add_argument("--generation", type=json.loads, help="Explicit finance.eval-generation.v1 JSON; omitted preserves native defaults")
    parser.add_argument("--serve-info", type=Path, help="Serve the real native fixture and write private connection JSON instead of running cases")
    parser.add_argument("--serve-seconds", type=int, default=1200, help="Fixture lifetime after READY, 1..1200 seconds")
    parser.add_argument("--stop-file", type=Path, help="Stop serving when this previously absent marker file appears")
    parser.add_argument("--live-model-base", help="Serve mode only: approved parent relay at a loopback HTTP(S) base URL")
    parser.add_argument("--live-model-name", help="Serve mode only: model name paired with live-model-base")
    args = parser.parse_args()
    try:
        live_model = live_model_options(args.live_model_base, args.live_model_name, args.serve_info)
    except ValueError as error:
        parser.error(str(error))
    if not 1 <= args.serve_seconds <= 1200 or args.stop_file is not None and args.serve_info is None:
        parser.error("serve-seconds must be 1..1200; stop-file requires serve-info")
    if args.serve_info is not None and (args.serve_info.exists() or args.serve_info.is_symlink()):
        parser.error("serve-info must not already exist")
    if args.stop_file is not None and (args.stop_file.exists() or args.stop_file.is_symlink()):
        parser.error("stop-file must not already exist")
    generation = validate_generation(args.generation) if args.generation is not None else None
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    bank = Endpoints()
    owner, model_ref = "launcher-native-test", "model-launcher-test"
    backend, launcher = None, None
    checks, results = [], []

    def save(name, value):
        write_private(output / name, value)

    def check(name, passed):
        checks.append({"name": name, "passed": bool(passed)})
        save("checks.json", checks)
        if not passed:
            raise RuntimeError(name)

    try:
        model_env = fixture_model_environment(bank.base, live_model)
        model_name = model_env["FH_MODEL_NAME"]
        save("models.json", {"schemaVersion": "finance-model-catalog.v1", "source": {"kind": "LIVE_LOOPBACK_RELAY" if live_model else "CONTROLLED_TEST"}, "models": [{
            "modelRef": model_ref, "displayName": model_name, "provider": "OpenAI", "modelName": model_name,
            "eaCatalogId": "launcher-test", "eaConfigured": False}]})
        save("bindings.json", {"schemaVersion": "finance-model-bindings.v1", "bindings": [{
            "modelRef": model_ref, "revision": "launcher-v1", "enabled": True, "protocol": "OPENAI_CHAT_COMPLETIONS",
            "provider": "OpenAI", "modelName": model_name, "urlEnvironment": "FH_CONN_LAUNCHER_TEST_URL", "keyEnvironment": "FH_CONN_LAUNCHER_TEST_KEY"}]})
        save("access.json", {"schemaVersion": "local-simulator-access.v0.1", "bankUrl": bank.base,
             "subjects": {customer: {"enabled": True, "expiresAt": expiry(), "scopes": ["wealth:facts:read", "wealth:customer:read", "account:read"]} for customer in ("customer-test-a", "customer-test-b")}})
        os.environ.update(FH_MODEL_CATALOG=str(output / "models.json"), FH_MODEL_REGISTRY=str(output / "bindings.json"),
                          **model_env, FH_BANK_URL=bank.base, FH_BANK_LOGIN="synthetic", FH_BANK_SECRET="synthetic",
                          FH_ACCESS_POLICY=str(output / "access.json"))
        # Grant diagnostic capture only to this synthetic test owner and the
        # build-time operator; business authorization remains the bank's grant.
        capture_owners = set(os.environ.get("FH_SNAPSHOT_CAPTURE_OWNERS", "").split(","))
        capture_owners.update((os.environ.get("FH_HISTORY_OWNER", "local-operator"), hashlib.sha256(owner.encode()).hexdigest()))
        os.environ["FH_SNAPSHOT_CAPTURE_OWNERS"] = ",".join(sorted(capture_owners - {""}))
        class DiagnosticBackend(BuilderBackend):
            def _talk(self, handle, action, body, deadline):
                result = super()._talk(handle, action, body, deadline)
                save("diagnostic-" + handle.build_id + "-" + action + ".json", result)
                return result

            def execute(self, handle, case, timeout_ms):
                result = super().execute(handle, case, timeout_ms)
                save("diagnostic-" + handle.build_id + "-case-result.json", result)
                return result

            def close(self, handle):
                if handle.build_id:
                    root = self.jobs.path(handle.build_id)
                    candidate = root / "candidate"
                    jars = list((candidate / "target").glob("*.jar"))
                    if len(jars) == 1 and (candidate / "service-lock.json").is_file():
                        probe = subprocess.run(["java", "-cp", str(jars[0]) + os.pathsep + str(candidate / "target/dependency/*"),
                            "io.github.bitlhk.finance.builder.local.ObservationAdmission", str(candidate)],
                            capture_output=True, text=True, timeout=30)
                        save("diagnostic-" + handle.build_id + "-admission.json", {
                            "exitCode": probe.returncode, "assessment": probe.stdout[:65536], "error": probe.stderr[:4096]})
                    for name in ("build-report.json", "solution-lock.json"):
                        file = root / name
                        if file.is_file() and not file.is_symlink():
                            save("diagnostic-" + handle.build_id + "-" + name, json.loads(file.read_text()))
                    for file in (root / "logs").glob("*.log"):
                        if not file.is_symlink() and file.stat().st_size <= 1024 * 1024:
                            target = output / ("diagnostic-" + handle.build_id + "-" + file.name)
                            shutil.copyfile(file, target)
                            target.chmod(0o600)
                return super().close(handle)
        backend = DiagnosticBackend(project, args.harness.resolve(), output / "jobs", max_instances=2)
        original_talk = backend.jobs.talk
        def diagnostic_talk(build_id, request_owner, action, body, **kwargs):
            result = original_talk(build_id, request_owner, action, body, **kwargs)
            save("diagnostic-" + build_id + "-host-" + action + ".json", {"status": result[0], "body": result[1]})
            return result
        backend.jobs.talk = diagnostic_talk
        api, store = backend.api, backend.jobs.method_assets
        if args.seed_cache:
            shutil.copytree(args.seed_cache.resolve() / "jobs/method-trials", output / "jobs/method-trials", dirs_exist_ok=True)
        current = catalog(args.harness.resolve())
        component = next(c for c in current["unifiedDirectory"]["components"]
                         if c["capabilityIds"] == ["account.balance.query"] and c.get("methodCustomizationSupported"))
        if args.resume_admission:
            prior = args.resume_admission.resolve()
            shutil.copytree(prior / "jobs/private-assets", output / "jobs/private-assets", dirs_exist_ok=True)
            if (prior / "jobs/method-trials").is_dir():
                # Existing seed admission and binary hash checks still apply.
                shutil.copytree(prior / "jobs/method-trials", output / "jobs/method-trials", dirs_exist_ok=True)
            release = json.loads((prior / "release.json").read_text())
            admitted_release = store.release(owner, {k: release[k] for k in ("assetId", "version", "releaseDigest")})
            store.check_source(admitted_release)
            data = admitted_release["data"]
            save("release.json", release)
            check("reused-real-admission-rechecked", True)
        else:
            code, preview = api.post("/api/v1/assets/customize-preview", {
                "componentId": component["id"], "directoryRevision": current["unifiedDirectory"]["revision"],
                "name": "Launcher controlled method"}, owner)
            check("current-source-preview", code == 200)
            data = preview["data"]
            draft = store.create(owner, data, source=preview["source"])
            admitted = store.trial_input(owner, draft["assetId"], draft["revision"])
            trial_report = run_trial(backend.jobs, owner, admitted)
            save("method-trial.json", trial_report)
            check("current-method-execution-admission", trial_report["status"] == "PASS" and trial_report["runtimeVerified"])
            trial = store.record_trial(owner, draft["assetId"], admitted, trial_report)
            release = store.publish(owner, draft["assetId"], draft["revision"], "1.0.0", trial["trialId"],
                                    binding_confirmations=data["capabilityIds"])
            save("release.json", release)
        files = {"SKILL.md": data["content"], **data["resources"]}
        original_store = {str(p.relative_to(store.root)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in store.root.rglob("*.json")}
        if args.draft:
            files["SKILL.md"] += "\n\n## Evaluation candidate\nReport missing information explicitly. Never infer a missing amount.\n"
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as z:
            for name, value in files.items():
                z.writestr(name, value)
        raw = archive.getvalue()
        source_hash = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
        skill = {"skillId": "launcher-balance-method", "version": "1.0.0", "artifactSha256": source_hash,
                 "instructions": files["SKILL.md"], "files": files, "archiveBase64": base64.b64encode(raw).decode(),
                 "archiveSha256": hashlib.sha256(raw).hexdigest()}
        registry = ModelRegistry()
        model = registry.snapshot([model_ref], model_ref, registry.catalog_revision)
        config_version = model["connections"][0]["configVersion"]
        presets = {}
        for key, runtime_id, family, version in [("oj", "openjiuwen-java-0.1.15", "openjiuwen", "0.1.15"),
                                                 ("as", "agentscope-java-2.0.3", "agentscope", "2.0.3")]:
            selection = {"extension_type": "finance-workbench.selection", "schema_version": "0.9", "runtimeId": runtime_id,
                         "applicationTemplateId": "banking-readonly-web-v1", "presentation": "MOBILE_WEB", "bankConnectionId": "local-simulator",
                         "capabilityIds": component["capabilityIds"], "selectedModelRefs": [model_ref], "defaultModelRef": model_ref,
                         "modelCatalogRevision": registry.catalog_revision, "interfaceConfig": InterfaceConfig().model_dump(),
                         "packBindings": component["packBindings"], "scenarioTemplateId": "mobile-banking", "moduleIds": component["moduleIds"],
                         "directoryRevision": current["unifiedTemplateModuleDirectory"]["revision"], "methodRef": {
                             "extension_type": "finance-private-method.reference", "schema_version": "0.1",
                             **{k: release[k] for k in ("assetId", "version", "releaseDigest")}}, "reuseFromBuildId": None}
            if key == "as":
                from finance_solution_builder.managed_runtime_support import declarations
                host = next(d for d in declarations(project) if d["id"] == runtime_id)["hosts"][0]
                selection.update(schema_version="0.10", hostId=host["id"], profileRef=host["profileRef"])
            presets[key] = {"runtime": {"type": family, "version": version}, "owner": owner, "selection": selection,
                            "allowDrafts": args.draft,
                            "model": {"modelId": "launcher-model", "upstreamModelId": model_name}, "modelRef": model_ref,
                            "modelMetadata": {"configVersion": config_version, "parameters": {}},
                            "businessDate": trusted_business_time_environment()["FH_BUSINESS_DATE"],
                            "toolNames": [t["toolName"] for t in component["tools"]],
                            "sourceFileDigests": {n: hashlib.sha256(v.encode()).hexdigest() for n, v in files.items()}}
        launcher = Launcher(backend, presets)
        if args.serve_info is not None:
            for key, preset in presets.items():
                save(key + "-preset.json", preset)
            serve_fixture(launcher, presets, skill, owner, args.serve_info.absolute(), args.serve_seconds,
                          args.stop_file.absolute() if args.stop_file is not None else None, generation, output, live_model)
            return
        for key in presets:
            body = {"schemaVersion": "finance.skill-eval.runtime-instance.v1", "runId": "native-probe", "side": "left" if key == "oj" else "right",
                    "modelId": "launcher-model", "variant": {"mode": "skill_version", **{k: skill[k] for k in ("skillId", "version", "artifactSha256")}},
                    "skill": skill, "environment": {"environmentId": key, "requiredToolNames": presets[key]["toolNames"]},
                    "configuration": {}, "secrets": {"mode": "inline", "values": {}}, "limits": {"timeoutMs": 600000}}
            if generation is not None:
                body["generation"] = copy.deepcopy(generation)
            save(key + "-preset.json", presets[key])
            checked = {**body, "sourceVerification": validate_skill(skill, body["variant"])}
            backend._validate(Handle(copy.deepcopy(checked), copy.deepcopy(presets[key]), threading.Event()))
            if args.draft:
                preflight = {"status": "DEFERRED_TO_EXECUTION_CANDIDATE_BACKEND", "publicationRequired": False}
            else:
                _, preflight = api.post("/api/v1/preflight", presets[key]["selection"], owner)
            save(key + "-preflight.json", preflight)
            state = launcher.create(owner, key, body, key)
            deadline = time.monotonic() + 600
            while state["status"] == "starting" and time.monotonic() < deadline:
                time.sleep(.2)
                state = launcher.get(owner, key, state["instanceId"])
            save(key + "-state.json", state)
            check(key + "-ready", state["status"] == "ready")
            markers = ["CASE_ALPHA_2026_07", "CASE_BETA_2026_08"]
            sessions = []
            for number, marker in enumerate(markers):
                bank.customer = "customer-test-a" if number == 0 else "customer-test-b"
                before = len(bank.models)
                reads_before = len(bank.reads)
                value = launcher.execute(owner, key, state["instanceId"], {"schemaVersion": "finance.skill-eval.runtime-case.v1",
                    "caseId": marker, "query": "请查询我的账户余额。" + marker, "requiredTools": presets[key]["toolNames"]})
                save(key + "-case-" + str(number) + ".json", value)
                calls = bank.models[before:]
                save(key + "-model-" + str(number) + ".json", calls)
                check(key + "-native-identity-" + str(number), value["runtimeIdentityVerified"] and value["loadedSkills"])
                check(key + "-actual-tool-" + str(number), any(c["status"] == "completed" for c in value["toolCalls"]))
                if generation is not None:
                    binding = {"modelRef": model_ref, "parameters": generation}
                    check(key + "-generation-host-" + str(number), value["metadata"].get("modelGeneration") == binding
                          and value["metadata"].get("modelGenerationSha256") == generation_digest(binding)
                          and value["metadata"].get("modelGenerationHostVerified") is True)
                    check(key + "-generation-wire-" + str(number), bool(calls) and all(
                        all(call.get(field) == generation[field] for field in ("temperature", "top_p", "max_tokens"))
                        for call in calls))
                if args.draft:
                    check(key + "-not-published-" + str(number), value["metadata"]["publicationStatus"] == "NOT_PUBLISHED"
                          and value["metadata"]["methodKind"] == "EXECUTION_CANDIDATE")
                if number:
                    check(key + "-fresh-history", bool(calls) and markers[0] not in json.dumps(calls)
                          and not any(m["role"] == "tool" for m in calls[0]["messages"]))
                    check(key + "-fresh-customer", bool(bank.reads[reads_before:])
                          and all(subject == "customer-test-b" for _, subject in bank.reads[reads_before:])
                          and "customer-test-a" not in json.dumps(calls))
                sessions.append(value["metadata"]["sessionId"])
                results.append(value)
            check(key + "-different-sessions", len(set(sessions)) == 2)
            launcher.delete(owner, key, state["instanceId"])
            retired_build = results[-1]["metadata"]["buildId"]
            cleanup_deadline = time.monotonic() + 20
            while time.monotonic() < cleanup_deadline:
                with backend.jobs.lock:
                    reclaimed = retired_build not in backend.jobs.jobs and retired_build not in backend.jobs.processes
                if reclaimed:
                    break
                time.sleep(.05)
            check(key + "-owned-build-reclaimed", reclaimed and not backend.jobs.path(retired_build).exists())
            check(key + "-version-evidence-retained", any((output / "jobs/eval-launcher-evidence").glob("*/" + retired_build + "/*.json")))
        check("no-bank-writes", not bank.writes)
        check("same-method-content", len({v["metadata"]["loadedSkillDigest"] for v in results}) == 1)
        check("formal-assets-unchanged", original_store == {str(p.relative_to(store.root)): hashlib.sha256(p.read_bytes()).hexdigest()
                          for p in store.root.rglob("*.json")})
        save("report.json", {"status": "PASS", "mode": "CONTROLLED_NATIVE_ONLY", "realModel": "NOT_RUN",
                             "modelParameterEquivalence": "CONTROLLED_WIRE_VERIFIED" if generation is not None else "NOT_VERIFIED",
                             "generation": generation, "uploadedCandidate": args.draft, "checks": checks, "cases": len(results)})
    finally:
        try:
            if launcher:
                launcher.close()
        finally:
            try:
                if backend:
                    backend.jobs.close()
            finally:
                try:
                    bank.close()
                finally:
                    save("fixture-model-requests.json", bank.models)
                    save("fixture-bank-reads.json", bank.reads)
                    if live_model is not None:
                        save("fixture-model-evidence.json", {"mode": "LIVE", "realModel": "LIVE",
                            "modelHttpEvidence": "EXTERNAL_LOOPBACK_RELAY", "modelInvocationStatus": "NOT_OBSERVED_BY_FIXTURE",
                            "fixtureModelRequestCount": len(bank.models)})


if __name__ == "__main__":
    main()
