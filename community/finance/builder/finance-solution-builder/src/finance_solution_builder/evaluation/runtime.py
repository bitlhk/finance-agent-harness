"""Run one frozen Sandbox Evaluation against one managed Candidate instance."""

from __future__ import annotations

import hashlib
import json
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from finance_builder_base import ErrorDetail, ResourceKind
from finance_builder_base.evaluation import (
    EvaluationConclusion,
    EvaluationEngine,
    EvaluationProgress,
    EvaluationReport,
)
from finance_solution_builder.candidate_backend import (
    CandidateArtifactVerification,
    CandidateBackend,
    CandidateDescriptor,
    CandidateResourceRef,
    EvidenceRequest,
    HealthRequest,
    InvocationRequest,
    LifecycleStatus,
    StartRequest,
    StopRequest,
    verify_lifecycle_identity,
    verify_run_handle,
)

from .models import (
    EvaluationSampleEvidence,
    SampleExecutionStatus,
    SandboxEvaluationReport,
    SandboxEvaluationRequest,
    canonical_digest,
)
from .pack import PreparedEvaluation, prepare_evaluation_pack
from .reporting import EvaluationArtifacts, build_evaluation_report, write_evaluation_evidence
from .snapshot import freeze_evaluation_snapshot


@dataclass(frozen=True)
class EvaluationStageFact:
    name: str
    status: str
    duration_ms: int
    detail: str


@dataclass(frozen=True)
class SandboxEvaluationExecution:
    report: SandboxEvaluationReport
    report_path: Path
    report_ref: CandidateResourceRef
    evidence_refs: tuple[CandidateResourceRef, ...]
    lifecycle_evidence_refs: tuple[CandidateResourceRef, ...]
    stages: tuple[EvaluationStageFact, ...]
    error_code: str | None = None


class _CandidateSampleInvoker:
    def __init__(
        self,
        *,
        backend: CandidateBackend,
        handle,
        descriptor: CandidateDescriptor,
        output_dir: Path,
        prepared: PreparedEvaluation,
    ) -> None:
        self.backend = backend
        self.handle = handle
        self.descriptor = descriptor
        self.output_dir = output_dir
        self.prepared = prepared
        self.evidence: dict[str, EvaluationSampleEvidence] = {}

    def invoke(self, case) -> Any:
        sample = self.prepared.samples[case.case_id]
        request_ref = self._write_request(case.case_id, sample.payload)
        started = time.perf_counter()
        try:
            result = self.backend.invoke(
                InvocationRequest(
                    request_id=f"evaluation-{case.case_id}",
                    handle=self.handle,
                    protocol=self.descriptor.invocation.protocol,
                    payload_ref=request_ref,
                )
            )
        except Exception as exc:  # noqa: BLE001 - mapped to a public-safe code
            duration_ms = int((time.perf_counter() - started) * 1000)
            self._fail_sample(
                case.case_id,
                duration_ms=duration_ms,
                error=ErrorDetail(
                    code=f"candidate.invoke.raised_{type(exc).__name__.lower()}",
                    message="Candidate invocation raised an exception.",
                ),
                cause=exc,
            )
        duration_ms = max(
            result.outcome.duration_ms,
            int((time.perf_counter() - started) * 1000),
        )
        try:
            verify_lifecycle_identity(self.handle, result.outcome)
        except ValueError as exc:
            self._fail_sample(
                case.case_id,
                duration_ms=duration_ms,
                error=ErrorDetail(
                    code="candidate.invoke.identity_mismatch",
                    message="Candidate invocation identity did not match the active run.",
                ),
                cause=exc,
            )
        if result.outcome.status is not LifecycleStatus.PASSED or result.response_ref is None:
            error = result.outcome.error or ErrorDetail(
                code="candidate.invoke.failed",
                message="Candidate invocation did not return a successful response.",
            )
            self._fail_sample(
                case.case_id,
                duration_ms=duration_ms,
                error=error,
                cause=None,
            )
        try:
            response = self._read_response(result.response_ref)
        except Exception as exc:  # noqa: BLE001 - mapped to a public-safe code
            detail = str(exc)
            code = (
                detail
                if isinstance(exc, RuntimeError) and detail.startswith("candidate.response.")
                else f"candidate.response.raised_{type(exc).__name__.lower()}"
            )
            self._fail_sample(
                case.case_id,
                duration_ms=duration_ms,
                error=ErrorDetail(
                    code=code,
                    message="Candidate response could not be verified.",
                ),
                cause=exc,
            )
        self.evidence[case.case_id] = self._sample_evidence(
            case.case_id,
            duration_ms=duration_ms,
            response_ref=result.response_ref,
            actual=response,
        )
        return {
            "response": response,
            "durationMs": duration_ms,
            "responseRef": result.response_ref.model_dump(
                mode="json", by_alias=True, exclude_none=True
            ),
        }

    def _fail_sample(
        self,
        sample_id: str,
        *,
        duration_ms: int,
        error: ErrorDetail,
        cause: Exception | None,
    ) -> None:
        self.evidence[sample_id] = self._sample_evidence(
            sample_id,
            duration_ms=duration_ms,
            error=error,
        )
        if cause is None:
            raise RuntimeError(error.code)
        raise RuntimeError(error.code) from cause

    def _write_request(self, sample_id: str, payload: dict[str, Any]) -> CandidateResourceRef:
        path = self.output_dir / "evaluation" / "requests" / f"{sample_id}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        body = (json.dumps(payload, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        path.write_bytes(body)
        return CandidateResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator=path.relative_to(self.output_dir).as_posix(),
            media_type="application/json",
            sha256=hashlib.sha256(body).hexdigest(),
        )

    def _read_response(self, reference: CandidateResourceRef) -> Any:
        if reference.kind is not ResourceKind.WORKSPACE_PATH:
            raise RuntimeError("candidate.response.unsupported_reference")
        path = (self.output_dir / reference.locator).resolve()
        root = self.output_dir.resolve()
        try:
            path.relative_to(root)
        except ValueError as exc:
            raise RuntimeError("candidate.response.outside_output") from exc
        if not path.is_file():
            raise RuntimeError("candidate.response.missing")
        body = path.read_bytes()
        if reference.sha256 is not None and hashlib.sha256(body).hexdigest() != reference.sha256:
            raise RuntimeError("candidate.response.digest_mismatch")
        try:
            return json.loads(body)
        except json.JSONDecodeError as exc:
            raise RuntimeError("candidate.response.invalid_json") from exc

    def _sample_evidence(
        self,
        sample_id: str,
        *,
        duration_ms: int,
        response_ref: CandidateResourceRef | None = None,
        actual: Any = None,
        error: ErrorDetail | None = None,
    ) -> EvaluationSampleEvidence:
        sample = self.prepared.samples[sample_id]
        return EvaluationSampleEvidence(
            sample_id=sample_id,
            dataset_id=self.prepared.dataset_ids[sample_id],
            status=(
                SampleExecutionStatus.FAILED if error is not None else SampleExecutionStatus.PASSED
            ),
            duration_ms=duration_ms,
            input_digest=canonical_digest(sample.payload),
            expected_digest=canonical_digest(sample.expected),
            actual_digest=None if error is not None else canonical_digest(actual),
            response_ref=response_ref,
            error_stage="candidate-invoke" if error is not None else None,
            error=error,
        )


