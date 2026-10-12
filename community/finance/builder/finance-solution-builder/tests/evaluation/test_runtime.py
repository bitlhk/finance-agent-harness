from __future__ import annotations

import hashlib
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from finance_builder_base import ErrorDetail, ResourceKind
from finance_builder_base.evaluation import (
    AcceptanceRule,
    EvaluationConclusion,
    RuleGroup,
    RuleOperator,
)
from finance_solution_builder.candidate_backend import (
    BackendIdentity,
    CandidateArtifactVerification,
    CandidateResourceRef,
    EvidenceResult,
    HealthResult,
    InvocationResult,
    LifecycleOperation,
    LifecycleResult,
    LifecycleStatus,
    RunHandle,
    RunState,
    RuntimeIdentity,
    RuntimeEndpoint,
    StartResult,
    StopResult,
    verify_candidate_artifact,
)
from finance_solution_builder.evaluation import (
    DatasetPartition,
    DatasetPurpose,
    EvaluationMode,
    EvaluationPack,
    EvaluationSample,
    MetricKind,
    MetricSpec,
    SandboxEvaluationCoordinator,
    SandboxEvaluationRequest,
)
from tests.candidate_backend.support import descriptor
from tests.candidate_backend.test_openjiuwen_python_zipapp import (
    _assemble_and_build,
    _backend,
)


def _outcome(
    operation: LifecycleOperation,
    *,
    status: LifecycleStatus = LifecycleStatus.PASSED,
    evidence_refs: tuple[CandidateResourceRef, ...] = (),
) -> LifecycleResult:
    return LifecycleResult(
        operation=operation,
        status=status,
        task_id="task-1",
        candidate_id="candidate-1",
        duration_ms=1,
        summary=f"{operation.value} {status.value}",
        evidence_refs=evidence_refs,
        error=(
            ErrorDetail(code="candidate.test.failed", message="Controlled failure.")
            if status is LifecycleStatus.FAILED
            else None
        ),
    )


class RecordingBackend:
    def __init__(self, root: Path, *, fail_at: str = "") -> None:
        self.root = root
        self.fail_at = fail_at
        self.events: list[str] = []
        self.identity = BackendIdentity(backend_id="test-backend", version="1.0")

    def start(self, request):
        self.events.append("start")
        if self.fail_at == "start":
            return StartResult(
                outcome=_outcome(LifecycleOperation.START, status=LifecycleStatus.FAILED)
            )
        value = request.descriptor
        return StartResult(
            outcome=_outcome(LifecycleOperation.START),
            handle=RunHandle(
                task_id=value.task_id,
                candidate_id=value.candidate_id,
                backend=value.backend,
                descriptor_digest=value.sha256(),
                lock_digest=value.lock_digest,
                state=RunState.RUNNING,
                endpoints=(
                    RuntimeEndpoint(name="a2a", protocol="http", uri="http://127.0.0.1:18080/a2a"),
                ),
            ),
        )

    def health(self, request):
        self.events.append("health")
        status = LifecycleStatus.FAILED if self.fail_at == "health" else LifecycleStatus.PASSED
        return HealthResult(
            outcome=_outcome(LifecycleOperation.HEALTH, status=status),
            ready=status is LifecycleStatus.PASSED,
        )

    def invoke(self, request):
        self.events.append(f"invoke:{request.request_id}")
        if self.fail_at == request.request_id:
            return InvocationResult(
                outcome=_outcome(LifecycleOperation.INVOKE, status=LifecycleStatus.FAILED),
                request_id=request.request_id,
            )
        response = {"result": {"intent": "wealth"}}
        body = (json.dumps(response) + "\n").encode()
        path = self.root / "evaluation" / "responses" / f"{request.request_id}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(body)
        return InvocationResult(
            outcome=_outcome(LifecycleOperation.INVOKE),
            request_id=request.request_id,
            response_ref=CandidateResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator=path.relative_to(self.root).as_posix(),
                sha256=hashlib.sha256(body).hexdigest(),
            ),
        )

    def collect_evidence(self, request):
        self.events.append("evidence")
        ref = CandidateResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator="runtime/evidence/lifecycle.json",
            sha256="e" * 64,
        )
        return EvidenceResult(
            outcome=_outcome(LifecycleOperation.COLLECT_EVIDENCE, evidence_refs=(ref,))
        )

    def stop(self, request):
        self.events.append("stop")
        status = LifecycleStatus.FAILED if self.fail_at == "stop" else LifecycleStatus.PASSED
        return StopResult(
            outcome=_outcome(LifecycleOperation.STOP, status=status),
            stopped=status is LifecycleStatus.PASSED,
        )


