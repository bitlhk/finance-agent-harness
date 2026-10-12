from finance_solution_builder.evaluation import FindingResponsibility
import pytest
from finance_solution_builder.optimization import (
    ActionDisposition,
    CorrectionExecution,
    CorrectionTargetKind,
    GovernedTarget,
    OptimizationController,
    OptimizationDataVersion,
    OptimizationRecordStore,
    OptimizationStopReason,
    RebuildOutcome,
)

from . import SHA, finding, report, request, resource, snapshot


class FakeCorrectionExecutor:
    def __init__(self) -> None:
        self.calls = 0

    def execute(self, target, round_index):
        self.calls += 1
        data = OptimizationDataVersion(
            version_id=f"data-{round_index}",
            task_id=f"data-{round_index}",
            source_digest=SHA,
            output_digest="c" * 64,
            resource_refs=(resource("data/optimization.jsonl"),),
            item_count=2,
        )
        return CorrectionExecution(
            change_digest=data.sha256(),
            evidence_refs=(resource("evidence/change.json"),),
            duration_ms=5,
            cost_microunits=1,
            data_version=data,
        )


class FakeExternalProposalGateway:
    def __init__(self) -> None:
        self.calls = []

    def write(self, request, selected_finding, target):
        self.calls.append((request.request_id, selected_finding.finding_id, target.target_ref))
        return resource("proposals/external-change.json")


class FakeRebuildGateway:
    def __init__(self) -> None:
        self.calls = 0

    def rebuild(self, parent, correction, round_index):
        del correction
        self.calls += 1
        candidate = snapshot(parent.candidate_id, "b")
        return RebuildOutcome(
            snapshot=candidate,
            lock_ref=resource("delivery/solution-lock.json").model_copy(
                update={"sha256": candidate.lock_digest}
            ),
            report_ref=resource("delivery/build-report.json"),
        )


class FakeEvaluationGateway:
    def __init__(self, accuracy=0.9, findings=(), *, final_gate_passed=True) -> None:
        self.accuracy = accuracy
        self.findings = findings
        self.calls = 0
        self.final_gate_passed = final_gate_passed
        self.call_facts = []

    def evaluate(self, candidate, correction, *, final_gate):
        self.calls += 1
        self.call_facts.append((correction is None, final_gate))
        return report(
            candidate,
            accuracy=self.accuracy,
            passed=(self.final_gate_passed if final_gate else True),
            findings=(() if final_gate else self.findings),
        )


def controller(tmp_path, correction, evaluation, external_proposal=None):
    return OptimizationController(
        correction_executor=correction,
        rebuild_gateway=FakeRebuildGateway(),
        evaluation_gateway=evaluation,
        record_store=OptimizationRecordStore(tmp_path / "records", tmp_path),
        external_proposal_gateway=external_proposal,
    )


def test_controller_repairs_rebuilds_evaluates_and_promotes(tmp_path) -> None:
    baseline = snapshot()
    correction = FakeCorrectionExecutor()
    evaluation = FakeEvaluationGateway()
    result = controller(tmp_path, correction, evaluation).run(
        request(baseline),
        report(baseline, accuracy=0.8, passed=False, findings=(finding(),)),
    )
    assert result.stop_reason is OptimizationStopReason.ACCEPTANCE_MET
    assert result.best_snapshot.candidate_id == baseline.candidate_id
    assert result.best_snapshot.lock_digest != baseline.lock_digest
    assert result.rounds == 1
    assert result.final_usage.rounds == 1
    assert correction.calls == evaluation.calls == 1
    assert (tmp_path / "records/round-001.json").exists()
    assert (tmp_path / "records/result.json").exists()


def test_controller_stops_before_write_for_external_target(tmp_path) -> None:
    baseline = snapshot()
    correction = FakeCorrectionExecutor()
    evaluation = FakeEvaluationGateway()
    governance = (
        GovernedTarget(
            responsibility=FindingResponsibility.SOLUTION_ASSETS.value,
            target_kind=CorrectionTargetKind.HARNESS_ASSET,
            target_ref="harness/domain-pack",
            owner_id="harness-owner",
            allowed_actions=("propose-change",),
            approval_policy=ActionDisposition.EXTERNAL_BLOCKED,
            validation_refs=(resource(),),
            priority=1,
        ),
    )
    proposal = FakeExternalProposalGateway()
    result = controller(tmp_path, correction, evaluation, proposal).run(
        request(
            baseline,
            findings=(finding(FindingResponsibility.SOLUTION_ASSETS),),
            governance=governance,
        ),
        report(
            baseline,
            accuracy=0.8,
            passed=False,
            findings=(finding(FindingResponsibility.SOLUTION_ASSETS),),
        ),
    )
    assert result.stop_reason is OptimizationStopReason.EXTERNAL_BLOCKED
    assert correction.calls == evaluation.calls == 0
    assert proposal.calls == [(
        result.request_id,
        "finding-1",
        "harness/domain-pack",
    )]
    round_record = (tmp_path / "records/round-001.json").read_text(encoding="utf-8")
    assert "proposals/external-change.json" in round_record


