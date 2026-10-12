"""Local single-operator UI, real build jobs and owned child-process lifecycle."""
from __future__ import annotations

import argparse
import atexit
import fcntl
from contextlib import nullcontext
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
import hashlib
import hmac
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import signal
import stat
import subprocess
import threading
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse, parse_qs
import uuid
import zipfile
import shutil
from datetime import datetime
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from finance_builder_base import ResourceKind, ResourceRef
from finance_builder_base.runtime import spawn_process, require_path_within
from .assets import optional_archive_anchor
from .builder import SolutionBuilder
from .service import MAIN, CASHFLOW, digest, solution_for, write_json, stop_owned_process
from .optimization import (
    OptimizationController,
    OptimizationRequest,
    OptimizationRecordStore,
    OptimizationRoundRecord,
)
from .evaluation import SandboxEvaluationReport
from .workbench_api import WorkbenchApi, public_error
from .workbench_contracts import API_VERSION, connection_status
from .model_registry import ModelRegistry
from .server_access import ServerAccess, exclusive_data_dir, private_file
from .workbench_projection import OptimizationViewBinding, OptimizationViewProjector
from .task_routes import TASK_ACTION_FIELDS, validate_task_body, task_runtime_required


def _remove_idle_task_scope(root: Path, data_dir: Path) -> None:
    if not root.exists() and not root.is_symlink():
        return
    if root.is_symlink() or not root.is_dir() or root.resolve() != root.absolute():
        raise ValueError("DELETE_PATH_INVALID")
    require_path_within(root, data_dir)
    lock_path = root / "host.lock"
    if lock_path.is_symlink():
        raise ValueError("DELETE_PATH_INVALID")
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(lock_path, flags, 0o600)
    locked = False
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise ValueError("DELETE_PATH_INVALID")
        try:
            fcntl.lockf(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            locked = True
        except BlockingIOError:
            raise ValueError("TASK_STATE_IN_USE") from None
        allowed_root_files = {"host.lock", "scope.json", "identity.json", "source-session.json"}
        for directory, dirs, files in os.walk(root, followlinks=False):
            current = Path(directory)
            for name in dirs + files:
                if (current / name).is_symlink():
                    raise ValueError("DELETE_PATH_INVALID")
            relative = current.relative_to(root).parts
            if not relative:
                if set(files) - allowed_root_files or set(dirs) - {"tasks"}:
                    raise ValueError("TASK_RECOVERY_STATE_RETAINED")
            elif relative == ("tasks",):
                if files or any(not re.fullmatch(r"[A-Za-z0-9_-]{1,160}", name) for name in dirs):
                    raise ValueError("TASK_RECOVERY_STATE_RETAINED")
            elif len(relative) == 2 and relative[0] == "tasks":
                if files or set(dirs) - {"handles", "confirmations", "checkpoints"}:
                    raise ValueError("TASK_RECOVERY_STATE_RETAINED")
            elif (dirs or len(relative) != 3 or relative[0] != "tasks"
                  or files and not (relative[2] == "checkpoints" and set(files) == {".lock"})):
                raise ValueError("TASK_RECOVERY_STATE_RETAINED")
        shutil.rmtree(root)
    finally:
        if locked:
            fcntl.lockf(fd, fcntl.LOCK_UN)
        os.close(fd)

PROJECT = Path(__file__).resolve().parents[2]
PUBLIC = PROJECT / "examples/local-workbench"
ENV_KEYS = ("FH_MODEL_URL", "FH_MODEL_NAME", "FH_MODEL_KEY", "FH_BANK_URL", "FH_BANK_LOGIN", "FH_BANK_SECRET", "FH_ACCESS_POLICY")


def trusted_business_time_environment(environment=None, now=None):
    """Freeze a Host-owned business date for one Agent process generation."""
    source = os.environ if environment is None else environment
    names = ("FH_BUSINESS_DATE", "FH_BUSINESS_TIME_ZONE", "FH_BUSINESS_DATE_SOURCE")
    configured = [source.get(name) for name in names]
    if any(configured):
        if not all(configured):
            raise ValueError("BUSINESS_TIME_CONFIGURATION_INCOMPLETE")
        try:
            instant = datetime.fromisoformat(configured[0])
            ZoneInfo(configured[1])
        except (ValueError, ZoneInfoNotFoundError):
            raise ValueError("BUSINESS_TIME_CONFIGURATION_INVALID") from None
        if instant.time() != datetime.min.time() or not configured[2].strip():
            raise ValueError("BUSINESS_TIME_CONFIGURATION_INVALID")
        return dict(zip(names, configured))
    zone_name = source.get("FH_WORKBENCH_BUSINESS_TIME_ZONE", "Asia/Shanghai")
    try:
        zone = ZoneInfo(zone_name)
    except ZoneInfoNotFoundError:
        raise ValueError("BUSINESS_TIME_CONFIGURATION_INVALID") from None
    instant = datetime.now(zone) if now is None else now.astimezone(zone)
    return {"FH_BUSINESS_DATE": instant.date().isoformat(),
            "FH_BUSINESS_TIME_ZONE": zone.key,
            "FH_BUSINESS_DATE_SOURCE": "WORKBENCH_HOST_CLOCK"}


@dataclass(frozen=True)
class InstanceRequest:
    child: subprocess.Popen
    token: str
    port: int


@dataclass(frozen=True)
class WorkbenchOptimizationGateways:
    """Environment adapters for one controlled Workbench optimization run."""

    correction_executor: object
    rebuild_gateway: object
    evaluation_gateway: object
    external_proposal_gateway: object | None = None


def _build_owned_ref(ref: ResourceRef, build_root: Path, project_root: Path) -> ResourceRef:
    """Convert a verified workspace reference to the selected Build's root."""
    if ref.kind is not ResourceKind.WORKSPACE_PATH or ref.sha256 is None:
        raise ValueError("OPTIMIZATION_REFERENCE_INVALID")
    for base in (build_root, project_root):
        path = (base / ref.locator).resolve()
        if path.is_file() and path.is_relative_to(build_root):
            if digest(path) != ref.sha256:
                raise ValueError("OPTIMIZATION_REFERENCE_DIGEST_MISMATCH")
            return ResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator=path.relative_to(build_root).as_posix(),
                sha256=ref.sha256,
            )
    raise ValueError("OPTIMIZATION_REFERENCE_OUTSIDE_BUILD")


def _file_ref(path: Path, build_root: Path) -> ResourceRef:
    path = path.resolve()
    if not path.is_file() or not path.is_relative_to(build_root):
        raise ValueError("OPTIMIZATION_REFERENCE_MISSING")
    return ResourceRef(
        kind=ResourceKind.WORKSPACE_PATH,
        locator=path.relative_to(build_root).as_posix(),
        sha256=digest(path),
    )


class _BuildOwnedCorrectionExecutor:
    def __init__(self, delegate, build_root: Path, project_root: Path) -> None:
        self.delegate = delegate
        self.build_root = build_root
        self.project_root = project_root

    def execute(self, target, round_index):
        result = self.delegate.execute(target, round_index)
        refs = tuple(
            _build_owned_ref(ref, self.build_root, self.project_root)
            for ref in result.evidence_refs
        )
        return result.model_copy(update={"evidence_refs": refs})


class _BuildOwnedRebuildGateway:
    def __init__(self, delegate, build_root: Path, project_root: Path) -> None:
        self.delegate = delegate
        self.build_root = build_root
        self.project_root = project_root

    def rebuild(self, parent, correction, round_index):
        result = self.delegate.rebuild(parent, correction, round_index)
        return result.model_copy(
            update={
                "lock_ref": _build_owned_ref(
                    result.lock_ref, self.build_root, self.project_root
                ),
                "report_ref": _build_owned_ref(
                    result.report_ref, self.build_root, self.project_root
                ),
            }
        )


