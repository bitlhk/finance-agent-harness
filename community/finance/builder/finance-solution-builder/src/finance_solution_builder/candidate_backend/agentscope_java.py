"""Native AgentScope finance-chat Backend; execution remains owned by the caller's Jobs."""
from copy import deepcopy
from functools import wraps
import json
import hashlib
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
from types import SimpleNamespace

from finance_builder_base import ErrorDetail, ResourceKind
from finance_builder_base.solution_build import CheckStatus

from .models import (AssemblyRequest, AssemblyResult, BackendCapability, BackendIdentity,
    BuildRequest, BuildResult, CandidateArtifact, CandidateCheck, CandidateDescriptor,
    CandidateEntrypoint, CandidateResourceRef, CandidateTarget, CapabilityProviderBinding,
    EvidenceResult, HealthResult, InvocationResult, LifecycleOperation, LifecycleResult,
    LifecycleStatus, PreflightRequest, PreflightResult, ProtocolBinding, RunHandle, RunState,
    RuntimeEndpoint, RuntimeIdentity, StartResult, StopResult, TypedExtension, verify_candidate_artifact, verify_run_handle)
from .registry import BackendRegistry
from .restricted_response import DeliveryRejected, ResponseStore, read_payload, require_grant
from ..agentscope_service import AgentScopeServiceBuild, canonical_digest, host_template_digest
from ..component_assembly import AssemblyRejected
from ..component_plan import qualify_solution
from ..managed_launcher import checked_file, jar_inventory, locked_classpath
from ..service import digest, write_json


QUALIFICATION_ENV = ("FH_AGENTSCOPE_QUALIFICATION_FILE", "FH_AGENTSCOPE_QUALIFICATION_SHA256",
                     "FH_OBSERVATION_QUALIFICATION_FILE", "FH_OBSERVATION_QUALIFICATION_SHA256")
ADMISSION_ENV = (*QUALIFICATION_ENV, "FH_HISTORY_DIR", "FH_HISTORY_OWNER", "FH_HISTORY_ENABLED",
                 "FH_SNAPSHOTS_ENABLED", "FH_SNAPSHOT_CAPTURE_OWNERS", "FH_SNAPSHOT_MAX_FILES",
                 "FH_SNAPSHOT_MAX_BYTES", "FH_SNAPSHOT_RETENTION_SECONDS", "FH_SNAPSHOT_FLUSH_MILLIS")


def _ready_assessment(value):
    if (not isinstance(value, dict) or value.get("schemaVersion") != "finance-observation-assessment.v0.1"
            or value.get("status") not in {"READY", "OPTIONAL_GAPS"}
            or not isinstance(value.get("items"), list) or not value["items"]):
        raise ValueError("OBSERVATION_ADMISSION_NOT_READY")
    items = []
    for item in value["items"]:
        requirement, descriptor = item["requirement"], item.get("descriptor") or {}
        if requirement["obligation"] == "REQUIRED" and (item.get("readiness") != "READY" or descriptor.get("verification") != "VERIFIED"
                or descriptor.get("support") != "SUPPORTED" or descriptor.get("availability") != "ENABLED"
                or not item.get("collectorId") or not item.get("collectorVersion")):
            raise ValueError("OBSERVATION_ADMISSION_NOT_READY")
        items.append({"semanticId": requirement["semanticId"], "boundary": requirement["boundary"],
                      "obligation": requirement["obligation"], "readiness": item["readiness"],
                      "collectorId": item.get("collectorId"), "collectorVersion": item.get("collectorVersion"),
                      "verification": descriptor.get("verification", "NOT_CHECKED"), "capture": item["capture"]})
    if not any(item["obligation"] == "REQUIRED" for item in items):
        raise ValueError("OBSERVATION_ADMISSION_NOT_REQUIRED")
    return {"schemaVersion": value["schemaVersion"], "status": value["status"], "requiredSatisfied": True, "items": items}


def _measured(operation):
    @wraps(operation)
    def measured(*args, **kwargs):
        began = time.monotonic()
        result = operation(*args, **kwargs)
        outcome = result.outcome.model_copy(update={"duration_ms": math.ceil((time.monotonic() - began) * 1000)})
        return result.model_copy(update={"outcome": outcome})
    return measured


