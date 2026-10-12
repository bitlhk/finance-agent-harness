"""Private Launcher adapter over the existing admitted Workbench lifecycle.

Presets are server-owned, never request configuration. In addition to the manager
fields they contain owner, selection (with a published methodRef), modelRef,
modelMetadata {configVersion, parameters}, and businessDate (ISO date).
sourceFileDigests binds the original Eval bytes to that published method; its
opaque Eval artifact identity is NOT a Builder installation-tree digest.
With allowDrafts=True, selection.methodRef remains a published admission template:
only imported text/resources change; its source, capabilities and model stay fixed.
"""
from __future__ import annotations

import copy
from dataclasses import dataclass, field
import hashlib
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid

from .model_generation import validate_generation, generation_digest


class BackendError(RuntimeError):
    """Stable public error code; never includes paths or upstream exceptions."""


class PrepareError(BackendError):
    """Internal ownership transfer; only the safe code belongs on the wire."""
    def __init__(self, code, cleanup_handle):
        super().__init__(code)
        self.cleanup_handle = cleanup_handle


def require(value, code):
    if not value:
        raise BackendError(code)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def text(value):
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("output", "answer", "content", "response", "result"):
            found = text(value.get(key))
            if found:
                return found
    return ""


RUNTIMES = {"openjiuwen-java-0.1.15": ("openjiuwen", "0.1.15"),
            "agentscope-java-2.0.3": ("agentscope", "2.0.3")}
TEMPLATES = {"openjiuwen": "maven://com.openjiuwen:agent-core-java:0.1.15",
             "agentscope": "maven://io.agentscope:agentscope-core:2.0.3"}
BUILD_DIAGNOSTICS = ("build-report.json", "candidate/build-report.json", *(
    "logs/" + name + ".log" for name in ("harness_contracts", "harness_tests_install",
        "candidate_package", "component_installation", "observation_admission")))
DIAGNOSTIC_FILE_LIMIT = 256 * 1024
DIAGNOSTIC_TOTAL_LIMIT = 1024 * 1024


@dataclass
class Handle:
    request: dict
    preset: dict
    cancel_event: threading.Event
    build_id: str | None = None
    instance_id: str | None = None
    session_id: str | None = None
    service_lock: dict = field(default_factory=dict)
    artifact_id: str | None = None
    method: dict = field(default_factory=dict)
    selected_capabilities: set = field(default_factory=set)
    skill_digest: str | None = None
    business_time: dict = field(default_factory=dict)
    closed: bool = False
    lifecycle_lock: threading.RLock = field(default_factory=threading.RLock)
    execution_lock: threading.Lock = field(default_factory=threading.Lock)
    prepare_done: threading.Event = field(default_factory=threading.Event)
    retired: threading.Event = field(default_factory=threading.Event)
    reaper: threading.Thread | None = None
    cleanup_error: str | None = None
    candidate_snapshot: dict | None = None
    base_method: dict | None = None
    generation_binding: dict | None = None


