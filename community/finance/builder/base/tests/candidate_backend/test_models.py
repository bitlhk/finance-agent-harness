"""Candidate Backend public model tests."""

import hashlib

import pytest
from pydantic import ValidationError

from finance_builder_base import ResourceKind
from finance_builder_base.candidate_backend import (
    BackendIdentity,
    BuildResult,
    CandidateArtifactVerification,
    CandidateDescriptor,
    CandidateResourceRef,
    LifecycleOperation,
    LifecycleResult,
    LifecycleStatus,
    RunHandle,
    RunState,
    RuntimeEndpoint,
    StartRequest,
    StartResult,
    TypedExtension,
    verify_candidate_artifact,
    verify_run_handle,
)

from .support import descriptor, outcome


def test_descriptor_uses_stable_camel_case_wire_format() -> None:
    candidate = descriptor()
    wire = candidate.to_wire()

    assert wire["schemaVersion"] == "1.0"
    assert wire["candidateId"] == "candidate-1"
    assert wire["installedCapabilityIds"] == ["wealth.previsit-context.query"]
    assert CandidateDescriptor.model_validate(wire) == candidate
    assert candidate.canonical_bytes() == CandidateDescriptor.model_validate(wire).canonical_bytes()


def test_descriptor_rejects_missing_assets_and_unbound_capability() -> None:
    candidate = descriptor()

    with pytest.raises(ValidationError, match="installed_asset_ids must not be empty"):
        CandidateDescriptor.model_validate({**candidate.to_wire(), "installedAssetIds": []})
    with pytest.raises(ValidationError, match="requires an installed capability"):
        CandidateDescriptor.model_validate(
            {**candidate.to_wire(), "installedCapabilityIds": []}
        )


def test_managed_entrypoint_must_be_the_verified_artifact() -> None:
    candidate = descriptor()
    wire = candidate.to_wire()
    wire["entrypoint"]["resource"]["locator"] = "candidate/unverified.cmd"

    with pytest.raises(ValidationError, match="verified artifact"):
        CandidateDescriptor.model_validate(wire)


def test_build_result_requires_descriptor_only_on_success() -> None:
    with pytest.raises(ValidationError, match="passed build requires exactly one descriptor"):
        BuildResult(outcome=outcome(LifecycleOperation.BUILD))

    failed = BuildResult(
        outcome=outcome(LifecycleOperation.BUILD, status=LifecycleStatus.FAILED)
    )
    assert failed.descriptor is None


def test_build_result_rejects_descriptor_from_another_task() -> None:
    candidate = descriptor(task_id="task-2")

    with pytest.raises(ValidationError, match="task identity"):
        BuildResult(
            outcome=outcome(LifecycleOperation.BUILD, task_id="task-1"),
            descriptor=candidate,
        )


def test_public_extensions_reject_credentials_and_are_versioned() -> None:
    with pytest.raises(ValidationError, match="credential fields"):
        TypedExtension(extension_type="java.maven", payload={"api_key": "value"})

    extension = TypedExtension(
        extension_type="java.maven",
        schema_version="1.0",
        payload={"module": "candidate-app"},
    )
    assert extension.to_wire()["extensionType"] == "java.maven"


def test_artifact_digest_binds_descriptor_to_delivery(tmp_path) -> None:
    artifact = tmp_path / "candidate" / "candidate.jar"
    artifact.parent.mkdir()
    artifact.write_bytes(b"candidate-v1")
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    original = descriptor()
    candidate = original.model_copy(
        update={
            "artifact": original.artifact.model_copy(
                update={
                    "resource": CandidateResourceRef(
                        kind=ResourceKind.WORKSPACE_PATH,
                        locator="candidate/candidate.jar",
                        sha256=digest,
                    )
                }
            )
        }
    )

    verification = verify_candidate_artifact(candidate, tmp_path)
    assert verification.descriptor_digest == candidate.sha256()
    assert verification.artifact_digest == digest
    artifact.write_bytes(b"tampered")
    with pytest.raises(ValueError, match="digest_mismatch"):
        verify_candidate_artifact(candidate, tmp_path)


def test_start_request_rejects_unbound_artifact_verification(tmp_path) -> None:
    artifact = tmp_path / "candidate" / "candidate.jar"
    artifact.parent.mkdir()
    artifact.write_bytes(b"candidate-v1")
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    original = descriptor()
    candidate = original.model_copy(
        update={
            "artifact": original.artifact.model_copy(
                update={
                    "resource": CandidateResourceRef(
                        kind=ResourceKind.WORKSPACE_PATH,
                        locator="candidate/candidate.jar",
                        sha256=digest,
                    )
                }
            )
        }
    )
    verification = verify_candidate_artifact(candidate, tmp_path)

    with pytest.raises(ValidationError, match="does not match descriptor"):
        StartRequest(
            descriptor=candidate.model_copy(update={"candidate_id": "candidate-2"}),
            artifact_verification=verification,
        )

    with pytest.raises(ValidationError, match="does not match artifact"):
        StartRequest(
            descriptor=candidate,
            artifact_verification=CandidateArtifactVerification(
                descriptor_digest=candidate.sha256(),
                artifact_digest="0" * 64,
            ),
        )


def test_artifact_may_not_escape_delivery_root() -> None:
    original = descriptor()
    with pytest.raises(ValidationError, match="safe relative path"):
        original.artifact.model_copy(
            update={
                "resource": CandidateResourceRef(
                    kind=ResourceKind.WORKSPACE_PATH,
                    locator="../outside.jar",
                    sha256="a" * 64,
                )
            }
        )


def test_run_handle_is_bound_to_descriptor_identity() -> None:
    candidate = descriptor()
    handle = RunHandle(
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
    )

    verify_run_handle(candidate, handle)
    with pytest.raises(ValueError, match="backend_mismatch"):
        verify_run_handle(
            candidate,
            handle.model_copy(
                update={
                    "backend": BackendIdentity(backend_id="other", version="1")
                }
            ),
        )


def test_passed_start_requires_running_handle() -> None:
    candidate = descriptor()
    stopped = RunHandle(
        task_id=candidate.task_id,
        candidate_id=candidate.candidate_id,
        backend=candidate.backend,
        descriptor_digest=candidate.sha256(),
        lock_digest=candidate.lock_digest,
        state=RunState.STOPPED,
        endpoints=(),
    )

    with pytest.raises(ValidationError, match="requires a running handle"):
        StartResult(
            outcome=outcome(LifecycleOperation.START),
            handle=stopped,
        )


def test_lifecycle_failure_requires_public_error() -> None:
    with pytest.raises(ValidationError, match="requires exactly one error"):
        LifecycleResult(
            operation=LifecycleOperation.START,
            status=LifecycleStatus.FAILED,
            task_id="task-1",
            candidate_id="candidate-1",
            duration_ms=1,
            summary="failed",
        )
