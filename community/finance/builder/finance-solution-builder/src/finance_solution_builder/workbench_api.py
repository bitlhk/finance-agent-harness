"""Versioned, owner-scoped facade over the existing build and instance implementation."""
from __future__ import annotations

import re
import json
import uuid

from .workbench_contracts import (
    API_VERSION, ApiError, artifact_identity, catalog, connection_status,
    default_selection, parse_selection, parse_saved_selection, preflight, stage_view,
    MultiModelSelection, DirectorySelection, TemplateModuleSelection,
    InstalledRuntimeSelection,
    MethodSelection,
    ManagedWorkbenchSelection,
    UnifiedWorkbenchSelection,
    start_readiness,
)
from .model_registry import ModelRegistry, ModelRegistryError
from .workbench_projection import OptimizationViewProjector
from .task_routes import TASK_ACTION_FIELDS, validate_task_body


def exact_body(body, fields):
    if not isinstance(body, dict) or set(body) != set(fields): raise ApiError("REQUEST_INVALID")
    if any(not isinstance(body[field], str) or not body[field].strip() for field in fields):
        raise ApiError("REQUEST_INVALID")


def frozen_runtime_presentation(job):
    value = job.get("runtimePresentation")
    if not isinstance(value, dict) or value.get("runtimeId") != job.get("selection", {}).get("runtimeId"):
        return None
    names = ("runtimeId", "label", "language", "version", "architectureLabel", "description")
    result = {key: value[key] for key in names if isinstance(value.get(key), str)
              and value[key].strip() and len(value[key]) <= 200}
    return result if "runtimeId" in result and "label" in result else None


def public_error(error):
    from .asset_identity import AssetIdentityError
    from .component_assembly import AssemblyRejected
    from .managed_runtime_support import SAFE_ERRORS
    if isinstance(error, AssetIdentityError):
        return error.status, {"code": error.code, "message": error.message,
                              "locations": error.locations, "retryable": False}
    if isinstance(error, ApiError): return error.status, {"code": error.code, "retryable": False}
    if isinstance(error, AssemblyRejected) and str(error) in SAFE_ERRORS:
        return 409, {"code": str(error), "retryable": False}
    if isinstance(error, ValueError) and str(error) in {
        "BUSINESS_TIME_CONFIGURATION_INCOMPLETE", "BUSINESS_TIME_CONFIGURATION_INVALID",
    }:
        return 503, {"code": str(error), "retryable": False}
    if isinstance(error, ValueError) and str(error) in {"INVALID_HISTORY_CURSOR", "INVALID_HISTORY_FILTER", "INVALID_HISTORY_LIMIT", "INVALID_SNAPSHOT_REFERENCE"}:
        return 400, {"code": str(error), "retryable": False}
    if isinstance(error, ModelRegistryError): return 409, {"code": str(error), "retryable": False}
    if isinstance(error, PermissionError):
        code = {"SNAPSHOT_VIEW_NOT_GRANTED": "SNAPSHOT_VIEW_NOT_GRANTED",
                "SNAPSHOT_UNAVAILABLE": "SNAPSHOT_READ_REJECTED"}.get(str(error), "ACCESS_REJECTED")
        return 403, {"code": code, "retryable": False}
    allowed = {"INSTANCE_CHANGED", "INSTANCE_START_FAILED", "INSTANCE_STOP_FAILED", "BUILD_QUEUE_FULL",
               "WORKBENCH_STOPPING", "BUILD_OR_STOP_REQUIRED", "NO_OWNED_PROCESS", "SESSION_ALREADY_ACTIVE",
               "CANDIDATE_JAR_MISSING", "BUILD_LOCK_MISMATCH", "ARTIFACT_CHANGED",
               "AGENT_STOP_REQUIRED", "DELETE_PATH_INVALID", "AGENT_DELETE_FAILED", "INSTANCE_LIMIT_REACHED",
               "BUILD_RETENTION_LIMIT_REACHED", "INSUFFICIENT_BUILD_STORAGE", "TASK_RECOVERY_STATE_RETAINED",
               "TASK_STATE_IN_USE",
               "SIMULATOR_TASK_CONFIGURATION_REQUIRED", "HOST_OPERATION_UNSUPPORTED"}
    code = str(error) if str(error) in allowed else "OPERATION_FAILED"
    status = 409 if code in {"INSTANCE_CHANGED", "ARTIFACT_CHANGED", "SESSION_ALREADY_ACTIVE", "BUILD_OR_STOP_REQUIRED", "AGENT_STOP_REQUIRED", "TASK_RECOVERY_STATE_RETAINED", "TASK_STATE_IN_USE"} else 503
    return status, {"code": code, "retryable": code in {"BUILD_QUEUE_FULL", "INSTANCE_CHANGED"}}


