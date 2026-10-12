import json
from pathlib import Path

from jsonschema import Draft202012Validator

from finance_solution_builder.construction_agent_backend import (
    construction_agent_backend_schema,
)
from tests.construction_agent_backend.support import task_request


SCHEMA_PATH = Path("contracts/json-schema/construction-agent-backend.v1.schema.json")


def test_generated_schema_matches_repository_contract() -> None:
    expected = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    assert construction_agent_backend_schema() == expected


def test_schema_accepts_task_request() -> None:
    schema = construction_agent_backend_schema()
    Draft202012Validator(schema).validate(task_request().to_wire())
