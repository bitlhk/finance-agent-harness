"""Candidate evidence identity and public-output tests."""

import pytest

from finance_solution_builder.candidate_backend import (
    BackendIdentity,
    LifecycleOperation,
    RunHandle,
    RunState,
    RuntimeEndpoint,
    verify_lifecycle_identity,
    verify_run_handle,
)

from .support import DIGEST, descriptor, outcome


def run_handle() -> RunHandle:
    candidate = descriptor()
    return RunHandle(
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


def test_evidence_matches_task_candidate_backend_descriptor_and_lock() -> None:
    candidate = descriptor()
    handle = run_handle()

    verify_run_handle(candidate, handle)
    verify_lifecycle_identity(handle, outcome(LifecycleOperation.HEALTH))


@pytest.mark.parametrize(
    ("field", "value", "code"),
    [
        ("task_id", "task-2", "task_mismatch"),
        ("candidate_id", "candidate-2", "candidate_mismatch"),
        ("lock_digest", "b" * 64, "lock_mismatch"),
        ("descriptor_digest", DIGEST, "descriptor_mismatch"),
    ],
)
def test_handle_identity_mismatch_is_rejected(
    field: str, value: object, code: str
) -> None:
    candidate = descriptor()
    handle = run_handle().model_copy(update={field: value})

    with pytest.raises(ValueError, match=code):
        verify_run_handle(candidate, handle)


def test_backend_mismatch_is_rejected() -> None:
    candidate = descriptor()
    handle = run_handle().model_copy(
        update={"backend": BackendIdentity(backend_id="other", version="1.0")}
    )

    with pytest.raises(ValueError, match="backend_mismatch"):
        verify_run_handle(candidate, handle)
