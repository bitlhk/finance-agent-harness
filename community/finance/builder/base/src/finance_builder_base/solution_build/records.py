"""Deterministic Lock and honest Report builders."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable

from finance_builder_base.solution_build.models import (
    BuildDefinition,
    BuildFailure,
    ResolvedBuildAsset,
    SolutionAssetLock,
    SolutionBuildReport,
    SolutionBuildStatus,
    StageExecutionRecord,
)


def _canonical_json(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def build_asset_lock(
    definition: BuildDefinition,
    assets: Iterable[ResolvedBuildAsset],
) -> SolutionAssetLock:
    """Build a deterministic lock from assets that actually participated."""

    normalized = tuple(
        sorted(
            (
                asset.model_copy(update={"dependencies": tuple(sorted(asset.dependencies))})
                for asset in assets
            ),
            key=lambda item: (item.asset_id, item.version, item.revision or ""),
        )
    )
    ids = [asset.asset_id for asset in normalized]
    if len(ids) != len(set(ids)):
        raise ValueError("asset_id must be unique within a solution lock")
    known = set(ids)
    missing = sorted(
        dependency
        for asset in normalized
        for dependency in asset.dependencies
        if dependency not in known
    )
    if missing:
        raise ValueError(f"asset dependencies are absent from the lock: {', '.join(missing)}")

    payload = {
        "protocol_version": definition.protocol_version,
        "task_id": definition.task_id,
        "input_digest": definition.input_digest,
        "assets": [asset.model_dump(mode="json") for asset in normalized],
    }
    digest = hashlib.sha256(_canonical_json(payload)).hexdigest()
    return SolutionAssetLock(**payload, lock_digest=digest)


def build_report(
    definition: BuildDefinition,
    asset_lock: SolutionAssetLock,
    status: SolutionBuildStatus,
    stages: Iterable[StageExecutionRecord],
) -> SolutionBuildReport:
    """Summarize executed checks without promoting skipped work to success."""

    records = tuple(stages)
    failures: tuple[BuildFailure, ...] = tuple(
        record.failure for record in records if record.failure is not None
    )
    if status is SolutionBuildStatus.COMPLETED:
        invalid = [
            record.stage_id
            for record in records
            if record.status.value not in {"passed", "skipped"}
        ]
        if invalid:
            raise ValueError("completed report cannot contain failed or blocked stages")
    return SolutionBuildReport(
        task_id=definition.task_id,
        input_digest=definition.input_digest,
        lock_digest=asset_lock.lock_digest,
        status=status,
        stages=records,
        failures=failures,
    )