class EvalLauncherBackend:
    def __init__(self, jobs, api=None, *, poll_seconds=0.05):
        if api is None:
            from .workbench_api import WorkbenchApi
            api = WorkbenchApi(jobs)
        self.jobs, self.api, self.poll_seconds = jobs, api, poll_seconds

    def _check(self, handle):
        require(not handle.closed and not handle.cancel_event.is_set(), "EVAL_CANCELLED")

    def prepare(self, request, preset, cancel_event):
        h = Handle(copy.deepcopy(request), copy.deepcopy(preset), cancel_event)
        try:
            self._check(h)
            self._validate(h)
            self._check(h)
            if h.candidate_snapshot is not None:
                state = self._create_candidate(h)
            else:
                options = {"generation_binding": h.generation_binding} if h.generation_binding is not None else {}
                code, state = self.api.post("/api/v1/builds", h.preset["selection"], h.preset["owner"], **options)
                require(code == 202, "EVAL_BUILD_REJECTED")
                h.build_id = state["buildId"]
            deadline = time.monotonic() + request["limits"]["timeoutMs"] / 1000
            while state["buildStatus"] in {"QUEUED", "RUNNING"}:
                self._check(h)
                require(time.monotonic() < deadline, "EVAL_PREPARE_TIMEOUT")
                cancel_event.wait(self.poll_seconds)
                state = self.api.inspect(h.build_id, h.preset["owner"])
            self._check(h)
            require(state["buildStatus"] == "SUCCEEDED", "EVAL_BUILD_FAILED")
            h.artifact_id = state["artifact"]["artifactId"]
            self._read_artifact(h)
            from .workbench import trusted_business_time_environment
            h.business_time = trusted_business_time_environment()
            require(h.business_time["FH_BUSINESS_DATE"] == preset["businessDate"], "EVAL_BUSINESS_DATE_CHANGED")
            code, started = self.api.post(f"/api/v1/builds/{h.build_id}/start",
                                         {"artifactId": h.artifact_id}, h.preset["owner"])
            require(code == 200, "EVAL_START_FAILED")
            h.instance_id = started["instance"]["instanceId"]
            require(started["instance"]["status"] == "READY" and h.instance_id, "EVAL_START_FAILED")
            self._check(h)
            require(trusted_business_time_environment() == h.business_time, "EVAL_BUSINESS_DATE_CHANGED")
            return h
        except Exception as exc:
            code = str(exc) if isinstance(exc, BackendError) else "EVAL_PREPARE_FAILED"
            try:
                self.close(h)
            except Exception:
                code = "EVAL_PREPARE_CLEANUP_FAILED"
            raise PrepareError(code, h if h.build_id else None) from None
        finally:
            h.prepare_done.set()

    def _validate(self, h):
        p, r = h.preset, h.request
        require(type(p.get("allowDrafts", False)) is bool, "EVAL_DRAFT_SETTING_INVALID")
        selection = p["selection"]
        runtime = RUNTIMES.get(selection.get("runtimeId"))
        require(runtime and p["runtime"] == {"type": runtime[0], "version": runtime[1]}, "EVAL_RUNTIME_UNSUPPORTED")
        require(selection.get("methodRef") and p.get("owner") and p.get("modelRef"), "EVAL_PRESET_INVALID")
        require(r["modelId"] == p["model"]["modelId"], "EVAL_MODEL_MISMATCH")
        if "generation" in r:
            h.generation_binding = {"modelRef": p["modelRef"], "parameters": validate_generation(r["generation"])}
        require(not r.get("configuration") and not r.get("secrets", {}).get("values"), "EVAL_CLIENT_CONFIGURATION_UNSUPPORTED")
        env = r.get("environment", {})
        require(not any(env.get(k) for k in ("commandScripts", "candidateMcpServices", "mcpServers", "runtimeToolConfig", "harnessToolNames")),
                "EVAL_EXECUTABLE_DEPENDENCY_UNSUPPORTED")
        verification = r["sourceVerification"]
        files = verification["sourceFiles"]
        actual = {name: sha(value.encode("utf-8")) for name, value in files.items()}
        require(actual and actual == verification["sourceFileDigests"], "EVAL_SOURCE_NOT_ADMITTED")
        if not p.get("allowDrafts"):
            require(actual == p["sourceFileDigests"], "EVAL_SOURCE_NOT_ADMITTED")
        require(verification["archiveSha256"] == r["skill"]["archiveSha256"], "EVAL_SOURCE_NOT_ADMITTED")
        require(r["variant"]["mode"] == "skill_version" and all(r["variant"][k] == r["skill"][k]
                for k in ("skillId", "version", "artifactSha256")), "EVAL_SKILL_MISMATCH")
        require(re.fullmatch(r"[a-f0-9]{64}", r["skill"]["artifactSha256"]), "EVAL_SKILL_MISMATCH")
        from .workbench_contracts import MethodReference
        method_reference = MethodReference.model_validate(selection["methodRef"]).reference()
        method = self.jobs.method_assets.release(p["owner"], method_reference)
        self.jobs.method_assets.check_source(method)
        if p.get("allowDrafts"):
            method = self._draft_snapshot(h, method)
            selection["methodRef"] = {key: method[key] for key in ("assetId", "version", "releaseDigest")}
            require(not selection.get("reuseFromBuildId"), "EVAL_DRAFT_REUSE_UNSUPPORTED")
        require(files == {"SKILL.md": method["data"]["content"], **method["data"]["resources"]},
                "EVAL_SOURCE_METHOD_MISMATCH")
        require(set(method["data"]["requiredResources"]) == set(method["data"]["resources"]),
                "EVAL_UNLOADED_SOURCE_RESOURCES")
        from .service import valid_capability_set
        require(valid_capability_set(method["data"]["capabilityIds"]), "EVAL_QUERY_ONLY_REQUIRED")
        plan = self._selection_plan(selection, method)
        capabilities = plan["capabilities"]
        require(capabilities and all(c.get("operation") == "QUERY" for c in capabilities), "EVAL_QUERY_ONLY_REQUIRED")
        h.selected_capabilities = {c["capabilityId"] for c in capabilities}
        require(len(h.selected_capabilities) == len(capabilities)
                and h.selected_capabilities == set(selection["capabilityIds"]), "EVAL_SELECTION_CAPABILITIES_MISMATCH")
        from .method_assets import runtime_files, installation_digest
        runtime = runtime_files(method["assetId"], method["version"], method["data"])
        require(installation_digest(runtime) == method["installationDigest"], "EVAL_METHOD_CHANGED")
        h.method = {k: method[k] for k in ("assetId", "version", "releaseDigest", "installationDigest")}
        h.skill_digest = "sha256:" + sha(runtime[f"skills/{method['assetId']}/SKILL.md"])
        require(isinstance(p["modelMetadata"]["parameters"], dict) and p["modelMetadata"]["configVersion"], "EVAL_MODEL_METADATA_REQUIRED")

    def _draft_snapshot(self, h, template):
        from .method_assets import canonical, sha as asset_sha, parse_content, validate_content, runtime_files, installation_digest
        store = self.jobs.method_assets
        store.require_active(h.preset["owner"], template["assetId"])
        preview = store.import_asset(h.preset["owner"], "candidate.zip", h.request["skill"]["archiveBase64"],
                                     template["data"]["name"], preview=True)
        files = {"SKILL.md": preview["data"]["content"], **preview["data"]["resources"]}
        require(files == h.request["sourceVerification"]["sourceFiles"]
                and preview["source"]["sha256"] == "sha256:" + h.request["sourceVerification"]["archiveSha256"],
                "EVAL_SOURCE_NOT_ADMITTED")
        # Client Markdown is content, never authority for capability/source/model selection.
        data = parse_content({**copy.deepcopy(template["data"]), "content": preview["data"]["content"],
                              "resources": preview["data"]["resources"], "requiredResources": sorted(preview["data"]["resources"])})
        require(validate_content(data)["status"] == "VALID", "EVAL_DRAFT_CONTENT_INVALID")
        require(isinstance(template.get("source"), dict) and template["source"].get("kind"), "EVAL_DRAFT_SOURCE_REQUIRED")
        reference = {k: template[k] for k in ("assetId", "version", "releaseDigest")}
        snapshot = {"schemaVersion": "finance-eval-execution-candidate.v1", "assetId": "method-" + uuid.uuid4().hex[:16],
                    "version": "0.0.0", "data": data, "source": copy.deepcopy(template["source"]),
                    "contentDigest": asset_sha(canonical(data)), "baseMethodRef": reference,
                    "admissionKind": "EXECUTION_CANDIDATE", "publicationStatus": "NOT_PUBLISHED",
                    "importSource": preview["source"]}
        store.check_source(snapshot)
        snapshot["installationDigest"] = installation_digest(runtime_files(snapshot["assetId"], "0.0.0", data))
        snapshot["releaseDigest"] = asset_sha(canonical(snapshot))
        h.candidate_snapshot, h.base_method = snapshot, reference
        return snapshot

    def _create_candidate(self, h):
        from .workbench_contracts import parse_selection, preflight
        selected = parse_selection(h.preset["selection"])
        result = preflight(selected, self.jobs.project, self.jobs.builder.harness_repo,
                           self.jobs.builder.maven, method_snapshot=h.candidate_snapshot)
        require(result["canBuild"], "EVAL_BUILD_REJECTED")
        self._check(h)
        view = _ExecutionCandidateJobs(self.jobs, h.preset["owner"], h.candidate_snapshot,
                                       h.base_method, h.cancel_event)
        options = {"generation_binding": h.generation_binding} if h.generation_binding is not None else {}
        job = view.create(selected.legacy(), h.preset["owner"], selection=selected.model_dump(),
                          model_snapshot=result.get("modelSelection"), directory_snapshot=result.get("directorySelection"),
                          method_snapshot=copy.deepcopy(h.candidate_snapshot), **options)
        h.build_id = job["id"]
        return self.api.inspect(h.build_id, h.preset["owner"])

    def _selection_plan(self, selection, method):
        from .workbench_contracts import parse_selection, UnifiedWorkbenchSelection
        from .component_plan import qualify_solution
        from .method_assets import runtime_reference
        from .service import solution_for
        selected = parse_selection(selection)
        if isinstance(selected, UnifiedWorkbenchSelection):
            _, _, plan = selected.resolve_details(self.jobs.project, self.jobs.builder.harness_repo,
                                                   "eval-readonly-preflight", method)
            return plan
        solution = solution_for(selected.capabilityIds, "eval-readonly-preflight",
                                runtime_reference(method), method["data"]["capabilityIds"])
        return qualify_solution(self.jobs.project, self.jobs.builder.harness_repo, solution, method)

    def _read_artifact(self, h):
        file = self.jobs.path(h.build_id) / "candidate/service-lock.json"
        require(not file.is_symlink(), "EVAL_ARTIFACT_CHANGED")
        raw = file.read_bytes()
        require("sha256:" + sha(raw) == h.artifact_id, "EVAL_ARTIFACT_CHANGED")
        lock = json.loads(raw)
        runtime = RUNTIMES[h.preset["selection"]["runtimeId"]]
        require(lock["buildId"] == h.build_id and lock["verification"] == "COMPILED", "EVAL_ARTIFACT_CHANGED")
        require(lock["runtime"]["family"] == runtime[0] and lock["runtime"]["language"] == "java"
                and lock["runtime"]["templateRef"] == TEMPLATES[runtime[0]], "EVAL_RUNTIME_MISMATCH")
        from .service import valid_capability_set
        require(valid_capability_set(lock["capabilities"]), "EVAL_QUERY_ONLY_REQUIRED")
        require(set(lock["capabilities"]) == h.selected_capabilities, "EVAL_SELECTION_CAPABILITIES_MISMATCH")
        require(all(lock.get("method", {}).get(k) == v for k, v in h.method.items()), "EVAL_METHOD_CHANGED")
        connections = lock["modelSelection"]["connections"]
        require(lock["modelSelection"].get("modelGeneration") == h.generation_binding, "EVAL_GENERATION_LOCK_MISMATCH")
        connection = next((c for c in connections if c["modelRef"] == h.preset["modelRef"]), {})
        require(connection.get("modelName") == h.preset["model"]["upstreamModelId"]
                and connection.get("configVersion") == h.preset["modelMetadata"]["configVersion"], "EVAL_MODEL_MISMATCH")
        h.service_lock = lock

    def close(self, h):
        # Independent of execution_lock: DELETE must be able to interrupt talk().
        h.cancel_event.set()
        with h.lifecycle_lock:
            h.closed = True
            if h.build_id and not h.retired.is_set() and (h.reaper is None or not h.reaper.is_alive()):
                h.cleanup_error = None
                h.reaper = threading.Thread(target=self._retire, args=(h,), name="eval-build-reaper", daemon=True)
                h.reaper.start()
            if not h.instance_id:
                return
            with self.jobs.lock:
                if h.build_id not in self.jobs.jobs:
                    return
                job = self.jobs.owned(h.build_id, h.preset["owner"])
                require(job.get("instanceId") == h.instance_id, "EVAL_INSTANCE_CHANGED")
                if h.build_id not in self.jobs.processes:
                    return
            try:
                self.jobs.stop(h.build_id, h.preset["owner"], expected_instance=h.instance_id)
            except Exception:
                raise BackendError("EVAL_CLEANUP_FAILED") from None

    def _retire(self, h):
        # Jobs cannot cancel one build. Do not set its shared service_cancel.
        h.prepare_done.wait()
        while True:
            try:
                with h.lifecycle_lock:
                    with self.jobs.lock:
                        if h.build_id not in self.jobs.jobs:
                            h.retired.set()
                            return
                        job = self.jobs.owned(h.build_id, h.preset["owner"])
                        busy = job["status"] in {"QUEUED", "BUILDING", "STARTING", "STOPPING"}
                        has_process = h.build_id in self.jobs.processes
                    if not busy:
                        if has_process:
                            require(h.instance_id and job.get("instanceId") == h.instance_id, "EVAL_INSTANCE_CHANGED")
                            self.jobs.stop(h.build_id, h.preset["owner"], expected_instance=h.instance_id)
                        self._retain_build_diagnostics(h, job)
                        self.jobs.delete(h.build_id, h.preset["owner"])
                        h.retired.set()
                        return
                time.sleep(self.poll_seconds)
            except Exception:
                h.cleanup_error = "EVAL_BUILD_RETIRE_FAILED"
                return

    def _retain_build_diagnostics(self, h, job):
        from .method_assets import write_private
        from finance_builder_base.runtime import require_path_within
        root = self.jobs.data_dir / "eval-launcher-evidence"
        directory = root / sha(h.preset["owner"].encode()) / h.build_id / "retainedEvidence"
        target = directory / "build-diagnostics.json"
        require_path_within(target, self.jobs.data_dir)
        for path in (root, directory.parent.parent, directory.parent, directory, target):
            require(not path.is_symlink(), "EVAL_EVIDENCE_PATH_INVALID")
        source = self.jobs.path(h.build_id)
        require_path_within(source, self.jobs.data_dir)
        require(not source.is_symlink(), "EVAL_EVIDENCE_PATH_INVALID")
        secrets = [v for k, v in os.environ.items()
                   if re.search(r"KEY|SECRET|TOKEN|PASSWORD|CREDENTIAL", k, re.I) and len(v) >= 4]

        def redact(value):
            for secret in sorted(secrets, key=len, reverse=True):
                value = value.replace(secret, "[REDACTED]")
            # Drop whole sensitive lines, including request dumps, rather than just values.
            return "\n".join("[REDACTED LINE]" if re.search(
                r"authorization|bearer\s|api[_-]?key|password|secret|token\s*[=:]|"
                r"request\s*(body|payload)|\b(messages|prompt|query)\s*[\"']?\s*[:=]|"
                r"https?://[^\s/]+@|[?&][^\s=]+=", line, re.I) else line
                for line in value.splitlines())

        records, remaining = [], DIAGNOSTIC_TOTAL_LIMIT
        for relative in BUILD_DIAGNOSTICS:
            path = source / relative
            if any(p.is_symlink() for p in (path.parent, path)):
                records.append({"path": relative, "omitted": "SYMLINK"})
                continue
            if not path.is_file():
                records.append({"path": relative, "omitted": "NOT_PRODUCED"})
                continue
            limit = min(DIAGNOSTIC_FILE_LIMIT, remaining)
            with path.open("rb") as stream:
                raw = stream.read(limit + 1)
            truncated = len(raw) > limit
            raw = raw[:limit]
            remaining -= len(raw)
            content = raw.decode("utf-8", errors="replace")
            if relative.endswith(".json"):
                # Report commands/configuration are not part of the diagnostic contract.
                try:
                    report = json.loads(content)
                    fields = ("schemaVersion", "buildId", "solutionId", "status", "lockSha256")
                    projected = {k: report[k] for k in fields if isinstance(report.get(k), (str, int, bool))}
                    projected = {k: redact(v) if isinstance(v, str) else v for k, v in projected.items()}
                    projected["stages"] = [{k: s[k] for k in
                        ("name", "status", "detail", "log", "logSha256", "exitCode", "elapsedMillis", "durationMs")
                        if isinstance(s.get(k), (str, int, float, bool))}
                        for s in report.get("stages", [])[:100] if isinstance(s, dict)]
                    for stage in projected["stages"]:
                        for key, value in stage.items():
                            if isinstance(value, str):
                                stage[key] = redact(value)
                    content = json.dumps(projected, ensure_ascii=True)
                except (ValueError, TypeError, AttributeError):
                    content = "[REPORT INVALID OR EXCEEDS LIMIT]"
            records.append({"path": relative, "capturedBytes": len(raw), "truncated": truncated,
                            "capturedSourceSha256": sha(raw),
                            "content": content if relative.endswith(".json") else redact(content)})
        record = {"schemaVersion": "finance-eval-build-diagnostics.v1", "buildId": h.build_id,
                  "instanceId": h.instance_id, "status": job["status"], "files": records,
                  "contentPolicy": "WHITELISTED_REDACTED_NOT_RAW", "sourceByteLimit": DIAGNOSTIC_TOTAL_LIMIT}
        for path in (root, directory.parent.parent, directory.parent, directory):
            path.mkdir(mode=0o700, exist_ok=True)
            path.chmod(0o700)
        write_private(target, record)

    def _talk(self, h, action, body, deadline):
        self._check(h)
        done, reply = threading.Event(), []
        def invoke():
            try:
                reply.append(self.jobs.talk(h.build_id, h.preset["owner"], action, body,
                    expected_instance=h.instance_id, expected_session=h.session_id))
            except Exception:
                reply.append((500, {}))
            finally:
                done.set()
        thread = threading.Thread(target=invoke, name="eval-case-host", daemon=True)
        thread.start()
        while not done.wait(self.poll_seconds):
            if h.closed or h.cancel_event.is_set() or time.monotonic() >= deadline:
                self.close(h)
                raise BackendError("EVAL_CASE_INTERRUPTED")
        self._check(h)
        require(time.monotonic() < deadline, "EVAL_CASE_TIMEOUT")
        code, result = reply[0]
        require(code == 200 and isinstance(result, dict), "EVAL_HOST_REJECTED")
        return result

    def _capabilities(self, h, installations):
        capabilities = [c for pack in installations for c in pack.get("capabilities", [])]
        require(capabilities and all(c.get("operation") == "QUERY" for c in capabilities), "EVAL_QUERY_ONLY_REQUIRED")
        mapping = {c["capabilityId"]: c["toolName"] for c in capabilities}
        require(len(mapping) == len(capabilities) and set(mapping) == set(h.service_lock["capabilities"])
                and set(mapping.values()) == set(h.preset["toolNames"]), "EVAL_TOOLS_MISMATCH")
        return mapping

    def _observed(self, h, result):
        if h.generation_binding is not None:
            require(result.get("modelGeneration") == h.generation_binding
                    and result.get("modelGenerationSha256") == generation_digest(h.generation_binding),
                    "EVAL_GENERATION_RESPONSE_MISMATCH")
        require(result.get("sessionId") == h.session_id and result.get("modelRef") == h.preset["modelRef"]
                and result.get("modelName") == h.preset["model"]["upstreamModelId"]
                and result.get("modelConfigVersion") == h.preset["modelMetadata"]["configVersion"], "EVAL_RESPONSE_IDENTITY_MISMATCH")
        lock = result["runtimeLock"]
        require(lock["solutionId"] == h.build_id, "EVAL_RUNTIME_MISMATCH")
        runtime = h.service_lock["runtime"]
        require(all(lock["runtime"].get(k) == runtime.get(k) for k in ("family", "language", "templateRef", "integrationRef")), "EVAL_RUNTIME_MISMATCH")
        mapping = self._capabilities(h, lock["installations"])
        require(any(a.get("id") == h.method["assetId"] and a.get("version") == h.method["version"]
                    and a.get("digest") == h.method["installationDigest"] for a in lock.get("assets", [])),
                "EVAL_LOADED_METHOD_MISMATCH")
        packs = [p for p in lock["domainInstallations"] if p["packId"] == h.method["assetId"]
                 and p["packVersion"] == h.method["version"]]
        require(len(packs) == 1 and any(s["digest"] == h.skill_digest and s["directory"] == h.method["assetId"]
                for s in packs[0]["skills"]), "EVAL_LOADED_SKILL_MISMATCH")
        return lock, mapping

    def _retain(self, h, run_id, lock, metadata):
        from .method_assets import canonical, write_private
        from finance_builder_base.runtime import require_path_within
        scope = sha(h.preset["owner"].encode())
        root = self.jobs.data_dir / "eval-launcher-evidence"
        file = root / scope / h.build_id / (run_id + ".json")
        require_path_within(file, self.jobs.data_dir)
        require(not any(path.is_symlink() for path in (root, root / scope, file.parent, file)), "EVAL_EVIDENCE_PATH_INVALID")
        record = {"schemaVersion": "finance-eval-builder-evidence.v1", "buildId": h.build_id,
                  "instanceId": h.instance_id, "sessionId": h.session_id, "runId": run_id,
                  "requestId": metadata["requestId"], "artifactLockSha256": metadata["artifactLockSha256"],
                  "serviceLock": h.service_lock, "runtimeLock": lock, "metadata": copy.deepcopy(metadata)}
        if h.candidate_snapshot is not None:
            record["executionCandidate"] = copy.deepcopy(h.candidate_snapshot)
        write_private(file, record)
        return {"collection": "eval-launcher-evidence", "ownerScope": scope, "buildId": h.build_id,
                "runId": run_id, "sha256": sha(canonical(record))}

    def _restart_for_case(self, h, deadline):
        with h.lifecycle_lock:
            self._check(h)
            require(time.monotonic() < deadline, "EVAL_CASE_TIMEOUT")
            old = h.instance_id
            self._read_artifact(h)
            self.jobs.stop(h.build_id, h.preset["owner"], expected_instance=old)
            h.session_id = None
            self._check(h)
            require(time.monotonic() < deadline, "EVAL_CASE_TIMEOUT")
            # Jobs.start has no expected-old-generation argument. Keep its RLock
            # through start/inspection so no other caller can replace our generation.
            with self.jobs.lock:
                job = self.jobs.owned(h.build_id, h.preset["owner"])
                require(job.get("instanceId") == old and job["status"] == "STOPPED"
                        and h.build_id not in self.jobs.processes, "EVAL_INSTANCE_CHANGED")
                self._read_artifact(h)
                try:
                    code, started = self.api.post(f"/api/v1/builds/{h.build_id}/start",
                        {"artifactId": h.artifact_id}, h.preset["owner"])
                finally:
                    # Even an inspection error after launch must retain the new
                    # cleanup target. The exclusive Jobs lock establishes ownership.
                    current = self.jobs.owned(h.build_id, h.preset["owner"])
                    if h.build_id in self.jobs.processes and current.get("instanceId"):
                        h.instance_id = current["instanceId"]
                require(code == 200 and started.get("instance", {}).get("status") == "READY",
                        "EVAL_START_FAILED")
                require(h.instance_id != old and started["instance"].get("instanceId") == h.instance_id,
                        "EVAL_INSTANCE_CHANGED")
            self._check(h)
            require(time.monotonic() < deadline, "EVAL_CASE_TIMEOUT")
            self._read_artifact(h)
            from .workbench import trusted_business_time_environment
            require(trusted_business_time_environment() == h.business_time, "EVAL_BUSINESS_DATE_CHANGED")

    def execute(self, h, case, timeout_ms):
        require(type(timeout_ms) is int and timeout_ms > 0, "EVAL_TIMEOUT_INVALID")
        began = time.monotonic()
        deadline = began + timeout_ms / 1000
        isolation_restart_ms = 0
        while not h.execution_lock.acquire(timeout=min(self.poll_seconds, max(0, deadline - time.monotonic()))):
            self._check(h)
            require(time.monotonic() < deadline, "EVAL_CASE_QUEUE_TIMEOUT")
        evidence = {"status": "failed", "modelId": h.preset["model"]["modelId"], "skillVersion": None,
                    "output": "", "elapsedMs": 0, "toolCalls": [], "loadedSkills": [], "usage": None,
                    "runtimeIdentityVerified": False, "streamCompleted": False, "error": None, "metadata": {}}
        try:
            self._check(h)
            require(isinstance(case.get("query"), str) and 0 < len(case["query"]) <= 4000, "EVAL_QUERY_INVALID")
            require(set(case.get("requiredTools", [])) <= set(h.preset["toolNames"]), "EVAL_TOOLS_MISMATCH")
            self._read_artifact(h)
            old = h.session_id
            if old:
                restarting = time.monotonic()
                try:
                    self._restart_for_case(h, deadline)
                finally:
                    isolation_restart_ms = max(0, int((time.monotonic() - restarting) * 1000))
            session = self._talk(h, "session", {"modelRef": h.preset["modelRef"]}, deadline)
            require(session.get("sessionId") and session["sessionId"] != old
                    and session.get("buildId") == h.build_id and session.get("instanceId") == h.instance_id
                    and session.get("modelRef") == h.preset["modelRef"]
                    and session.get("modelConfigVersion") == h.preset["modelMetadata"]["configVersion"], "EVAL_SESSION_MISMATCH")
            h.session_id = session["sessionId"]
            self._capabilities(h, session["installations"])
            run_id = "eval-" + uuid.uuid4().hex
            result = self._talk(h, "chat", {"query": case["query"], "runId": run_id}, deadline)
            lock, mapping = self._observed(h, result)
            output = text(result.get("response"))
            require(output, "EVAL_RESPONSE_TEXT_MISSING")
            calls, outcomes = [], []
            for item in result.get("toolResults", []):
                data = item.get("data", {}) if isinstance(item, dict) else {}
                capability = data.get("capabilityId")
                if capability in mapping:
                    status = data.get("resultStatus", "NOT_REPORTED")
                    require(isinstance(status, str) and re.fullmatch(r"[A-Z_]{1,64}", status), "EVAL_TOOL_STATUS_INVALID")
                    calls.append({"name": mapping[capability], "status": "completed" if status == "COMPLETED" else "failed"})
                    outcomes.append({"name": mapping[capability], "capabilityId": capability, "resultStatus": status})
            observation = result.get("executionObservation") or {}
            request_id = observation.get("requestId")
            require(request_id is None or isinstance(request_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", request_id),
                    "EVAL_REQUEST_ID_INVALID")
            skill = h.request["skill"]
            evidence.update(status="completed", output=output, toolCalls=calls, skillVersion=skill["version"],
                loadedSkills=[{"skillId": skill["skillId"], "version": skill["version"]}],
                runtimeIdentityVerified=True, streamCompleted=True,
                metadata={"buildId": h.build_id, "instanceId": h.instance_id, "runId": run_id, "requestId": request_id,
                    "isolationMode": "FRESH_HOST_SESSION", "isolationRestartMs": isolation_restart_ms,
                    "executionHistoryRequestId": request_id, "toolOutcomes": outcomes, "businessOutcome": "NOT_ASSESSED",
                    "archiveStatus": observation.get("archiveStatus") if observation.get("archiveStatus") in {"RECORDING", "DEGRADED", "DISABLED"} else "NOT_REPORTED",
                    "skillArtifactSha256": skill["artifactSha256"],
                    "skillIdentityBasis": "IMPORTED_SOURCE_BYTES_TO_EXECUTION_CANDIDATE" if h.candidate_snapshot else "ADMITTED_SOURCE_BYTES_TO_PUBLISHED_METHOD",
                    "methodKind": "EXECUTION_CANDIDATE" if h.candidate_snapshot else "PUBLISHED_METHOD",
                    "publicationStatus": "NOT_PUBLISHED" if h.candidate_snapshot else "PUBLISHED",
                    "archiveSha256": h.request["sourceVerification"]["archiveSha256"],
                    "methodDigest": h.method["releaseDigest"], "installationDigest": h.method["installationDigest"],
                    "loadedSkillDigest": h.skill_digest, "artifactLockSha256": h.artifact_id.removeprefix("sha256:"),
                    "runtimeLockSha256": sha(json.dumps(lock, sort_keys=True, separators=(",", ":")).encode()),
                    "runtime": h.preset["runtime"], "modelRef": result["modelRef"], "upstreamModelId": result["modelName"],
                    "modelConfigVersion": result["modelConfigVersion"], "declaredModelParameters": h.preset["modelMetadata"]["parameters"],
                    "modelParametersVerified": False, "modelParametersBasis": "SERVER_DECLARATION_NOT_WIRE_OBSERVATION",
                    "businessDate": h.business_time["FH_BUSINESS_DATE"], "businessTimeZone": h.business_time["FH_BUSINESS_TIME_ZONE"],
                    "businessDateBasis": h.business_time["FH_BUSINESS_DATE_SOURCE"], "sessionId": h.session_id,
                    "evidenceKind": result.get("evidenceKind", "NOT_REPORTED")})
            if h.candidate_snapshot is not None:
                evidence["metadata"].update(baseMethodRef=h.base_method,
                    executionCandidateRef={k: h.candidate_snapshot[k] for k in ("assetId", "version", "contentDigest", "installationDigest")})
            if h.generation_binding is not None:
                evidence["metadata"].update(
                    declaredModelParameters={k: v for k, v in h.generation_binding["parameters"].items() if k != "schemaVersion"},
                    modelGeneration=copy.deepcopy(h.generation_binding), modelGenerationSha256=generation_digest(h.generation_binding),
                    modelGenerationHostVerified=True, modelParametersBasis="LOCKED_HOST_CONFIGURATION_NOT_WIRE_OBSERVATION")
            self._check(h)
            evidence["metadata"]["retainedEvidenceRef"] = self._retain(h, run_id, lock, evidence["metadata"])
            self._check(h)
        except Exception as exc:
            evidence.update(status="failed", output="", toolCalls=[], loadedSkills=[], skillVersion=None,
                            runtimeIdentityVerified=False, streamCompleted=False,
                            metadata={"isolationMode": "FRESH_HOST_SESSION", "isolationRestartMs": isolation_restart_ms},
                            error=str(exc) if isinstance(exc, BackendError) else "EVAL_EXECUTION_FAILED")
            try:
                self.close(h)
            except BackendError:
                evidence["metadata"]["cleanupError"] = "EVAL_CLEANUP_FAILED"
        finally:
            evidence["elapsedMs"] = max(0, int((time.monotonic() - began) * 1000))
            h.execution_lock.release()
        return evidence


class BuilderBackend(EvalLauncherBackend):
    """CLI constructor. Use a dedicated Jobs store with provisioned releases."""
    def __init__(self, project, harness, data_dir, maven="mvn", max_instances=2):
        from .workbench import Jobs
        require(type(max_instances) is int and max_instances > 0, "EVAL_INSTANCE_LIMIT_INVALID")
        jobs = Jobs(Path(project), Path(harness), maven=maven,
                    data_dir=Path(data_dir), max_instances=max_instances, protect_disk=True)
        super().__init__(jobs)


class _ExecutionCandidateMethods:
    """Exact, owner-bound snapshot resolver only inside a private Jobs view.

The release() name implements Jobs' existing lookup protocol; it does not write
or expose a MethodAssets release. Public Workbench still uses the original store.
"""
    def __init__(self, store, owner, snapshot, base_reference, cancel):
        self.store, self.owner = store, owner
        self.snapshot, self.base_reference = copy.deepcopy(snapshot), copy.deepcopy(base_reference)
        self.cancel = cancel

    def _check(self):
        from .method_assets import canonical, sha as asset_sha
        require(not self.cancel.is_set(), "EVAL_CANCELLED")
        self.store.require_active(self.owner, self.base_reference["assetId"])
        self.store.check_source(self.store.release(self.owner, self.base_reference))
        unsigned = {k: v for k, v in self.snapshot.items() if k != "releaseDigest"}
        require(asset_sha(canonical(unsigned)) == self.snapshot["releaseDigest"], "EVAL_CANDIDATE_CHANGED")
        self.store.check_source(self.snapshot)

    def release(self, owner, reference):
        require(owner == self.owner and reference == {k: self.snapshot[k] for k in ("assetId", "version", "releaseDigest")},
                "EVAL_CANDIDATE_REFERENCE_MISMATCH")
        self._check()
        return copy.deepcopy(self.snapshot)

    def require_active(self, owner, asset_id):
        require(owner == self.owner and asset_id == self.snapshot["assetId"], "EVAL_CANDIDATE_REFERENCE_MISMATCH")
        self._check()
        return copy.deepcopy(self.snapshot)

    def check_source(self, snapshot, **kwargs):
        require(snapshot == self.snapshot, "EVAL_CANDIDATE_CHANGED")
        self._check()


class _ExecutionCandidateJobs:
    """Reuse Jobs lifecycle without replacing shared methods or publication gates."""
    def __init__(self, jobs, owner, snapshot, base_reference, cancel):
        self._jobs = jobs
        self.method_assets = _ExecutionCandidateMethods(jobs.method_assets, owner, snapshot, base_reference, cancel)

    def __getattr__(self, name):
        return getattr(self._jobs, name)

    def create(self, *args, **kwargs):
        from .workbench import Jobs
        return Jobs.create(self, *args, **kwargs)

    def build(self, job_id):
        from .workbench import Jobs
        return Jobs.build(self, job_id)

    def save(self, job):
        snapshot = self.method_assets.snapshot
        require(job.get("owner") == self.method_assets.owner and job.get("methodRef") == {
            k: snapshot[k] for k in ("assetId", "version", "releaseDigest")}, "EVAL_CANDIDATE_REFERENCE_MISMATCH")
        job["executionCandidate"] = {"admissionKind": "EXECUTION_CANDIDATE", "publicationStatus": "NOT_PUBLISHED",
            "baseMethodRef": copy.deepcopy(self.method_assets.base_reference), "contentDigest": snapshot["contentDigest"]}
        self._jobs.save(job)
