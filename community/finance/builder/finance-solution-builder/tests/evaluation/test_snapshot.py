from __future__ import annotations

import pytest

from finance_builder_base import ResourceKind
from finance_builder_base.evaluation import AcceptanceRule, RuleGroup, RuleOperator
from finance_solution_builder.candidate_backend import (
    BackendIdentity,
    CandidateArtifact,
    CandidateDescriptor,
    CandidateEntrypoint,
    CandidateResourceRef,
    ProtocolBinding,
    RuntimeIdentity,
)
from finance_solution_builder.evaluation import (
    DatasetPartition,
    DatasetPurpose,
    EvaluationMode,
    EvaluationPack,
    EvaluationSample,
    MetricKind,
    MetricSpec,
    SandboxEvaluationRequest,
    freeze_evaluation_snapshot,
)


def _descriptor() -> CandidateDescriptor:
    artifact = CandidateResourceRef(
        kind=ResourceKind.WORKSPACE_PATH,
        locator="candidate/target/demo.jar",
        sha256="b" * 64,
    )
    return CandidateDescriptor(
        task_id="build-1",
        candidate_id="candidate-1",
        backend=BackendIdentity(backend_id="openjiuwen-java", version="1.0"),
        language="java",
        runtime=RuntimeIdentity(family="openjiuwen", version="1.0"),
        artifact=CandidateArtifact(
            artifact_id="candidate-jar",
            artifact_kind="jar",
            resource=artifact,
        ),
        entrypoint=CandidateEntrypoint(mode="managed-artifact", resource=artifact),
        invocation=ProtocolBinding(protocol="a2a-jsonrpc", path="/a2a"),
        health=ProtocolBinding(protocol="http", path="/actuator/health"),
        lock_digest="c" * 64,
        installed_asset_ids=("wealth-pack",),
    )


def _partition(
    purpose: DatasetPurpose,
    sample_id: str,
    *,
    include_sample: bool = True,
) -> DatasetPartition:
    samples = (
        (
            EvaluationSample(
                sample_id=sample_id,
                payload={"query": f"question-{sample_id}"},
                expected={"intent": "wealth"},
                expected_path="/result",
            ),
        )
        if include_sample
        else ()
    )
    return DatasetPartition(
        dataset_id=f"data-{purpose.value}",
        version="1.0",
        purpose=purpose,
        source=CandidateResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator=f"datasets/{purpose.value}.jsonl",
            sha256="d" * 64,
        ),
        authorized=True,
        authorization_id="sanitized-fixture",
        declared_sample_count=1,
        samples=samples,
    )


def _request(
    partitions: tuple[DatasetPartition, ...],
    *,
    mode: EvaluationMode = EvaluationMode.TUNING,
) -> SandboxEvaluationRequest:
    return SandboxEvaluationRequest(
        request_id="evaluation-1",
        mode=mode,
        actor_id="evaluation-agent",
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


def test_snapshot_is_stable_and_bound_to_all_inputs() -> None:
    request = _request((_partition(DatasetPurpose.REGRESSION, "sample-1"),))

    first = freeze_evaluation_snapshot(request, _descriptor())
    second = freeze_evaluation_snapshot(request, _descriptor())
    changed = freeze_evaluation_snapshot(
        request.model_copy(update={"purpose": "release candidate verification"}),
        _descriptor(),
    )

    assert first.snapshot_id == second.snapshot_id
    assert first.snapshot_id != changed.snapshot_id
    assert first.candidate_id == "candidate-1"
    assert first.lock_digest == "c" * 64
    assert first.artifact_digest == "b" * 64
    assert first.sample_order == ("sample-1",)


def test_snapshot_rejects_sample_overlap_across_partitions() -> None:
    request = _request(
        (
            _partition(DatasetPurpose.OPTIMIZATION, "sample-1"),
            _partition(DatasetPurpose.REGRESSION, "sample-1"),
        )
    )

    with pytest.raises(ValueError, match="sample identity overlaps"):
        freeze_evaluation_snapshot(request, _descriptor())


def test_snapshot_rejects_same_content_under_different_sample_ids() -> None:
    first = _partition(DatasetPurpose.OPTIMIZATION, "sample-1")
    duplicate = _partition(DatasetPurpose.REGRESSION, "sample-2").model_copy(
        update={"samples": (first.samples[0].model_copy(update={"sample_id": "sample-2"}),)}
    )
    request = _request((first, duplicate))

    with pytest.raises(ValueError, match="sample content overlaps"):
        freeze_evaluation_snapshot(request, _descriptor())


def test_tuning_cannot_load_holdout_samples() -> None:
    request = _request((_partition(DatasetPurpose.HOLDOUT, "holdout-1"),))

    with pytest.raises(ValueError, match="holdout samples are sealed"):
        freeze_evaluation_snapshot(request, _descriptor())


def test_final_gate_records_holdout_access() -> None:
    request = _request(
        (_partition(DatasetPurpose.HOLDOUT, "holdout-1"),),
        mode=EvaluationMode.FINAL_GATE,
    )

    snapshot = freeze_evaluation_snapshot(request, _descriptor())

    assert snapshot.holdout_access is not None
    assert snapshot.holdout_access.actor_id == "evaluation-agent"
    assert snapshot.holdout_access.purpose == "pre-release quality gate"


def test_tuning_accepts_sealed_holdout_metadata() -> None:
    request = _request((_partition(DatasetPurpose.HOLDOUT, "holdout-1", include_sample=False),))

    snapshot = freeze_evaluation_snapshot(request, _descriptor())

    assert snapshot.sample_order == ()
    assert snapshot.partitions[0].declared_sample_count == 1
    assert snapshot.holdout_access is None
