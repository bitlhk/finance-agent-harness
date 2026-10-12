"""Reusable lifecycle conformance scenarios for Candidate Backends."""

from pathlib import Path

import pytest

from finance_builder_base.solution_build import CheckStatus

from finance_solution_builder.assets import ResolvedAsset
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.candidate_backend import (
    AssemblyRequest,
    AssemblyResult,
    BackendCapability,
    BackendIdentity,
    BuildRequest,
    BuildResult,
    CandidateCheck,
    EvidenceRequest,
    EvidenceResult,
    HealthRequest,
    HealthResult,
    InvocationRequest,
    InvocationResult,
    LifecycleOperation,
    LifecycleResult,
    LifecycleStatus,
    PreflightRequest,
    PreflightResult,
    RunHandle,
    RunState,
    RuntimeEndpoint,
    StartRequest,
    StartResult,
    StopRequest,
    StopResult,
    verify_candidate_artifact,
)

from .conformance import ConformanceCase, run_successful_lifecycle
from .support import (
    ALL_OPERATIONS,
    ARTIFACT_BYTES,
    DIGEST,
    descriptor,
    outcome,
    resource,
    target,
)


class MemoryBackend:
    """Minimal Backend used to exercise the public contract without a runtime."""

    def __init__(
        self,
        delivery_root: Path,
        *,
        fail_at: LifecycleOperation | None = None,
        tamper_after_build: bool = False,
    ) -> None:
        self._identity = BackendIdentity(backend_id="memory-backend", version="1.0")
        self._capability = BackendCapability(
            backend=self._identity,
            languages=("java",),
            runtime_families=("openjiuwen",),
            artifact_kinds=("jar",),
            invocation_protocols=("a2a",),
            operations=ALL_OPERATIONS,
        )
        self.fail_at = fail_at
        self.delivery_root = delivery_root
        self.tamper_after_build = tamper_after_build
        self.actions: list[LifecycleOperation] = []
        self.active = False

    @property
    def identity(self) -> BackendIdentity:
        return self._identity

    @property
    def capability(self) -> BackendCapability:
        return self._capability

    def _result(self, operation: LifecycleOperation) -> LifecycleResult:
        self.actions.append(operation)
        status = LifecycleStatus.FAILED if self.fail_at is operation else LifecycleStatus.PASSED
        return outcome(operation, status=status)

    def preflight(self, request: PreflightRequest) -> PreflightResult:
        result = self._result(LifecycleOperation.PREFLIGHT)
        return PreflightResult(
            outcome=result,
            backend=self.identity,
            checks=(
                CandidateCheck(
                    check_id="target-support",
                    status=(
                        CheckStatus.FAILED
                        if result.status is LifecycleStatus.FAILED
                        else CheckStatus.PASSED
                    ),
                    summary="Target contract checked.",
                ),
            ),
        )

    def assemble(self, request: AssemblyRequest) -> AssemblyResult:
        return AssemblyResult(
            outcome=self._result(LifecycleOperation.ASSEMBLE),
            candidate_id="candidate-1",
            assembly_refs=(resource("candidate/assembly.json", sha256=DIGEST),),
        )

    def build(self, request: BuildRequest) -> BuildResult:
        result = self._result(LifecycleOperation.BUILD)
        built_descriptor = (
            descriptor(backend_id=self.identity.backend_id)
            if result.status is LifecycleStatus.PASSED
            else None
        )
        if built_descriptor is not None:
            artifact = self.delivery_root / built_descriptor.artifact.resource.locator
            artifact.parent.mkdir(parents=True, exist_ok=True)
            artifact.write_bytes(ARTIFACT_BYTES)
            if self.tamper_after_build:
                artifact.write_bytes(b"tampered-after-build")
        return BuildResult(
            outcome=result,
            descriptor=built_descriptor,
        )

    def start(self, request: StartRequest) -> StartResult:
        result = self._result(LifecycleOperation.START)
        if result.status is LifecycleStatus.FAILED:
            return StartResult(outcome=result)
        candidate = request.descriptor
        self.active = True
        return StartResult(
            outcome=result,
            handle=RunHandle(
                task_id=candidate.task_id,
                candidate_id=candidate.candidate_id,
                backend=candidate.backend,
                descriptor_digest=candidate.sha256(),
                lock_digest=candidate.lock_digest,
                state=RunState.RUNNING,
                endpoints=(
                    RuntimeEndpoint(
                        name="agent",
                        protocol="a2a",
                        uri="http://127.0.0.1:18080/a2a",
                    ),
                ),
            ),
        )

    def health(self, request: HealthRequest) -> HealthResult:
        result = self._result(LifecycleOperation.HEALTH)
        return HealthResult(
            outcome=result,
            ready=result.status is LifecycleStatus.PASSED,
        )

    def invoke(self, request: InvocationRequest) -> InvocationResult:
        result = self._result(LifecycleOperation.INVOKE)
        return InvocationResult(
            outcome=result,
            request_id=request.request_id,
            response_ref=(
                resource("runtime/responses/result.json", sha256=DIGEST)
                if result.status is LifecycleStatus.PASSED
                else None
            ),
        )

    def collect_evidence(self, request: EvidenceRequest) -> EvidenceResult:
        result = self._result(LifecycleOperation.COLLECT_EVIDENCE).model_copy(
            update={
                "evidence_refs": (
                    resource("evidence/lifecycle.json", sha256=DIGEST),
                )
            }
        )
        return EvidenceResult(
            outcome=result,
        )

    def stop(self, request: StopRequest) -> StopResult:
        result = self._result(LifecycleOperation.STOP)
        if result.status is LifecycleStatus.PASSED:
            self.active = False
        return StopResult(
            outcome=result,
            stopped=result.status is LifecycleStatus.PASSED,
        )