def test_external_proposal_failure_is_recorded_and_fails_closed(tmp_path) -> None:
    class FailingProposalGateway:
        def write(self, request, selected_finding, target):
            del request, selected_finding, target
            raise ValueError("proposal context is incomplete")

    baseline = snapshot()
    governance = (
        GovernedTarget(
            responsibility=FindingResponsibility.SOLUTION_ASSETS.value,
            target_kind=CorrectionTargetKind.HARNESS_ASSET,
            target_ref="harness/domain-pack",
            owner_id="harness-owner",
            allowed_actions=("propose-change",),
            approval_policy=ActionDisposition.EXTERNAL_BLOCKED,
            validation_refs=(resource(),),
            priority=1,
        ),
    )
    active_finding = finding(FindingResponsibility.SOLUTION_ASSETS)
    result = controller(
        tmp_path,
        FakeCorrectionExecutor(),
        FakeEvaluationGateway(),
        FailingProposalGateway(),
    ).run(
        request(baseline, findings=(active_finding,), governance=governance),
        report(
            baseline,
            accuracy=0.8,
            passed=False,
            findings=(active_finding,),
        ),
    )

    assert result.stop_reason is OptimizationStopReason.FAILED
    round_record = (tmp_path / "records/round-001.json").read_text(encoding="utf-8")
    assert '"status": "failed"' in round_record
    assert "proposal context is incomplete" not in round_record


def test_controller_keeps_best_and_stops_after_no_improvement(tmp_path) -> None:
    baseline = snapshot()
    evaluation = FakeEvaluationGateway(accuracy=0.8, findings=(finding(),))
    result = controller(tmp_path, FakeCorrectionExecutor(), evaluation).run(
        request(baseline, max_no_improvement=1),
        report(baseline, accuracy=0.8, passed=False, findings=(finding(),)),
    )
    assert result.stop_reason is OptimizationStopReason.NO_IMPROVEMENT
    assert result.best_snapshot == baseline
    assert result.rounds == 1


def test_passing_but_unpromoted_candidate_does_not_mark_old_best_accepted(tmp_path) -> None:
    baseline = snapshot()
    result = controller(
        tmp_path,
        FakeCorrectionExecutor(),
        FakeEvaluationGateway(accuracy=0.8, findings=()),
    ).run(
        request(baseline),
        report(baseline, accuracy=0.8, passed=False, findings=(finding(),)),
    )
    assert result.stop_reason is OptimizationStopReason.NO_IMPROVEMENT
    assert result.best_snapshot == baseline


def test_failed_report_without_findings_stops_without_claiming_acceptance(tmp_path) -> None:
    baseline = snapshot()
    correction = FakeCorrectionExecutor()
    evaluation = FakeEvaluationGateway()

    result = controller(tmp_path, correction, evaluation).run(
        request(baseline, findings=()),
        report(baseline, accuracy=0.8, passed=False, findings=()),
    )

    assert result.stop_reason is OptimizationStopReason.NO_EXECUTABLE_TARGET
    assert result.rounds == 0
    assert correction.calls == evaluation.calls == 0


def test_initial_findings_must_match_the_baseline_report(tmp_path) -> None:
    baseline = snapshot()

    with pytest.raises(ValueError, match="findings_mismatch"):
        controller(
            tmp_path,
            FakeCorrectionExecutor(),
            FakeEvaluationGateway(),
        ).run(
            request(baseline, findings=()),
            report(baseline, accuracy=0.8, passed=False, findings=(finding(),)),
        )


def test_baseline_report_must_match_declared_dataset_snapshot(tmp_path) -> None:
    baseline = snapshot().model_copy(update={"dataset_digest": "f" * 64})

    with pytest.raises(ValueError, match="identity_mismatch"):
        controller(
            tmp_path,
            FakeCorrectionExecutor(),
            FakeEvaluationGateway(),
        ).run(
            request(baseline),
            report(baseline, accuracy=0.8, passed=False, findings=(finding(),)),
        )


def test_holdout_runs_once_only_after_normal_regression_passes(tmp_path) -> None:
    baseline = snapshot()
    evaluation = FakeEvaluationGateway()

    result = controller(tmp_path, FakeCorrectionExecutor(), evaluation).run(
        request(
            baseline,
            findings=(),
            final_gate=True,
            holdout_authorized=True,
        ),
        report(baseline, accuracy=0.9, passed=True, findings=()),
    )

    assert evaluation.call_facts == [(True, True)]
    assert result.stop_reason is OptimizationStopReason.ACCEPTANCE_MET
    assert result.holdout_consumed is True
    assert result.final_report_digest is not None


