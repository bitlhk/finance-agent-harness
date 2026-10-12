"""Compatibility checks for the Base-owned Candidate Backend contract."""

import json
from pathlib import Path

import finance_builder_base.candidate_backend as base_contract
import finance_solution_builder.candidate_backend as legacy_contract
from finance_builder_base.candidate_backend.models import (
    CandidateDescriptor as BaseDescriptor,
)
from finance_solution_builder.candidate_backend.models import (
    CandidateDescriptor as LegacyDescriptor,
)

from .support import descriptor


def test_legacy_contract_imports_share_one_type_identity() -> None:
    assert legacy_contract.CandidateDescriptor is base_contract.CandidateDescriptor
    assert legacy_contract.CandidateBackend is base_contract.CandidateBackend
    assert legacy_contract.BackendRegistry is base_contract.BackendRegistry
    assert LegacyDescriptor is BaseDescriptor


def test_legacy_runtime_is_a_thin_base_compatibility_wrapper() -> None:
    assert issubclass(
        legacy_contract.PythonZipAppManagedRuntime,
        base_contract.PythonZipAppManagedRuntime,
    )


def test_legacy_and_base_schema_files_are_equal() -> None:
    builder_root = Path(__file__).parents[3]
    legacy_schema = json.loads(
        (builder_root / "finance-solution-builder/contracts/json-schema/candidate-backend.v1.schema.json")
        .read_text(encoding="utf-8")
    )
    base_schema = json.loads(
        (builder_root / "base/contracts/json-schema/candidate-backend.v1.schema.json").read_text(
            encoding="utf-8"
        )
    )

    assert legacy_schema == base_schema == base_contract.candidate_backend_schema()


def test_legacy_wire_and_digest_round_trip_through_base_type() -> None:
    legacy = descriptor()
    migrated = BaseDescriptor.model_validate(legacy.to_wire())

    assert migrated.to_wire() == legacy.to_wire()
    assert migrated.canonical_bytes() == legacy.canonical_bytes()
    assert migrated.sha256() == legacy.sha256()