def preflight_managed_admission(project, harness, solution, runtime_binding, environment=None, *, method_snapshot=None):
    """Execute pinned packaged qualification code before queueing, without compiling or starting a Host."""
    env = os.environ if environment is None else environment
    names = ("FH_AGENTSCOPE_ADMISSION_CANDIDATE", "FH_AGENTSCOPE_ADMISSION_LOCK_SHA256",
             "FH_OBSERVATION_REQUIREMENTS", *QUALIFICATION_ENV)
    if any(not env.get(name) for name in names):
        raise AssemblyRejected("MANAGED_RUNTIME_QUALIFICATION_REQUIRED")
    try:
        qualify_solution(Path(project), Path(harness), solution, method_snapshot, runtime_binding=runtime_binding)
        candidate = Path(env["FH_AGENTSCOPE_ADMISSION_CANDIDATE"])
        lock_file = checked_file(candidate, "service-lock.json")
        expected = env["FH_AGENTSCOPE_ADMISSION_LOCK_SHA256"]
        if not re.fullmatch(r"sha256:[a-f0-9]{64}", expected) or "sha256:" + digest(lock_file) != expected:
            raise ValueError
        lock = json.loads(lock_file.read_bytes())
        template_digest = host_template_digest(project, runtime_binding)
        if lock.get("runtime") != solution["runtime"] or lock.get("hostTemplateDigest") != template_digest:
            raise ValueError
        files = lock["files"]
        for relative, sha in files.items():
            if digest(checked_file(candidate, relative)) != sha:
                raise ValueError
        locked_classpath(candidate, files)
        actual = jar_inventory(candidate)
        child_env = {k: env[k] for k in ("PATH", "HOME", "JAVA_HOME", "TMPDIR", "LANG", *ADMISSION_ENV) if k in env}
        with tempfile.TemporaryDirectory(prefix="finance-agentscope-preflight-") as temporary:
            root = Path(temporary)
            builder = SimpleNamespace(project_root=Path(project), harness_repo=Path(harness), service_output_root=root)
            build = AgentScopeServiceBuild(builder, solution, root / "admission", runtime_binding, None,
                observation_requirements=Path(env["FH_OBSERVATION_REQUIREMENTS"]), method_snapshot=method_snapshot)
            build.assemble()
            for relative in sorted(actual):
                source = checked_file(candidate, relative)
                destination = build.candidate / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
                if digest(destination) != files[relative]:
                    raise ValueError("QUALIFIED_ARTIFACT_CHANGED")
            temporary_files = build.files()
            build.write_lock({"runtime": solution["runtime"], "hostTemplateDigest": template_digest,
                              "runtimeBinding": runtime_binding,
                              "managedTemplateDigest": runtime_binding["managedTemplate"]["digest"],
                              "sharedHostSources": build.source_inventory,
                              "capabilities": sorted(solution["capabilityBindings"]), "files": temporary_files})
            child_env.setdefault("FH_HISTORY_DIR", str(root / "history"))
            command = ["java", "-Xmx256m", "-cp", locked_classpath(build.candidate, temporary_files),
                       "io.github.bitlhk.finance.builder.local.AgentScopeAdmission", str(build.candidate)]
            completed = subprocess.run(command, env=child_env, cwd=root, capture_output=True, timeout=45)
            if completed.returncode != 0 or len(completed.stdout) > 2_000_000:
                raise ValueError
            result = json.loads(completed.stdout)
            if (result.get("schemaVersion") != "finance-agentscope-host-admission.v0.1"
                    or result.get("runtime") != runtime_binding["runtimeId"] or result.get("status") != "READY"
                    or result.get("installation") != "NOT_OBSERVED" or result.get("modelInvocation") != "NOT_OBSERVED"):
                raise ValueError
            assessments = {key: _ready_assessment(result[key])
                           for key in ("observationAssessment", "hostObservationAssessment")}
            plan = checked_file(build.candidate, "component-installation-plan.json")
            if digest(plan) != result.get("installationPlanSha256"):
                raise ValueError
        return {"schemaVersion": "finance-managed-backend-admission.v0.1", "status": "READY", "requiredSatisfied": True,
                "runtimeId": runtime_binding["runtimeId"], "solutionDigest": "sha256:" + canonical_digest(solution),
                "bindingDigest": "sha256:" + canonical_digest(runtime_binding), "hostTemplateDigest": template_digest,
                "admissionArtifactDigest": expected,
                "runtimeDependencies": {Path(p).name: files[p] for p in sorted(actual) if p.startswith("target/dependency/")},
                "nativeQualificationDigest": env["FH_AGENTSCOPE_QUALIFICATION_SHA256"],
                "hostQualificationDigest": env["FH_OBSERVATION_QUALIFICATION_SHA256"],
                "assessments": assessments,
                "invocation": "NOT_RUN"}
    except AssemblyRejected:
        raise
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
        raise AssemblyRejected("MANAGED_RUNTIME_QUALIFICATION_REJECTED") from None


