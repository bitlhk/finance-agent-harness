"""Factories shared by Candidate Backend contract tests."""

import hashlib

from finance_builder_base import ErrorDetail, ResourceKind

from finance_builder_base.candidate_backend import (
    BackendIdentity,
    CandidateArtifact,
    CandidateDescriptor,
    CandidateEntrypoint,
    CandidateResourceRef,
    CandidateTarget,
    CapabilityProviderBinding,
    LifecycleOperation,
    LifecycleResult,
    LifecycleStatus,
    ProtocolBinding,
    RuntimeIdentity,
)

ARTIFACT_BYTES = b"candidate-v1"
DIGEST = hashlib.sha256(ARTIFACT_BYTES).hexdigest()
ALL_OPERATIONS = tuple(LifecycleOperation)


def resource(locator: str, *, sha256: str | None = None) -> CandidateResourceRef:
    return CandidateResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator=locator, sha256=sha256)


def target(**overrides: object) -> CandidateTarget:
    values: dict[str, object] = {
        "language": "java",
        "runtime_family": "openjiuwen",
        "runtime_version": "1.0",
        "artifact_kind": "jar",
        "invocation_protocol": "a2a",
        "required_operations": ALL_OPERATIONS,
        "required_asset_ids": ("runtime", "domain-pack", "provider"),
        "required_capability_ids": ("wealth.previsit-context.query",),
        "required_provider_operation_types": ("query",),
        "profile_id": "employee-assisted",
        "host_wiring_ids": ("employee-host",),
    }
    values.update(overrides)
    return CandidateTarget(**values)


def descriptor(*, backend_id: str = "test-backend", task_id: str = "task-1") -> CandidateDescriptor:
    return CandidateDescriptor(
        task_id=task_id,
        candidate_id="candidate-1",
        backend=BackendIdentity(backend_id=backend_id, version="1.0"),
        language="java",
        runtime=RuntimeIdentity(family="openjiuwen", version="1.0"),
        artifact=CandidateArtifact(
            artifact_id="candidate-jar",
            artifact_kind="jar",
            resource=resource("candidate/candidate.jar", sha256=DIGEST),
        ),
        entrypoint=CandidateEntrypoint(
            mode="managed-artifact",
            resource=resource("candidate/candidate.jar", sha256=DIGEST),
        ),
        invocation=ProtocolBinding(
            protocol="a2a", path="/a2a", media_type="application/json"
        ),
        health=ProtocolBinding(protocol="http", path="/actuator/health"),
        lock_digest=DIGEST,
        installed_asset_ids=("runtime", "domain-pack", "provider"),
        installed_capability_ids=("wealth.previsit-context.query",),
        installed_tool_ids=("wealth-previsit",),
        provider_bindings=(
            CapabilityProviderBinding(
                capability_id="wealth.previsit-context.query",
                provider_id="bank-crm",
                operation_types=("query",),
            ),
        ),
        provenance_refs=(resource("solution-lock.json", sha256=DIGEST),),
    )


def outcome(
    operation: LifecycleOperation,
    *,
    status: LifecycleStatus = LifecycleStatus.PASSED,
    task_id: str = "task-1",
    candidate_id: str | None = "candidate-1",
) -> LifecycleResult:
    error = None
    if status in {LifecycleStatus.FAILED, LifecycleStatus.BLOCKED}:
        error = ErrorDetail(
            code=f"candidate.test.{operation.value.replace('-', '_')}",
            message="The controlled test Backend rejected this operation.",
        )
    return LifecycleResult(
        operation=operation,
        status=status,
        task_id=task_id,
        candidate_id=candidate_id,
        duration_ms=1,
        summary=f"{operation.value} {status.value}",
        error=error,
    )
