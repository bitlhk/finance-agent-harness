from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Callable

from finance_builder_base import ResourceKind
from finance_builder_base.contracts import sanitize_public_text

from .assets import AssetResolver, HarnessArchiveAnchor, ResolvedAsset, tree_digest
from .asset_governance import AssetCatalog, AssetClosure, AssetKind
from .build_process import build_process_options
from .candidate_backend import (
    AssemblyRequest,
    BackendRegistry,
    BuildRequest as CandidateBuildRequest,
    CandidateDescriptor,
    CandidateResourceRef,
    EvidenceRequest,
    HealthRequest,
    InvocationRequest,
    LifecycleStatus,
    OpenJiuwenJavaBackend,
    OpenJiuwenPythonZipAppBackend,
    PreflightRequest,
    StartRequest,
    StopRequest,
    verify_candidate_artifact,
)
from .config import load_solution, validate_solution
from .evaluation import (
    SandboxEvaluationCoordinator,
    SandboxEvaluationReport,
    SandboxEvaluationRequest,
)
from .optimization.models import CandidateOverlay
from .optimization.revision import apply_candidate_overlay


@dataclass
class Stage:
    name: str
    status: str
    durationMs: int
    command: list[str] | None = None
    detail: str | None = None

    def record(self) -> dict[str, Any]:
        return {key: value for key, value in self.__dict__.items() if value is not None}


@dataclass
class BuildResult:
    output_dir: Path
    lock_path: Path
    report_path: Path
    candidate_dir: Path
    status: str
    warnings: list[str] = field(default_factory=list)
    evaluation_report_path: Path | None = None


