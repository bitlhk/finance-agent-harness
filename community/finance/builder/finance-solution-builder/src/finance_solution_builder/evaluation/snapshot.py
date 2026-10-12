"""Deterministic Dataset Snapshot and Holdout isolation guard."""

from __future__ import annotations

from datetime import UTC, datetime

from finance_solution_builder.candidate_backend import CandidateDescriptor

from .models import (
    DatasetPurpose,
    DatasetSnapshot,
    EvaluationMode,
    EvaluationSnapshot,
    HoldoutAccessFact,
    SandboxEvaluationRequest,
    canonical_digest,
)


def freeze_evaluation_snapshot(
    request: SandboxEvaluationRequest,
    descriptor: CandidateDescriptor,
) -> EvaluationSnapshot:
    """Validate and freeze every input before the Candidate is started."""

    sample_ids: set[str] = set()
    sample_digests: set[str] = set()
    ordered_sample_ids: list[str] = []
    partition_snapshots: list[DatasetSnapshot] = []
    contains_loaded_holdout = False

    for partition in request.pack.partitions:
        if partition.purpose is DatasetPurpose.HOLDOUT and partition.samples:
            contains_loaded_holdout = True
            if request.mode is not EvaluationMode.FINAL_GATE:
                raise ValueError("holdout samples are sealed outside the final gate")
        for sample in partition.samples:
            digest = sample.content_digest()
            if sample.sample_id in sample_ids:
                raise ValueError("sample identity overlaps across dataset partitions")
            if digest in sample_digests:
                raise ValueError("sample content overlaps across dataset partitions")
            sample_ids.add(sample.sample_id)
            sample_digests.add(digest)
            ordered_sample_ids.append(sample.sample_id)
        partition_snapshots.append(
            DatasetSnapshot(
                dataset_id=partition.dataset_id,
                version=partition.version,
                purpose=partition.purpose,
                source_digest=partition.source.sha256,
                authorization_id=partition.authorization_id,
                declared_sample_count=partition.declared_sample_count,
                loaded_sample_count=len(partition.samples),
                content_digest=canonical_digest(
                    [sample.content_digest() for sample in partition.samples]
                ),
            )
        )

    if request.mode is EvaluationMode.FINAL_GATE and not contains_loaded_holdout:
        raise ValueError("final gate requires an authorized loaded holdout partition")

    pack_digest = request.pack.sha256()
    rules_digest = canonical_digest(request.pack.acceptance.model_dump(mode="json"))
    identity = {
        "requestId": request.request_id,
        "mode": request.mode.value,
        "actorId": request.actor_id,
        "purpose": request.purpose,
        "evidenceLevel": request.evidence_level.value,
        "candidateId": descriptor.candidate_id,
        "descriptorDigest": descriptor.sha256(),
        "lockDigest": descriptor.lock_digest,
        "artifactDigest": descriptor.artifact.resource.sha256,
        "packId": request.pack.pack_id,
        "packVersion": request.pack.version,
        "packDigest": pack_digest,
        "rulesDigest": rules_digest,
        "partitions": [item.to_wire() for item in partition_snapshots],
        "sampleOrder": ordered_sample_ids,
    }
    snapshot_id = canonical_digest(identity)
    holdout_access = (
        HoldoutAccessFact(
            actor_id=request.actor_id,
            purpose=request.purpose,
            pack_digest=pack_digest,
            accessed_at=datetime.now(UTC),
        )
        if contains_loaded_holdout
        else None
    )
    return EvaluationSnapshot(
        snapshot_id=snapshot_id,
        request_id=request.request_id,
        mode=request.mode,
        candidate_id=descriptor.candidate_id,
        descriptor_digest=descriptor.sha256(),
        lock_digest=descriptor.lock_digest,
        artifact_digest=descriptor.artifact.resource.sha256,
        pack_id=request.pack.pack_id,
        pack_version=request.pack.version,
        pack_digest=pack_digest,
        rules_digest=rules_digest,
        partitions=tuple(partition_snapshots),
        sample_order=tuple(ordered_sample_ids),
        holdout_access=holdout_access,
    )
