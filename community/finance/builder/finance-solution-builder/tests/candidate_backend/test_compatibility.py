"""Candidate Backend v1 forward-compatibility tests."""

import pytest
from pydantic import ValidationError

from finance_solution_builder.candidate_backend import CandidateDescriptor, TypedExtension

from .support import descriptor


def test_v1_consumer_ignores_future_optional_field() -> None:
    wire = descriptor().to_wire()
    wire["futureOptionalField"] = {"enabled": True}

    restored = CandidateDescriptor.model_validate(wire)

    assert restored == descriptor()
    assert "futureOptionalField" not in restored.to_wire()


def test_v1_consumer_rejects_missing_required_field() -> None:
    wire = descriptor().to_wire()
    del wire["candidateId"]

    with pytest.raises(ValidationError):
        CandidateDescriptor.model_validate(wire)


def test_language_specific_fields_remain_in_versioned_extension() -> None:
    candidate = descriptor().model_copy(
        update={
            "extensions": (
                TypedExtension(
                    extension_type="java.maven",
                    schema_version="1.0",
                    payload={"mainClass": "example.CandidateApplication"},
                ),
            )
        }
    )

    assert candidate.to_wire()["extensions"][0]["extensionType"] == "java.maven"
    assert "mainClass" not in CandidateDescriptor.model_fields
