import json

import pytest
from pydantic import ValidationError

from finance_builder_base import ResourceKind
from finance_solution_builder.construction_agent_backend import (
    ConstructionResourceRef,
    ConstructionTaskRequest,
)
from tests.construction_agent_backend.support import task_request


@pytest.mark.parametrize(
    "payload",
    [
        {"apiKey": "not-a-real-key"},
        {"reasoning": "private model reasoning"},
        {"future": {"systemPrompt": "hidden"}},
    ],
)
def test_unknown_private_fields_are_rejected(payload: dict[str, object]) -> None:
    wire = task_request().to_wire()
    wire.update(payload)
    with pytest.raises(ValidationError, match="private or credential"):
        ConstructionTaskRequest.model_validate(wire)


def test_public_summary_rejects_host_absolute_path() -> None:
    with pytest.raises(ValidationError, match="host paths"):
        task_request(goal="Edit C:\\Users\\operator\\candidate.py")


def test_http_reference_rejects_credentials() -> None:
    with pytest.raises(ValidationError):
        ConstructionResourceRef(
            kind=ResourceKind.HTTP,
            locator="https://user:password@example.invalid/evidence",
        )


def test_serialized_request_contains_no_private_field_names() -> None:
    wire = json.dumps(task_request().to_wire(), ensure_ascii=False).lower()
    assert "apikey" not in wire
    assert "systemprompt" not in wire
    assert "reasoning" not in wire
