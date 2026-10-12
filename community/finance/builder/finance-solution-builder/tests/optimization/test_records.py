import json
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from finance_solution_builder.optimization import (
    OptimizationBudget,
    OptimizationRecordStore,
    OptimizationResult,
    OptimizationRoundRecord,
    OptimizationStopReason,
    OptimizationUsage,
    budget_stop_reason,
)
from finance_solution_builder.optimization.models import OptimizationRoundStatus

from . import request, snapshot


def test_budget_stop_reasons_are_stable() -> None:
    budget = OptimizationBudget(
        max_rounds=2,
        max_duration_ms=100,
        max_cost_microunits=5,
        max_no_improvement=2,
    )
    assert budget_stop_reason(
        budget,
        OptimizationUsage(
            rounds=2, duration_ms=0, cost_microunits=0, consecutive_no_improvement=0
        ),
    ) is OptimizationStopReason.MAX_ROUNDS
    assert budget_stop_reason(
        budget,
        OptimizationUsage(
            rounds=0, duration_ms=100, cost_microunits=0, consecutive_no_improvement=0
        ),
    ) is OptimizationStopReason.MAX_DURATION
    free_only = budget.model_copy(update={"max_cost_microunits": 0})
    assert budget_stop_reason(
        free_only,
        OptimizationUsage(
            rounds=0,
            duration_ms=0,
            cost_microunits=0,
            consecutive_no_improvement=0,
        ),
    ) is None


def test_records_are_append_only_and_public(tmp_path) -> None:
    best = snapshot()
    usage = OptimizationUsage(
        rounds=1, duration_ms=1, cost_microunits=0, consecutive_no_improvement=1
    )
    record = OptimizationRoundRecord(
        request_id="optimization-1",
        round_index=1,
        parent_snapshot=best,
        finding_ids=("finding-1",),
        status=OptimizationRoundStatus.BLOCKED,
        approval_fact="Automatic policy was applied.",
        usage=usage,
        best_candidate_id=best.candidate_id,
        best_lock_digest=best.lock_digest,
        stop_reason=OptimizationStopReason.NO_IMPROVEMENT,
    )
    store = OptimizationRecordStore(tmp_path / "records", tmp_path)
    assert store.append_round(record) == store.append_round(record)
    changed = record.model_copy(update={"approval_fact": "Different fact."})
    with pytest.raises(FileExistsError, match="append-only"):
        store.append_round(changed)
    payload = json.loads((tmp_path / "records/round-001.json").read_text(encoding="utf-8"))
    assert "prompt" not in json.dumps(payload).lower()


def test_terminal_result_is_idempotent(tmp_path) -> None:
    best = snapshot()
    result = OptimizationResult(
        request_id=request(best).request_id,
        best_snapshot=best,
        rounds=0,
        stop_reason=OptimizationStopReason.ACCEPTANCE_MET,
        final_usage=OptimizationUsage(
            rounds=0,
            duration_ms=0,
            cost_microunits=0,
            consecutive_no_improvement=0,
        ),
        record_refs=(),
    )
    store = OptimizationRecordStore(tmp_path / "records", tmp_path)
    assert store.write_result(result) == store.write_result(result)


def test_request_binding_rejects_identity_reuse(tmp_path) -> None:
    best = snapshot()
    store = OptimizationRecordStore(tmp_path / "records", tmp_path)
    store.bind_request(request(best))

    changed = request(best).model_copy(
        update={"holdout_authorized": True, "final_gate": True}
    )
    with pytest.raises(FileExistsError, match="append-only"):
        store.bind_request(changed)


def test_holdout_claim_is_atomic_across_store_instances(tmp_path) -> None:
    best = snapshot()
    optimization_request = request(
        best,
        findings=(),
        final_gate=True,
        holdout_authorized=True,
    )
    first = OptimizationRecordStore(tmp_path / "records", tmp_path)
    second = OptimizationRecordStore(tmp_path / "records", tmp_path)
    barrier = threading.Barrier(2)

    def claim(store):
        barrier.wait()
        return store.claim_holdout(optimization_request, best)

    with ThreadPoolExecutor(max_workers=2) as executor:
        outcomes = tuple(executor.map(claim, (first, second)))
    assert sorted(outcomes) == [False, True]

    changed_snapshot = snapshot(best.candidate_id, "b")
    with pytest.raises(FileExistsError, match="identity mismatch"):
        second.claim_holdout(optimization_request, changed_snapshot)


def test_round_and_terminal_models_reject_incomplete_evidence() -> None:
    best = snapshot()
    usage = OptimizationUsage(
        rounds=1,
        duration_ms=0,
        cost_microunits=0,
        consecutive_no_improvement=0,
    )
    with pytest.raises(ValueError, match="rebuild and evaluation"):
        OptimizationRoundRecord(
            request_id="optimization-1",
            round_index=1,
            parent_snapshot=best,
            finding_ids=("finding-1",),
            status=OptimizationRoundStatus.SUCCEEDED,
            approval_fact="Automatic policy was applied.",
            usage=usage,
            best_candidate_id=best.candidate_id,
            best_lock_digest=best.lock_digest,
        )
    with pytest.raises(ValueError, match="terminal round facts"):
        OptimizationResult(
            request_id="optimization-1",
            best_snapshot=best,
            rounds=1,
            stop_reason=OptimizationStopReason.MAX_ROUNDS,
            final_usage=usage,
            record_refs=(),
        )
