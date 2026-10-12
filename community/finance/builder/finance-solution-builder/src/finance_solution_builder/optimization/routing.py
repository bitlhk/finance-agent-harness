"""Deterministic Finding-to-correction routing."""

from __future__ import annotations

from finance_solution_builder.evaluation import EvaluationFinding, FindingResponsibility

from .models import (
    ActionDisposition,
    CorrectionTarget,
    CorrectionTargetKind,
    GovernedTarget,
    OptimizationRequest,
    OptimizationStopReason,
    RoutingDecision,
)

_EXTERNAL_RESPONSIBILITIES = {
    FindingResponsibility.SOLUTION_ASSETS.value,
    FindingResponsibility.PROVIDER_HARNESS_ENVIRONMENT.value,
    FindingResponsibility.ACCEPTANCE_RULE.value,
}


def route_correction(request: OptimizationRequest) -> RoutingDecision:
    """Choose exactly one governed target, or fail closed with a stable reason."""

    finding_ids = tuple(sorted(item.finding_id for item in request.findings))
    if not finding_ids:
        return RoutingDecision(
            stop_reason=OptimizationStopReason.ACCEPTANCE_MET,
            considered_finding_ids=(),
            detail="No active Evaluation Finding remains.",
        )

    candidates = []
    for finding in sorted(request.findings, key=lambda item: item.finding_id):
        if finding.responsibility is FindingResponsibility.ACCEPTANCE_RULE:
            continue
        matches = [
            target
            for target in request.governance
            if target.responsibility == finding.responsibility.value
        ]
        if not matches:
            continue
        best_priority = min(item.priority for item in matches)
        best = [item for item in matches if item.priority == best_priority]
        if len(best) != 1:
            return RoutingDecision(
                stop_reason=OptimizationStopReason.NO_EXECUTABLE_TARGET,
                considered_finding_ids=finding_ids,
                detail="Governance metadata does not identify one correction target.",
            )
        candidates.append((best_priority, finding.finding_id, best[0]))

    if not candidates:
        external = any(
            item.responsibility.value in _EXTERNAL_RESPONSIBILITIES
            for item in request.findings
        )
        return RoutingDecision(
            stop_reason=(
                OptimizationStopReason.EXTERNAL_BLOCKED
                if external
                else OptimizationStopReason.NO_EXECUTABLE_TARGET
            ),
            considered_finding_ids=finding_ids,
            detail="No complete governance entry is available for the active Finding.",
        )

    candidates.sort(key=lambda item: (item[0], item[1], item[2].target_ref))
    priority, finding_id, governed = candidates[0]
    if sum(item[0] == priority for item in candidates) > 1:
        return RoutingDecision(
            stop_reason=OptimizationStopReason.NO_EXECUTABLE_TARGET,
            considered_finding_ids=finding_ids,
            detail="Multiple Findings have the same correction priority.",
        )
    if governed.approval_policy is ActionDisposition.APPROVAL_REQUIRED:
        return RoutingDecision(
            stop_reason=OptimizationStopReason.APPROVAL_REQUIRED,
            considered_finding_ids=finding_ids,
            detail="The selected correction requires a human approval.",
        )
    if (
        governed.approval_policy is ActionDisposition.EXTERNAL_BLOCKED
        or governed.target_kind
        in {CorrectionTargetKind.HARNESS_ASSET, CorrectionTargetKind.EXTERNAL_ENVIRONMENT}
    ):
        return RoutingDecision(
            stop_reason=OptimizationStopReason.EXTERNAL_BLOCKED,
            considered_finding_ids=finding_ids,
            detail="The selected correction is owned outside this Builder.",
        )
    if len(governed.allowed_actions) != 1:
        return RoutingDecision(
            stop_reason=OptimizationStopReason.NO_EXECUTABLE_TARGET,
            considered_finding_ids=finding_ids,
            detail="One automatic correction action was not declared.",
        )
    return RoutingDecision(
        target=CorrectionTarget(
            finding_id=finding_id,
            target_kind=governed.target_kind,
            target_ref=governed.target_ref,
            action=governed.allowed_actions[0],
            disposition=ActionDisposition.AUTO,
            reason="Selected by Finding responsibility and explicit governance priority.",
            owner_id=governed.owner_id,
            allowed_write_paths=governed.allowed_write_paths,
            validation_refs=governed.validation_refs,
        ),
        considered_finding_ids=finding_ids,
        detail="One governed automatic correction target was selected.",
    )

def external_proposal_context(
    request: OptimizationRequest,
) -> tuple[EvaluationFinding, GovernedTarget] | None:
    """Return the one external Finding/governance pair selected by routing."""

    routing = route_correction(request)
    if routing.stop_reason is not OptimizationStopReason.EXTERNAL_BLOCKED:
        return None
    candidates = []
    for finding in sorted(request.findings, key=lambda item: item.finding_id):
        if finding.responsibility is FindingResponsibility.ACCEPTANCE_RULE:
            continue
        matches = [
            target
            for target in request.governance
            if target.responsibility == finding.responsibility.value
        ]
        if not matches:
            continue
        best_priority = min(item.priority for item in matches)
        best = [item for item in matches if item.priority == best_priority]
        if len(best) != 1:
            return None
        candidates.append((best_priority, finding.finding_id, finding, best[0]))
    candidates.sort(key=lambda item: (item[0], item[1], item[3].target_ref))
    if not candidates or sum(item[0] == candidates[0][0] for item in candidates) > 1:
        return None
    _, _, finding, target = candidates[0]
    if (
        target.approval_policy is not ActionDisposition.EXTERNAL_BLOCKED
        and target.target_kind
        not in {CorrectionTargetKind.HARNESS_ASSET, CorrectionTargetKind.EXTERNAL_ENVIRONMENT}
    ):
        return None
    return finding, target