def _run_successful_lifecycle(backend: MemoryBackend) -> RunHandle:
    target_value = target()
    return run_successful_lifecycle(
        backend,
        ConformanceCase(
            task_id="task-1",
            target=target_value,
            resolved_asset_ids=target_value.required_asset_ids,
            lock_digest=DIGEST,
            workspace=resource("candidate"),
            asset_refs=(resource("assets/domain-pack", sha256=DIGEST),),
            invocation_payload_ref=resource(
                "evaluation/request.json", sha256=DIGEST
            ),
            delivery_root=backend.delivery_root,
        ),
    )


def test_lightweight_backend_passes_complete_lifecycle(tmp_path: Path) -> None:
    backend = MemoryBackend(tmp_path)

    _run_successful_lifecycle(backend)

    assert backend.actions == list(ALL_OPERATIONS)
    assert backend.active is False


def test_preflight_request_rejects_incomplete_asset_closure() -> None:
    target_value = target()

    try:
        PreflightRequest(
            task_id="task-1",
            target=target_value,
            resolved_asset_ids=("runtime",),
        )
    except ValueError as error:
        assert "cover every required asset" in str(error)
    else:
        raise AssertionError("incomplete asset closure was accepted")


def test_stop_is_idempotent_for_known_handle(tmp_path: Path) -> None:
    backend = MemoryBackend(tmp_path)
    handle = _run_successful_lifecycle(backend)

    second = backend.stop(StopRequest(handle=handle))

    assert second.stopped is True
    assert backend.active is False


def test_failure_stops_dependent_actions_and_preserves_cleanup(tmp_path: Path) -> None:
    backend = MemoryBackend(tmp_path, fail_at=LifecycleOperation.HEALTH)
    candidate = descriptor(backend_id=backend.identity.backend_id)
    artifact = tmp_path / candidate.artifact.resource.locator
    artifact.parent.mkdir(parents=True)
    artifact.write_bytes(ARTIFACT_BYTES)
    started = backend.start(
        StartRequest(
            descriptor=candidate,
            artifact_verification=verify_candidate_artifact(candidate, tmp_path),
        )
    )
    assert started.handle is not None

    health = backend.health(HealthRequest(handle=started.handle))
    assert health.outcome.status is LifecycleStatus.FAILED
    assert LifecycleOperation.INVOKE not in backend.actions
    assert backend.stop(StopRequest(handle=started.handle)).stopped is True
    assert backend.active is False


