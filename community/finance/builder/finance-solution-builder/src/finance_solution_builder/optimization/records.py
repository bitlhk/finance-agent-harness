"""Budget guards and append-only public optimization records."""

from __future__ import annotations

import json
import os
from pathlib import Path

from finance_builder_base import ResourceKind, ResourceRef

from .models import (
    OptimizationBudget,
    OptimizationRequest,
    OptimizationResult,
    OptimizationRoundRecord,
    OptimizationSnapshot,
    OptimizationStopReason,
    OptimizationUsage,
)


def budget_stop_reason(
    budget: OptimizationBudget, usage: OptimizationUsage
) -> OptimizationStopReason | None:
    """Check every budget before a downstream action is created."""

    if usage.rounds >= budget.max_rounds:
        return OptimizationStopReason.MAX_ROUNDS
    if usage.duration_ms >= budget.max_duration_ms:
        return OptimizationStopReason.MAX_DURATION
    if (
        budget.max_cost_microunits > 0
        and usage.cost_microunits >= budget.max_cost_microunits
    ):
        return OptimizationStopReason.MAX_COST
    if usage.consecutive_no_improvement >= budget.max_no_improvement:
        return OptimizationStopReason.NO_IMPROVEMENT
    return None


class OptimizationRecordStore:
    """Write immutable round facts and one idempotent terminal result."""

    def __init__(self, root: Path, resource_root: Path) -> None:
        self.root = root.resolve()
        self.resource_root = resource_root.resolve()
        if self.root == self.resource_root or not self.root.is_relative_to(self.resource_root):
            raise ValueError("optimization record root must be inside the workspace")
        self.root.mkdir(parents=True, exist_ok=True)

    def append_round(self, record: OptimizationRoundRecord) -> ResourceRef:
        return self._write_immutable(
            self.root / f"round-{record.round_index:03d}.json", record.to_wire()
        )

    def bind_request(self, request: OptimizationRequest) -> ResourceRef:
        """Bind this record directory to one canonical request payload."""

        return self._write_immutable(self.root / "request.json", request.to_wire())

    def read_result(self, request_id: str) -> OptimizationResult | None:
        """Return the immutable terminal result for an idempotent retry."""

        path = self.root / "result.json"
        if not path.exists():
            return None
        result = OptimizationResult.model_validate_json(path.read_text(encoding="utf-8"))
        if result.request_id != request_id:
            raise ValueError("optimization record request identity mismatch")
        return result

    def claim_holdout(
        self, request: OptimizationRequest, snapshot: OptimizationSnapshot
    ) -> bool:
        """Atomically claim the final Holdout for one request and snapshot."""

        claim_dir = self.root / "holdout-claim"
        payload = {
            "schema_version": "1.0",
            "request_id": request.request_id,
            "request_digest": request.sha256(),
            "snapshot_digest": snapshot.sha256(),
        }
        try:
            claim_dir.mkdir()
        except FileExistsError:
            claim_path = claim_dir / "claim.json"
            if not claim_path.exists():
                return False
            if json.loads(claim_path.read_text(encoding="utf-8")) != payload:
                raise FileExistsError("holdout claim identity mismatch")
            return False

        claim_path = claim_dir / "claim.json"
        temporary = claim_dir / "claim.json.tmp"
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary, claim_path)
        return True

    def write_result(self, result: OptimizationResult) -> ResourceRef:
        return self._write_immutable(self.root / "result.json", result.to_wire())

    def _write_immutable(self, path: Path, value: dict) -> ResourceRef:
        payload = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        if path.exists():
            if path.read_bytes() != payload:
                raise FileExistsError("optimization record is append-only")
        else:
            temporary = path.with_suffix(path.suffix + ".tmp")
            temporary.write_bytes(payload)
            os.replace(temporary, path)
        import hashlib

        return ResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator=path.relative_to(self.resource_root).as_posix(),
            sha256=hashlib.sha256(payload).hexdigest(),
        )
