import pytest
from pydantic import ValidationError

from finance_solution_builder.construction_agent_backend import ConstructionTaskRequest
from tests.construction_agent_backend.support import task_request


def test_consumer_ignores_additive_optional_field() -> None:
    wire = task_request().to_wire()
    wire["futureOptionalField"] = {"publicFact": "value"}
    assert ConstructionTaskRequest.model_validate(wire).task_id == "task-1"


def test_consumer_rejects_missing_required_field() -> None:
    wire = task_request().to_wire()
    wire.pop("parentLockDigest")
    with pytest.raises(ValidationError):
        ConstructionTaskRequest.model_validate(wire)


def test_consumer_rejects_unknown_enum() -> None:
    wire = task_request().to_wire()
    wire["approvalKinds"] = ["vendor-private-action"]
    with pytest.raises(ValidationError):
        ConstructionTaskRequest.model_validate(wire)