@pytest.mark.parametrize(
    ("fail_at", "last_dependent_action"),
    [
        (LifecycleOperation.BUILD, LifecycleOperation.BUILD),
        (LifecycleOperation.START, LifecycleOperation.START),
        (LifecycleOperation.HEALTH, LifecycleOperation.HEALTH),
        (LifecycleOperation.INVOKE, LifecycleOperation.INVOKE),
    ],
)
def test_each_failed_stage_truncates_later_work_and_leaves_no_instance(
    fail_at: LifecycleOperation,
    last_dependent_action: LifecycleOperation,
    tmp_path: Path,
) -> None:
    backend = MemoryBackend(tmp_path, fail_at=fail_at)
    target_value = target()
    backend.preflight(
        PreflightRequest(
            task_id="task-1",
            target=target_value,
            resolved_asset_ids=target_value.required_asset_ids,
        )
    )
    assembly = backend.assemble(
        AssemblyRequest(
            task_id="task-1",
            target=target_value,
            lock_digest=DIGEST,
            workspace=resource("candidate"),
            asset_refs=(resource("assets/domain-pack", sha256=DIGEST),),
        )
    )
    built = backend.build(
        BuildRequest(
            task_id="task-1",
            candidate_id=assembly.candidate_id,
            lock_digest=DIGEST,
            assembly_refs=assembly.assembly_refs,
        )
    )
    handle = None
    if built.descriptor is not None:
        started = backend.start(
            StartRequest(
                descriptor=built.descriptor,
                artifact_verification=verify_candidate_artifact(
                    built.descriptor, tmp_path
                ),
            )
        )
        handle = started.handle
    if handle is not None:
        health = backend.health(HealthRequest(handle=handle))
        if health.ready:
            invocation = backend.invoke(
                InvocationRequest(
                    request_id="failure-request",
                    handle=handle,
                    protocol="a2a",
                    payload_ref=resource("evaluation/request.json", sha256=DIGEST),
                )
            )
            assert invocation.outcome.status is LifecycleStatus.FAILED
        backend.stop(StopRequest(handle=handle))

    dependent_order = [
        LifecycleOperation.BUILD,
        LifecycleOperation.START,
        LifecycleOperation.HEALTH,
        LifecycleOperation.INVOKE,
    ]
    failed_index = dependent_order.index(last_dependent_action)
    assert all(
        operation not in backend.actions
        for operation in dependent_order[failed_index + 1 :]
        if operation is not LifecycleOperation.STOP
    )
    assert backend.active is False


def test_conformance_rejects_tampered_artifact_before_start(tmp_path: Path) -> None:
    backend = MemoryBackend(tmp_path, tamper_after_build=True)

    with pytest.raises(ValueError, match="candidate.artifact.digest_mismatch"):
        _run_successful_lifecycle(backend)

    assert LifecycleOperation.START not in backend.actions
    assert backend.active is False


def test_python_runtime_resolution_never_uses_java_fallback(tmp_path: Path) -> None:
    requested_ids: list[str] = []

    class Resolver:
        def resolve(self, reference):
            requested_ids.append(reference["id"])
            if reference["id"] == "openjiuwen-python-template":
                return ResolvedAsset(
                    id=reference["id"],
                    version="pinned",
                    source=reference["source"],
                    status="failed",
                    error="controlled missing template",
                )
            return ResolvedAsset(
                id=reference["id"],
                version="pinned",
                source=reference["source"],
                status="resolved",
                digest="sha256:" + DIGEST,
                path=tmp_path,
            )

    builder = SolutionBuilder(
        project_root=tmp_path,
        harness_repo=tmp_path,
        allow_template_fallback=True,
    )
    solution = {
        "runtime": {
            "language": "python",
            "templateRef": "repo://templates/python",
            "integrationRef": "repo://integrations/python",
        }
    }

    with pytest.raises(RuntimeError, match="runtime template could not be resolved"):
        builder._resolve_runtime(solution, Resolver(), [])

    assert requested_ids == ["openjiuwen-python-template", "runtime-integration"]