class SolutionBuilder:
    def __init__(
        self,
        project_root: Path,
        harness_repo: Path,
        maven: str = "mvn",
        git: str = "git",
        node: str = "node",
        pnpm: str = "pnpm",
        python: str = sys.executable,
        allow_template_fallback: bool = False,
        service_output_root: Path | None = None,
        harness_archive_anchor: HarnessArchiveAnchor | None = None,
    ) -> None:
        self.project_root = project_root.resolve()
        self.harness_repo = harness_repo.resolve()
        self.maven = shutil.which(maven) or maven
        self.git = shutil.which(git) or git
        self.node = shutil.which(node) or node
        self.pnpm = shutil.which(pnpm) or pnpm
        self.python = shutil.which(python) or python
        self.allow_template_fallback = allow_template_fallback
        self.service_output_root = service_output_root
        self.harness_archive_anchor = harness_archive_anchor

    def build(
        self,
        solution_path: Path,
        output_dir: Path,
        verify: bool = True,
        *,
        model_selection=None,
        interface_config=None,
        method_snapshot=None,
        reuse_candidate=None,
        observation_requirements=None,
        managed=False,
        runtime_binding=None,
        runtime_admission=None,
        sandbox_evaluation: SandboxEvaluationRequest | None = None,
        candidate_overlay: CandidateOverlay | None = None,
    ) -> BuildResult:
        started = datetime.now(UTC)
        output_dir = output_dir.resolve()
        external_service = (
            self.service_output_root is not None
            and output_dir.is_relative_to(self.service_output_root)
            and output_dir != self.service_output_root
        )
        if (
            output_dir == self.project_root or not output_dir.is_relative_to(self.project_root)
        ) and not external_service:
            raise ValueError("output directory must be a child of the Builder project root")
        stages: list[Stage] = []
        solution = self._timed(
            stages, "validate_input", lambda: (self._load_and_validate(solution_path, managed=True, method_snapshot=method_snapshot,
                                                                        runtime_binding=runtime_binding)
                                               if managed else self._load_and_validate(solution_path))
        )
        local_service_profile = solution["profileRef"]["id"] == "local-read-only"
        smart_audit_profile = solution["profileRef"]["id"] == "internal-control"
        # All managed profiles use the declared local Host template.  Smart Audit
        # has its own Profile wiring in that template; the generic Java reference
        # template contains an unrelated wealth previsit application.
        if managed or local_service_profile:
            if candidate_overlay is not None:
                raise ValueError("candidate overlay is not supported by the delegated builder")
            if sandbox_evaluation is not None:
                raise ValueError(
                    "sandbox evaluation is not supported by the delegated local service builder"
                )
            if runtime_binding is not None:
                from .candidate_backend.agentscope_java import build_managed_candidate
                return build_managed_candidate(self, solution, output_dir, verify=verify,
                    runtime_binding=runtime_binding, runtime_admission=runtime_admission,
                    model_selection=model_selection, interface_config=interface_config,
                    method_snapshot=method_snapshot, reuse_candidate=reuse_candidate,
                    observation_requirements=observation_requirements)
            from .service import build_service
            if solution['runtime']['language'] == 'python':
                from .candidate_backend.openjiuwen_python import OpenJiuwenPythonBackend
                return OpenJiuwenPythonBackend(builder=self, solution=solution, output=output_dir,
                    options=dict(model_selection=model_selection, interface_config=interface_config,
                        method_snapshot=method_snapshot, reuse_candidate=reuse_candidate,
                        observation_requirements=observation_requirements, **({"managed": True} if managed else {}))).generate(verify)
            return build_service(self, solution, output_dir, verify, model_selection=model_selection, interface_config=interface_config,
                                 method_snapshot=method_snapshot, reuse_candidate=reuse_candidate,
                                 observation_requirements=observation_requirements, managed=managed)
        if managed and not smart_audit_profile:
            raise ValueError("MANAGED_HOST_UNSUPPORTED")
        if observation_requirements is not None:
            raise ValueError("OBSERVATION_REQUIREMENTS_REQUIRE_LOCAL_SERVICE")
        if external_service and not smart_audit_profile:
            raise ValueError("EXTERNAL_OUTPUT_ONLY_FOR_LOCAL_SERVICE")
        if solution["profileRef"]["id"] == "shared-read-only":
            if candidate_overlay is not None:
                raise ValueError("candidate overlay is not supported by the delegated builder")
            if sandbox_evaluation is not None:
                raise ValueError(
                    "sandbox evaluation is not supported by the delegated read-only builder"
                )
            from .readonly import build_readonly

            return build_readonly(self, solution, output_dir, verify)
        candidate_dir = output_dir / "candidate"
        # Asset content is immutable for a pinned source reference. Keep it outside
        # the disposable build directory so candidates can share the cold-start cost.
        cache_dir = self.project_root / ".cache" / "assets"
        warnings: list[str] = []
        harness_source, harness_revision = self._harness_identity()
        resolver = AssetResolver(self.harness_repo, cache_dir, self.git)

        catalog = self._timed(
            stages,
            "validate_asset_catalog",
            lambda: AssetCatalog.load(
                self.harness_repo,
                harness_revision,
                archive_anchor=self.harness_archive_anchor,
            ),
        )
        closure = self._timed(
            stages,
            "resolve_asset_graph",
            lambda: catalog.resolve_solution(solution),
        )
        refs = closure.content_references()
        assets = self._timed(
            stages, "resolve_assets", lambda: [resolver.resolve(ref) for ref in refs]
        )
        unresolved = [asset for asset in assets if asset.status != "resolved"]
        if unresolved:
            unresolved_ids = ", ".join(sorted(asset.id for asset in unresolved))
            raise RuntimeError(f"required Solution assets could not be resolved: {unresolved_ids}")

        runtime = self._resolve_runtime(solution, resolver, warnings)
        resolved_with_kind = zip(
            (item.asset.kind for item in closure.installable_entries()),
            assets,
            strict=True,
        )
        classified_assets = tuple(resolved_with_kind)
        domain_packs = [asset for kind, asset in classified_assets if kind is AssetKind.DOMAIN_PACK]
        provider_assets = [asset for kind, asset in classified_assets if kind is AssetKind.PROVIDER]
        if not domain_packs or not provider_assets:
            raise RuntimeError("required Harness domain pack or provider could not be resolved")

        lock = self._lock(
            solution,
            harness_source,
            harness_revision,
            assets,
            runtime,
            started,
            closure=closure,
            candidate_overlay=candidate_overlay,
        )
        lock_bytes = (json.dumps(lock, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        lock_digest = hashlib.sha256(lock_bytes).hexdigest()
        task_id = f"{solution['solutionId']}-build"
        language = solution["runtime"]["language"]
        if language == "java":
            candidate_backend = OpenJiuwenJavaBackend(
                project_root=self.project_root,
                delivery_root=output_dir,
                candidate_dir=candidate_dir,
                solution=solution,
                assets=assets,
                runtime_assets=runtime,
                assemble_action=lambda: self._assemble_candidate(
                    solution,
                    solution_path,
                    candidate_dir,
                    domain_packs,
                    runtime,
                    candidate_overlay=candidate_overlay,
                ),
                maven=self.maven,
            )
        elif language == "python":
            candidate_backend = OpenJiuwenPythonZipAppBackend(
                project_root=self.project_root,
                delivery_root=output_dir,
                candidate_dir=candidate_dir,
                solution=solution,
                assets=assets,
                runtime_assets=runtime,
                python=self.python,
            )
        else:
            raise ValueError("runtime language must explicitly select java or python")
        target = candidate_backend.target()
        backend = BackendRegistry((candidate_backend,)).select(
            target, backend_id=f"openjiuwen-{language}"
        )
        preflight = self._backend_stage(
            stages,
            "candidate_backend_preflight",
            lambda: backend.preflight(
                PreflightRequest(
                    task_id=task_id,
                    target=target,
                    resolved_asset_ids=target.required_asset_ids,
                )
            ),
        )
        if preflight.outcome.status is not LifecycleStatus.PASSED:
            raise RuntimeError("candidate Backend preflight blocked")

        if output_dir.exists():
            shutil.rmtree(output_dir, onerror=self._remove_readonly)
        output_dir.mkdir(parents=True)
        asset_refs = tuple(
            CandidateResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator=f"assets/{asset.id}",
                sha256=asset.digest.removeprefix("sha256:"),
            )
            for asset in [*assets, *runtime.values()]
            if asset.digest is not None
        )
        assembly = self._backend_stage(
            stages,
            "assemble_candidate",
            lambda: backend.assemble(
                AssemblyRequest(
                    task_id=task_id,
                    target=target,
                    lock_digest=lock_digest,
                    workspace=CandidateResourceRef(
                        kind=ResourceKind.WORKSPACE_PATH,
                        locator="candidate",
                    ),
                    asset_refs=asset_refs,
                )
            ),
        )
        if assembly.outcome.status is not LifecycleStatus.PASSED:
            raise RuntimeError("candidate Backend assembly failed")

        lock_path = output_dir / "solution-lock.json"
        lock_path.write_bytes(lock_bytes)
        shutil.copy2(lock_path, candidate_dir / "solution-lock.json")

        descriptor = None
        lifecycle_evidence_refs: tuple[CandidateResourceRef, ...] = ()
        sandbox_evaluation_report: SandboxEvaluationReport | None = None
        sandbox_evaluation_ref: CandidateResourceRef | None = None
        evaluation_report_path: Path | None = None

        def run_reported_stage(name: str, command: list[str], cwd: Path, *, maven: bool = False) -> None:
            try:
                self._run_stage(stages, name, command, cwd, maven=maven)
            except RuntimeError:
                self._write_build_report(
                    output_dir=output_dir,
                    candidate_dir=candidate_dir,
                    solution=solution,
                    started=started,
                    stages=stages,
                    warnings=warnings,
                    descriptor=descriptor,
                    lifecycle_evidence_refs=lifecycle_evidence_refs,
                )
                raise

        if verify and language == "java":
            run_reported_stage("harness_tests", [self.maven, "-q", "test"], self.harness_repo, maven=True)
            run_reported_stage(
                "harness_install",
                [self.maven, "-q", "install", "-DskipTests"],
                self.harness_repo,
                maven=True,
            )
        if verify:
            built = self._backend_stage(
                stages,
                "candidate_compile",
                lambda: backend.build(
                    CandidateBuildRequest(
                        task_id=task_id,
                        candidate_id=assembly.candidate_id,
                        lock_digest=lock_digest,
                        assembly_refs=assembly.assembly_refs,
                    )
                ),
            )
            if built.outcome.status is not LifecycleStatus.PASSED or built.descriptor is None:
                self._write_build_report(
                    output_dir=output_dir,
                    candidate_dir=candidate_dir,
                    solution=solution,
                    started=started,
                    stages=stages,
                    warnings=warnings,
                    descriptor=None,
                    lifecycle_evidence_refs=(),
                )
                raise RuntimeError("candidate Backend build failed")
            descriptor = built.descriptor
            descriptor_path = candidate_dir / "candidate-descriptor.json"
            descriptor_path.write_text(
                json.dumps(descriptor.to_wire(), ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            if (candidate_dir / "agents/domain-runtime/pom.xml").exists():
                run_reported_stage(
                    "candidate_asset_tests",
                    [
                        self.maven,
                        "-q",
                        "-pl",
                        "agents/domain-runtime",
                        "-am",
                        "-Dtest=WealthHarnessAssetTest,EmployeeAssistedBuilderAssetTest",
                        "-Dsurefire.failIfNoSpecifiedTests=false",
                        "test",
                    ],
                    candidate_dir,
                    maven=True,
                )
            if sandbox_evaluation is not None:
                request_ref = self._write_protocol_probe(output_dir, solution, task_id)
                try:
                    execution = SandboxEvaluationCoordinator(output_dir).run(
                        backend=backend,
                        descriptor=descriptor,
                        artifact_verification=verify_candidate_artifact(descriptor, output_dir),
                        request=sandbox_evaluation,
                        protocol_probe_ref=request_ref,
                    )
                except (ValueError, RuntimeError) as exc:
                    stages.append(
                        Stage(
                            name="evaluation_snapshot",
                            status="failed",
                            durationMs=0,
                            detail=sanitize_public_text(str(exc)),
                        )
                    )
                    self._write_build_report(
                        output_dir=output_dir,
                        candidate_dir=candidate_dir,
                        solution=solution,
                        started=started,
                        stages=stages,
                        warnings=warnings,
                        descriptor=descriptor,
                        lifecycle_evidence_refs=(),
                    )
                    raise
                stages.extend(
                    Stage(
                        name=fact.name,
                        status=fact.status,
                        durationMs=fact.duration_ms,
                        detail=fact.detail,
                    )
                    for fact in execution.stages
                )
                lifecycle_evidence_refs = execution.lifecycle_evidence_refs
                sandbox_evaluation_report = execution.report
                sandbox_evaluation_ref = execution.report_ref
                evaluation_report_path = execution.report_path
                if (
                    execution.error_code is not None
                    or execution.report.conclusion.value != "passed"
                ):
                    self._write_build_report(
                        output_dir=output_dir,
                        candidate_dir=candidate_dir,
                        solution=solution,
                        started=started,
                        stages=stages,
                        warnings=warnings,
                        descriptor=descriptor,
                        lifecycle_evidence_refs=lifecycle_evidence_refs,
                        sandbox_evaluation_report=sandbox_evaluation_report,
                        sandbox_evaluation_ref=sandbox_evaluation_ref,
                    )
                    raise RuntimeError("candidate Sandbox Evaluation did not pass")
            else:
                started_candidate = self._backend_stage(
                    stages,
                    "candidate_start",
                    lambda: backend.start(
                        StartRequest(
                            descriptor=descriptor,
                            artifact_verification=verify_candidate_artifact(descriptor, output_dir),
                        )
                    ),
                )
                if (
                    started_candidate.outcome.status is not LifecycleStatus.PASSED
                    or started_candidate.handle is None
                ):
                    self._write_build_report(
                        output_dir=output_dir,
                        candidate_dir=candidate_dir,
                        solution=solution,
                        started=started,
                        stages=stages,
                        warnings=warnings,
                        descriptor=descriptor,
                        lifecycle_evidence_refs=(),
                    )
                    raise RuntimeError("candidate Backend start failed")
                handle = started_candidate.handle
                lifecycle_error: RuntimeError | None = None
                try:
                    health = self._backend_stage(
                        stages,
                        "candidate_health",
                        lambda: backend.health(HealthRequest(handle=handle)),
                    )
                    if health.outcome.status is not LifecycleStatus.PASSED or not health.ready:
                        lifecycle_error = RuntimeError("candidate Backend health check failed")
                    else:
                        request_ref = self._write_protocol_probe(output_dir, solution, task_id)
                        invoked = self._backend_stage(
                            stages,
                            "candidate_protocol_invoke",
                            lambda: backend.invoke(
                                InvocationRequest(
                                    request_id="builder-protocol-probe",
                                    handle=handle,
                                    protocol=descriptor.invocation.protocol,
                                    payload_ref=request_ref,
                                )
                            ),
                        )
                        if invoked.outcome.status is not LifecycleStatus.PASSED:
                            lifecycle_error = RuntimeError(
                                "candidate Backend protocol invocation failed"
                            )
                finally:
                    evidence = self._backend_stage(
                        stages,
                        "candidate_lifecycle_evidence",
                        lambda: backend.collect_evidence(EvidenceRequest(handle=handle)),
                    )
                    lifecycle_evidence_refs = evidence.outcome.evidence_refs
                    if (
                        evidence.outcome.status is not LifecycleStatus.PASSED
                        and lifecycle_error is None
                    ):
                        lifecycle_error = RuntimeError(
                            "candidate Backend evidence collection failed"
                        )
                    stopped = self._backend_stage(
                        stages,
                        "candidate_stop",
                        lambda: backend.stop(StopRequest(handle=handle)),
                    )
                    if (
                        stopped.outcome.status is not LifecycleStatus.PASSED
                        and lifecycle_error is None
                    ):
                        lifecycle_error = RuntimeError("candidate Backend stop failed")
                if lifecycle_error is not None:
                    self._write_build_report(
                        output_dir=output_dir,
                        candidate_dir=candidate_dir,
                        solution=solution,
                        started=started,
                        stages=stages,
                        warnings=warnings,
                        descriptor=descriptor,
                        lifecycle_evidence_refs=lifecycle_evidence_refs,
                    )
                    raise lifecycle_error
            evaluation = next(
                (
                    asset
                    for asset in assets
                    if asset.source.startswith("git+")
                    and asset.path is not None
                    and (asset.path / "eval.yaml").exists()
                ),
                None,
            )
            if evaluation is not None and evaluation.root_path is not None:
                run_reported_stage(
                    "evaluation_dependencies",
                    [self.pnpm, "install", "--frozen-lockfile", "--ignore-scripts"],
                    evaluation.root_path,
                )
                run_reported_stage(
                    "evaluation_frozen_replay",
                    [
                        self.node,
                        str(evaluation.root_path / "node_modules/tsx/dist/cli.mjs"),
                        str(evaluation.root_path / "src/cli.ts"),
                        "replay",
                        "--pack",
                        str(evaluation.path / "eval.yaml"),
                        "--out",
                        str(output_dir / "evaluation"),
                    ],
                    evaluation.root_path,
                )

        if verify:
            profile_id = solution["profileRef"]["id"]
            requirement = (
                "EA deployment identity and an authorized customer reference are required"
                if profile_id == "employee-assisted"
                else "Provider test credentials and an authorized test subject are required"
            )
            warnings.append(
                "generated candidate online business evaluation was not executed; " + requirement
            )

        report_path, status = self._write_build_report(
            output_dir=output_dir,
            candidate_dir=candidate_dir,
            solution=solution,
            started=started,
            stages=stages,
            warnings=warnings,
            descriptor=descriptor,
            lifecycle_evidence_refs=lifecycle_evidence_refs,
            sandbox_evaluation_report=sandbox_evaluation_report,
            sandbox_evaluation_ref=sandbox_evaluation_ref,
        )
        return BuildResult(
            output_dir,
            lock_path,
            report_path,
            candidate_dir,
            status,
            warnings,
            evaluation_report_path,
        )

    @staticmethod
    def _write_build_report(
        *,
        output_dir: Path,
        candidate_dir: Path,
        solution: dict[str, Any],
        started: datetime,
        stages: list[Stage],
        warnings: list[str],
        descriptor: CandidateDescriptor | None,
        lifecycle_evidence_refs: tuple[CandidateResourceRef, ...],
        sandbox_evaluation_report: SandboxEvaluationReport | None = None,
        sandbox_evaluation_ref: CandidateResourceRef | None = None,
    ) -> tuple[Path, str]:
        failed = any(stage.status in {"failed", "blocked"} for stage in stages)
        status = "failed" if failed else ("passed_with_risk" if warnings else "passed")
        artifact = (
            {
                "path": descriptor.artifact.resource.locator.removeprefix("candidate/"),
                "digest": descriptor.artifact.resource.sha256,
            }
            if descriptor is not None
            else None
        )
        report = {
            "schemaVersion": "finance-solution-build-report.v1",
            "solutionId": solution["solutionId"],
            "status": status,
            "startedAt": started.isoformat().replace("+00:00", "Z"),
            "finishedAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
            "stages": [stage.record() for stage in stages],
            "warnings": warnings,
            "candidateArtifact": artifact,
            "candidateBackend": (
                None if descriptor is None else descriptor.backend.to_wire()
            ),
            "candidateLanguage": None if descriptor is None else descriptor.language,
            "candidateRuntime": (
                None if descriptor is None else descriptor.runtime.to_wire()
            ),
            "lifecycleEvidenceRefs": [
                reference.model_dump(mode="json", by_alias=True, exclude_none=True)
                for reference in lifecycle_evidence_refs
            ],
            "sandboxEvaluationReportRef": (
                None
                if sandbox_evaluation_ref is None
                else sandbox_evaluation_ref.model_dump(
                    mode="json", by_alias=True, exclude_none=True
                )
            ),
            "verification": {
                "candidateStarted": all(
                    any(s.name == name and s.status == "passed" for s in stages)
                    for name in ("candidate_start", "candidate_health")
                ),
                "candidateProtocolInvocationExecuted": any(
                    s.name == "candidate_protocol_invoke" and s.status == "passed" for s in stages
                ),
                "candidateAssetTestsExecuted": any(
                    s.name == "candidate_asset_tests" and s.status == "passed" for s in stages
                ),
                "frozenEvidenceReplayExecuted": any(
                    s.name == "evaluation_frozen_replay" and s.status == "passed" for s in stages
                ),
                "candidateOnlineEvaluationExecuted": False,
                "sandboxCandidateEvaluationExecuted": (sandbox_evaluation_report is not None),
                "sandboxCandidateEvaluationStatus": (
                    "not-executed"
                    if sandbox_evaluation_report is None
                    else sandbox_evaluation_report.conclusion.value
                ),
                "note": (
                    "Frozen evidence replay validates the referenced Eval Pack; it does not "
                    "substitute for an authorized online evaluation of the generated Candidate."
                ),
            },
        }
        report_path = output_dir / "build-report.json"
        report_path.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        shutil.copy2(report_path, candidate_dir / "build-report.json")
        return report_path, status

    @staticmethod
    def _write_protocol_probe(
        output_dir: Path, solution: dict[str, Any], task_id: str
    ) -> CandidateResourceRef:
        request_path = output_dir / "runtime" / "requests" / "builder-protocol-probe.json"
        request_path.parent.mkdir(parents=True, exist_ok=True)
        metadata = {
            "verification": "builder-protocol-probe",
            "solutionId": solution["solutionId"],
        }
        document = {
            "jsonrpc": "2.0",
            "id": "builder-protocol-probe",
            "method": "SendMessage",
            "params": {
                "message": {
                    "role": "ROLE_USER",
                    "messageId": "builder-protocol-probe",
                    "contextId": task_id,
                    "parts": [
                        {
                            "kind": "text",
                            "text": "Confirm that the installed Agent service can accept a controlled request.",
                        }
                    ],
                    "metadata": metadata,
                },
                "metadata": metadata,
            },
        }
        payload = (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        request_path.write_bytes(payload)
        return CandidateResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator=request_path.relative_to(output_dir).as_posix(),
            media_type="application/json",
            sha256=hashlib.sha256(payload).hexdigest(),
        )

    @staticmethod
    def _backend_stage(stages: list[Stage], name: str, action: Callable[[], Any]) -> Any:
        started = time.perf_counter()
        result = action()
        outcome = result.outcome
        detail = outcome.error.code if outcome.error is not None else outcome.summary
        stages.append(
            Stage(
                name=name,
                status=outcome.status.value,
                durationMs=max(0, int((time.perf_counter() - started) * 1000)),
                detail=detail,
            )
        )
        return result

    @staticmethod
    def _remove_readonly(function: Callable[..., Any], path: str, _error: Any) -> None:
        os.chmod(path, stat.S_IWRITE)
        function(path)

    @staticmethod
    def _copy_template(source: Path, destination: Path) -> None:
        ignored = {".git", "target", "node_modules", ".asset-revision", ".asset-source"}
        if os.name == "nt" and shutil.which("robocopy"):
            result = subprocess.run(
                [
                    "robocopy",
                    str(source),
                    str(destination),
                    "/E",
                    "/NFL",
                    "/NDL",
                    "/NJH",
                    "/NJS",
                    "/NP",
                    "/MT:16",
                    "/XD",
                    ".git",
                    "target",
                    "node_modules",
                    "/XF",
                    ".asset-revision",
                    ".asset-source",
                ],
                capture_output=True,
                text=True,
            )
            if result.returncode <= 7:
                return
            raise RuntimeError(f"template copy failed: {result.stderr.strip()}")
        shutil.copytree(
            source,
            destination,
            ignore=lambda _directory, names: [name for name in names if name in ignored],
        )

    def _load_and_validate(self, solution_path: Path, *, managed=False, method_snapshot=None,
                           runtime_binding=None) -> dict[str, Any]:
        solution = load_solution(solution_path)
        validate_solution(
            solution,
            self.harness_repo
            / "contracts/json-schema/solution/solution-build-input.v1.schema.json",
        )
        if managed:
            from .component_plan import qualify_solution
            if runtime_binding is not None:
                qualify_solution(self.project_root, self.harness_repo, solution, method_snapshot,
                                 runtime_binding=runtime_binding)
            elif solution["runtime"]["language"] == "python":
                from .python_service import validate_python
                validate_python(solution, managed=True, project=self.project_root,
                                harness=self.harness_repo, method_snapshot=method_snapshot)
            elif solution["runtime"]["language"] == "java":
                qualify_solution(self.project_root, self.harness_repo, solution, method_snapshot)
            else:
                raise ValueError("MANAGED_HOST_UNSUPPORTED")
        else:
            self._validate_assembly_support(solution)
        return solution

    @staticmethod
    def _validate_assembly_support(solution: dict[str, Any]) -> None:
        """Validate delegated recipes; Catalog validates all general compositions."""

        profile = solution["profileRef"]["id"]
        if profile == "local-read-only":
            if solution['runtime']['language'] == 'python':
                from .python_service import validate_python
                validate_python(solution)
                return
            from .service import validate_service

            validate_service(solution)
        elif profile == "shared-read-only":
            from .readonly import validate_readonly

            validate_readonly(solution)

    def _resolve_runtime(
        self, solution: dict[str, Any], resolver: AssetResolver, warnings: list[str]
    ) -> dict[str, Any]:
        runtime = solution["runtime"]
        language = runtime["language"]
        template_ref = {
            "id": f"openjiuwen-{language}-template",
            "version": "pinned",
            "source": runtime["templateRef"],
        }
        integration_ref = {
            "id": "runtime-integration",
            "version": "pinned",
            "source": runtime["integrationRef"],
        }
        template = resolver.resolve(template_ref)
        integration = resolver.resolve(integration_ref)
        fallback = self.project_root / "templates/java-reference-agent"
        if template.status != "resolved":
            if language != "java" or not self.allow_template_fallback:
                raise RuntimeError(f"runtime template could not be resolved: {template.error}")
            warnings.append(f"runtime template fallback used: {template.error}")
            template = ResolvedAsset(
                template.id,
                template.version,
                template.source,
                "fallback",
                tree_digest(fallback),
                revision=template.revision,
                path=fallback,
                error=template.error,
            )
        if integration.status != "resolved":
            raise RuntimeError(f"runtime integration could not be resolved: {integration.error}")
        return {"template": template, "integration": integration}

    def _assemble_candidate(
        self,
        solution: dict[str, Any],
        solution_path: Path,
        candidate_dir: Path,
        domain_packs: list[ResolvedAsset],
        runtime: dict[str, ResolvedAsset],
        *,
        candidate_overlay: CandidateOverlay | None = None,
    ) -> None:
        template = runtime["template"]
        multi_module = (
            template.status == "resolved"
            and template.root_path is not None
            and template.path != template.root_path
        )
        source_root = template.root_path if multi_module else template.path
        self._copy_template(source_root, candidate_dir)
        runtime_dir = (
            candidate_dir / template.path.relative_to(template.root_path)
            if multi_module
            else candidate_dir
        )
        replacements = {
            "@@SOLUTION_ID@@": solution["solutionId"],
            "@@DISPLAY_NAME@@": solution["displayName"],
            "@@MODEL_REF@@": solution["modelRef"],
        }
        for file in runtime_dir.rglob("*"):
            if file.is_file() and file.suffix.lower() in {".xml", ".java", ".md", ".properties"}:
                text = file.read_text(encoding="utf-8")
                for key, value in replacements.items():
                    text = text.replace(key, value)
                file.write_text(text, encoding="utf-8")
        if multi_module:
            if (candidate_dir / "framework/finance-harness-openjiuwen/pom.xml").exists():
                if solution["profileRef"]["id"] == "employee-assisted":
                    self._apply_employee_assisted_overlay(candidate_dir)
            else:
                self._apply_openjiuwen_v11_overlay(candidate_dir, solution["profileRef"]["id"], solution)
            skills_root = candidate_dir / "agents/wealth-aggregate/assets/skills"
        else:
            skills_root = candidate_dir / "src/main/resources/skills"
        skills_root.mkdir(parents=True, exist_ok=True)
        for pack in domain_packs:
            source_skills = pack.path / "skills"
            if source_skills.exists():
                shutil.copytree(source_skills, skills_root, dirs_exist_ok=True)
        shutil.copy2(solution_path, candidate_dir / "solution.yaml")
        (candidate_dir / "solution.normalized.json").write_text(
            json.dumps(solution, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        env_lines = ["FINANCE_AGENT_MODEL="]
        if solution["profileRef"]["id"] == "employee-assisted":
            env_lines.extend(
                [
                    "EA_BASE_URI=http://127.0.0.1:8080",
                    "EA_RUNTIME_ID=",
                    "EA_AGENT_ID=",
                    "EA_ADOPT_ID=",
                    "INTERNAL_RUNTIME_TOKEN_SECRET=",
                    "INTERNAL_RUNTIME_TOKEN_TTL_SECONDS=60",
                ]
            )
        elif solution["profileRef"]["id"] == "internal-control":
            env_lines.extend(
                [
                    "SMART_AUDIT_PRINCIPAL_ID=audit-reviewer",
                    "SMART_AUDIT_SUBJECT_REF=demo-audit-subject",
                    "SMART_AUDIT_SESSION_REF=demo-audit-session",
                    "SMART_AUDIT_PERSONAL_RULE_BASELINE=assets/smart-audit/personal-loan-rules/personal-rule-baseline.v1.json",
                    "SMART_AUDIT_CONTROLLED_TEST_POLICY_ID=PPL-DEMO-2026-01",
                ]
            )
        else:
            env_lines.extend(
                [
                    "NON_AI_BANKING_BASE_URL=",
                    "NON_AI_BANKING_LOGIN_NAME=",
                    "NON_AI_BANKING_LOGIN_SECRET=",
                ]
            )
        (candidate_dir / ".env.example").write_text("\n".join([*env_lines, ""]), encoding="utf-8")
        if candidate_overlay is not None:
            apply_candidate_overlay(candidate_dir, self.project_root, candidate_overlay)

    def _apply_openjiuwen_v11_overlay(self, candidate_dir: Path, profile_id: str, solution: dict[str, Any]) -> None:
        profiles = {
            "customer-self-service": {
                "provider": "finance-provider-non-ai-banking",
                "integration": None,
            },
            "employee-assisted": {
                "provider": "finance-provider-employee-agent",
                "integration": "finance-integration-employee-agent-java",
            },
            "internal-control": {
                "provider": "finance-provider-smart-audit",
                "integration": None,
            },
        }
        if profile_id not in profiles:
            raise ValueError(f"unsupported OpenJiuwen V1.1 profile: {profile_id}")

        root_pom = candidate_dir / "pom.xml"
        root_text = root_pom.read_text(encoding="utf-8")
        if "<finance.harness.version>" not in root_text:
            root_text = root_text.replace(
                "  </properties>",
                "    <finance.harness.version>0.1.0-SNAPSHOT</finance.harness.version>\n"
                "  </properties>",
            )
            root_pom.write_text(root_text, encoding="utf-8")

        dependencies = [
            "finance-harness-openjiuwen-java",
            profiles[profile_id]["provider"],
        ]
        if profiles[profile_id]["integration"]:
            dependencies.append(profiles[profile_id]["integration"])
        domain_pom = candidate_dir / "agents/domain-runtime/pom.xml"
        domain_text = domain_pom.read_text(encoding="utf-8")
        generated = "\n".join(
            "    <dependency>\n"
            "      <groupId>io.github.bitlhk.finance</groupId>\n"
            f"      <artifactId>{artifact}</artifactId>\n"
            "      <version>${finance.harness.version}</version>\n"
            "    </dependency>"
            for artifact in dependencies
        )
        domain_text = domain_text.replace("  </dependencies>", generated + "\n  </dependencies>")
        domain_pom.write_text(domain_text, encoding="utf-8")

        java_root = candidate_dir / (
            "agents/domain-runtime/src/main/java/com/openjiuwen/intelligence/finance/harness"
        )
        java_root.mkdir(parents=True, exist_ok=True)
        overlay = self.project_root / "templates/openjiuwen-v1.1-overlay"
        if profile_id == "internal-control":
            packs = {ref["id"] for ref in solution["capabilityPackRefs"]}
            allowed = {"smart-audit-personal-loan-audit", "smart-audit-enterprise-credit-audit"}
            if len(packs) != 1 or not packs <= allowed:
                raise ValueError("internal-control candidate must install exactly one Smart Audit pack")
            scenario = "personal-loan" if "smart-audit-personal-loan-audit" in packs else "enterprise-credit"
            shutil.copy2(overlay / profile_id / "SmartAuditCapabilityAdapter.java", java_root)
            shutil.copy2(overlay / profile_id / "FinanceHarnessProfileSupport.java", java_root)
            rules = self.harness_repo / "domain-packs/smart-audit/personal-loan-audit/rules"
            if not rules.is_dir():
                raise ValueError("Smart Audit personal-loan rules are unavailable")
            shutil.copytree(rules, candidate_dir / "assets/smart-audit/personal-loan-rules", dirs_exist_ok=True)
        else:
            shutil.copy2(overlay / "common/FinanceHarnessCapabilityAdapter.java", java_root)
            shutil.copy2(overlay / profile_id / "FinanceHarnessProfileSupport.java", java_root)

        assembly = candidate_dir / (
            "agents/domain-runtime/src/main/java/com/openjiuwen/intelligence/domain/"
            "DomainRuntimeAssembly.java"
        )
        assembly_text = assembly.read_text(encoding="utf-8")
        import_marker = "import com.openjiuwen.intelligence.execution.UpstreamHttpQueryAdapter;"
        assembly_text = assembly_text.replace(
            import_marker,
            import_marker
            + "\nimport com.openjiuwen.intelligence.finance.harness.FinanceHarnessProfileSupport;",
        )
        call_marker = "            tools.add(new CatalogDomainToolAdapter(agentId, catalog));"
        assembly_text = assembly_text.replace(
            call_marker,
            "            FinanceHarnessProfileSupport.maybeAdapter(environment)"
            ".ifPresent(tools::add);\n" + call_marker,
        )
        assembly.write_text(assembly_text, encoding="utf-8")

        if profile_id == "employee-assisted":
            legacy_overlay = self.project_root / "templates/openjiuwen-java-overlay/agents"
            shutil.copytree(legacy_overlay, candidate_dir / "agents", dirs_exist_ok=True)

        application = candidate_dir / "agents/domain-runtime/src/main/resources/application.yml"
        profile_config = [
            "",
            "---",
            "# Generated Finance Harness profile binding",
            "three-engine:",
            "  finance-harness:",
            f"    profile: {profile_id}",
        ]
        if profile_id == "customer-self-service":
            profile_config.extend(
                [
                    "    base-url: ${NON_AI_BANKING_BASE_URL:}",
                    "    login-name: ${NON_AI_BANKING_LOGIN_NAME:}",
                    "    login-secret: ${NON_AI_BANKING_LOGIN_SECRET:}",
                ]
            )
        elif profile_id == "employee-assisted":
            profile_config.extend(
                [
                    "    employee-agent:",
                    "      base-uri: ${EA_BASE_URI:}",
                    "      runtime-id: ${EA_RUNTIME_ID:}",
                    "      agent-id: ${EA_AGENT_ID:}",
                    "      adopt-id: ${EA_ADOPT_ID:}",
                ]
            )
        else:
            profile_config.extend(
                [
                    "    smart-audit:",
                    f"      scenario: {scenario}",
                    "      principal-id: ${SMART_AUDIT_PRINCIPAL_ID:audit-reviewer}",
                    "      subject-ref: ${SMART_AUDIT_SUBJECT_REF:demo-audit-subject}",
                    "      session-ref: ${SMART_AUDIT_SESSION_REF:demo-audit-session}",
                    "      personal-rule-baseline: ${SMART_AUDIT_PERSONAL_RULE_BASELINE:assets/smart-audit/personal-loan-rules/personal-rule-baseline.v1.json}",
                    "      controlled-test-policy-id: ${SMART_AUDIT_CONTROLLED_TEST_POLICY_ID:PPL-DEMO-2026-01}",
                ]
            )
        application.write_text(
            application.read_text(encoding="utf-8") + "\n".join(profile_config) + "\n",
            encoding="utf-8",
        )

    def _apply_employee_assisted_overlay(self, candidate_dir: Path) -> None:
        overlay = self.project_root / "templates/openjiuwen-java-overlay"
        shutil.copytree(overlay, candidate_dir, dirs_exist_ok=True)

        integration_pom = candidate_dir / "framework/finance-harness-openjiuwen/pom.xml"
        pom_text = integration_pom.read_text(encoding="utf-8")
        dependency = """
    <dependency>
      <groupId>io.github.bitlhk.finance</groupId>
      <artifactId>finance-integration-employee-agent-java</artifactId>
      <version>${finance.harness.version}</version>
    </dependency>"""
        marker = "  </dependencies>"
        if "finance-integration-employee-agent-java" not in pom_text:
            pom_text = pom_text.replace(marker, dependency + "\n" + marker)
            integration_pom.write_text(pom_text, encoding="utf-8")

        assembly = candidate_dir / (
            "agents/domain-runtime/src/main/java/com/openjiuwen/intelligence/domain/"
            "DomainRuntimeAssembly.java"
        )
        assembly_text = assembly.read_text(encoding="utf-8")
        import_marker = (
            "import com.openjiuwen.intelligence.finance.harness.FinanceHarnessOpenJiuwenSupport;"
        )
        employee_import = (
            "import com.openjiuwen.intelligence.finance.harness.EmployeeAgentHarnessSupport;"
        )
        if employee_import not in assembly_text:
            assembly_text = assembly_text.replace(
                import_marker, import_marker + "\n" + employee_import
            )
        call_marker = (
            "FinanceHarnessOpenJiuwenSupport.maybeQueryAdapter(\n"
            "                        environment,\n"
            "                        Set.of(NonAiBankingProvider.WEALTH_CUSTOMER_CONTEXT_QUERY))"
            ".ifPresent(tools::add);"
        )
        employee_call = (
            "EmployeeAgentHarnessSupport.maybeQueryAdapter(environment)"
            ".ifPresent(tools::add);\n                "
        )
        if "EmployeeAgentHarnessSupport.maybeQueryAdapter" not in assembly_text:
            assembly_text = assembly_text.replace(call_marker, employee_call + call_marker)
        assembly.write_text(assembly_text, encoding="utf-8")

        application = candidate_dir / "agents/domain-runtime/src/main/resources/application.yml"
        application.write_text(
            application.read_text(encoding="utf-8")
            + "\n---\n# Finance Solution Builder employee-assisted overlay\n"
            + "three-engine:\n"
            + "  finance-harness:\n"
            + "    profile: employee-assisted\n"
            + "    employee-agent:\n"
            + "      base-uri: ${EA_BASE_URI:}\n"
            + "      runtime-id: ${EA_RUNTIME_ID:}\n"
            + "      agent-id: ${EA_AGENT_ID:}\n"
            + "      adopt-id: ${EA_ADOPT_ID:}\n",
            encoding="utf-8",
        )

    def _lock(
        self,
        solution: dict[str, Any],
        harness_source: str,
        harness_revision: str,
        assets: list[ResolvedAsset],
        runtime: dict[str, ResolvedAsset],
        started: datetime,
        *,
        closure: AssetClosure | None = None,
        candidate_overlay: CandidateOverlay | None = None,
    ) -> dict[str, Any]:
        lock = {
            "schemaVersion": "finance-solution-lock.v1",
            "solutionId": solution["solutionId"],
            "resolvedAt": started.isoformat().replace("+00:00", "Z"),
            "financeHarness": {
                "source": harness_source,
                "revision": harness_revision,
            },
            "runtimeTemplate": runtime["template"].lock_record(),
            "runtimeIntegration": runtime["integration"].lock_record(),
            "assets": [asset.lock_record() for asset in assets],
            "modelRef": solution["modelRef"],
        }
        if closure is not None:
            lock["assetCatalog"] = closure.snapshot.lock_record()
            lock["assetClosure"] = closure.lock_records()
        closure_digest = self.asset_closure_digest(lock)
        if candidate_overlay is not None:
            if candidate_overlay.asset_closure_digest != closure_digest:
                raise ValueError("optimization.overlay.asset_closure_mismatch")
            lock["optimization"] = {
                "parentCandidateId": candidate_overlay.parent_candidate_id,
                "parentLockDigest": candidate_overlay.parent_lock_digest,
                "roundIndex": candidate_overlay.round_index,
                "assetClosureDigest": closure_digest,
                "overlayDigest": candidate_overlay.sha256(),
                "dataDigests": list(candidate_overlay.data_digests),
                "files": [item.to_wire() for item in candidate_overlay.files],
            }
        return lock

    @staticmethod
    def asset_closure_digest(lock: dict[str, Any]) -> str:
        """Digest the immutable Solution asset closure, excluding time and optimization facts."""

        closure = {
            key: value for key, value in lock.items() if key not in {"resolvedAt", "optimization"}
        }
        payload = json.dumps(
            closure, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        return hashlib.sha256(payload).hexdigest()

    def _run_stage(self, stages: list[Stage], name: str, command: list[str], cwd: Path, *, maven: bool = False) -> None:
        started = time.perf_counter()
        result = subprocess.run(
            command,
            cwd=cwd,
            text=True,
            encoding="utf-8",
            errors="replace",
            capture_output=True,
            **build_process_options(maven=maven),
        )
        duration = int((time.perf_counter() - started) * 1000)
        detail = "\n".join(
            part.strip() for part in (result.stdout, result.stderr) if part and part.strip()
        )
        detail = self._public_stage_text(detail[-4000:]) or None
        public_command = [self._public_stage_text(part) for part in command]
        stages.append(
            Stage(
                name,
                "passed" if result.returncode == 0 else "failed",
                duration,
                public_command,
                detail,
            )
        )
        if result.returncode != 0:
            raise RuntimeError(f"{name} failed: {detail}")

    @staticmethod
    def _timed(stages: list[Stage], name: str, action: Callable[[], Any]) -> Any:
        started = time.perf_counter()
        try:
            value = action()
        except Exception as exc:
            stages.append(
                Stage(
                    name,
                    "failed",
                    int((time.perf_counter() - started) * 1000),
                    detail=SolutionBuilder._public_stage_text(str(exc)),
                )
            )
            raise
        stages.append(Stage(name, "passed", int((time.perf_counter() - started) * 1000)))
        return value

    @staticmethod
    def _public_stage_text(value: str) -> str:
        sanitized = sanitize_public_text(value)
        return re.sub(r"<redacted-path>[^\r\n]*", "<redacted-path>", sanitized)

    def _harness_identity(self) -> tuple[str, str]:
        if not (self.harness_repo / ".git").exists():
            if self.harness_archive_anchor is None:
                raise ValueError("asset.archive.external_anchor_required")
            return (
                self.harness_archive_anchor.source,
                self.harness_archive_anchor.revision,
            )
        return self._git_remote(self.harness_repo), subprocess.check_output(
            [self.git, "-C", str(self.harness_repo), "rev-parse", "HEAD"], text=True
        ).strip()

    def _git_remote(self, repo: Path) -> str:
        result = subprocess.run(
            [self.git, "-C", str(repo), "remote", "get-url", "origin"],
            text=True,
            capture_output=True,
        )
        return result.stdout.strip() if result.returncode == 0 else str(repo)
