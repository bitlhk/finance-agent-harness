from __future__ import annotations

import pytest
from pydantic import ValidationError

from finance_builder_base.evaluation import AcceptanceRule, RuleGroup, RuleOperator
from finance_solution_builder.candidate_backend import CandidateResourceRef
from finance_solution_builder.evaluation import (
    DatasetPartition,
    DatasetPurpose,
    EvaluationMode,
    EvaluationPack,
    EvaluationSample,
    MetricKind,
    MetricSpec,
    SandboxEvaluationRequest,
)
from finance_builder_base import ResourceKind


def _partition(
    *, purpose: DatasetPurpose, samples: tuple[EvaluationSample, ...]
) -> DatasetPartition:
    return DatasetPartition(
        dataset_id=f"dataset-{purpose.value}",
        version="1.0",
        purpose=purpose,
        source=CandidateResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator=f"datasets/{purpose.value}.jsonl",
            sha256="a" * 64,
        ),
        authorized=True,
        authorization_id="sanitized-fixture",
        declared_sample_count=len(samples),
        samples=samples,
    )


def _request(partitions: tuple[DatasetPartition, ...]) -> SandboxEvaluationRequest:
    return SandboxEvaluationRequest(
        request_id="evaluation-1",
        mode=EvaluationMode.TUNING,
        actor_id="builder-evaluator",
        purpose="pre-release quality gate",
        pack=EvaluationPack(
            pack_id="wealth-eval",
            version="1.0",
            partitions=partitions,
            metrics=(MetricSpec(name="accuracy", kind=MetricKind.ACCURACY),),
            acceptance=RuleGroup(
                logic="all",
                rules=(
                    AcceptanceRule(
                        metric="accuracy",
                        operator=RuleOperator.GTE,
                        threshold=0.9,
                    ),
                ),
            ),
        ),
    )


def test_pack_rejects_duplicate_dataset_ids() -> None:
    sample = EvaluationSample(sample_id="sample-1", payload={"query": "demo"}, expected={})
    first = _partition(purpose=DatasetPurpose.OPTIMIZATION, samples=(sample,))
    duplicate = first.model_copy(update={"purpose": DatasetPurpose.REGRESSION})

    with pytest.raises(ValidationError, match="dataset_id must be unique"):
        _request((first, duplicate))


def test_partition_requires_authorization_and_digest() -> None:
    sample = EvaluationSample(sample_id="sample-1", payload={"query": "demo"}, expected={})

    with pytest.raises(ValidationError, match="authorized"):
        _partition(purpose=DatasetPurpose.REGRESSION, samples=(sample,)).model_copy(
            update={"authorized": False}
        ).model_validate(
            {
                **_partition(purpose=DatasetPurpose.REGRESSION, samples=(sample,)).model_dump(),
                "authorized": False,
            }
        )


def test_sample_payload_rejects_credential_fields() -> None:
    credential_key = "api" + "Key"
    with pytest.raises(ValidationError, match="credential"):
        EvaluationSample(
            sample_id="sample-1",
            payload={credential_key: "test-value"},
            expected={},
        )