class _BuildOwnedProposalGateway:
    def __init__(self, delegate, build_root: Path, project_root: Path) -> None:
        self.delegate = delegate
        self.build_root = build_root
        self.project_root = project_root

    def write(self, request, finding, target):
        return _build_owned_ref(
            self.delegate.write(request, finding, target),
            self.build_root,
            self.project_root,
        )


class _RecordedEvaluationGateway:
    def __init__(self, delegate, build_root: Path) -> None:
        self.delegate = delegate
        self.build_root = build_root
        self.directory = build_root / "optimization" / "evaluations"
        self.directory.mkdir(parents=True, exist_ok=True)

    def record(self, report, *, baseline: bool = False) -> ResourceRef:
        name = "baseline.json" if baseline else f"{report.sha256()}.json"
        path = self.directory / name
        payload = json.dumps(
            report.to_wire(), ensure_ascii=False, indent=2
        ) + "\n"
        if path.exists() and path.read_text(encoding="utf-8") != payload:
            raise FileExistsError("optimization evaluation record is immutable")
        if not path.exists():
            temporary = path.with_suffix(path.suffix + ".tmp")
            temporary.write_text(payload, encoding="utf-8")
            temporary.replace(path)
        return _file_ref(path, self.build_root)

    def evaluate(self, candidate, correction, *, final_gate):
        report = self.delegate.evaluate(
            candidate, correction, final_gate=final_gate
        )
        self.record(report)
        return report