class WorkbenchApi:
    def __init__(self, jobs):
        self.jobs = jobs
        from .method_api import MethodApi
        self.methods = MethodApi(jobs)

    def inspect(self, build_id, owner, *, expected_instance=None, expected_status=None):
        with self.jobs.lock:
            job = self.jobs.owned(build_id, owner)
            old = self.jobs.public(job)
            if expected_instance is not None and old.get("instanceId") != expected_instance:
                raise ApiError("INSTANCE_CHANGED", 409)
            if expected_status is not None and old["status"] != expected_status:
                raise ApiError("INSTANCE_CHANGED", 409)
            report = old.get("report", {})
            artifact = artifact_identity(self.jobs.path(build_id) / "candidate", report, build_id)
            build_status = {"BUILDING": "RUNNING", "QUEUED": "QUEUED", "INTERRUPTED": "INTERRUPTED"}.get(old["status"])
            if build_status is None:
                if report.get("status") == "BUILT": build_status = "SUCCEEDED" if artifact else "INVALID_ARTIFACT"
                elif report.get("status") == "NOT_RUN": build_status = "NOT_RUN"
                elif report.get("status") == "FAILED": build_status = "FAILED"
                else: build_status = "FAILED" if old["status"] == "FAILED" else "UNKNOWN"
            running = old["status"] == "RUNNING"
            instance_id = old.get("instanceId") if running or old["status"] in {"STOPPING", "STOPPED", "FAILED"} else None
            instance_status = {"RUNNING": "READY", "STARTING": "STARTING", "STOPPING": "STOPPING",
                               "STOPPED": "STOPPED", "UNMANAGED_AFTER_RESTART": "UNMANAGED"}.get(old["status"], "NOT_STARTED")
            if old["status"] == "FAILED" and report.get("status") == "BUILT": instance_status = "FAILED"
            instance = {"instanceId": instance_id, "status": instance_status,
                        "artifactId": artifact["artifactId"] if artifact else None,
                        "sessionId": self.jobs.sessions.get(build_id) if running else None}
            if old.get("activeModelRef"): instance["modelRef"] = old["activeModelRef"]
            error = None
            if build_status == "INVALID_ARTIFACT": error = {"code": "ARTIFACT_ASSOCIATION_INVALID", "retryable": False}
            elif old.get("error"):
                allowed = {"BUILD_FAILED_CHECK_STAGES", "INSTANCE_EXITED", "INSTANCE_START_FAILED", "INSTANCE_STOP_FAILED", "MODEL_OR_INSTANCE_TIMEOUT"}
                error = {"code": old["error"] if old["error"] in allowed else "OPERATION_FAILED", "retryable": False}
            sources = {}
            for component in ("builder", "harness"):
                item = report.get("sources", {}).get(component, {})
                commit = item.get("commit")
                if isinstance(commit, str) and re.fullmatch(r"[0-9a-f]{40}", commit):
                    sources[component] = {"commit": commit, "worktreeDirty": item.get("worktreeDirty") is not False}
            installation = {"status": "NOT_OBSERVED"}
            evidence_root = self.jobs.path(build_id) / "candidate/runtime-evidence"
            if not evidence_root.is_symlink():
                records = sorted(evidence_root.glob("*/runtime-lock.json"), key=lambda p: p.stat().st_mtime, reverse=True)
                for file in records:
                    if file.is_symlink() or file.parent.is_symlink(): continue
                    try:
                        observed = json.loads(file.read_text())
                        if observed.get("solutionId") == build_id:
                            installation = {"status": "RECORDED_FOR_ARTIFACT", "lock": observed,
                                            "currentInstanceClaim": False}
                            break
                        bridge = observed.get("javaBridge", {})
                        if (old.get("selection", {}).get("runtimeId") == "openjiuwen-python-0.1.18"
                                and observed.get("runtime") == "openjiuwen-python-0.1.18"
                                and observed.get("registrationStatus") == "RUNTIME_REGISTERED"
                                and bridge.get("schemaVersion") in {"finance-installation-plan.v0.1", "finance-installation-plan.v0.2"}
                                and bridge.get("solutionId") == build_id):
                            declared = [c["definition"] for c in bridge["capabilities"]]
                            if (not declared or {c["capabilityId"] for c in declared} != set(old["capabilities"])
                                    or set(observed["pythonTools"]) != {c["toolName"] for c in declared}
                                    or any(c["operation"] != "QUERY" for c in declared)):
                                continue
                            # Preserve the bridge/runtime distinction; do not expose prompt bodies.
                            public_bridge = {k: bridge[k] for k in ("schemaVersion", "solutionId", "status", "assets")}
                            public_bridge["methods"] = [{**{k: m[k] for k in ("packId", "version", "capabilities")},
                                "skills": [{k: s[k] for k in ("directory", "entry", "digest")} for s in m["skills"]]}
                                for m in bridge["methods"]]
                            installation = {"status": "RECORDED_FOR_ARTIFACT", "currentInstanceClaim": False,
                                "format": "PYTHON_REGISTRATION_WITH_JAVA_BRIDGE",
                                "lock": {"javaBridge": public_bridge, "pythonTools": observed["pythonTools"],
                                         "runtime": observed["runtime"], "registrationStatus": observed["registrationStatus"]}}
                            break
                    except (OSError, ValueError, KeyError, TypeError, AttributeError): continue
            return {
                "schemaVersion": API_VERSION, "buildId": build_id, "buildStatus": build_status,
                "selection": old.get("selection") or default_selection(old.get("capabilities", [])),
                "selectionOrigin": "V02_REQUEST" if old.get("selection") else "LEGACY_FIXED_RECIPE",
                "modelSelection": old.get("modelSelection"),
                "startReadiness": start_readiness(old.get("modelSelection")),
                "stages": stage_view(report, 'python' if old.get('selection', {}).get('runtimeId') == 'openjiuwen-python-0.1.18' else None), "error": error, "artifact": artifact, "instance": instance,
                "directorySelection": old.get("directorySelection"),
                "runtimePresentation": frozen_runtime_presentation(old),
                "methodRef": old.get("methodRef"),
                "installation": installation,
                "sources": sources,
                "createdAtEpochSeconds": old.get("createdAt"),
                "capabilities": {"selected": old.get("capabilities", []), "installationEvidence": "RETURNED_BY_SESSION_CREATION"},
                "links": {"self": "/api/v1/builds/" + build_id,
                          "artifact": "/api/jobs/" + build_id + "/artifact" if artifact else None,
                          "agentPage": "/?agent=" + build_id if artifact else None},
                "verification": {"realModel": "NOT_ASSERTED_BY_BUILD", "realBankAuthorization": "NOT_RUN",
                                 "agUi": "WORKBENCH_ADAPTER_AVAILABLE", "humanOnboarding": "NOT_RUN"},
            }

    def optimization_view(self, build_id, owner):
        inspection = self.inspect(build_id, owner)
        with self.jobs.lock:
            job = self.jobs.owned(build_id, owner)
            binding = job.get("optimizationViewBinding")
        return OptimizationViewProjector(
            self.jobs.path(build_id), build_id
        ).project(
            binding,
            build_status=inspection["buildStatus"],
            created_at=inspection["createdAtEpochSeconds"],
            selection=inspection["selection"],
        )

    def get(self, path, owner):
        if path == "/api/v1/assets" or path.startswith("/api/v1/assets/"):
            return self.methods.get(path, owner)
        if path == "/api/v1/catalog": return 200, catalog(self.jobs.builder.harness_repo)
        if path == "/api/v1/connections": return 200, connection_status()
        if path == "/api/v1/builds":
            with self.jobs.lock:
                builds = [self.inspect(key, owner) for key, value in self.jobs.jobs.items() if value["owner"] == owner]
            return 200, {"schemaVersion": API_VERSION, "builds": builds}
        parts = path.split("/")
        if (
            len(parts) == 6
            and parts[1:4] == ["api", "v1", "builds"]
            and parts[5] == "optimization-view"
        ):
            return 200, self.optimization_view(parts[4], owner)
        if len(parts) == 5 and parts[1:4] == ["api", "v1", "builds"]:
            return 200, self.inspect(parts[4], owner)
        raise ApiError("NOT_FOUND", 404)

    def post(self, path, body, owner, *, generation_binding=None):
        if generation_binding is not None and path != "/api/v1/builds":
            raise ApiError("REQUEST_INVALID")
        if path == "/api/v1/assets" or path.startswith("/api/v1/assets/"):
            return self.methods.post(path, body, owner, self)
        if path in {"/api/v1/preflight", "/api/v1/builds"}:
            selection = parse_selection(body)
            method = None
            unified = isinstance(selection, UnifiedWorkbenchSelection)
            if unified and selection.methodRef is not None:
                method = self.jobs.method_assets.release(owner, selection.methodRef.reference())
                self.jobs.method_assets.check_source(method)
                if selection.reuseFromBuildId:
                    with self.jobs.lock: self.jobs.owned(selection.reuseFromBuildId, owner)
            result = (preflight(selection, self.jobs.project, self.jobs.builder.harness_repo, self.jobs.builder.maven,
                                method_snapshot=method) if unified else
                      preflight(selection, self.jobs.project, self.jobs.builder.harness_repo, self.jobs.builder.maven))
            if isinstance(selection, MethodSelection):
                method = self.jobs.method_assets.release(owner, selection.methodRef.reference())
                self.jobs.method_assets.check_source(method)
                if not set(method["data"]["capabilityIds"]) <= set(selection.capabilityIds):
                    raise ApiError("ASSET_CAPABILITY_NOT_INSTALLED", 409)
                if selection.reuseFromBuildId:
                    with self.jobs.lock: self.jobs.owned(selection.reuseFromBuildId, owner)
                    if selection.runtimeId == 'openjiuwen-python-0.1.18':
                        raise ApiError('PYTHON_HOST_REUSE_UNSUPPORTED', 409)
                result["method"] = {k: method[k] for k in ("assetId", "version", "releaseDigest", "installationDigest")}
            if unified and method is not None:
                result["method"] = {k: method[k] for k in ("assetId", "version", "releaseDigest", "installationDigest")}
            if path.endswith("preflight"): return 200, result
            if not result["canBuild"]: raise ApiError("BUILD_PREFLIGHT_FAILED", 409)
            options = {"model_snapshot": result["modelSelection"]} if result.get("modelSelection") else {}
            if result.get("directorySelection"):
                options["directory_snapshot"] = result["directorySelection"]
            if method is not None: options["method_snapshot"] = method
            if generation_binding is not None: options["generation_binding"] = generation_binding
            job = self.jobs.create(selection.legacy(), owner, selection=selection.model_dump(), **options)
            return 202, self.inspect(job["id"], owner)
        parts = path.split("/")
        if len(parts) != 6 or parts[1:4] != ["api", "v1", "builds"]: raise ApiError("NOT_FOUND", 404)
        build_id, action = parts[4:]
        with self.jobs.lock: self.jobs.owned(build_id, owner)
        if action == "experience":
            exact_body(body, {"instanceId"})
            self.inspect(build_id, owner, expected_instance=body["instanceId"], expected_status="RUNNING")
            return 200, self.jobs.experience.issue(build_id, owner)
        if action == "optimization-runs":
            if not isinstance(body, dict) or set(body) != {
                "requestRef",
                "baselineEvaluationRef",
            }:
                raise ApiError("REQUEST_INVALID")
            if self.jobs.optimization_gateway_factory is None:
                raise ApiError("OPTIMIZATION_EXECUTION_UNAVAILABLE", 409)
            self.jobs.run_optimization_from_records(
                build_id,
                owner,
                body["requestRef"],
                body["baselineEvaluationRef"],
            )
            return 200, self.optimization_view(build_id, owner)
        if action == "delete":
            exact_body(body, {"buildId"})
            if body['buildId'] != build_id: raise ApiError('REQUEST_INVALID')
            return 200, self.jobs.delete(build_id, owner)
        if action == "start":
            exact_body(body, {"artifactId"})
            if not re.fullmatch(r"sha256:[0-9a-f]{64}", body["artifactId"]): raise ApiError("REQUEST_INVALID")
            selection = parse_saved_selection(self.inspect(build_id, owner)["selection"])
            if isinstance(selection, ManagedWorkbenchSelection):
                # Startup verifies the frozen artifact in Jobs.start, not today's catalog.
                snapshot = self.inspect(build_id, owner)["modelSelection"]
                ModelRegistry().check_frozen(snapshot)
                readiness = start_readiness(snapshot=snapshot)
                if not readiness["configurationReady"]:
                    raise ApiError("START_PREFLIGHT_FAILED", 409)
                started = self.jobs.start(build_id, owner, expected_artifact=body["artifactId"])
                return 200, self.inspect(build_id, owner, expected_instance=started["instanceId"], expected_status="RUNNING")
            if isinstance(selection, (DirectorySelection, TemplateModuleSelection)):
                # Starting an immutable old artifact does not reselect today's catalog.
                fields = InstalledRuntimeSelection.model_fields
                selection = InstalledRuntimeSelection.model_validate({**{k: v for k, v in selection.model_dump().items() if k in fields},
                    "schema_version": "0.3", "presentation": "MOBILE_WEB"})
            if isinstance(selection, MultiModelSelection):
                registry = ModelRegistry()
                registry.check_frozen(self.inspect(build_id, owner)["modelSelection"])
                selection = selection.model_copy(update={"modelCatalogRevision": registry.catalog_revision})
            result = preflight(selection, self.jobs.project, self.jobs.builder.harness_repo, self.jobs.builder.maven)
            if not result["startPrerequisitesSatisfied"]: raise ApiError("START_PREFLIGHT_FAILED", 409)
            started = self.jobs.start(build_id, owner, expected_artifact=body["artifactId"])
            return 200, self.inspect(build_id, owner, expected_instance=started["instanceId"], expected_status="RUNNING")
        if action == "stop":
            exact_body(body, {"instanceId"})
            self.jobs.stop(build_id, owner, expected_instance=body["instanceId"])
            return 200, self.inspect(build_id, owner, expected_instance=body["instanceId"], expected_status="STOPPED")
        if action not in {"sessions", "switch-model", "chat", "authorization", "revoke", *TASK_ACTION_FIELDS}: raise ApiError("NOT_FOUND", 404)
        fields = {"instanceId"} if action == "sessions" else {"instanceId", "sessionId"}
        fields.update(TASK_ACTION_FIELDS.get(action, ()))
        if action == "switch-model" or action == "sessions" and "modelRef" in body: fields.add("modelRef")
        if action == "chat": fields.add("query")
        exact_body(body, fields)
        if action == "chat" and len(body["query"]) > 4000: raise ApiError("REQUEST_INVALID")
        translated = {"sessions": "session", "authorization": "status"}.get(action, action)
        run_id = "run-" + uuid.uuid4().hex if action == "chat" else None
        host_body = {"query": body["query"], "runId": run_id} if action == "chat" else {"modelRef": body["modelRef"]} if "modelRef" in body else {}
        if action in TASK_ACTION_FIELDS:
            host_body = {field: body[field] for field in TASK_ACTION_FIELDS[action]}
            try: validate_task_body(action, host_body)
            except ValueError: raise ApiError("REQUEST_INVALID") from None
        code, result = self.jobs.talk(build_id, owner, translated, host_body,
                                     expected_instance=body["instanceId"], expected_session=body.get("sessionId"))
        # The underlying host rechecks grants before each model call and before returning facts.
        # Do not cache/replay result bodies via a GET evidence endpoint.
        envelope = {"schemaVersion": API_VERSION, "buildId": build_id, "instanceId": body["instanceId"],
                    "sessionId": result.get("sessionId", body.get("sessionId")),
                    "runId": run_id,
                    "clearConversation": bool(result.get("clearConversation"))}
        if code != 200:
            known = {"INSTANCE_CHANGED", "SESSION_REQUIRED", "SESSION_UNAVAILABLE", "AUTHORIZATION_REJECTED",
                     "MODEL_OR_RUNTIME_FAILED", "MODEL_OR_INSTANCE_TIMEOUT", "SERVICE_DEPENDENCY_FAILED",
                     "DEPENDENCY_FAILURE", "INVALID_CHAT_INPUT", "HOST_OWNS_IDENTITY"}
            known |= {"MODEL_NOT_SELECTED", "MODEL_CONNECTION_UNAVAILABLE", "MODEL_CONFIGURATION_CHANGED"}
            known |= {"STEP_UP_REQUIRED", "TASK_NOT_INSTALLED", "TASK_NOT_AVAILABLE", "INVALID_TASK_REQUEST",
                      "CONFIRMATION_NOT_AVAILABLE", "CONFIRMATION_BINDING_MISMATCH", "CONFIRMATION_EXPIRED_OR_CHANGED"}
            envelope["error"] = {"code": result.get("error") if result.get("error") in known else "HOST_OPERATION_FAILED", "retryable": False}
        else:
            envelope["result"] = result
        return code, envelope
