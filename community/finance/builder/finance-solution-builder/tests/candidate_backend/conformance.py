"""Reusable successful-lifecycle conformance for concrete Candidate Backends."""

from dataclasses import dataclass
from pathlib import Path

from finance_solution_builder.candidate_backend import (
    AssemblyRequest,
    CandidateBackend,
    CandidateResourceRef,
    CandidateTarget,
    BuildRequest,
    EvidenceRequest,
    HealthRequest,
    InvocationRequest,
    LifecycleOperation,
    LifecycleStatus,
    PreflightRequest,
    RunHandle,
    StartRequest,
    StopRequest,
    verify_candidate_artifact,
    verify_lifecycle_identity,
    verify_run_handle,
)


@dataclass(frozen=True)
class ConformanceCase:
    """Backend-neutral inputs supplied by each concrete implementation."""

    task_id: str
    target: CandidateTarget
    resolved_asset_ids: tuple[str, ...]
    lock_digest: str
    workspace: CandidateResourceRef
    asset_refs: tuple[CandidateResourceRef, ...]
    invocation_payload_ref: CandidateResourceRef
    delivery_root: Path


def run_successful_lifecycle(
    backend: CandidateBackend,
    case: ConformanceCase,
) -> RunHandle:
    """Assert the public success path without inspecting Backend-private fields."""

    preflight = backend.preflight(
        PreflightRequest(
            task_id=case.task_id,
            target=case.target,
            resolved_asset_ids=case.resolved_asset_ids,
        )
    )
    assert preflight.outcome.status is LifecycleStatus.PASSED
    assert preflight.backend == backend.identity

    assembly = backend.assemble(
        AssemblyRequest(
            task_id=case.task_id,
            target=case.target,
            lock_digest=case.lock_digest,
            workspace=case.workspace,
            asset_refs=case.asset_refs,
        )
    )
    assert assembly.outcome.status is LifecycleStatus.PASSED

    built = backend.build(
        BuildRequest(
            task_id=case.task_id,
            candidate_id=assembly.candidate_id,
            lock_digest=case.lock_digest,
            assembly_refs=assembly.assembly_refs,
        )
    )
    assert built.outcome.status is LifecycleStatus.PASSED
    assert built.descriptor is not None

    verification = verify_candidate_artifact(built.descriptor, case.delivery_root)
    started = backend.start(
        StartRequest(
            descriptor=built.descriptor,
            artifact_verification=verification,
        )
    )
    assert started.outcome.status is LifecycleStatus.PASSED
    assert started.handle is not None
    handle = started.handle
    verify_run_handle(built.descriptor, handle)

    health = backend.health(HealthRequest(handle=handle))
    assert health.ready is True
    verify_lifecycle_identity(handle, health.outcome)

    invocation = backend.invoke(
        InvocationRequest(
            request_id="conformance-request",
            handle=handle,
            protocol=built.descriptor.invocation.protocol,
            payload_ref=case.invocation_payload_ref,
        )
    )
    assert invocation.response_ref is not None
    verify_lifecycle_identity(handle, invocation.outcome)

    evidence = backend.collect_evidence(EvidenceRequest(handle=handle))
    assert evidence.outcome.operation is LifecycleOperation.COLLECT_EVIDENCE
    assert evidence.outcome.evidence_refs
    verify_lifecycle_identity(handle, evidence.outcome)

    stopped = backend.stop(StopRequest(handle=handle))
    assert stopped.stopped is True
    verify_lifecycle_identity(handle, stopped.outcome)
    return handle