def _request() -> SandboxEvaluationRequest:
    samples = (
        EvaluationSample(
            sample_id="sample-1",
            payload={"query": "first private query"},
            expected="wealth",
            expected_path="/result/intent",
        ),
        EvaluationSample(
            sample_id="sample-2",
            payload={"query": "second private query"},
            expected="wealth",
            expected_path="/result/intent",
        ),
    )
    return SandboxEvaluationRequest(
        request_id="evaluation-1",
        mode=EvaluationMode.TUNING,
        actor_id="evaluation-agent",
        purpose="pre-release gate",
        pack=EvaluationPack(
            pack_id="wealth-eval",
            version="1.0",
            partitions=(
                DatasetPartition(
                    dataset_id="regression-v1",
                    version="1.0",
                    purpose=DatasetPurpose.REGRESSION,
                    source=CandidateResourceRef(
                        kind=ResourceKind.WORKSPACE_PATH,
                        locator="datasets/regression.jsonl",
                        sha256="a" * 64,
                    ),
                    authorized=True,
                    authorization_id="fixture",
                    declared_sample_count=2,
                    samples=samples,
                ),
            ),
            metrics=(MetricSpec(name="accuracy", kind=MetricKind.ACCURACY),),
            acceptance=RuleGroup(
                logic="all",
                rules=(
                    AcceptanceRule(metric="accuracy", operator=RuleOperator.GTE, threshold=1.0),
                ),
            ),
        ),
    )


def _verification() -> CandidateArtifactVerification:
    value = descriptor()
    return CandidateArtifactVerification(
        descriptor_digest=value.sha256(),
        artifact_digest=value.artifact.resource.sha256,
    )


def test_coordinator_runs_all_samples_on_one_candidate_and_stops(tmp_path: Path) -> None:
    backend = RecordingBackend(tmp_path)

    result = SandboxEvaluationCoordinator(tmp_path).run(
        backend=backend,
        descriptor=descriptor(),
        artifact_verification=_verification(),
        request=_request(),
    )

    assert result.report.conclusion is EvaluationConclusion.PASSED
    assert result.report.progress.completed == 2
    assert backend.events == [
        "start",
        "health",
        "invoke:evaluation-sample-1",
        "invoke:evaluation-sample-2",
        "evidence",
        "stop",
    ]
    assert result.report_ref.locator == "evaluation/evaluation-report.json"


def test_same_frozen_evaluation_contract_accepts_python_descriptor(tmp_path: Path) -> None:
    backend = RecordingBackend(tmp_path)
    value = descriptor()
    python_descriptor = value.model_copy(
        update={
            "language": "python",
            "runtime": RuntimeIdentity(family="openjiuwen", version="0.1.16"),
            "artifact": value.artifact.model_copy(
                update={"artifact_kind": "python-zipapp"}
            ),
        }
    )
    verification = CandidateArtifactVerification(
        descriptor_digest=python_descriptor.sha256(),
        artifact_digest=python_descriptor.artifact.resource.sha256,
    )

    result = SandboxEvaluationCoordinator(tmp_path).run(
        backend=backend,
        descriptor=python_descriptor,
        artifact_verification=verification,
        request=_request(),
    )

    assert result.report.conclusion is EvaluationConclusion.PASSED
    assert result.report.progress.completed == 2


def test_real_python_candidate_uses_canonical_harness_adapter_for_evaluation(
    tmp_path: Path,
) -> None:
    received: list[dict[str, object]] = []

    class AdapterHandler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802
            length = int(self.headers.get("Content-Length", "0"))
            received.append(json.loads(self.rfile.read(length)))
            body = json.dumps({"intent": "wealth"}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), AdapterHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        adapter_url = f"http://127.0.0.1:{server.server_port}/capabilities/invoke"
        backend = _backend(tmp_path, environment={"HARNESS_ADAPTER_URL": adapter_url})
        built = _assemble_and_build(backend)
        assert built.descriptor is not None

        result = SandboxEvaluationCoordinator(backend.delivery_root).run(
            backend=backend,
            descriptor=built.descriptor,
            artifact_verification=verify_candidate_artifact(
                built.descriptor, backend.delivery_root
            ),
            request=_request(),
        )
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)

    assert result.report.conclusion is EvaluationConclusion.PASSED
    assert [item["schemaVersion"] for item in received] == [
        "finance-capability-request.v1",
        "finance-capability-request.v1",
    ]
    assert all(
        item["capabilityId"] == "wealth.previsit-context.query" for item in received
    )


def test_missing_harness_adapter_makes_python_evaluation_indeterminate(
    tmp_path: Path,
) -> None:
    backend = _backend(tmp_path)
    built = _assemble_and_build(backend)
    assert built.descriptor is not None

    result = SandboxEvaluationCoordinator(backend.delivery_root).run(
        backend=backend,
        descriptor=built.descriptor,
        artifact_verification=verify_candidate_artifact(
            built.descriptor, backend.delivery_root
        ),
        request=_request(),
    )

    assert result.report.conclusion is EvaluationConclusion.INDETERMINATE
    assert result.error_code == "candidate.runtime.invoke_failed"


