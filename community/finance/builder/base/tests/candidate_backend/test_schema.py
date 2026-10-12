"""Checked-in Candidate Backend JSON Schema consistency tests."""

import json
from pathlib import Path

from finance_builder_base.candidate_backend import candidate_backend_schema


SCHEMA_PATH = (
    Path(__file__).resolve().parents[2]
    / "contracts/json-schema/candidate-backend.v1.schema.json"
)


def test_checked_in_schema_matches_public_models() -> None:
    checked_in = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))

    assert checked_in == candidate_backend_schema()


def test_schema_contains_portable_descriptor_and_lifecycle_roots() -> None:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    definitions = schema["$defs"]

    assert "CandidateDescriptor" in definitions
    assert "RunHandle" in definitions
    assert "InvocationRequest" in definitions
    assert "StopResult" in definitions
    assert definitions["CandidateDescriptor"]["properties"]["language"]
    assert "maven" not in definitions["CandidateDescriptor"]["properties"]