class SandboxEvaluationCoordinator:
    """Own one start-to-stop lifecycle for an optional Sandbox evaluation."""

    def __init__(self, output_dir: Path) -> None:
        self.output_dir = output_dir.resolve()

    def run(
        self,
        *,
        backend: CandidateBackend,
        descriptor: CandidateDescriptor,
        artifact_verification: CandidateArtifactVerification,
        request: SandboxEvaluationRequest,
        protocol_probe_ref: CandidateResourceRef | None = None,
        on_progress: Callable[[EvaluationProgress], None] | None = None,
    ) -> SandboxEvaluationExecution:
        snapshot = freeze_evaluation_snapshot(request, descriptor)
        prepared = prepare_evaluation_pack(request.pack)
        stages: list[EvaluationStageFact] = []
        lifecycle_refs: tuple[CandidateResourceRef, ...] = ()
        base_report: EvaluationReport | None = None
        invoker: _CandidateSampleInvoker | None = None
        error_code: str | None = None
        handle = None

        started_result, stage, raised = self._call_stage(
            "candidate_start",
            lambda: backend.start(
                StartRequest(
                    descriptor=descriptor,
                    artifact_verification=artifact_verification,
                )
            ),
        )
        stages.append(stage)
        if raised is not None or started_result is None:
            error_code = raised or "candidate.start.failed"
        elif (
            started_result.outcome.status is not LifecycleStatus.PASSED
            or started_result.handle is None
        ):
            error_code = _outcome_error(started_result.outcome, "candidate.start.failed")
        else:
            handle = started_result.handle
            try:
                verify_run_handle(descriptor, handle)
            except ValueError:
                error_code = "candidate.start.identity_mismatch"

        if handle is not None and error_code is None:
            health, stage, raised = self._call_stage(
                "candidate_health", lambda: backend.health(HealthRequest(handle=handle))
            )
            stages.append(stage)
            if raised is not None or health is None:
                error_code = raised or "candidate.health.failed"
            elif health.outcome.status is not LifecycleStatus.PASSED or not health.ready:
                error_code = _outcome_error(health.outcome, "candidate.health.failed")
            else:
                try:
                    verify_lifecycle_identity(handle, health.outcome)
                except ValueError:
                    error_code = "candidate.health.identity_mismatch"

        if handle is not None and error_code is None and protocol_probe_ref is not None:
            probe, stage, raised = self._call_stage(
                "candidate_protocol_invoke",
                lambda: backend.invoke(
                    InvocationRequest(
                        request_id="builder-protocol-probe",
                        handle=handle,
                        protocol=descriptor.invocation.protocol,
                        payload_ref=protocol_probe_ref,
                    )
                ),
            )
            stages.append(stage)
            if raised is not None or probe is None:
                error_code = raised or "candidate.protocol_probe.failed"
            elif probe.outcome.status is not LifecycleStatus.PASSED:
                error_code = _outcome_error(probe.outcome, "candidate.protocol_probe.failed")
            else:
                try:
                    verify_lifecycle_identity(handle, probe.outcome)
                except ValueError:
                    error_code = "candidate.protocol_probe.identity_mismatch"

        if handle is not None and error_code is None:
            invoker = _CandidateSampleInvoker(
                backend=backend,
                handle=handle,
                descriptor=descriptor,
                output_dir=self.output_dir,
                prepared=prepared,
            )
            started_at = time.perf_counter()
            base_report = EvaluationEngine().run(
                prepared.cases,
                invoker=invoker,
                expected=prepared.expected,
                metrics=prepared.metrics,
                acceptance=prepared.acceptance,
                on_progress=on_progress,
            )
            stages.append(
                EvaluationStageFact(
                    name="candidate_sandbox_evaluation",
                    status=(
                        "passed"
                        if base_report.conclusion is EvaluationConclusion.PASSED
                        else "failed"
                    ),
                    duration_ms=max(0, int((time.perf_counter() - started_at) * 1000)),
                    detail=base_report.conclusion.value,
                )
            )
            if base_report.conclusion is EvaluationConclusion.INDETERMINATE:
                error_code = next(
                    (
                        sample.error.code
                        for sample in invoker.evidence.values()
                        if sample.error is not None
                    ),
                    "evaluation.incomplete",
                )

        if handle is not None:
            evidence, stage, raised = self._call_stage(
                "candidate_lifecycle_evidence",
                lambda: backend.collect_evidence(EvidenceRequest(handle=handle)),
            )
            stages.append(stage)
            if raised is not None or evidence is None:
                error_code = error_code or raised or "candidate.evidence.failed"
            elif evidence.outcome.status is not LifecycleStatus.PASSED:
                error_code = error_code or _outcome_error(
                    evidence.outcome, "candidate.evidence.failed"
                )
            else:
                try:
                    verify_lifecycle_identity(handle, evidence.outcome)
                except ValueError:
                    error_code = error_code or "candidate.evidence.identity_mismatch"
                else:
                    lifecycle_refs = evidence.outcome.evidence_refs
            stopped, stage, raised = self._call_stage(
                "candidate_stop", lambda: backend.stop(StopRequest(handle=handle))
            )
            stages.append(stage)
            if raised is not None or stopped is None:
                error_code = error_code or raised or "candidate.stop.failed"
            elif stopped.outcome.status is not LifecycleStatus.PASSED or not stopped.stopped:
                error_code = error_code or _outcome_error(stopped.outcome, "candidate.stop.failed")
            else:
                try:
                    verify_lifecycle_identity(handle, stopped.outcome)
                except ValueError:
                    error_code = error_code or "candidate.stop.identity_mismatch"

        if base_report is None:
            base_report = EvaluationReport(
                snapshot_id=snapshot.snapshot_id,
                conclusion=EvaluationConclusion.INDETERMINATE,
                progress=EvaluationProgress(completed=0, total=len(prepared.cases)),
                samples=(),
                metrics=prepared.metrics.calculate(prepared.cases, ()),
            )
        sample_evidence = () if invoker is None else tuple(invoker.evidence.values())
        report = build_evaluation_report(
            snapshot,
            base_report,
            sample_evidence,
            lifecycle_evidence_refs=lifecycle_refs,
            incomplete_reason=error_code,
        )
        artifacts: EvaluationArtifacts = write_evaluation_evidence(self.output_dir, report)
        return SandboxEvaluationExecution(
            report=report,
            report_path=artifacts.report_path,
            report_ref=artifacts.report_ref,
            evidence_refs=artifacts.evidence_refs,
            lifecycle_evidence_refs=lifecycle_refs,
            stages=tuple(stages),
            error_code=error_code,
        )

    @staticmethod
    def _call_stage(name: str, action):
        started = time.perf_counter()
        try:
            result = action()
        except Exception as exc:  # noqa: BLE001 - converted at the public boundary
            code = f"evaluation.{name}.raised_{type(exc).__name__.lower()}"
            return (
                None,
                EvaluationStageFact(
                    name=name,
                    status="failed",
                    duration_ms=max(0, int((time.perf_counter() - started) * 1000)),
                    detail=code,
                ),
                code,
            )
        outcome = result.outcome
        return (
            result,
            EvaluationStageFact(
                name=name,
                status=outcome.status.value,
                duration_ms=max(0, int((time.perf_counter() - started) * 1000)),
                detail=_outcome_error(outcome, outcome.summary),
            ),
            None,
        )


def _outcome_error(outcome, fallback: str) -> str:
    return fallback if outcome.error is None else outcome.error.code
