"""Public Candidate contract privacy boundaries."""

import json

import pytest
from pydantic import ValidationError

from finance_builder_base import ErrorDetail, ResourceKind
from finance_solution_builder.candidate_backend import (
    CandidateCheck,
    CandidateResourceRef,
    LifecycleOperation,
    LifecycleResult,
    LifecycleStatus,
    RuntimeEndpoint,
    TypedExtension,
)


@pytest.mark.parametrize(
    "message",
    [
        "api_key=private-value",
        "failed under D:\\customer\\workspace",
        "failed under /home/customer/workspace",
    ],
)
def test_public_errors_reject_credentials_and_host_paths(message: str) -> None:
    with pytest.raises(ValidationError):
        ErrorDetail(code="candidate.public.failure", message=message)


def test_public_summaries_reject_sensitive_text() -> None:
    with pytest.raises(ValidationError):
        CandidateCheck(
            check_id="privacy",
            status="failed",
            summary="authorization: bearer-private",
        )


def test_http_resource_rejects_embedded_credential_assignment() -> None:
    with pytest.raises(ValidationError, match="credential material"):
        CandidateResourceRef(
            kind=ResourceKind.HTTP,
            locator="https://example.invalid/evidence?api_key=private",
        )


def test_http_resource_and_endpoint_reject_userinfo() -> None:
    with pytest.raises(ValidationError, match="credential material"):
        CandidateResourceRef(
            kind=ResourceKind.HTTP,
            locator="https://user:private@example.invalid/evidence",
        )
    with pytest.raises(ValidationError, match="credential material"):
        RuntimeEndpoint(
            name="agent",
            protocol="a2a",
            uri="https://user:private@example.invalid/a2a",
        )
    with pytest.raises(ValidationError, match="credential material"):
        RuntimeEndpoint(
            name="agent",
            protocol="a2a",
            uri="https://example.invalid/a2a?token=private",
        )


def test_extension_payload_rejects_nested_secret_field() -> None:
    with pytest.raises(ValidationError, match="credential fields"):
        TypedExtension(
            extension_type="python.runtime",
            payload={"environment": {"password": "private"}},
        )
    with pytest.raises(ValidationError, match="credential fields"):
        TypedExtension(
            extension_type="python.runtime",
            payload={"client_secret": "private"},
        )


def test_safe_lifecycle_result_contains_only_public_facts() -> None:
    result = LifecycleResult(
        operation=LifecycleOperation.START,
        status=LifecycleStatus.PASSED,
        task_id="task-1",
        candidate_id="candidate-1",
        duration_ms=12,
        summary="Candidate process reached the declared ready state.",
    )
    encoded = json.dumps(result.to_wire(), ensure_ascii=False)

    assert "api_key" not in encoded.lower()
    assert "chain of thought" not in encoded.lower()
    assert "D:\\" not in encoded