def test_failed_holdout_is_terminal_and_never_becomes_correction_input(tmp_path) -> None:
    baseline = snapshot()
    correction = FakeCorrectionExecutor()
    evaluation = FakeEvaluationGateway(final_gate_passed=False)

    result = controller(tmp_path, correction, evaluation).run(
        request(
            baseline,
            findings=(),
            final_gate=True,
            holdout_authorized=True,
        ),
        report(baseline, accuracy=0.9, passed=True, findings=()),
    )

    assert result.stop_reason is OptimizationStopReason.FINAL_GATE_NOT_MET
    assert result.holdout_consumed is True
    assert correction.calls == 0
    assert evaluation.call_facts == [(True, True)]


def test_holdout_is_not_repeated_after_controller_restart(tmp_path) -> None:
    baseline = snapshot()
    optimization_request = request(
        baseline,
        findings=(),
        final_gate=True,
        holdout_authorized=True,
    )
    baseline_report = report(baseline, accuracy=0.9, passed=True, findings=())
    first_evaluation = FakeEvaluationGateway()
    first_result = controller(
        tmp_path, FakeCorrectionExecutor(), first_evaluation
    ).run(optimization_request, baseline_report)

    second_evaluation = FakeEvaluationGateway()
    second_result = controller(
        tmp_path, FakeCorrectionExecutor(), second_evaluation
    ).run(optimization_request, baseline_report)

    assert first_evaluation.call_facts == [(True, True)]
    assert second_evaluation.call_facts == []
    assert second_result == first_result


def test_failed_holdout_is_not_repeated_after_controller_restart(tmp_path) -> None:
    baseline = snapshot()
    optimization_request = request(
        baseline,
        findings=(),
        final_gate=True,
        holdout_authorized=True,
    )
    baseline_report = report(baseline, accuracy=0.9, passed=True, findings=())
    first_evaluation = FakeEvaluationGateway(final_gate_passed=False)
    first_result = controller(
        tmp_path, FakeCorrectionExecutor(), first_evaluation
    ).run(optimization_request, baseline_report)

    second_evaluation = FakeEvaluationGateway(final_gate_passed=False)
    second_result = controller(
        tmp_path, FakeCorrectionExecutor(), second_evaluation
    ).run(optimization_request, baseline_report)

    assert first_evaluation.call_facts == [(True, True)]
    assert second_evaluation.call_facts == []
    assert first_result.stop_reason is OptimizationStopReason.FINAL_GATE_NOT_MET
    assert second_result == first_result


def test_interrupted_holdout_claim_fails_closed_after_restart(tmp_path) -> None:
    baseline = snapshot()
    optimization_request = request(
        baseline,
        findings=(),
        final_gate=True,
        holdout_authorized=True,
    )
    store = OptimizationRecordStore(tmp_path / "records", tmp_path)
    store.bind_request(optimization_request)
    assert store.claim_holdout(optimization_request, baseline) is True

    evaluation = FakeEvaluationGateway()
    subject = OptimizationController(
        correction_executor=FakeCorrectionExecutor(),
        rebuild_gateway=FakeRebuildGateway(),
        evaluation_gateway=evaluation,
        record_store=OptimizationRecordStore(tmp_path / "records", tmp_path),
    )

    with pytest.raises(RuntimeError, match="holdout.already_claimed"):
        subject.run(
            optimization_request,
            report(baseline, accuracy=0.9, passed=True, findings=()),
        )
    assert evaluation.call_facts == []


def test_correction_that_exhausts_cost_budget_stops_before_rebuild(tmp_path) -> None:
    class ExpensiveCorrection(FakeCorrectionExecutor):
        def execute(self, target, round_index):
            result = super().execute(target, round_index)
            return result.model_copy(update={"cost_microunits": 101})

    baseline = snapshot()
    correction = ExpensiveCorrection()
    rebuild = FakeRebuildGateway()
    evaluation = FakeEvaluationGateway()
    subject = OptimizationController(
        correction_executor=correction,
        rebuild_gateway=rebuild,
        evaluation_gateway=evaluation,
        record_store=OptimizationRecordStore(tmp_path / "records", tmp_path),
    )

    result = subject.run(
        request(baseline),
        report(baseline, accuracy=0.8, passed=False, findings=(finding(),)),
    )

    assert result.stop_reason is OptimizationStopReason.MAX_COST
    assert result.final_usage.cost_microunits == 101
    assert rebuild.calls == evaluation.calls == 0
