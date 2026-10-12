"""Deterministic Finding projection without model-based pass decisions."""

from __future__ import annotations

from collections import defaultdict

from finance_builder_base.evaluation import SampleResult

from .models import (
    EvaluationFinding,
    EvaluationSampleEvidence,
    FindingResponsibility,
    canonical_digest,
)


def responsibility_from_error(code: str) -> FindingResponsibility:
    """Map stable error namespaces to the owner that can act on the problem."""

    if code.startswith(("provider.", "harness.", "external.")):
        return FindingResponsibility.PROVIDER_HARNESS_ENVIRONMENT
    if code.startswith(("solution.", "asset.")):
        return FindingResponsibility.SOLUTION_ASSETS
    if code.startswith("evaluation.data."):
        return FindingResponsibility.DATA
    if code.startswith("evaluation.rule."):
        return FindingResponsibility.ACCEPTANCE_RULE
    return FindingResponsibility.CANDIDATE


def project_findings(
    results: tuple[SampleResult, ...],
    samples: tuple[EvaluationSampleEvidence, ...],
) -> tuple[EvaluationFinding, ...]:
    """Group failed sample facts without changing any evaluated object."""

    evidence_by_id = {sample.sample_id: sample for sample in samples}
    grouped: dict[FindingResponsibility, list[EvaluationSampleEvidence]] = defaultdict(list)
    for result in results:
        if result.error is None and result.matched is not False:
            continue
        evidence = evidence_by_id.get(result.case_id)
        if evidence is None:
            continue
        specific_error = evidence.error or result.error
        responsibility = (
            responsibility_from_error(specific_error.code)
            if specific_error is not None
            else FindingResponsibility.CANDIDATE
        )
        grouped[responsibility].append(evidence)

    findings: list[EvaluationFinding] = []
    for responsibility in FindingResponsibility:
        affected = grouped.get(responsibility, [])
        if not affected:
            continue
        sample_ids = tuple(sorted(item.sample_id for item in affected))
        refs_by_locator = {
            item.response_ref.locator: item.response_ref
            for item in affected
            if item.response_ref is not None
        }
        refs = tuple(refs_by_locator[key] for key in sorted(refs_by_locator))
        finding_id = (
            "finding-"
            + canonical_digest({"responsibility": responsibility.value, "sampleIds": sample_ids})[
                :16
            ]
        )
        findings.append(
            EvaluationFinding(
                finding_id=finding_id,
                responsibility=responsibility,
                sample_ids=sample_ids,
                summary=(
                    f"{len(sample_ids)} evaluated sample(s) require review in the "
                    f"{responsibility.value} boundary."
                ),
                evidence_refs=refs,
            )
        )
    return tuple(findings)


def project_lifecycle_finding(error_code: str) -> EvaluationFinding:
    responsibility = responsibility_from_error(error_code)
    return EvaluationFinding(
        finding_id="finding-" + canonical_digest({"lifecycleError": error_code})[:16],
        responsibility=responsibility,
        sample_ids=(),
        summary=f"Sandbox lifecycle requires review in the {responsibility.value} boundary.",
    )
