"""Public-safe projection of immutable Evaluation and Optimization facts."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Literal

from pydantic import model_validator

from finance_builder_base import ContractModel, ResourceKind, ResourceRef
from finance_builder_base.contracts import sanitize_public_text

from .candidate_backend import CandidateDescriptor
from .evaluation import SandboxEvaluationReport
from .optimization import (
    OptimizationRequest,
    OptimizationResult,
    OptimizationRoundRecord,
    OptimizationSnapshot,
    dataset_snapshot_digest,
)

VIEW_SCHEMA = "finance-workbench-optimization-view.v1"
MAX_RECORD_BYTES = 4 * 1024 * 1024


class OptimizationViewBinding(ContractModel):
    """Explicit Build-owned references consumed by the read-only workbench."""

    schema_version: Literal["1.0"] = "1.0"
    candidate_descriptor_ref: ResourceRef
    solution_lock_ref: ResourceRef
    request_ref: ResourceRef
    baseline_evaluation_ref: ResourceRef
    round_refs: tuple[ResourceRef, ...] = ()
    result_ref: ResourceRef | None = None
    evaluation_refs: tuple[ResourceRef, ...] = ()
    proposal_refs: tuple[ResourceRef, ...] = ()

    @model_validator(mode="after")
    def validate_refs(self) -> "OptimizationViewBinding":
        refs = (
            self.candidate_descriptor_ref,
            self.solution_lock_ref,
            self.request_ref,
            self.baseline_evaluation_ref,
            *self.round_refs,
            *self.evaluation_refs,
            *self.proposal_refs,
        )
        if self.result_ref is not None:
            refs = (*refs, self.result_ref)
        if any(
            ref.kind is not ResourceKind.WORKSPACE_PATH or ref.sha256 is None
            for ref in refs
        ):
            raise ValueError("projection requires digested workspace references")
        locators = [ref.locator for ref in refs]
        if len(locators) != len(set(locators)):
            raise ValueError("projection references must be unique")
        return self


class ProjectionIssue(Exception):
    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


def _public_ref(ref: ResourceRef) -> dict[str, str]:
    if sanitize_public_text(ref.locator) != ref.locator:
        raise ProjectionIssue("OPTIMIZATION_REFERENCE_NOT_PUBLIC")
    return {"locator": ref.locator, "sha256": ref.sha256 or ""}


def _require_public_text(value: Any, code: str) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or sanitize_public_text(value) != value:
        raise ProjectionIssue(code)
    return value


def _finding_view(finding) -> dict[str, Any]:
    return {
        "findingId": finding.finding_id,
        "responsibility": finding.responsibility.value,
        "sampleIds": list(finding.sample_ids),
        "summary": _require_public_text(
            finding.summary, "FINDING_CONTENT_NOT_PUBLIC"
        ),
        "evidenceRefs": [_public_ref(ref) for ref in finding.evidence_refs],
    }


def _evaluation_view(report: SandboxEvaluationReport) -> dict[str, Any]:
    progress = report.progress
    return {
        "snapshotId": report.snapshot.snapshot_id,
        "candidateId": report.snapshot.candidate_id,
        "conclusion": report.conclusion.value,
        "progress": {
            "completed": progress.completed,
            "total": progress.total,
            "phase": "completed" if progress.completed == progress.total else "executing",
        },
        "metrics": [
            {
                "name": item.name,
                "value": item.value,
                "numerator": item.numerator,
                "denominator": item.denominator,
            }
            for item in report.metrics
        ],
        "samples": [
            {
                "sampleId": item.sample_id,
                "datasetId": item.dataset_id,
                "status": item.status.value,
                "matched": item.matched,
                "durationMillis": item.duration_ms,
                "errorStage": item.error_stage,
                "errorCode": None if item.error is None else item.error.code,
            }
            for item in report.samples
        ],
        "findings": [_finding_view(item) for item in report.findings],
        "evidenceLevels": [
            {
                "level": item.level.value,
                "executed": item.executed,
                "status": item.status.value,
                "evidenceRefs": [_public_ref(ref) for ref in item.evidence_refs],
            }
            for item in report.evidence_levels
        ],
    }


class OptimizationViewProjector:
    """Validate Build-local references and assemble one UI projection."""

    def __init__(self, build_root: Path, build_id: str) -> None:
        self.build_root = build_root.resolve()
        self.build_id = build_id

    def project(
        self,
        raw_binding: dict[str, Any] | None,
        *,
        build_status: str,
        created_at: float | None,
        selection: dict[str, Any] | None,
    ) -> dict[str, Any]:
        if raw_binding is None:
            return self._empty(
                "not-executed", build_status, created_at, selection, None
            )
        try:
            binding = OptimizationViewBinding.model_validate(raw_binding)
            return self._project_verified(
                binding, build_status, created_at, selection
            )
        except ProjectionIssue as error:
            return self._empty(
                "unverifiable", build_status, created_at, selection, error.code
            )
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            return self._empty(
                "unverifiable",
                build_status,
                created_at,
                selection,
                "OPTIMIZATION_FACT_INVALID",
            )

    def _project_verified(self, binding, build_status, created_at, selection):
        request = self._read_model(binding.request_ref, OptimizationRequest)
        baseline = self._read_model(
            binding.baseline_evaluation_ref, SandboxEvaluationReport
        )
        self._verify_evaluation(request.baseline, baseline)
        if request.findings != baseline.findings:
            raise ProjectionIssue("OPTIMIZATION_FINDINGS_MISMATCH")

        reports = [baseline]
        reports.extend(
            self._read_model(ref, SandboxEvaluationReport)
            for ref in binding.evaluation_refs
        )
        reports_by_digest = {}
        report_refs_by_digest = {}
        findings = {}
        report_refs = (
            binding.baseline_evaluation_ref,
            *binding.evaluation_refs,
        )
        for report, ref in zip(reports, report_refs, strict=True):
            digest = report.sha256()
            if digest in reports_by_digest:
                raise ProjectionIssue("EVALUATION_REPORT_DUPLICATE")
            reports_by_digest[digest] = report
            report_refs_by_digest[digest] = ref
            for finding in report.findings:
                previous = findings.setdefault(finding.finding_id, finding)
                if previous != finding:
                    raise ProjectionIssue("FINDING_IDENTITY_DRIFT")

        rounds = [
            self._read_model(ref, OptimizationRoundRecord)
            for ref in binding.round_refs
        ]
        expected = list(range(1, len(rounds) + 1))
        if [item.round_index for item in rounds] != expected:
            raise ProjectionIssue("OPTIMIZATION_ROUND_SEQUENCE_INVALID")
        if any(item.request_id != request.request_id for item in rounds):
            raise ProjectionIssue("OPTIMIZATION_REQUEST_IDENTITY_DRIFT")

        result = None
        if binding.result_ref is not None:
            result = self._read_model(binding.result_ref, OptimizationResult)
            if result.request_id != request.request_id:
                raise ProjectionIssue("OPTIMIZATION_RESULT_IDENTITY_DRIFT")
            if result.record_refs != binding.round_refs:
                raise ProjectionIssue("OPTIMIZATION_RESULT_RECORDS_MISMATCH")

        current_report = baseline
        expected_parent = request.baseline
        used_evaluation_digests = set()
        snapshots = {request.baseline.lock_digest: request.baseline}
        for record in rounds:
            if record.parent_snapshot != expected_parent:
                raise ProjectionIssue("OPTIMIZATION_PARENT_SNAPSHOT_DRIFT")
            current_findings = {
                item.finding_id for item in current_report.findings
            }
            if set(record.finding_ids) - current_findings:
                raise ProjectionIssue("OPTIMIZATION_FINDING_REFERENCE_UNKNOWN")
            if record.candidate_snapshot is not None:
                snapshots[record.candidate_snapshot.lock_digest] = (
                    record.candidate_snapshot
                )
            if record.evaluation_report_digest is not None:
                current_report = reports_by_digest.get(
                    record.evaluation_report_digest
                )
                if current_report is None or record.candidate_snapshot is None:
                    raise ProjectionIssue("EVALUATION_REPORT_REFERENCE_MISSING")
                self._verify_evaluation(
                    record.candidate_snapshot, current_report
                )
                used_evaluation_digests.add(record.evaluation_report_digest)
            if record.best_lock_digest not in snapshots:
                raise ProjectionIssue("OPTIMIZATION_BEST_SNAPSHOT_UNKNOWN")
            expected_parent = snapshots[record.best_lock_digest]

        if result is not None:
            recorded_best = snapshots.get(result.best_snapshot.lock_digest)
            if recorded_best is None or recorded_best != result.best_snapshot:
                raise ProjectionIssue("OPTIMIZATION_RESULT_SNAPSHOT_UNKNOWN")
            if result.final_report_digest is not None:
                final_report = reports_by_digest.get(result.final_report_digest)
                if final_report is None:
                    raise ProjectionIssue("FINAL_EVALUATION_REPORT_MISSING")
                self._verify_evaluation(result.best_snapshot, final_report)
                current_report = final_report
                used_evaluation_digests.add(result.final_report_digest)

        supplied_evaluation_digests = {
            report.sha256() for report in reports[1:]
        }
        if supplied_evaluation_digests - used_evaluation_digests:
            raise ProjectionIssue("EVALUATION_REPORT_NOT_REFERENCED")

        current = (
            result.best_snapshot
            if result is not None
            else snapshots[rounds[-1].best_lock_digest]
            if rounds
            else request.baseline
        )
        descriptor = self._read_model(
            binding.candidate_descriptor_ref, CandidateDescriptor
        )
        if (
            descriptor.candidate_id != current.candidate_id
            or descriptor.sha256() != current.descriptor_digest
            or descriptor.lock_digest != current.lock_digest
            or descriptor.artifact.resource.sha256 != current.artifact_digest
        ):
            raise ProjectionIssue("CANDIDATE_DESCRIPTOR_IDENTITY_DRIFT")
        self._read_json(
            binding.solution_lock_ref,
            expected_content_digest=current.lock_digest,
        )

        proposals = self._proposal_views(binding.proposal_refs, rounds)
        status = "completed" if result is not None else "executing"
        stop_reason = None if result is None else result.stop_reason.value
        return {
            "schemaVersion": VIEW_SCHEMA,
            "buildId": self.build_id,
            "status": status,
            "verifiability": {"status": "verified", "issues": []},
            "build": self._build_view(build_status, created_at, selection),
            "candidate": self._candidate_view(current, descriptor),
            "request": {
                "requestId": request.request_id,
                "budget": request.budget.to_wire(),
                "objectives": [item.to_wire() for item in request.objectives],
                "finalGate": request.final_gate,
                "holdoutAuthorized": request.holdout_authorized,
            },
            "evaluation": _evaluation_view(current_report),
            "rounds": [
                self._round_view(item, findings, proposals) for item in rounds
            ],
            "result": self._result_view(result),
            "storyline": self._storyline(
                binding,
                baseline,
                rounds,
                result,
                report_refs_by_digest,
                created_at,
            ),
            "proposals": list(proposals.values()),
            "nextAction": self._next_action(status, stop_reason),
        }

    def _read_bytes(self, ref: ResourceRef) -> bytes:
        path = (self.build_root / ref.locator).resolve()
        if path == self.build_root or not path.is_relative_to(self.build_root):
            raise ProjectionIssue("OPTIMIZATION_REFERENCE_OUTSIDE_BUILD")
        if not path.is_file():
            raise ProjectionIssue("OPTIMIZATION_REFERENCE_MISSING")
        if path.stat().st_size > MAX_RECORD_BYTES:
            raise ProjectionIssue("OPTIMIZATION_RECORD_TOO_LARGE")
        payload = path.read_bytes()
        actual = hashlib.sha256(payload).hexdigest()
        if ref.sha256 is None or actual != ref.sha256:
            raise ProjectionIssue("OPTIMIZATION_REFERENCE_DIGEST_MISMATCH")
        return payload

    def _read_json(
        self, ref: ResourceRef, *, expected_content_digest: str | None = None
    ) -> Any:
        payload = self._read_bytes(ref)
        if (
            expected_content_digest is not None
            and hashlib.sha256(payload).hexdigest() != expected_content_digest
        ):
            raise ProjectionIssue("OPTIMIZATION_CONTENT_IDENTITY_DRIFT")
        return json.loads(payload)

    def _read_model(self, ref: ResourceRef, model):
        return model.model_validate(self._read_json(ref))

    @staticmethod
    def _verify_evaluation(
        snapshot: OptimizationSnapshot, report: SandboxEvaluationReport
    ) -> None:
        frozen = report.snapshot
        if (
            frozen.candidate_id != snapshot.candidate_id
            or frozen.descriptor_digest != snapshot.descriptor_digest
            or frozen.lock_digest != snapshot.lock_digest
            or frozen.artifact_digest != snapshot.artifact_digest
            or frozen.pack_digest != snapshot.evaluation_pack_digest
            or frozen.rules_digest != snapshot.rules_digest
            or dataset_snapshot_digest(frozen.partitions) != snapshot.dataset_digest
            or snapshot.evidence_level
            not in {item.level.value for item in report.evidence_levels}
        ):
            raise ProjectionIssue("EVALUATION_SNAPSHOT_IDENTITY_DRIFT")

    def _proposal_views(self, refs, rounds):
        referenced = {
            ref.locator for record in rounds for ref in record.validation_refs
        }
        views = {}
        for ref in refs:
            if ref.locator not in referenced:
                raise ProjectionIssue("PROPOSAL_NOT_REFERENCED_BY_ROUND")
            value = self._read_json(ref)
            if (
                value.get("schemaVersion")
                != "finance-harness-asset-change-proposal.v1"
            ):
                raise ProjectionIssue("PROPOSAL_SCHEMA_UNSUPPORTED")
            target = value.get("target", {})
            proposed = value.get("proposedChange", {})
            summary = proposed.get("summary", "")
            if (
                not isinstance(summary, str)
                or sanitize_public_text(summary) != summary
            ):
                raise ProjectionIssue("PROPOSAL_CONTENT_NOT_PUBLIC")
            scopes = proposed.get("scopes", [])
            if (
                not isinstance(scopes, list)
                or any(
                    not isinstance(item, str)
                    or sanitize_public_text(item) != item
                    for item in scopes
                )
            ):
                raise ProjectionIssue("PROPOSAL_CONTENT_NOT_PUBLIC")
            proposal_id = _require_public_text(
                value.get("proposalId"), "PROPOSAL_CONTENT_NOT_PUBLIC"
            )
            status = _require_public_text(
                value.get("status"), "PROPOSAL_CONTENT_NOT_PUBLIC"
            )
            target_view = {
                key: _require_public_text(
                    target.get(key), "PROPOSAL_CONTENT_NOT_PUBLIC"
                )
                for key in ("kind", "id", "version")
            }
            views[ref.locator] = {
                "proposalId": proposal_id,
                "status": status,
                "target": {
                    "kind": target_view["kind"],
                    "id": target_view["id"],
                    "version": target_view["version"],
                },
                "summary": summary,
                "scopes": scopes,
                "ref": _public_ref(ref),
            }
        return views

    @staticmethod
    def _candidate_view(snapshot, descriptor):
        return {
            "candidateId": snapshot.candidate_id,
            "descriptorDigest": snapshot.descriptor_digest,
            "lockDigest": snapshot.lock_digest,
            "artifactDigest": snapshot.artifact_digest,
            "runtime": {
                "family": descriptor.runtime.family,
                "version": descriptor.runtime.version,
                "language": descriptor.language,
            },
            "evidenceLevel": snapshot.evidence_level,
        }

    @staticmethod
    def _result_view(result):
        if result is None:
            return None
        return {
            "rounds": result.rounds,
            "stopReason": result.stop_reason.value,
            "usage": result.final_usage.to_wire(),
            "holdoutConsumed": result.holdout_consumed,
            "finalReportDigest": result.final_report_digest,
        }

    @staticmethod
    def _round_view(record, findings, proposals):
        promotion = record.promotion
        return {
            "round": record.round_index,
            "status": record.status.value,
            "findingIds": list(record.finding_ids),
            "findings": [
                _finding_view(findings[item]) for item in record.finding_ids
            ],
            "target": (
                None
                if record.target is None
                else {
                    "kind": record.target.target_kind.value,
                    "ref": record.target.target_ref,
                    "action": record.target.action,
                    "reason": record.target.reason,
                    "ownerId": record.target.owner_id,
                }
            ),
            "approvalFact": record.approval_fact,
            "changeDigest": record.change_digest,
            "parentLockDigest": record.parent_snapshot.lock_digest,
            "candidateLockDigest": (
                None
                if record.candidate_snapshot is None
                else record.candidate_snapshot.lock_digest
            ),
            "bestLockDigest": record.best_lock_digest,
            "metrics": (
                []
                if promotion is None
                else [
                    {
                        "name": item.name,
                        "baseline": item.baseline,
                        "candidate": item.candidate,
                        "delta": item.improvement,
                    }
                    for item in promotion.deltas
                ]
            ),
            "promoted": None if promotion is None else promotion.promoted,
            "comparable": None if promotion is None else promotion.comparable,
            "usage": record.usage.to_wire(),
            "stopReason": (
                None if record.stop_reason is None else record.stop_reason.value
            ),
            "proposalRefs": [
                proposals[ref.locator]["ref"]
                for ref in record.validation_refs
                if ref.locator in proposals
            ],
        }

    def _storyline(
        self,
        binding,
        baseline,
        rounds,
        result,
        report_refs_by_digest,
        created_at,
    ):
        nodes = [
            self._node(
                "build-input",
                "构建输入已冻结",
                "completed",
                "Solution、资产版本和验收目标已经锁定。",
                (),
                (binding.request_ref,),
                created_at,
            ),
            self._node(
                "candidate",
                "Candidate 已准备",
                "completed",
                "候选 Agent 的描述、制品和 Lock 身份已经核验。",
                (binding.request_ref,),
                (binding.candidate_descriptor_ref, binding.solution_lock_ref),
                created_at,
            ),
            self._node(
                "baseline-evaluation",
                "执行基线评测",
                baseline.conclusion.value,
                (
                    f"已执行 {baseline.progress.completed} / "
                    f"{baseline.progress.total} 条样本。"
                ),
                (binding.candidate_descriptor_ref,),
                (binding.baseline_evaluation_ref,),
            ),
        ]
        prior_evaluation_ref = binding.baseline_evaluation_ref
        for index, record in enumerate(rounds):
            round_ref = binding.round_refs[index]
            nodes.append(
                self._node(
                    f"round-{record.round_index}-diagnosis",
                    f"第 {record.round_index} 轮：定位问题",
                    "completed",
                    f"归集 {len(record.finding_ids)} 个 Finding 并确定责任边界。",
                    (prior_evaluation_ref,),
                    (round_ref,),
                )
            )
            if record.target is None:
                nodes.append(
                    self._node(
                        f"round-{record.round_index}-correction",
                        f"第 {record.round_index} 轮：等待处置",
                        record.status.value,
                        "当前 Finding 没有可自动执行的修正对象。",
                        (round_ref,),
                        record.validation_refs,
                    )
                )
                continue
            nodes.append(
                self._node(
                    f"round-{record.round_index}-correction",
                    f"第 {record.round_index} 轮：受控修正",
                    record.status.value,
                    (
                        f"对 {record.target.target_kind.value} 执行 "
                        f"{record.target.action}。"
                    ),
                    (round_ref,),
                    record.validation_refs,
                )
            )
            if record.candidate_snapshot is not None:
                lock_outputs = (
                    () if record.new_lock_ref is None else (record.new_lock_ref,)
                )
                nodes.append(
                    self._node(
                        f"round-{record.round_index}-rebuild",
                        f"第 {record.round_index} 轮：重建并复测",
                        record.status.value,
                        "生成新的 Candidate Lock，并按同一快照和规则复测。",
                        (round_ref,),
                        lock_outputs,
                    )
                )
            if record.evaluation_report_digest is not None:
                prior_evaluation_ref = report_refs_by_digest[
                    record.evaluation_report_digest
                ]
        decision_inputs = tuple(binding.round_refs)
        if result is not None and result.final_report_digest is not None:
            decision_inputs = (
                *decision_inputs,
                report_refs_by_digest[result.final_report_digest],
            )
        nodes.append(
            self._node(
                "decision",
                "形成交付裁决" if result is not None else "等待下一条优化事实",
                "completed" if result is not None else "executing",
                (
                    "停止原因：" + result.stop_reason.value
                    if result is not None
                    else "Builder 正在等待本轮修正、评测或裁决记录。"
                ),
                decision_inputs,
                (() if binding.result_ref is None else (binding.result_ref,)),
            )
        )
        return nodes

    @staticmethod
    def _node(
        identity,
        title,
        status,
        summary,
        inputs,
        outputs,
        occurred_at_epoch_seconds=None,
    ):
        return {
            "id": identity,
            "title": title,
            "status": status,
            "summary": summary,
            "occurredAtEpochSeconds": occurred_at_epoch_seconds,
            "inputRefs": [_public_ref(ref) for ref in inputs],
            "outputRefs": [_public_ref(ref) for ref in outputs],
        }

    def _empty(self, status, build_status, created_at, selection, issue):
        unverifiable = status == "unverifiable"
        return {
            "schemaVersion": VIEW_SCHEMA,
            "buildId": self.build_id,
            "status": status,
            "verifiability": {
                "status": status,
                "issues": [] if issue is None else [issue],
            },
            "build": self._build_view(build_status, created_at, selection),
            "storyline": [],
            "rounds": [],
            "evaluation": None,
            "result": None,
            "proposals": [],
            "nextAction": {
                "kind": "inspect-evidence" if unverifiable else "run-evaluation",
                "label": (
                    "检查记录引用"
                    if unverifiable
                    else "先运行 Candidate Evaluation"
                ),
                "reason": (
                    "记录缺失、摘要不一致或身份关系无法核验。"
                    if unverifiable
                    else "当前 Build 尚未绑定评测与优化记录。"
                ),
            },
        }

    @staticmethod
    def _build_view(status, created_at, selection):
        return {
            "status": status,
            "createdAtEpochSeconds": created_at,
            "capabilityIds": list((selection or {}).get("capabilityIds", [])),
        }

    @staticmethod
    def _next_action(status: str, stop_reason: str | None) -> dict[str, str]:
        if status != "completed":
            return {
                "kind": "refresh",
                "label": "等待本轮记录更新",
                "reason": "优化任务尚未写入终态结果。",
            }
        mapping = {
            "acceptance-met": (
                "review-delivery",
                "核对证据并准备交付",
                "Candidate 已达到本轮验收规则。",
            ),
            "approval-required": (
                "review-approval",
                "处理待批准修订",
                "继续执行需要 Solution 负责人作出决定。",
            ),
            "external-blocked": (
                "resolve-external",
                "跟进外部资产提案",
                "修正对象属于 Finance Harness 或外部环境。",
            ),
            "final-gate-not-met": (
                "review-holdout",
                "复核最终门禁结果",
                "Holdout 已执行，但未达到交付门禁。",
            ),
        }
        kind, label, reason = mapping.get(
            stop_reason,
            (
                "review-stop",
                "复核停止原因",
                "优化已停止，请依据结构化证据决定后续动作。",
            ),
        )
        return {"kind": kind, "label": label, "reason": reason}
