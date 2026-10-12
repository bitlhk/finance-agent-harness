from finance_solution_builder.evaluation import FindingResponsibility
from finance_solution_builder.optimization import (
    ActionDisposition,
    CorrectionTargetKind,
    GovernedTarget,
    OptimizationStopReason,
    route_correction,
)

from . import finding, request, resource, snapshot


def governed(**updates) -> GovernedTarget:
    values = {
        "responsibility": FindingResponsibility.CANDIDATE.value,
        "target_kind": CorrectionTargetKind.CANDIDATE_OVERLAY,
        "target_ref": "candidate/source",
        "owner_id": "owner",
        "allowed_actions": ("repair",),
        "approval_policy": ActionDisposition.AUTO,
        "allowed_write_paths": ("src",),
        "validation_refs": (resource(),),
        "priority": 1,
    }
    values.update(updates)
    return GovernedTarget(**values)


def test_routes_one_governed_target_deterministically() -> None:
    work = request(snapshot(), governance=(governed(),))
    assert route_correction(work) == route_correction(work)
    assert route_correction(work).target.target_ref == "candidate/source"


def test_tied_targets_fail_closed() -> None:
    work = request(
        snapshot(), governance=(governed(target_ref="candidate/a"), governed(target_ref="candidate/b"))
    )
    assert route_correction(work).stop_reason is OptimizationStopReason.NO_EXECUTABLE_TARGET


def test_external_and_approval_targets_do_not_create_write_action() -> None:
    external = governed(
        responsibility=FindingResponsibility.SOLUTION_ASSETS.value,
        target_kind=CorrectionTargetKind.HARNESS_ASSET,
        approval_policy=ActionDisposition.EXTERNAL_BLOCKED,
        allowed_write_paths=(),
    )
    work = request(
        snapshot(),
        findings=(finding(FindingResponsibility.SOLUTION_ASSETS),),
        governance=(external,),
    )
    assert route_correction(work).stop_reason is OptimizationStopReason.EXTERNAL_BLOCKED
    approval = governed(approval_policy=ActionDisposition.APPROVAL_REQUIRED)
    assert route_correction(request(snapshot(), governance=(approval,))).stop_reason is (
        OptimizationStopReason.APPROVAL_REQUIRED
    )


def test_acceptance_rule_cannot_be_routed_to_an_automatic_builder_edit() -> None:
    unsafe = governed(responsibility=FindingResponsibility.ACCEPTANCE_RULE.value)
    work = request(
        snapshot(),
        findings=(finding(FindingResponsibility.ACCEPTANCE_RULE),),
        governance=(unsafe,),
    )
    assert route_correction(work).stop_reason is OptimizationStopReason.EXTERNAL_BLOCKED