class Jobs:
    def __init__(
        self,
        project: Path,
        harness: Path,
        maven="mvn",
        *,
        data_dir=None,
        max_instances=None,
        protect_disk=False,
        optimization_gateway_factory=None,
        business_package_anchor=None,
        harness_archive_anchor=None,
    ):
        from .business_packages import FINANCE_HARNESS_BUSINESS_PACKAGE_V01

        self.project = project
        self.data_dir = data_dir or project / "build"
        self.max_instances = max_instances
        self.protect_disk = protect_disk
        self.builder = SolutionBuilder(project, harness, maven=maven,
                                       allow_template_fallback=True,
                                       service_output_root=self.data_dir if data_dir is not None else None,
                                       harness_archive_anchor=harness_archive_anchor)
        self.builder.service_cancel = threading.Event()
        self.records = self.data_dir / "workbench-records"
        self.records.mkdir(parents=True, exist_ok=True)
        self.inputs = self.data_dir / "workbench-inputs"
        self.inputs.mkdir(exist_ok=True)
        self.jobs: dict[str, dict] = {}
        self.processes: dict[str, subprocess.Popen] = {}
        self.tokens: dict[str, str] = {}
        self.sessions: dict[str, str] = {}
        self.pending_sessions: dict[str, object] = {}
        self.optimizations: set[str] = set()
        self.optimization_gateway_factory = optimization_gateway_factory
        self.business_package_anchor = (
            business_package_anchor or FINANCE_HARNESS_BUSINESS_PACKAGE_V01
        )
        self.closing = False
        self.lock = threading.RLock()
        from .experience import Experience
        self.experience = Experience(self)
        self.queue = ThreadPoolExecutor(max_workers=1)
        from .method_assets import MethodAssets
        from .business_assets import check_source
        self.method_assets = MethodAssets(
            self.data_dir / "private-assets",
            source_check=lambda value: check_source(
                self.project,
                self.builder.harness_repo,
                value,
                package_anchor=self.business_package_anchor,
            ),
        )
        from .execution_history import ExecutionHistory
        self.execution_history = ExecutionHistory(self.data_dir)
        for path in self.records.glob("agent-*.json"):
            job = json.loads(path.read_text(encoding="utf-8"))
            if job["status"] in {"RUNNING", "STARTING", "STOPPING"}:
                job["status"] = "UNMANAGED_AFTER_RESTART"
            if job["status"] in {"QUEUED", "BUILDING"}:
                job["status"] = "INTERRUPTED"
            self.jobs[job["id"]] = job
        atexit.register(self.close)

    def path(self, job_id):
        return self.data_dir / "workbench" / job_id

    def save(self, job):
        write_json(self.records / (job["id"] + ".json"), job)

    def owned(self, job_id, owner):
        job = self.jobs.get(job_id)
        if job is None or job["owner"] != owner:
            raise PermissionError("JOB_NOT_AVAILABLE")
        return job

    def create(self, body, owner, *, selection=None, model_snapshot=None, directory_snapshot=None, method_snapshot=None,
               generation_binding=None):
        if generation_binding is not None:
            from .model_generation import freeze_generation
            model_snapshot = freeze_generation(model_snapshot, generation_binding)
        if not isinstance(body, dict) or set(body) != {"capabilities", "runtime", "model", "connection"}:
            raise ValueError("INVALID_SELECTION")
        from .python_service import RUNTIME_ID, python_solution
        explicit_runtime = selection is not None and selection.get("schema_version") == "0.10"
        from .managed_runtime_support import RUNTIME_ID as MANAGED_RUNTIME_ID
        allowed_runtimes = {"openjiuwen-java-0.1.15", RUNTIME_ID}
        if explicit_runtime:
            allowed_runtimes.add(MANAGED_RUNTIME_ID)
        if (body["runtime"] not in allowed_runtimes
                or (body["model"], body["connection"]) != ("configured-model", "local-simulator")):
            raise ValueError("UNSUPPORTED_COMBINATION")
        from .qualification_access import require_qualification_access
        require_qualification_access(runtime_id=body["runtime"])
        job_id = "agent-" + uuid.uuid4().hex[:16]
        from .method_assets import runtime_reference
        unified = selection is not None and selection.get("schema_version") in {"0.9", "0.10"}
        managed = unified or selection is not None and selection.get("schema_version") == "0.8"
        runtime_binding = runtime_admission = None
        if unified:
            from .workbench_contracts import parse_selection
            selected = parse_selection(selection)
            if selected.legacy() != body:
                raise ValueError("INVALID_SELECTION")
            if selected.methodRef is not None:
                owned_snapshot = self.method_assets.release(owner, selected.methodRef.reference())
                if method_snapshot is not None and method_snapshot != owned_snapshot:
                    raise ValueError("ASSEMBLY_METHOD_SNAPSHOT_MISMATCH")
                method_snapshot = owned_snapshot
                self.method_assets.check_source(method_snapshot)
                if selected.reuseFromBuildId:
                    with self.lock: self.owned(selected.reuseFromBuildId, owner)
            solution, directory_snapshot, plan = selected.resolve_details(
                self.builder.project_root, self.builder.harness_repo, job_id, method_snapshot)
            if explicit_runtime:
                from .managed_runtime_support import require_backend_admission
                runtime_binding = plan["runtimeBinding"]
                runtime_admission = require_backend_admission(self.builder.project_root,
                    self.builder.harness_repo, solution, runtime_binding, method_snapshot=method_snapshot)
        elif managed:
            from .workbench_contracts import ManagedWorkbenchSelection
            selected = ManagedWorkbenchSelection.model_validate(selection)
            if selected.legacy() != body or method_snapshot is not None:
                raise ValueError("INVALID_SELECTION")
            solution = selected.resolve(self.builder.project_root, self.builder.harness_repo, job_id)
        elif body['runtime'] == RUNTIME_ID:
            solution = python_solution(body['capabilities'], job_id,
                runtime_reference(method_snapshot) if method_snapshot is not None else None,
                method_snapshot['data']['capabilityIds'] if method_snapshot is not None else None)
        elif method_snapshot is not None:
            solution = solution_for(body["capabilities"], job_id, runtime_reference(method_snapshot), method_snapshot["data"]["capabilityIds"])
        else:
            solution = solution_for(body["capabilities"], job_id)
        source_id = selection.get("reuseFromBuildId") if selection is not None else None
        if source_id:
            with self.lock:
                self._require_reuse_runtime(source_id, owner, solution["runtime"])
        if selection is not None and not managed:
            from .component_plan import qualify_solution
            # Python's delegated Java bridge is qualified again in build_python_service.
            bridge_view = dict(solution, runtime=solution_for(body["capabilities"], job_id)["runtime"])
            qualify_solution(self.builder.project_root, self.builder.harness_repo, bridge_view, method_snapshot)
        with self.lock:
            if self.closing: raise ValueError("WORKBENCH_STOPPING")
            if method_snapshot is not None:
                self.method_assets.require_active(owner, method_snapshot["assetId"])
                self.method_assets.check_source(method_snapshot)
            if self.protect_disk and len(self.jobs) >= 20:
                raise ValueError("BUILD_RETENTION_LIMIT_REACHED")
            if self.protect_disk and shutil.disk_usage(self.data_dir).free < 5 * 1024 ** 3:
                raise ValueError("INSUFFICIENT_BUILD_STORAGE")
            if sum(j["status"] in {"BUILDING", "QUEUED"} for j in self.jobs.values()) >= 5:
                raise ValueError("BUILD_QUEUE_FULL")
            job = {"id": job_id, "owner": owner, "status": "QUEUED", "capabilities": body["capabilities"], "createdAt": time.time()}
            if selection is not None: job["selection"] = selection
            if model_snapshot is not None: job["modelSelection"] = model_snapshot
            if directory_snapshot is not None: job["directorySelection"] = directory_snapshot
            if runtime_binding is not None:
                job["runtimeBinding"] = json.loads(json.dumps(runtime_binding))
                job["runtimeAdmission"] = json.loads(json.dumps(runtime_admission))
                job["runtimePresentation"] = json.loads(json.dumps(runtime_binding["runtimePresentation"]))
            elif directory_snapshot is not None and isinstance(directory_snapshot.get("runtime"), dict):
                from .managed_launcher import runtime_presentation
                job["runtimePresentation"] = runtime_presentation(directory_snapshot["runtime"], body["runtime"])
            if method_snapshot is not None:
                job["methodRef"] = {k: method_snapshot[k] for k in ("assetId", "version", "releaseDigest")}
            self.jobs[job_id] = job; self.save(job)
            write_json(self.inputs / (job_id + ".json"), solution)
            self.queue.submit(self.build, job_id)
            return self.public(job)

    def _require_reuse_runtime(self, source_id, owner, runtime):
        source = self.owned(source_id, owner)
        if source["status"] not in {"BUILT", "STOPPED", "RUNNING"}:
            raise ValueError("REUSABLE_HOST_INCOMPATIBLE")
        from .managed_launcher import require_reuse_runtime
        require_reuse_runtime(self.path(source_id) / "candidate", runtime)
        return source

    def build(self, job_id):
        job = self.jobs[job_id]
        with self.lock:
            job["status"] = "BUILDING"; self.save(job)
        try:
            options = {"model_selection": job["modelSelection"]} if job.get("modelSelection") else {}
            if job.get("selection", {}).get("schema_version") in {"0.8", "0.9", "0.10"}:
                options["managed"] = True
            if job.get("runtimeBinding") is not None:
                options.update(runtime_binding=job["runtimeBinding"], runtime_admission=job["runtimeAdmission"])
            selected_observation = os.environ.get("FH_OBSERVATION_REQUIREMENTS")
            if selected_observation:
                options["observation_requirements"] = Path(selected_observation)
            if job.get("selection", {}).get("schema_version") in {"0.6", "0.7", "0.8", "0.9", "0.10"}:
                from .terminal_ui import presentation_config
                options["interface_config"] = presentation_config(job["selection"])
            if job.get("methodRef"):
                options["method_snapshot"] = self.method_assets.release(job["owner"], job["methodRef"])
                self.method_assets.check_source(options["method_snapshot"])
                source_id = job.get("selection", {}).get("reuseFromBuildId")
                solution = json.loads((self.inputs / (job_id + ".json")).read_bytes())
                if source_id:
                    with self.lock:
                        self._require_reuse_runtime(source_id, job["owner"], solution["runtime"])
                    options["reuse_candidate"] = self.path(source_id) / "candidate"
                elif (solution["runtime"]["family"], solution["runtime"]["language"]) == ("openjiuwen", "java"):
                    from .method_trials import reusable_seed
                    seed = reusable_seed(self, job["owner"])
                    if seed is not None:
                        from .managed_launcher import require_reuse_runtime
                        require_reuse_runtime(seed, solution["runtime"])
                        options["reuse_candidate"] = seed
            self.builder.build(self.inputs / (job_id + ".json"), self.path(job_id), **options)
            status, error = "BUILT", None
        except Exception:
            status, error = "FAILED", "BUILD_FAILED_CHECK_STAGES"
        with self.lock:
            job.update(status=status, error=error); self.save(job)

    def public(self, job):
        with self.lock:
            child = self.processes.get(job["id"])
            if child is not None and child.poll() is not None and job["status"] == "RUNNING":
                job.update(status="FAILED", error="INSTANCE_EXITED"); self.sessions.pop(job["id"], None); self.save(job)
            private_fields = {"owner", "optimizationViewBinding"}
            value = {k: v for k, v in job.items() if k not in private_fields}
            report = self.path(job["id"]) / "build-report.json"
            if report.exists():
                value["report"] = json.loads(report.read_text(encoding="utf-8"))
            value["sessionActive"] = job["id"] in self.sessions
            return value

    def attach_optimization(self, job_id, owner, binding):
        """Persist validated references for the owner-scoped read model."""
        validated = OptimizationViewBinding.model_validate(binding)
        with self.lock:
            job = self.owned(job_id, owner)
            job["optimizationViewBinding"] = validated.model_dump(mode="json")
            self.save(job)
            return self.public(job)

    def run_optimization(
        self,
        job_id,
        owner,
        request,
        baseline_report,
        *,
        correction_executor,
        rebuild_gateway,
        evaluation_gateway,
        external_proposal_gateway=None,
    ):
        """Run the accepted controller and publish its durable Build-owned view."""
        with self.lock:
            job = self.owned(job_id, owner)
            if job["status"] not in {"BUILT", "STOPPED"}:
                raise ValueError("BUILD_OR_STOP_REQUIRED")
            if job_id in self.optimizations:
                raise ValueError("OPTIMIZATION_ALREADY_RUNNING")
            self.optimizations.add(job_id)
            build_root = self.path(job_id).resolve()
            created_at = job.get("createdAt")
            selection = job.get("selection")
        try:
            evaluations = _RecordedEvaluationGateway(evaluation_gateway, build_root)
            baseline_ref = evaluations.record(baseline_report, baseline=True)
            proposal_gateway = (
                None
                if external_proposal_gateway is None
                else _BuildOwnedProposalGateway(
                    external_proposal_gateway, build_root, self.project.resolve()
                )
            )
            controller = OptimizationController(
                correction_executor=_BuildOwnedCorrectionExecutor(
                    correction_executor, build_root, self.project.resolve()
                ),
                rebuild_gateway=_BuildOwnedRebuildGateway(
                    rebuild_gateway, build_root, self.project.resolve()
                ),
                evaluation_gateway=evaluations,
                record_store=OptimizationRecordStore(
                    build_root / "optimization", build_root
                ),
                external_proposal_gateway=proposal_gateway,
            )
            result = controller.run(request, baseline_report)
            binding = self._optimization_binding(
                build_root, request, baseline_report, baseline_ref, result
            )
            view = OptimizationViewProjector(build_root, job_id).project(
                binding.model_dump(mode="json"),
                build_status="SUCCEEDED",
                created_at=created_at,
                selection=selection,
            )
            if view["verifiability"]["status"] != "verified":
                raise ValueError("OPTIMIZATION_BINDING_INVALID")
            self.attach_optimization(job_id, owner, binding)
            return result
        finally:
            with self.lock:
                self.optimizations.discard(job_id)

    def run_optimization_from_records(
        self, job_id, owner, request_ref, baseline_evaluation_ref
    ):
        """Run Optimization from verified Build-owned control-plane records."""
        build_root = self.path(job_id).resolve()
        project_root = self.project.resolve()
        with self.lock:
            self.owned(job_id, owner)
            factory = self.optimization_gateway_factory
        if factory is None:
            raise ValueError("OPTIMIZATION_EXECUTION_UNAVAILABLE")
        request_path = build_root / _build_owned_ref(
            ResourceRef.model_validate(request_ref), build_root, project_root
        ).locator
        baseline_path = build_root / _build_owned_ref(
            ResourceRef.model_validate(baseline_evaluation_ref),
            build_root,
            project_root,
        ).locator
        request = OptimizationRequest.model_validate_json(
            request_path.read_text(encoding="utf-8")
        )
        baseline_report = SandboxEvaluationReport.model_validate_json(
            baseline_path.read_text(encoding="utf-8")
        )
        gateways = factory(job_id, owner, request, baseline_report)
        if not isinstance(gateways, WorkbenchOptimizationGateways):
            raise TypeError("OPTIMIZATION_GATEWAYS_INVALID")
        return self.run_optimization(
            job_id,
            owner,
            request,
            baseline_report,
            correction_executor=gateways.correction_executor,
            rebuild_gateway=gateways.rebuild_gateway,
            evaluation_gateway=gateways.evaluation_gateway,
            external_proposal_gateway=gateways.external_proposal_gateway,
        )

    def _optimization_binding(
        self,
        build_root,
        request,
        baseline_report,
        baseline_ref,
        result,
    ):
        request_ref = _file_ref(build_root / "optimization" / "request.json", build_root)
        result_ref = _file_ref(build_root / "optimization" / "result.json", build_root)
        rounds = [
            OptimizationRoundRecord.model_validate_json(
                (build_root / ref.locator).read_text(encoding="utf-8")
            )
            for ref in result.record_refs
        ]

        if result.best_snapshot.lock_digest == request.baseline.lock_digest:
            lock_ref = _file_ref(build_root / "solution-lock.json", build_root)
            descriptor_ref = _file_ref(
                build_root / "candidate" / "candidate-descriptor.json", build_root
            )
        else:
            matching = next(
                (
                    item
                    for item in reversed(rounds)
                    if item.candidate_snapshot == result.best_snapshot
                ),
                None,
            )
            if matching is None or matching.new_lock_ref is None:
                raise ValueError("OPTIMIZATION_BEST_SNAPSHOT_MISSING")
            lock_ref = _build_owned_ref(
                matching.new_lock_ref, build_root, self.project.resolve()
            )
            lock_path = build_root / lock_ref.locator
            descriptor_ref = _file_ref(
                lock_path.parent / "candidate" / "candidate-descriptor.json",
                build_root,
            )

        report_digests = []
        for record in rounds:
            if record.evaluation_report_digest is not None:
                report_digests.append(record.evaluation_report_digest)
        if result.final_report_digest is not None:
            report_digests.append(result.final_report_digest)
        evaluation_refs = []
        for report_digest in dict.fromkeys(report_digests):
            if report_digest != baseline_report.sha256():
                evaluation_refs.append(
                    _file_ref(
                        build_root
                        / "optimization"
                        / "evaluations"
                        / f"{report_digest}.json",
                        build_root,
                    )
                )

        proposal_refs = []
        proposal_locators = set()
        for record in rounds:
            for ref in record.validation_refs:
                owned = _build_owned_ref(ref, build_root, self.project.resolve())
                path = build_root / owned.locator
                try:
                    value = json.loads(path.read_text(encoding="utf-8"))
                except (OSError, UnicodeDecodeError, json.JSONDecodeError):
                    continue
                if (
                    value.get("schemaVersion")
                    == "finance-harness-asset-change-proposal.v1"
                    and owned.locator not in proposal_locators
                ):
                    proposal_refs.append(owned)
                    proposal_locators.add(owned.locator)

        return OptimizationViewBinding(
            candidate_descriptor_ref=descriptor_ref,
            solution_lock_ref=lock_ref,
            request_ref=request_ref,
            baseline_evaluation_ref=baseline_ref,
            round_refs=result.record_refs,
            result_ref=result_ref,
            evaluation_refs=tuple(evaluation_refs),
            proposal_refs=tuple(proposal_refs),
        )

    def _capture(self, job_id):
        # Called under lock. Popen object identity (not PID) plus token identifies a generation.
        return InstanceRequest(self.processes.get(job_id), self.tokens.get(job_id), self.jobs[job_id].get("port"))

    def _same_instance(self, job_id, target):
        return (target.child is not None and self.processes.get(job_id) is target.child
                and self.tokens.get(job_id) == target.token)

    def _detach(self, job_id, target):
        # Called under lock; cleanup is conditional on the captured generation.
        if not self._same_instance(job_id, target): return False
        self.processes.pop(job_id, None); self.tokens.pop(job_id, None)
        self.sessions.pop(job_id, None); self.pending_sessions.pop(job_id, None)
        return True

    def _request(self, job_id, route, body=None, timeout=130, *, instance=None):
        with self.lock:
            target = instance or self._capture(job_id)
            if not self._same_instance(job_id, target): raise RuntimeError("INSTANCE_CHANGED")
            if target.child.poll() is not None: raise RuntimeError("INSTANCE_NOT_RUNNING")
        # Never resolve the port/token again after releasing the generation lock.
        request = urllib.request.Request("http://127.0.0.1:" + str(target.port) + route,
                data=None if body is None else json.dumps(body).encode(),
                headers={"Authorization": "Bearer " + target.token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def _stream_request(self, job_id, route, body, timeout=130, *, instance=None):
        """Yield one decoded event from an authenticated candidate SSE response."""
        with self.lock:
            target = instance or self._capture(job_id)
            if not self._same_instance(job_id, target): raise RuntimeError("INSTANCE_CHANGED")
            if target.child.poll() is not None: raise RuntimeError("INSTANCE_NOT_RUNNING")
        request = urllib.request.Request("http://127.0.0.1:" + str(target.port) + route,
                data=json.dumps(body).encode(),
                headers={"Authorization": "Bearer " + target.token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                if response.status != 200: raise RuntimeError("AG_UI_STREAM_REJECTED")
                for raw in response:
                    line = raw.decode("utf-8").rstrip("\r\n")
                    if not line.startswith("data: "): continue
                    event = json.loads(line[6:])
                    if not isinstance(event, dict): raise RuntimeError("AG_UI_EVENT_INVALID")
                    yield event
        except urllib.error.HTTPError as error:
            raise RuntimeError("AG_UI_STREAM_REJECTED") from error

    def start(self, job_id, owner, *, expected_artifact=None):
        with self.lock:
            if self.closing: raise ValueError("WORKBENCH_STOPPING")
            job = self.owned(job_id, owner)
            report_file = self.path(job_id) / "build-report.json"
            retry_built = (job["status"] == "FAILED" and report_file.is_file()
                           and json.loads(report_file.read_text()).get("status") == "BUILT"
                           and (job_id not in self.processes or self.processes[job_id].poll() is not None))
            if job["status"] not in {"BUILT", "STOPPED"} and not retry_built:
                raise ValueError("BUILD_OR_STOP_REQUIRED")
            if self.max_instances is not None and sum(p.poll() is None for p in self.processes.values()) >= self.max_instances:
                raise ValueError("INSTANCE_LIMIT_REACHED")
            required_keys = ENV_KEYS[3:] if job.get("modelSelection") else ENV_KEYS
            missing = [key for key in required_keys if not os.environ.get(key)]
            cashflow = bool(CASHFLOW & set(job.get("capabilities", [])))
            if cashflow and not os.environ.get("FH_CASHFLOW_ACCESS_POLICY"):
                missing.append("FH_CASHFLOW_ACCESS_POLICY")
            if missing: raise ValueError("CONFIGURATION_MISSING: " + ",".join(missing))
            model_mode = os.environ.get("FH_MODEL_MODE", "LIVE")
            if model_mode not in {"LIVE", "CONTROLLED_TEST"}: raise ValueError("MODEL_MODE_INVALID")
            candidate = self.path(job_id) / "candidate"
            (candidate / "instance.json").unlink(missing_ok=True)
            token = secrets.token_urlsafe(48)
            env = {k: os.environ[k] for k in ("PATH", "HOME", "JAVA_HOME", "TMPDIR", "LANG", *required_keys) if k in os.environ}
            if cashflow:
                env["FH_ACCESS_POLICY"] = os.environ["FH_CASHFLOW_ACCESS_POLICY"]
            if job.get("modelSelection"):
                env.update(ModelRegistry().child_environment(job["modelSelection"]))
            report = json.loads((self.path(job_id) / "build-report.json").read_text(encoding="utf-8"))
            if digest(candidate / "service-lock.json") != report["lockSha256"]:
                raise ValueError("BUILD_LOCK_MISMATCH")
            if expected_artifact is not None and expected_artifact != "sha256:" + report["lockSha256"]:
                raise RuntimeError("ARTIFACT_CHANGED")
            env.update(FH_HOST_TOKEN=token, FH_PORT="0", FH_MODEL_MODE=model_mode, FH_EXPECTED_LOCK=report["lockSha256"])
            if any(capability.startswith("smartaudit.personal.") for capability in job.get("capabilities", [])):
                # An explicit, non-production policy fixture is required for the local personal-loan
                # demonstration. The provider labels its output as advisory and manual-review-only.
                env["FH_SMART_AUDIT_CONTROLLED_TEST_POLICY_ID"] = "PPL-DEMO-2026-01"
            env.update(trusted_business_time_environment())
            env.update(FH_HISTORY_DIR=str(self.data_dir / "execution-history" / hashlib.sha256(owner.encode()).hexdigest()),
                       FH_HISTORY_OWNER=hashlib.sha256(owner.encode()).hexdigest())
            for key in ("FH_SNAPSHOTS_ENABLED", "FH_SNAPSHOT_CAPTURE_OWNERS", "FH_SNAPSHOT_MAX_FILES",
                        "FH_SNAPSHOT_MAX_BYTES", "FH_SNAPSHOT_RETENTION_SECONDS", "FH_SNAPSHOT_FLUSH_MILLIS",
                        "FH_OTHER_MODEL_BODY_COLLECTOR", "FH_OBSERVATION_QUALIFICATION_FILE",
                        "FH_OBSERVATION_QUALIFICATION_SHA256", "FH_AGENTSCOPE_QUALIFICATION_FILE",
                        "FH_AGENTSCOPE_QUALIFICATION_SHA256"):
                if key in os.environ: env[key] = os.environ[key]
            manifest = json.loads((candidate / 'service-lock.json').read_text())
            if ("modelGeneration" in job.get("modelSelection", {}) or
                    "modelGeneration" in manifest.get("modelSelection", {})):
                if job.get("modelSelection") != manifest.get("modelSelection"):
                    raise ValueError("MODEL_GENERATION_LOCK_MISMATCH")
            if job.get("runtimeBinding") is not None and manifest.get("runtimeBinding") != job["runtimeBinding"]:
                raise ValueError("MANAGED_ARTIFACT_BINDING_MISMATCH")
            if manifest["runtime"].get("family") == "agentscope":
                from .component_assembly import AssemblyRejected
                admission = manifest.get("runtimeAdmission", {})
                for field, key in (("nativeQualificationDigest", "FH_AGENTSCOPE_QUALIFICATION_SHA256"),
                                   ("hostQualificationDigest", "FH_OBSERVATION_QUALIFICATION_SHA256")):
                    if not admission.get(field) or admission[field] != env.get(key):
                        raise AssemblyRejected("MANAGED_RUNTIME_QUALIFICATION_REJECTED")
            if task_runtime_required(candidate, manifest):
                if os.environ.get("FH_TASK_SIMULATOR_ENABLED") != "true" or not os.environ.get("FH_TASK_ACCESS_POLICY"):
                    raise ValueError("SIMULATOR_TASK_CONFIGURATION_REQUIRED")
                owner_scope = hashlib.sha256(owner.encode()).hexdigest()
                env.update(FH_TASK_SIMULATOR_ENABLED="true", FH_ACCESS_POLICY=os.environ["FH_TASK_ACCESS_POLICY"],
                           FH_TASK_OWNER=owner_scope,
                           FH_TASK_STATE_DIR=str(self.data_dir / "task-state" / owner_scope / job_id))
            from .managed_launcher import launch_command
            command = launch_command(candidate, manifest)
            logfile = self.path(job_id) / "instance.log"
            with logfile.open("wb") as stream:
                logfile.chmod(0o600)
                child = spawn_process(command, cwd=candidate, env=env, stdout=stream, stderr=subprocess.STDOUT)
            self.processes[job_id] = child; self.tokens[job_id] = token
            self.sessions.pop(job_id, None); self.pending_sessions.pop(job_id, None)
            job.update(status="STARTING", error=None); self.save(job)
            target = self._capture(job_id)
        try:
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline:
                with self.lock:
                    if not self._same_instance(job_id, target) or job["status"] != "STARTING":
                        raise RuntimeError("INSTANCE_CHANGED")
                if child.poll() is not None: raise RuntimeError("INSTANCE_START_FAILED")
                info = candidate / "instance.json"
                if info.exists():
                    try: instance = json.loads(info.read_text(encoding="utf-8"))
                    except ValueError: time.sleep(.1); continue
                    if instance.get("pid") != child.pid or instance.get("buildId") != job_id:
                        raise RuntimeError("INSTANCE_IDENTITY_MISMATCH")
                    with self.lock:
                        if not self._same_instance(job_id, target) or job["status"] != "STARTING":
                            raise RuntimeError("INSTANCE_CHANGED")
                        job.update(port=instance["port"], instanceId=instance["instanceId"])
                        target = self._capture(job_id)
                    code, health = self._request(job_id, "/health", timeout=5, instance=target)
                    lock = json.loads((candidate / "service-lock.json").read_text(encoding="utf-8"))
                    if code != 200 or health.get("artifactLock") != lock or health.get("instanceId") != instance["instanceId"]:
                        raise RuntimeError("INSTANCE_LOCK_MISMATCH")
                    with self.lock:
                        if not self._same_instance(job_id, target) or job["status"] != "STARTING":
                            raise RuntimeError("INSTANCE_CHANGED")
                        job.update(status="RUNNING", artifactLockSha256=digest(candidate / "service-lock.json")); self.save(job)
                        return self.public(job)
                time.sleep(.2)
            raise RuntimeError("INSTANCE_START_TIMEOUT")
        except Exception:
            with self.lock:
                if not self._same_instance(job_id, target) or job["status"] != "STARTING":
                    raise RuntimeError("INSTANCE_CHANGED") from None
                self._detach(job_id, target)
                job.update(status="FAILED", error="INSTANCE_START_FAILED"); self.save(job)
            stop_owned_process(target.child)
            raise RuntimeError("INSTANCE_START_FAILED") from None

    def _terminate(self, job_id, target):
        with self.lock:
            if not self._detach(job_id, target): return
        stop_owned_process(target.child)

    def stop(self, job_id, owner, *, expected_instance=None):
        with self.lock:
            job = self.owned(job_id, owner)
            if expected_instance is not None and job.get("instanceId") != expected_instance:
                raise RuntimeError("INSTANCE_CHANGED")
            if job_id not in self.processes: raise ValueError("NO_OWNED_PROCESS")
            if job["status"] == "STOPPING": raise RuntimeError("INSTANCE_CHANGED")
            target = self._capture(job_id)
            job["status"] = "STOPPING"; self.save(job)
        try:
            stop_owned_process(target.child)
        except Exception:
            with self.lock:
                if not self._same_instance(job_id, target) or job["status"] != "STOPPING":
                    raise RuntimeError("INSTANCE_CHANGED") from None
                job.update(status="FAILED", error="INSTANCE_STOP_FAILED"); self.save(job)
            raise RuntimeError("INSTANCE_STOP_FAILED") from None
        with self.lock:
            if not self._same_instance(job_id, target) or job["status"] != "STOPPING":
                raise RuntimeError("INSTANCE_CHANGED")
            self._detach(job_id, target)
            job["status"] = "STOPPED"; self.save(job)
            return self.public(job)

    def artifact(self, job_id, owner):
        job = self.owned(job_id, owner)
        candidate = self.path(job_id) / "candidate"
        report = json.loads((self.path(job_id) / "build-report.json").read_text())
        if job["status"] not in {"BUILT", "RUNNING", "STOPPED"} and not (job["status"] == "FAILED" and report.get("status") == "BUILT"):
            raise ValueError("BUILD_REQUIRED")
        lock_file = candidate / "service-lock.json"
        if digest(lock_file) != report["lockSha256"]: raise ValueError("ARTIFACT_LOCK_MISMATCH")
        lock = json.loads(lock_file.read_text())
        output = self.path(job_id) / "candidate.zip"
        with self.lock:
            temporary = output.with_suffix(".zip.tmp")
            with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED) as archive:
                for relative, expected in lock["files"].items():
                    source = require_path_within(candidate / relative, candidate)
                    if digest(source) != expected: raise ValueError("ARTIFACT_FILE_MISMATCH")
                    archive.write(source, job_id + "/" + relative)
                archive.write(lock_file, job_id + "/service-lock.json")
            temporary.replace(output)
        return output

    def delete(self, job_id, owner):
        # Serialize against start/stop and preserve only a non-sensitive deletion receipt.
        with self.lock:
            job = self.owned(job_id, owner)
            child = self.processes.get(job_id)
            if (job['status'] not in {'BUILT', 'STOPPED', 'FAILED', 'INTERRUPTED', 'DELETE_FAILED'}
                    or child is not None and child.poll() is None or job_id in self.pending_sessions
                    or job_id in self.optimizations):
                raise ValueError('AGENT_STOP_REQUIRED')
            if not re.fullmatch(r'agent-[a-z0-9-]+', job_id): raise ValueError('DELETE_PATH_INVALID')
            paths = [self.path(job_id), self.inputs / (job_id + '.json'), self.records / (job_id + '.json')]
            archive = self.data_dir / 'deletion-receipts'
            for path in [*paths, archive]:
                if path.is_symlink() or path.resolve() != path.absolute(): raise ValueError('DELETE_PATH_INVALID')
                require_path_within(path, self.data_dir)
            report_path = paths[0] / 'build-report.json'
            report = {}
            if report_path.is_file() and not report_path.is_symlink():
                try: report = json.loads(report_path.read_text(encoding='utf-8'))
                except (OSError, ValueError): pass
            sha = report.get('lockSha256') if isinstance(report, dict) else None
            receipt = {'schemaVersion': 'finance-build-deletion.v1', 'buildId': job_id,
                       'deletedAtEpochSeconds': time.time(),
                       'artifactLockSha256': sha if isinstance(sha, str) and re.fullmatch(r'[a-f0-9]{64}', sha) else None}
            archive.mkdir(parents=True, exist_ok=True)
            try:
                task_scope = self.data_dir / "task-state" / hashlib.sha256(owner.encode()).hexdigest() / job_id
                _remove_idle_task_scope(task_scope, self.data_dir)
                job['status'] = 'DELETE_FAILED'; self.save(job)
                if paths[0].exists(): shutil.rmtree(paths[0])
                paths[1].unlink(missing_ok=True)
                write_json(archive / (job_id + '.json'), receipt)
                paths[2].unlink(missing_ok=True)
            except OSError:
                raise ValueError('AGENT_DELETE_FAILED') from None
            self.jobs.pop(job_id, None); self.processes.pop(job_id, None)
            self.tokens.pop(job_id, None); self.sessions.pop(job_id, None)
            return {'schemaVersion': API_VERSION, 'buildId': job_id, 'status': 'DELETED',
                    'retention': 'MINIMAL_DELETION_RECEIPT_ONLY'}

    def talk(self, job_id, owner, action, body, *, expected_instance=None, expected_session=None):
        changed = (409, {"error": "INSTANCE_CHANGED", "clearConversation": True})
        with self.lock:
            job = self.owned(job_id, owner)
            if job["status"] != "RUNNING": return changed
            if expected_instance is not None and job.get("instanceId") != expected_instance: return changed
            target = self._capture(job_id)
            session = self.sessions.get(job_id)
            if expected_session is not None and session != expected_session: return changed
            reservation = None
            if action in {"session", "switch-model"}:
                if body and (set(body) != {"modelRef"} or not isinstance(body["modelRef"], str)):
                    raise ValueError("HOST_OWNS_IDENTITY")
                if action == "session" and session or job_id in self.pending_sessions: raise ValueError("SESSION_ALREADY_ACTIVE")
                if action == "switch-model" and not session: return changed
                snapshot = job.get("modelSelection")
                if body and (not snapshot or body["modelRef"] not in snapshot["selectedModelRefs"]):
                    return 403, {"error": "MODEL_NOT_SELECTED"}
                reservation = object(); self.pending_sessions[job_id] = reservation
                route = "/sessions"
                if action == "switch-model": body = dict(body, previousSessionId=session)
            else:
                if not session: return 403, {"error": "SESSION_REQUIRED", "clearConversation": True}
                if action not in {"chat", "revoke", "status", *TASK_ACTION_FIELDS}: raise ValueError("INVALID_REQUEST")
                if action in TASK_ACTION_FIELDS: validate_task_body(action, body)
                if action == "chat" and (set(body) not in ({"query"}, {"query", "runId"}) or not isinstance(body["query"], str)
                        or "runId" in body and (not isinstance(body["runId"], str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,128}", body["runId"]))):
                    raise ValueError("INVALID_CHAT_INPUT")
                if action not in {"chat", *TASK_ACTION_FIELDS} and body: raise ValueError("INVALID_REQUEST")
                route = "/sessions/" + session + "/" + action

        def current():
            return (self._same_instance(job_id, target) and job["status"] == "RUNNING"
                    and self.sessions.get(job_id) == session
                    and (reservation is None or self.pending_sessions.get(job_id) is reservation))

        try: code, result = self._request(job_id, route, body, instance=target)
        except Exception:
            with self.lock:
                if not current(): return changed
                self._detach(job_id, target)
                job.update(status="FAILED", error="MODEL_OR_INSTANCE_TIMEOUT"); self.save(job)
            stop_owned_process(target.child)
            return 504, {"error": "MODEL_OR_INSTANCE_TIMEOUT", "clearConversation": True}
        with self.lock:
            if not current() or target.child.poll() is not None: return changed
            if reservation is not None:
                self.pending_sessions.pop(job_id, None)
                if code == 200: self.sessions[job_id] = result["sessionId"]
                if code == 200:
                    job["activeModelRef"] = result.get("modelRef"); self.save(job)
            if result.get("clearConversation"): self.sessions.pop(job_id, None)
            return code, result

    def close(self):
        with self.lock:
            self.closing = True
            instances = [(job_id, self._capture(job_id)) for job_id in self.processes]
        self.builder.service_cancel.set()
        stop_owned_process(getattr(self.builder, "service_process", None), group=True)
        for job_id, target in instances:
            self._terminate(job_id, target)
            with self.lock:
                if target.child.poll() is not None and job_id not in self.processes:
                    self.jobs[job_id].update(status="STOPPED"); self.save(self.jobs[job_id])
        self.queue.shutdown(wait=True, cancel_futures=True)


def serve(
    harness,
    port=18118,
    maven="mvn",
    *,
    data_dir=None,
    access=None,
    max_instances=None,
    optimization_gateway_factory=None,
    harness_archive_anchor=None,
):
    if access is not None and (data_dir is None or max_instances is None or max_instances < 1):
        raise ValueError("SERVER_STORAGE_AND_INSTANCE_LIMIT_REQUIRED")
    with exclusive_data_dir(data_dir) if data_dir is not None else nullcontext():
        _serve(
            harness,
            port,
            maven,
            data_dir=data_dir,
            access=access,
            max_instances=max_instances,
            optimization_gateway_factory=optimization_gateway_factory,
            harness_archive_anchor=harness_archive_anchor,
        )


def _serve(
    harness,
    port,
    maven,
    *,
    data_dir,
    access,
    max_instances,
    optimization_gateway_factory,
    harness_archive_anchor,
):
    def shutdown(_number, _frame): raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, shutdown)
    jobs = Jobs(
        PROJECT,
        harness,
        maven,
        data_dir=data_dir,
        max_instances=max_instances,
        protect_disk=access is not None,
        optimization_gateway_factory=optimization_gateway_factory,
        harness_archive_anchor=harness_archive_anchor,
    )
    api = WorkbenchApi(jobs)
    secret_path = jobs.records / ".local-session-key"
    if not secret_path.exists():
        fd = os.open(secret_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as stream: stream.write(secrets.token_hex(32))
    secret = private_file(secret_path) if access else secret_path.read_bytes()
    cookie_name = "__Host-fh_studio" if access else "fh_local"

    def sign(value): return hmac.new(secret, value.encode(), hashlib.sha256).hexdigest()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args): pass

        def reply(self, status, value, cookie=None, content_type="application/json; charset=utf-8"):
            data = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", content_type); self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store"); self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")
            if cookie: self.send_header("Set-Cookie", cookie_name + "=" + cookie + "; HttpOnly; SameSite=Strict; Path=/" + ("; Secure" if access else ""))
            self.end_headers(); self.wfile.write(data)

        def identity(self):
            cookie = SimpleCookie(self.headers.get("Cookie", ""))
            value = cookie.get(cookie_name)
            if value:
                uid, _, signature = value.value.partition(".")
                if (len(uid) == 32 and hmac.compare_digest(signature, sign(uid))
                        and (not access or uid == access.owner(self.proxy_user, secret))): return uid
            return None

        def guard(self):
            if access:
                self.proxy_user = access.authenticate(self.headers, self.client_address[0])
                return
            expected = "127.0.0.1:" + str(self.server.server_port)
            if self.headers.get("Host") != expected: raise PermissionError("LOOPBACK_HOST_REQUIRED")
            origin = self.headers.get("Origin")
            if origin and origin != "http://" + expected: raise PermissionError("ORIGIN_REJECTED")

        def api_failure(self, error):
            status, detail = public_error(error)
            self.reply(status, {"schemaVersion": API_VERSION, "error": detail,
                                "clearConversation": detail["code"] in {"INSTANCE_CHANGED", "AUTHORIZATION_REJECTED",
                                    "SESSION_REQUIRED", "SESSION_UNAVAILABLE", "MODEL_CONNECTION_UNAVAILABLE",
                                    "MODEL_CONFIGURATION_CHANGED"}})

        def do_GET(self):
            try:
                self.guard(); path = urlparse(self.path).path
                if path.startswith("/experience/"):
                    parts = path.split("/")
                    if len(parts) != 4 or not re.fullmatch(r"agent-[a-z0-9-]+", parts[2]): raise PermissionError("EXPERIENCE_REJECTED")
                    name = parts[3] or "index.html"
                    if name not in {"index.html", "app.js", "style.css", "icons.svg"}: raise PermissionError("EXPERIENCE_REJECTED")
                    root = jobs.path(parts[2]) / "candidate"
                    report = json.loads((jobs.path(parts[2]) / "build-report.json").read_text())
                    if digest(root / "service-lock.json") != report["lockSha256"]: raise PermissionError("ARTIFACT_CHANGED")
                    lock = json.loads((root / "service-lock.json").read_text())
                    file = root / "ui" / name
                    if file.is_symlink() or digest(file) != lock.get("files", {}).get("ui/" + name): raise PermissionError("ARTIFACT_CHANGED")
                    types = {"html": "text/html; charset=utf-8", "js": "text/javascript; charset=utf-8", "css": "text/css", "svg": "image/svg+xml"}
                    self.reply(200, file.read_bytes(), content_type=types[name.split(".")[-1]]); return
                if path in {"/", "/app.js", "/ag-ui.js", "/style.css", "/catalog.css", "/live.css", "/terminal.css", "/icons.svg",
                            "/finance-harness-logo.png", "/finance-harness-mark.png", "/finance-harness-favicon.png"}:
                    name = "index.html" if path == "/" else path[1:]
                    types = {"html": "text/html; charset=utf-8", "js": "text/javascript; charset=utf-8", "css": "text/css; charset=utf-8", "svg": "image/svg+xml", "png": "image/png"}
                    self.reply(200, (PUBLIC / name).read_bytes(), content_type=types[name.split(".")[-1]]); return
                if path == "/api/bootstrap":
                    uid = access.owner(self.proxy_user, secret) if access else self.identity() or secrets.token_hex(16)
                    self.reply(200, {"csrf": sign("csrf:" + uid), "runtime": "openjiuwen-java-0.1.15",
                            "modelConfigured": all(os.environ.get(k) for k in ENV_KEYS[:3]),
                            "modelName": connection_status()["connections"][0]["modelName"] or "未配置",
                            "bankConfigured": all(os.environ.get(k) for k in ENV_KEYS[3:]),
                            "deploymentMode": "AUTHENTICATED_SERVER" if access else "LOCAL",
                            "jobs": [] if access else [jobs.public(j) for j in list(jobs.jobs.values()) if j["owner"] == uid]}, uid + "." + sign(uid)); return
                uid = self.identity()
                if uid is None: raise PermissionError("LOCAL_SESSION_REQUIRED")
                if path == "/api/v1/execution-history":
                    parameters = parse_qs(urlparse(self.path).query, keep_blank_values=True, max_num_fields=8)
                    if any(len(values) != 1 for values in parameters.values()): raise ValueError("INVALID_HISTORY_FILTER")
                    self.reply(200, jobs.execution_history.list(uid, {key: values[0] for key, values in parameters.items()})); return
                if path.startswith("/api/v1/execution-history/"):
                    parts = path.split("/")
                    if len(parts) == 8 and parts[5] == "snapshots":
                        self.reply(200, jobs.execution_history.snapshot(uid, parts[4], parts[6], parts[7])); return
                    self.reply(200, jobs.execution_history.get(uid, path.rsplit("/", 1)[1])); return
                if path.startswith("/api/v1/"):
                    try:
                        code, value = api.get(path, uid); self.reply(code, value)
                    except Exception as error: self.api_failure(error)
                    return
                parts = path.split("/")
                if not access and len(parts) == 4 and parts[1:3] == ["api", "jobs"]:
                    self.reply(200, jobs.public(jobs.owned(parts[3], uid))); return
                if len(parts) == 5 and parts[1:3] == ["api", "jobs"] and parts[4] == "artifact":
                    path = jobs.artifact(parts[3], uid)
                    self.send_response(200); self.send_header("Content-Type", "application/zip")
                    self.send_header("Content-Length", str(path.stat().st_size))
                    self.send_header("Content-Disposition", 'attachment; filename="' + parts[3] + '.zip"')
                    self.send_header("Cache-Control", "no-store"); self.end_headers()
                    with path.open("rb") as stream: shutil.copyfileobj(stream, self.wfile)
                    return
                self.reply(404, {"error": "NOT_FOUND"})
            except PermissionError as e:
                if urlparse(self.path).path.startswith("/api/v1/"): self.api_failure(e)
                else: self.reply(403, {"error": str(e)})
            except Exception as e:
                if urlparse(self.path).path.startswith("/api/v1/"): self.api_failure(e)
                else: self.reply(500, {"error": "REQUEST_FAILED"})

        def do_POST(self):
            try:
                self.guard()
                parts = urlparse(self.path).path.split("/")
                if len(parts) == 6 and parts[1] == "experience" and parts[3] == "api":
                    self.reply(404, {"error": "NOT_FOUND"}); return
                if len(parts) == 5 and parts[1] == "experience" and parts[3] == "api":
                    token = self.headers.get("X-Agent-Token", "")
                    jobs.experience.current(parts[2], token)
                    length = int(self.headers.get("Content-Length", "0"))
                    limit = 4*1024*1024 if parts[4] == "transcribe" else 16384
                    if not 0 < length <= limit: raise ValueError("INVALID_BODY_SIZE")
                    raw = self.rfile.read(length)
                    try:
                        if parts[4] == "transcribe":
                            self.reply(200, jobs.experience.audio(parts[2], token, raw, self.headers.get("Content-Type", ""))); return
                        if self.headers.get("Content-Type", "").split(";")[0] != "application/json": raise ValueError("JSON_REQUIRED")
                        body = json.loads(raw)
                        if not isinstance(body, dict): raise ValueError("OBJECT_REQUIRED")
                        if parts[4] == "ag-ui":
                            opened = False
                            def send_event(data):
                                nonlocal opened
                                if not opened:
                                    self.send_response(200); self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                                    self.send_header("Cache-Control", "no-store"); self.send_header("X-Accel-Buffering", "no")
                                    self.send_header("Connection", "close"); self.end_headers(); self.close_connection = True; opened = True
                                self.wfile.write(data); self.wfile.flush()
                            try: jobs.experience.stream(parts[2], token, body, send_event)
                            except (BrokenPipeError, ConnectionResetError): pass
                            except Exception as error:
                                if not opened: self.api_failure(error)
                            return
                        self.reply(200, jobs.experience.call(parts[2], token, parts[4], body)); return
                    except Exception as error:
                        from .workbench_contracts import ApiError
                        if isinstance(error, ValueError) and str(error) in {"ASR_UNAVAILABLE", "ASR_DISABLED", "ASR_SERVICE_FAILED", "ASR_SERVICE_REJECTED", "ASR_NO_SPEECH", "AUDIO_INVALID", "AUDIO_DECODE_FAILED", "AUDIO_DURATION_INVALID", "AUDIO_SILENT"}:
                            error = ApiError(str(error), 422)
                        self.api_failure(error); return
                uid = self.identity()
                if uid is None or not hmac.compare_digest(self.headers.get("X-CSRF-Token", ""), sign("csrf:" + uid)):
                    raise PermissionError("LOCAL_SESSION_REQUIRED")
                if self.headers.get("Content-Type", "").split(";")[0] != "application/json": raise ValueError("JSON_REQUIRED")
                length = int(self.headers.get("Content-Length", "0"))
                limit = 1024 * 1024 if parts[1:4] == ["api", "v1", "assets"] else 16_384
                if length <= 0 or length > limit: raise ValueError("INVALID_BODY_SIZE")
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict): raise ValueError("OBJECT_REQUIRED")
                parts = urlparse(self.path).path.split("/")
                if len(parts) == 6 and parts[1:4] == ["api", "v1", "execution-history"] and parts[5] == "client-timing":
                    self.reply(200, jobs.execution_history.save_client_timing(uid, parts[4], body)); return
                if len(parts) == 6 and parts[1:4] == ["api", "v1", "builds"] and parts[5] == "ag-ui":
                    from .ag_ui_transport import parse_input, stream_run
                    request = parse_input(body)
                    opened = False
                    def send_event(data):
                        nonlocal opened
                        if not opened:
                            self.send_response(200)
                            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                            self.send_header("Cache-Control", "no-store")
                            self.send_header("X-Accel-Buffering", "no")
                            self.send_header("Connection", "close")
                            self.end_headers()
                            self.close_connection = True
                            opened = True
                        self.wfile.write(data); self.wfile.flush()
                    try: stream_run(api, parts[4], uid, request, send_event)
                    except (BrokenPipeError, ConnectionResetError): pass
                    except Exception as error:
                        if not opened: self.api_failure(error)
                    return
                if parts[1:3] == ["api", "v1"]:
                    try:
                        if parts[-1:] == ["optimization-runs"]:
                            supplied = self.headers.get("X-Builder-Control-Token", "")
                            expected = os.environ.get("FH_OPTIMIZATION_CONTROL_TOKEN", "")
                            if not expected or not hmac.compare_digest(supplied, expected):
                                raise PermissionError("CONTROL_PLANE_ACCESS_REJECTED")
                        code, value = api.post(urlparse(self.path).path, body, uid); self.reply(code, value)
                    except Exception as error: self.api_failure(error)
                    return
                if access:
                    self.reply(404, {"error": "NOT_FOUND"}); return
                if parts == ["", "api", "jobs"]:
                    self.reply(202, jobs.create(body, uid)); return
                if len(parts) != 5 or parts[1:3] != ["api", "jobs"]: self.reply(404, {"error": "NOT_FOUND"}); return
                job_id, action = parts[3:]
                if action in {"start", "stop"}:
                    if body: raise ValueError("INVALID_REQUEST")
                    self.reply(200, getattr(jobs, action)(job_id, uid)); return
                if action not in {"session", "chat", "revoke", "status"}: self.reply(404, {"error": "NOT_FOUND"}); return
                code, result = jobs.talk(job_id, uid, action, body); self.reply(code, result)
            except PermissionError as e:
                if urlparse(self.path).path.startswith("/api/v1/"): self.api_failure(e)
                else: self.reply(403, {"error": str(e)})
            except ValueError as e:
                if urlparse(self.path).path.startswith("/api/v1/"):
                    self.reply(400, {"schemaVersion": API_VERSION, "error": {"code": "REQUEST_INVALID", "retryable": False}})
                else: self.reply(400, {"error": str(e)})
            except RuntimeError as e:
                if str(e) == "INSTANCE_CHANGED":
                    self.reply(409, {"error": "INSTANCE_CHANGED", "clearConversation": True})
                else: self.reply(503, {"error": "OPERATION_FAILED"})
            except Exception: self.reply(503, {"error": "OPERATION_FAILED"})

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print("Local Finance Workbench: http://127.0.0.1:" + str(server.server_port), flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close(); jobs.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness-repo", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18118)
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--data-dir", type=Path)
    parser.add_argument("--harness-source")
    parser.add_argument("--harness-revision")
    parser.add_argument("--harness-tree-digest")
    parser.add_argument("--public-origin", help="Exact HTTPS origin behind the authenticated local reverse proxy")
    parser.add_argument("--proxy-token-file", type=Path)
    parser.add_argument("--max-instances", type=int, default=2)
    args = parser.parse_args()
    if bool(args.public_origin) != bool(args.proxy_token_file):
        parser.error("public-origin and proxy-token-file must be supplied together")
    access = ServerAccess.load(args.public_origin, args.proxy_token_file) if args.public_origin else None
    if args.max_instances < 1: parser.error("max-instances must be positive")
    if access and args.data_dir is None: parser.error("server mode requires data-dir")
    try:
        archive_anchor = optional_archive_anchor(source=args.harness_source,
                                                 revision=args.harness_revision,
                                                 tree_digest=args.harness_tree_digest)
    except ValueError as error:
        parser.error(str(error))
    if access: os.umask(0o077)
    serve(args.harness_repo.resolve(), args.port, args.maven, data_dir=args.data_dir,
          access=access, max_instances=args.max_instances if access else None,
          harness_archive_anchor=archive_anchor)


if __name__ == "__main__": main()