class AgentScopeJavaBackend:
    def __init__(self, *, builder, solution, output, runtime_binding, runtime_admission=None,
                 options=None, verify=True, jobs=None, owner=None):
        self.builder, self.solution, self.output = builder, deepcopy(solution), Path(output)
        self.binding, self.admission = deepcopy(runtime_binding), deepcopy(runtime_admission)
        self.options, self.verify = options or {}, verify
        self.jobs, self.owner = jobs, owner
        self._assembly = self._service = self._descriptor = self._handle = self._instance = None
        self._instance_id = None
        self._stopped = set()
        self.result = None

    @property
    def identity(self):
        return BackendIdentity(backend_id="agentscope-java", version="0.1")

    @property
    def capability(self):
        return BackendCapability(backend=self.identity, languages=("java",), runtime_families=("agentscope",),
            artifact_kinds=("directory-manifest",), invocation_protocols=("finance-chat",), operations=tuple(LifecycleOperation))

    def target(self):
        plan = qualify_solution(self.builder.project_root, self.builder.harness_repo, self.solution,
            self.options.get("method_snapshot"), runtime_binding=self.binding)
        operations = tuple(sorted({item["operation"].lower() for item in plan["capabilities"]}))
        return CandidateTarget(language="java", runtime_family="agentscope", runtime_version="2.0.3",
            artifact_kind="directory-manifest", invocation_protocol="finance-chat", required_operations=tuple(LifecycleOperation),
            required_asset_ids=tuple(dict.fromkeys(ref["id"] for ref in [self.solution["profileRef"],
                *self.solution["domainPackRefs"], *self.solution["capabilityPackRefs"], *self.solution["providerRefs"]])),
            required_capability_ids=tuple(sorted(self.solution["capabilityBindings"])), required_provider_operation_types=operations,
            profile_id=self.solution["profileRef"]["id"], host_wiring_ids=(self.binding["hostId"],))

    def _outcome(self, operation, passed=True, *, task=None, refs=(), skipped=False):
        return LifecycleResult(operation=operation, status=LifecycleStatus.SKIPPED if skipped else
            LifecycleStatus.PASSED if passed else LifecycleStatus.BLOCKED, task_id=task or self.solution["solutionId"],
            candidate_id=self.solution["solutionId"], duration_ms=0, evidence_refs=refs,
            summary="Managed native lifecycle completed." if passed else "Managed native lifecycle boundary rejected.",
            error=None if passed or skipped else ErrorDetail(code="candidate.agentscope.boundary_rejected",
                message="Candidate identity, qualification or owned lifecycle did not match."))

    @_measured
    def preflight(self, request):
        passed = False
        try:
            if request.target != self.target() or request.task_id != self.solution["solutionId"]:
                raise ValueError
            if self.options.get("reuse_candidate") is not None:
                raise AssemblyRejected("MANAGED_RUNTIME_REUSE_UNSUPPORTED")
            qualify_solution(self.builder.project_root, self.builder.harness_repo, self.solution,
                self.options.get("method_snapshot"), runtime_binding=self.binding)
            actual = preflight_managed_admission(self.builder.project_root, self.builder.harness_repo, self.solution, self.binding,
                                                method_snapshot=self.options.get("method_snapshot"))
            if self.admission is not None and self.admission != actual:
                raise AssemblyRejected("MANAGED_RUNTIME_QUALIFICATION_REJECTED")
            self.admission = actual
            passed = True
        except (OSError, ValueError, KeyError, TypeError):
            pass
        return PreflightResult(outcome=self._outcome(LifecycleOperation.PREFLIGHT, passed, task=request.task_id),
            backend=self.identity, checks=(CandidateCheck(check_id="native-artifact-qualified", status=CheckStatus.PASSED
                if passed else CheckStatus.BLOCKED, summary="Pinned native and Host qualification is required."),))

    @_measured
    def assemble(self, request):
        passed = False
        try:
            checked = self.preflight(PreflightRequest(task_id=request.task_id, target=request.target,
                                                     resolved_asset_ids=request.target.required_asset_ids))
            if (checked.outcome.status != LifecycleStatus.PASSED or request.lock_digest != self.input_digest()
                    or request.workspace.kind != ResourceKind.WORKSPACE_PATH or request.workspace.locator != "candidate"):
                raise ValueError
            self._service = AgentScopeServiceBuild(self.builder, self.solution, self.output, self.binding, self.admission, **self.options)
            self._service.assemble()
            self._assembly = (request.task_id, request.lock_digest, request.asset_refs)
            passed = True
        except (OSError, ValueError, KeyError, TypeError):
            pass
        return AssemblyResult(outcome=self._outcome(LifecycleOperation.ASSEMBLE, passed, task=request.task_id),
            candidate_id=self.solution["solutionId"], assembly_refs=request.asset_refs if passed else ())

    def input_digest(self):
        return canonical_digest({"solution": self.solution, "runtimeBinding": self.binding})

    @_measured
    def build(self, request):
        try:
            if (self._assembly != (request.task_id, request.lock_digest, request.assembly_refs)
                    or request.candidate_id != self.solution["solutionId"] or self._service is None):
                raise ValueError
            self.result = self._service.build(self.verify)
            if not self.verify:
                return BuildResult(outcome=self._outcome(LifecycleOperation.BUILD, skipped=True))
            lock = json.loads(self.result.lock_path.read_bytes())
            plan = json.loads((self.result.candidate_dir / "component-installation-plan.json").read_bytes())
            resource = CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="candidate/service-lock.json",
                                             sha256=digest(self.result.lock_path))
            self._descriptor = CandidateDescriptor(task_id=request.task_id, candidate_id=request.candidate_id,
                backend=self.identity, language="java", runtime=RuntimeIdentity(family="agentscope", version="2.0.3"),
                artifact=CandidateArtifact(artifact_id=request.candidate_id, artifact_kind="directory-manifest", resource=resource),
                entrypoint=CandidateEntrypoint(mode="managed-artifact", resource=resource),
                invocation=ProtocolBinding(protocol="finance-chat", path="/sessions", media_type="application/json"),
                health=ProtocolBinding(protocol="http", path="/health", media_type="application/json"), lock_digest=resource.sha256,
                installed_asset_ids=self.target().required_asset_ids, installed_capability_ids=tuple(lock["capabilities"]),
                installed_tool_ids=tuple(item["definition"]["toolName"] for item in plan["capabilities"]),
                provider_bindings=tuple(CapabilityProviderBinding(capability_id=item["definition"]["capabilityId"],
                    provider_id=item["providerId"], operation_types=(item["definition"]["operation"].lower(),)) for item in plan["capabilities"]),
                provenance_refs=(CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH,
                    locator="candidate/component-installation-plan.json",
                    sha256=digest(self.result.candidate_dir / "component-installation-plan.json")),),
                extensions=(TypedExtension(extension_type="finance.candidate-preparation", payload={
                    "status": "PREPARED_NOT_RUNTIME_REGISTERED", "runtimeInstallation": "NOT_OBSERVED",
                    "modelInvocation": "NOT_OBSERVED", "installationEvidence": "SESSION_RUNTIME_LOCK_REQUIRED"}),))
            write_json(self.output / "candidate-descriptor.json", self._descriptor.to_wire())
            return BuildResult(outcome=self._outcome(LifecycleOperation.BUILD), descriptor=self._descriptor)
        except Exception:
            return BuildResult(outcome=self._outcome(LifecycleOperation.BUILD, False))

    @_measured
    def start(self, request):
        running = None
        try:
            if self.jobs is None or self.owner is None or self._handle is not None:
                raise ValueError
            identity = self.solution["solutionId"]
            if self.jobs.path(identity).resolve() != self.output.resolve():
                raise ValueError
            with self.jobs.lock:
                self.jobs.owned(identity, self.owner)
            stored = CandidateDescriptor.model_validate_json((self.output / "candidate-descriptor.json").read_bytes())
            if (stored != request.descriptor or stored.backend != self.identity or stored.candidate_id != identity
                    or stored.task_id != identity or stored.runtime != RuntimeIdentity(family="agentscope", version="2.0.3")
                    or stored.invocation.protocol != "finance-chat"):
                raise ValueError
            if verify_candidate_artifact(stored, self.output) != request.artifact_verification:
                raise ValueError
            running = self.jobs.start(identity, self.owner, expected_artifact="sha256:" + stored.lock_digest)
            with self.jobs.lock:
                self._instance = self.jobs._capture(identity)
                if self.jobs.owned(identity, self.owner).get("instanceId") != running["instanceId"]:
                    raise ValueError
                self._instance_id = running["instanceId"]
            self._descriptor = stored
            self._handle = RunHandle(task_id=stored.task_id, candidate_id=identity, backend=self.identity,
                descriptor_digest=stored.sha256(), lock_digest=stored.lock_digest, state=RunState.RUNNING,
                endpoints=(RuntimeEndpoint(name="host", protocol="finance-chat", uri="http://127.0.0.1:" + str(running["port"])),))
            return StartResult(outcome=self._outcome(LifecycleOperation.START), handle=self._handle)
        except Exception:
            if running is not None:
                try:
                    self.jobs.stop(self.solution["solutionId"], self.owner, expected_instance=running["instanceId"])
                except (ValueError, RuntimeError, PermissionError):
                    pass
            return StartResult(outcome=self._outcome(LifecycleOperation.START, False))

    def _owned(self, handle):
        if handle != self._handle or self.jobs is None or self._instance is None:
            raise ValueError("INSTANCE_CHANGED")
        verify_run_handle(self._descriptor, handle)
        with self.jobs.lock:
            job = self.jobs.owned(handle.candidate_id, self.owner)
            if (job.get("instanceId") != self._instance_id or job.get("status") != "RUNNING"
                    or not self.jobs._same_instance(handle.candidate_id, self._instance)
                    or self._instance.child.poll() is not None):
                raise ValueError("INSTANCE_CHANGED")
        return self.jobs

    @_measured
    def health(self, request):
        passed = False
        try:
            code, result = self._owned(request.handle)._request(request.handle.candidate_id, "/health", instance=self._instance)
            self._owned(request.handle)
            passed = code == 200 and result.get("runtime") == self.binding["runtimeId"]
        except Exception:
            pass
        return HealthResult(outcome=self._outcome(LifecycleOperation.HEALTH, passed), ready=passed)

    @_measured
    def invoke(self, request):
        response = store = reservation = None
        refs = ()
        attempted = False
        # Jobs may reject locally or replace a transport result after a race.
        # These facts record calls and returned codes, not HTTP transmission.
        facts = {"sessionAttempted": False, "chatAttempted": False, "sessionResultCode": None,
                 "chatResultCode": None, "hostRequestIdSha256": None, "businessOutcome": "NOT_ASSESSED"}
        error_code = "boundary_rejected"
        try:
            jobs = self._owned(request.handle)
            require_grant(self.owner)
            if request.protocol != "finance-chat" or request.payload_ref.kind != ResourceKind.WORKSPACE_PATH:
                raise DeliveryRejected("payload_rejected")
            payload = read_payload(self.output, request.payload_ref)
            store = ResponseStore(self.output, self.owner)
            verify_identity = lambda: self._owned(request.handle)
            reservation = store.reserve(request, self._instance_id, verify_identity)
            identity, instance_id = request.handle.candidate_id, self._instance_id
            with jobs.lock:
                needs_session = identity not in jobs.sessions
            reservation._current(verify_identity)
            if needs_session:
                attempted = facts["sessionAttempted"] = True
                code, _ = jobs.talk(identity, self.owner, "session", {}, expected_instance=instance_id)
                facts["sessionResultCode"] = code
                if code != 200:
                    raise DeliveryRejected("transport_failed")
            reservation._current(verify_identity)
            attempted = facts["chatAttempted"] = True
            code, actual = jobs.talk(identity, self.owner, "chat", payload, expected_instance=instance_id)
            facts["chatResultCode"] = code
            host_request = actual.get("requestId") if isinstance(actual, dict) else None
            if isinstance(host_request, str) and len(host_request) <= 200:
                facts["hostRequestIdSha256"] = hashlib.sha256(host_request.encode()).hexdigest()
            if code != 200:
                raise DeliveryRejected("transport_failed")
            response = reservation.complete(actual, facts, verify_identity)
        except Exception as failure:
            error_code = failure.code if isinstance(failure, DeliveryRejected) else "delivery_unavailable"
            if attempted and reservation is not None:
                reservation.failed(facts, error_code)
        finally:
            if reservation is not None:
                evidence = reservation.evidence()
                refs = (evidence,) if evidence is not None else ()
            if store is not None:
                store.close()
        if response is not None:
            outcome = self._outcome(LifecycleOperation.INVOKE, refs=refs)
        else:
            outcome = LifecycleResult(operation=LifecycleOperation.INVOKE,
                status=LifecycleStatus.FAILED if attempted else LifecycleStatus.BLOCKED,
                task_id=request.handle.task_id, candidate_id=request.handle.candidate_id, duration_ms=0,
                summary="Response delivery failed after a Jobs call attempt; no automatic retry." if attempted else
                        "Response delivery was blocked before any Jobs call attempt.", evidence_refs=refs,
                error=ErrorDetail(code="candidate.agentscope." + error_code,
                                  message="Restricted response delivery did not complete.", retryable=False))
        return InvocationResult(outcome=outcome, request_id=request.request_id, response_ref=response)

    @_measured
    def collect_evidence(self, request):
        refs = ()
        try:
            jobs = self._owned(request.handle)
            with jobs.lock:
                session = jobs.sessions.get(request.handle.candidate_id)
            if session is None:
                raise ValueError("SESSION_REQUIRED")
            directory = self.output / "candidate/runtime-evidence" / session
            current = directory / self._instance_id / "runtime-lock.json"
            if not current.exists():
                # Existing immutable QUERY artifacts used the session-only layout.
                current = directory / "runtime-lock.json"
            refs = tuple(CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH,
                locator=p.relative_to(self.output).as_posix(), sha256=digest(checked_file(self.output, p.relative_to(self.output))))
                for p in [current])
            self._owned(request.handle)
        except Exception:
            refs = ()
        return EvidenceResult(outcome=self._outcome(LifecycleOperation.COLLECT_EVIDENCE, bool(refs), refs=refs))

    @_measured
    def stop(self, request):
        if request.handle.sha256() in self._stopped:
            return StopResult(outcome=self._outcome(LifecycleOperation.STOP), stopped=True)
        passed = False
        try:
            jobs = self._owned(request.handle)
            jobs.stop(request.handle.candidate_id, self.owner, expected_instance=self._instance_id)
            self._stopped.add(request.handle.sha256())
            self._handle = self._instance = None
            self._instance_id = None
            passed = True
        except Exception:
            pass
        return StopResult(outcome=self._outcome(LifecycleOperation.STOP, passed), stopped=passed)