@pytest.mark.parametrize("failure", ["health", "evaluation-sample-1", "stop"])
def test_failure_is_indeterminate_and_started_candidate_is_stopped(
    tmp_path: Path, failure: str
) -> None:
    backend = RecordingBackend(tmp_path, fail_at=failure)

    result = SandboxEvaluationCoordinator(tmp_path).run(
        backend=backend,
        descriptor=descriptor(),
        artifact_verification=_verification(),
        request=_request(),
    )

    assert result.report.conclusion is EvaluationConclusion.INDETERMINATE
    assert backend.events[-1] == "stop"
    assert result.error_code is not None


def test_snapshot_failure_happens_before_candidate_start(tmp_path: Path) -> None:
    backend = RecordingBackend(tmp_path)
    request = _request()
    holdout = request.pack.partitions[0].model_copy(update={"purpose": DatasetPurpose.HOLDOUT})
    invalid = request.model_copy(
        update={"pack": request.pack.model_copy(update={"partitions": (holdout,)})}
    )

    with pytest.raises(ValueError, match="holdout samples are sealed"):
        SandboxEvaluationCoordinator(tmp_path).run(
            backend=backend,
            descriptor=descriptor(),
            artifact_verification=_verification(),
            request=invalid,
        )

    assert backend.events == []


def test_missing_candidate_response_keeps_one_error_fact_per_sample(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    backend = RecordingBackend(tmp_path)

    def missing_response(request):
        backend.events.append(f"invoke:{request.request_id}")
        return InvocationResult(
            outcome=_outcome(LifecycleOperation.INVOKE),
            request_id=request.request_id,
            response_ref=CandidateResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator=f"evaluation/responses/{request.request_id}.json",
                sha256="a" * 64,
            ),
        )

    monkeypatch.setattr(backend, "invoke", missing_response)

    result = SandboxEvaluationCoordinator(tmp_path).run(
        backend=backend,
        descriptor=descriptor(),
        artifact_verification=_verification(),
        request=_request(),
    )

    assert result.report.conclusion is EvaluationConclusion.INDETERMINATE
    assert result.report.progress.completed == 2
    assert len(result.report.samples) == 2
    assert all(
        sample.error is not None and sample.error.code == "candidate.response.missing"
        for sample in result.report.samples
    )
    assert result.report.findings[0].responsibility.value == "candidate"
    assert backend.events[-2:] == ["evidence", "stop"]


@pytest.mark.parametrize("stage", ["protocol", "evidence", "stop"])
def test_coordinator_rejects_lifecycle_identity_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    stage: str,
) -> None:
    backend = RecordingBackend(tmp_path)

    def mismatched(operation: LifecycleOperation) -> LifecycleResult:
        return LifecycleResult(
            operation=operation,
            status=LifecycleStatus.PASSED,
            task_id="task-1",
            candidate_id="different-candidate",
            duration_ms=1,
            summary="mismatched lifecycle identity",
        )

    if stage == "protocol":

        def invoke(request):
            backend.events.append(f"invoke:{request.request_id}")
            return InvocationResult(
                outcome=mismatched(LifecycleOperation.INVOKE),
                request_id=request.request_id,
                response_ref=CandidateResourceRef(
                    kind=ResourceKind.WORKSPACE_PATH,
                    locator="runtime/responses/probe.json",
                    sha256="a" * 64,
                ),
            )

        monkeypatch.setattr(backend, "invoke", invoke)
    elif stage == "evidence":

        def collect_evidence(_request):
            backend.events.append("evidence")
            return EvidenceResult(outcome=mismatched(LifecycleOperation.COLLECT_EVIDENCE))

        monkeypatch.setattr(backend, "collect_evidence", collect_evidence)
    else:

        def stop(_request):
            backend.events.append("stop")
            return StopResult(
                outcome=mismatched(LifecycleOperation.STOP),
                stopped=True,
            )

        monkeypatch.setattr(backend, "stop", stop)

    result = SandboxEvaluationCoordinator(tmp_path).run(
        backend=backend,
        descriptor=descriptor(),
        artifact_verification=_verification(),
        request=_request(),
        protocol_probe_ref=CandidateResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator="runtime/requests/probe.json",
            sha256="b" * 64,
        ),
    )

    assert result.report.conclusion is EvaluationConclusion.INDETERMINATE
    expected_stage = "protocol_probe" if stage == "protocol" else stage
    assert result.error_code == f"candidate.{expected_stage}.identity_mismatch"
    assert backend.events[-1] == "stop"
