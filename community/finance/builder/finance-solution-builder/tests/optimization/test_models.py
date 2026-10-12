from pathlib import Path

import pytest
from pydantic import ValidationError

from finance_builder_base import ResourceKind, ResourceRef
from finance_solution_builder.optimization import (
    CorrectionExecution,
    CandidateOverlay,
    GovernedTarget,
    ActionDisposition,
    CorrectionTargetKind,
    OptimizationBudget,
    OptimizationDataVersion,
    OverlayFile,
    optimization_schema,
)

from . import SHA


def test_optimization_schema_and_models_are_stable() -> None:
    budget = OptimizationBudget(
        max_rounds=2,
        max_duration_ms=100,
        max_cost_microunits=0,
        max_no_improvement=1,
    )
    assert budget == OptimizationBudget.model_validate(budget.to_wire())
    assert "OptimizationRequest" in optimization_schema()["$defs"]


def test_public_contract_rejects_secrets_and_invalid_budget() -> None:
    with pytest.raises(ValidationError, match="private fields"):
        OptimizationBudget.model_validate(
            {
                "maxRounds": 1,
                "maxDurationMs": 1,
                "maxCostMicrounits": 1,
                "maxNoImprovement": 1,
                "apiKey": "private",
            }
        )
    with pytest.raises(ValidationError):
        OptimizationBudget(
            max_rounds=0,
            max_duration_ms=1,
            max_cost_microunits=0,
            max_no_improvement=1,
        )


def test_overlay_rejects_unsafe_and_duplicate_paths() -> None:
    reference = ResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="overlays/one")
    with pytest.raises(ValidationError):
        CandidateOverlay(
            overlay_id="overlay-1",
            round_index=1,
            parent_candidate_id="candidate-1",
            parent_lock_digest=SHA,
            asset_closure_digest=SHA,
            root_ref=reference,
            files=(OverlayFile(path=str(Path("C:/private")), digest=SHA),),
        )
    with pytest.raises(ValidationError, match="unique"):
        CandidateOverlay(
            overlay_id="overlay-1",
            round_index=1,
            parent_candidate_id="candidate-1",
            parent_lock_digest=SHA,
            asset_closure_digest=SHA,
            root_ref=reference,
            files=(OverlayFile(path="src/a.py", digest=SHA),) * 2,
        )
    with pytest.raises(ValidationError, match="files or data"):
        CandidateOverlay(
            overlay_id="overlay-1",
            round_index=1,
            parent_candidate_id="candidate-1",
            parent_lock_digest=SHA,
            asset_closure_digest=SHA,
            root_ref=reference,
        )


def test_public_record_rejects_credentials_embedded_in_http_reference() -> None:
    with pytest.raises(ValidationError, match="credential URLs"):
        GovernedTarget(
            responsibility="candidate",
            target_kind=CorrectionTargetKind.CANDIDATE_OVERLAY,
            target_ref="candidate/source",
            owner_id="owner",
            allowed_actions=("repair",),
            approval_policy=ActionDisposition.AUTO,
            allowed_write_paths=("src",),
            validation_refs=(
                ResourceRef(
                    kind=ResourceKind.HTTP,
                    locator="https://example.invalid/check?token=private",
                ),
            ),
            priority=1,
        )


def test_correction_digest_must_match_its_data_revision() -> None:
    data = OptimizationDataVersion(
        version_id="data-1",
        task_id="task-1",
        source_digest=SHA,
        output_digest=SHA,
        resource_refs=(),
        item_count=0,
    )
    with pytest.raises(ValidationError, match="change_digest"):
        CorrectionExecution(
            change_digest="b" * 64,
            evidence_refs=(),
            duration_ms=0,
            cost_microunits=0,
            data_version=data,
        )