def build_managed_candidate(builder, solution, output, *, verify=True, runtime_binding, runtime_admission=None, **options):
    backend = AgentScopeJavaBackend(builder=builder, solution=solution, output=output, runtime_binding=runtime_binding,
                                   runtime_admission=runtime_admission, options=options, verify=verify)
    target = backend.target()
    selected = BackendRegistry((backend,)).select(target, backend_id=runtime_binding["backendId"])
    task = solution["solutionId"]
    preflight = selected.preflight(PreflightRequest(task_id=task, target=target, resolved_asset_ids=target.required_asset_ids))
    if preflight.outcome.status != LifecycleStatus.PASSED:
        raise AssemblyRejected("MANAGED_RUNTIME_QUALIFICATION_REJECTED")
    assembled = selected.assemble(AssemblyRequest(task_id=task, target=target, lock_digest=backend.input_digest(),
        workspace=CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="candidate"), asset_refs=()))
    if assembled.outcome.status != LifecycleStatus.PASSED:
        raise AssemblyRejected("MANAGED_HOST_BINDING_MISMATCH")
    built = selected.build(BuildRequest(task_id=task, candidate_id=task, lock_digest=backend.input_digest(),
                                       assembly_refs=assembled.assembly_refs))
    if built.outcome.status not in {LifecycleStatus.PASSED, LifecycleStatus.SKIPPED} or backend.result is None:
        raise RuntimeError("MANAGED_SERVICE_BUILD_FAILED")
    return backend.result
