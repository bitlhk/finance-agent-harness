"""Public-safe contracts for the bounded Candidate optimization loop."""

from __future__ import annotations

import hashlib
import json
import re
from enum import Enum
from pathlib import PurePosixPath
from typing import Any, Literal
from urllib.parse import parse_qsl, urlsplit

from pydantic import ConfigDict, Field, field_validator, model_validator

from finance_builder_base import ContractModel, ResourceKind, ResourceRef
from finance_builder_base.contracts import sanitize_public_text
from finance_solution_builder.evaluation import EvaluationFinding

SCHEMA_VERSION = "1.0"
_IDENTIFIER = r"^[A-Za-z0-9][A-Za-z0-9_.:-]*$"
_SHA256 = r"^[0-9a-f]{64}$"
_SENSITIVE_KEY = re.compile(
    r"(?i)^(?:authorization|api[_-]?key|access[_-]?token|password|client[_-]?secret|"
    r"private[_-]?key|token|secret|prompt|system[_-]?prompt|chain[_-]?of[_-]?thought|reasoning)$"
)


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


def _public_payload(value: Any) -> Any:
    if isinstance(value, ContractModel):
        _public_payload(value.model_dump(mode="json", by_alias=True, exclude_none=True))
    elif isinstance(value, dict):
        for key, item in value.items():
            if _SENSITIVE_KEY.fullmatch(str(key)):
                raise ValueError("optimization records may not contain private fields")
            _public_payload(item)
    elif isinstance(value, (list, tuple)):
        for item in value:
            _public_payload(item)
    elif isinstance(value, str):
        parsed = urlsplit(value)
        if parsed.username is not None or parsed.password is not None or any(
            _SENSITIVE_KEY.fullmatch(key)
            for key, _ in parse_qsl(parsed.query, keep_blank_values=True)
        ):
            raise ValueError("optimization records may not contain credential URLs")
        if sanitize_public_text(value) != value:
            raise ValueError("optimization records may not contain sensitive text or host paths")
    return value


def _public_text(value: str) -> str:
    if sanitize_public_text(value) != value:
        raise ValueError("public text may not contain sensitive text or host paths")
    return value


def _relative_path(value: str) -> str:
    normalized = value.replace("\\", "/")
    path = PurePosixPath(normalized)
    if (
        not normalized
        or normalized.startswith("/")
        or path.is_absolute()
        or ".." in path.parts
        or ":" in path.parts[0]
    ):
        raise ValueError("path must be workspace-relative")
    return path.as_posix().rstrip("/")


class OptimizationWireModel(ContractModel):
    """Strict, deterministic and public-safe optimization wire model."""

    model_config = ConfigDict(
        extra="forbid",
        validate_assignment=True,
        populate_by_name=True,
        alias_generator=_to_camel,
    )

    @model_validator(mode="before")
    @classmethod
    def reject_private_input(cls, value: Any) -> Any:
        return _public_payload(value)

    def to_wire(self, *, exclude_none: bool = True) -> dict[str, Any]:
        return self.model_dump(mode="json", by_alias=True, exclude_none=exclude_none)

    def canonical_bytes(self) -> bytes:
        return json.dumps(
            self.to_wire(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")

    def sha256(self) -> str:
        return hashlib.sha256(self.canonical_bytes()).hexdigest()


class CorrectionTargetKind(str, Enum):
    BUILDER_CONFIG = "builder-config"
    CANDIDATE_OVERLAY = "candidate-overlay"
    OPTIMIZATION_DATA = "optimization-data"
    HARNESS_ASSET = "harness-asset"
    EXTERNAL_ENVIRONMENT = "external-environment"


class ActionDisposition(str, Enum):
    AUTO = "auto"
    APPROVAL_REQUIRED = "approval-required"
    EXTERNAL_BLOCKED = "external-blocked"


class OptimizationStopReason(str, Enum):
    ACCEPTANCE_MET = "acceptance-met"
    MAX_ROUNDS = "max-rounds"
    MAX_DURATION = "max-duration"
    MAX_COST = "max-cost"
    NO_IMPROVEMENT = "no-improvement"
    APPROVAL_REQUIRED = "approval-required"
    EXTERNAL_BLOCKED = "external-blocked"
    NO_EXECUTABLE_TARGET = "no-executable-target"
    FINAL_GATE_NOT_MET = "final-gate-not-met"
    FAILED = "failed"


class OptimizationRoundStatus(str, Enum):
    SUCCEEDED = "succeeded"
    REJECTED = "rejected"
    BLOCKED = "blocked"
    FAILED = "failed"


class MetricDirection(str, Enum):
    HIGHER = "higher"
    LOWER = "lower"


class OptimizationBudget(OptimizationWireModel):
    max_rounds: int = Field(gt=0)
    max_duration_ms: int = Field(gt=0)
    max_cost_microunits: int = Field(ge=0)
    max_no_improvement: int = Field(gt=0)


class OptimizationUsage(OptimizationWireModel):
    rounds: int = Field(ge=0)
    duration_ms: int = Field(ge=0)
    cost_microunits: int = Field(ge=0)
    consecutive_no_improvement: int = Field(ge=0)


class OptimizationSnapshot(OptimizationWireModel):
    candidate_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    descriptor_digest: str = Field(pattern=_SHA256)
    lock_digest: str = Field(pattern=_SHA256)
    artifact_digest: str = Field(pattern=_SHA256)
    evaluation_pack_digest: str = Field(pattern=_SHA256)
    dataset_digest: str = Field(pattern=_SHA256)
    rules_digest: str = Field(pattern=_SHA256)
    runtime_digest: str = Field(pattern=_SHA256)
    evidence_level: str = Field(min_length=1, max_length=100)


class MetricObjective(OptimizationWireModel):
    name: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    direction: MetricDirection
    required: bool = True


class GovernedTarget(OptimizationWireModel):
    responsibility: str = Field(min_length=1, max_length=100)
    target_kind: CorrectionTargetKind
    target_ref: str = Field(min_length=1, max_length=500)
    owner_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    allowed_actions: tuple[str, ...] = Field(min_length=1)
    approval_policy: ActionDisposition
    allowed_write_paths: tuple[str, ...] = ()
    validation_refs: tuple[ResourceRef, ...] = Field(min_length=1)
    priority: int = Field(ge=0)

    _safe_ref = field_validator("target_ref")(_public_text)

    @field_validator("allowed_actions")
    @classmethod
    def validate_actions(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if len(value) != len(set(value)) or any(not item.strip() for item in value):
            raise ValueError("allowed_actions must be non-empty and unique")
        return tuple(_public_text(item) for item in value)

    @field_validator("allowed_write_paths")
    @classmethod
    def validate_write_paths(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        normalized = tuple(_relative_path(item) for item in value)
        if len(normalized) != len(set(normalized)):
            raise ValueError("allowed_write_paths must be unique")
        return normalized

    @model_validator(mode="after")
    def validate_policy(self) -> "GovernedTarget":
        internal = self.target_kind in {
            CorrectionTargetKind.BUILDER_CONFIG,
            CorrectionTargetKind.CANDIDATE_OVERLAY,
            CorrectionTargetKind.OPTIMIZATION_DATA,
        }
        if self.approval_policy is ActionDisposition.AUTO and not internal:
            raise ValueError("external targets cannot be modified automatically")
        if self.target_kind is not CorrectionTargetKind.OPTIMIZATION_DATA:
            if internal and not self.allowed_write_paths:
                raise ValueError("writable targets require allowed_write_paths")
        return self


class CorrectionTarget(OptimizationWireModel):
    finding_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    target_kind: CorrectionTargetKind
    target_ref: str = Field(min_length=1, max_length=500)
    action: str = Field(min_length=1, max_length=200)
    disposition: ActionDisposition
    reason: str = Field(min_length=1, max_length=1000)
    owner_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    allowed_write_paths: tuple[str, ...] = ()
    validation_refs: tuple[ResourceRef, ...]

    _safe_text = field_validator("target_ref", "action", "reason")(_public_text)

    @model_validator(mode="after")
    def validate_executable_target(self) -> "CorrectionTarget":
        if self.disposition is not ActionDisposition.AUTO:
            raise ValueError("a correction target must be authorized for automatic execution")
        if self.target_kind in {
            CorrectionTargetKind.HARNESS_ASSET,
            CorrectionTargetKind.EXTERNAL_ENVIRONMENT,
        }:
            raise ValueError("external targets cannot become executable corrections")
        if (
            self.target_kind is not CorrectionTargetKind.OPTIMIZATION_DATA
            and not self.allowed_write_paths
        ):
            raise ValueError("a writable correction requires allowed paths")
        return self


class RoutingDecision(OptimizationWireModel):
    target: CorrectionTarget | None = None
    stop_reason: OptimizationStopReason | None = None
    considered_finding_ids: tuple[str, ...] = ()
    detail: str = Field(min_length=1, max_length=1000)

    _safe_detail = field_validator("detail")(_public_text)

    @model_validator(mode="after")
    def require_one_outcome(self) -> "RoutingDecision":
        if (self.target is None) == (self.stop_reason is None):
            raise ValueError("routing requires exactly one target or stop reason")
        return self


class OverlayFile(OptimizationWireModel):
    path: str
    digest: str = Field(alias="sha256", pattern=_SHA256)

    _safe_path = field_validator("path")(_relative_path)


class CandidateOverlay(OptimizationWireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    overlay_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    round_index: int = Field(gt=0)
    parent_candidate_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    parent_lock_digest: str = Field(pattern=_SHA256)
    asset_closure_digest: str = Field(pattern=_SHA256)
    root_ref: ResourceRef
    files: tuple[OverlayFile, ...] = ()
    data_digests: tuple[str, ...] = ()

    @model_validator(mode="after")
    def validate_overlay(self) -> "CandidateOverlay":
        if self.root_ref.kind is not ResourceKind.WORKSPACE_PATH:
            raise ValueError("overlay root must be a workspace path")
        paths = tuple(item.path for item in self.files)
        if len(paths) != len(set(paths)):
            raise ValueError("overlay paths must be unique")
        if not self.files and not self.data_digests:
            raise ValueError("optimization revision requires files or data digests")
        if any(not re.fullmatch(_SHA256, item) for item in self.data_digests):
            raise ValueError("data_digests must contain sha256 values")
        return self


class OptimizationDataVersion(OptimizationWireModel):
    version_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    purpose: Literal["optimization"] = "optimization"
    source_digest: str = Field(pattern=_SHA256)
    output_digest: str = Field(pattern=_SHA256)
    resource_refs: tuple[ResourceRef, ...]
    item_count: int = Field(ge=0)


class CorrectionExecution(OptimizationWireModel):
    change_digest: str = Field(pattern=_SHA256)
    evidence_refs: tuple[ResourceRef, ...]
    duration_ms: int = Field(ge=0)
    cost_microunits: int = Field(ge=0)
    overlay: CandidateOverlay | None = None
    data_version: OptimizationDataVersion | None = None

    @model_validator(mode="after")
    def require_one_revision(self) -> "CorrectionExecution":
        if (self.overlay is None) == (self.data_version is None):
            raise ValueError("correction execution requires exactly one revision")
        revision = self.overlay if self.overlay is not None else self.data_version
        if revision is None or self.change_digest != revision.sha256():
            raise ValueError("change_digest must identify the correction revision")
        return self


class RebuildOutcome(OptimizationWireModel):
    snapshot: OptimizationSnapshot
    lock_ref: ResourceRef
    report_ref: ResourceRef

    @model_validator(mode="after")
    def validate_rebuild_evidence(self) -> "RebuildOutcome":
        if self.lock_ref.sha256 != self.snapshot.lock_digest:
            raise ValueError("lock reference must identify the rebuilt snapshot")
        if self.report_ref.sha256 is None:
            raise ValueError("build report reference requires a sha256 digest")
        return self


class MetricDelta(OptimizationWireModel):
    name: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    baseline: float
    candidate: float
    improvement: float


class PromotionDecision(OptimizationWireModel):
    comparable: bool
    promoted: bool
    reason: str = Field(min_length=1, max_length=1000)
    deltas: tuple[MetricDelta, ...] = ()
    holdout_consumed: bool = False

    _safe_reason = field_validator("reason")(_public_text)

    @model_validator(mode="after")
    def validate_promotion(self) -> "PromotionDecision":
        if self.promoted and not self.comparable:
            raise ValueError("an incomparable candidate cannot be promoted")
        return self


class OptimizationRequest(OptimizationWireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    request_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    baseline: OptimizationSnapshot
    findings: tuple[EvaluationFinding, ...]
    governance: tuple[GovernedTarget, ...]
    objectives: tuple[MetricObjective, ...] = Field(min_length=1)
    budget: OptimizationBudget
    final_gate: bool = False
    holdout_authorized: bool = False
    holdout_already_consumed: bool = False

    @model_validator(mode="after")
    def validate_request(self) -> "OptimizationRequest":
        finding_ids = tuple(item.finding_id for item in self.findings)
        if len(finding_ids) != len(set(finding_ids)):
            raise ValueError("finding ids must be unique")
        objective_names = tuple(item.name for item in self.objectives)
        if len(objective_names) != len(set(objective_names)):
            raise ValueError("metric objectives must be unique")
        if (self.holdout_authorized or self.holdout_already_consumed) and not self.final_gate:
            raise ValueError("holdout authorization only applies to the final gate")
        return self


class OptimizationRoundRecord(OptimizationWireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    request_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    round_index: int = Field(gt=0)
    parent_snapshot: OptimizationSnapshot
    candidate_snapshot: OptimizationSnapshot | None = None
    new_lock_ref: ResourceRef | None = None
    build_report_ref: ResourceRef | None = None
    finding_ids: tuple[str, ...]
    target: CorrectionTarget | None = None
    status: OptimizationRoundStatus
    approval_fact: str
    change_digest: str | None = Field(default=None, pattern=_SHA256)
    validation_refs: tuple[ResourceRef, ...] = ()
    evaluation_report_digest: str | None = Field(default=None, pattern=_SHA256)
    promotion: PromotionDecision | None = None
    usage: OptimizationUsage
    best_candidate_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    best_lock_digest: str = Field(pattern=_SHA256)
    stop_reason: OptimizationStopReason | None = None

    _safe_approval = field_validator("approval_fact")(_public_text)

    @model_validator(mode="after")
    def validate_round_facts(self) -> "OptimizationRoundRecord":
        if self.usage.rounds != self.round_index:
            raise ValueError("round usage must match round_index")
        completed = self.status in {
            OptimizationRoundStatus.SUCCEEDED,
            OptimizationRoundStatus.REJECTED,
        }
        required_completed = (
            self.candidate_snapshot,
            self.new_lock_ref,
            self.build_report_ref,
            self.target,
            self.change_digest,
            self.evaluation_report_digest,
            self.promotion,
        )
        if completed and any(value is None for value in required_completed):
            raise ValueError("a completed round requires rebuild and evaluation facts")
        if completed and self.stop_reason is not None:
            raise ValueError("a completed round cannot carry a stop reason")
        if not completed and self.stop_reason is None:
            raise ValueError("a blocked or failed round requires a stop reason")
        allowed_best_locks = {self.parent_snapshot.lock_digest}
        if self.candidate_snapshot is not None:
            allowed_best_locks.add(self.candidate_snapshot.lock_digest)
        if self.best_lock_digest not in allowed_best_locks:
            raise ValueError("best_lock_digest must identify a recorded snapshot")
        return self


class OptimizationResult(OptimizationWireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    request_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    best_snapshot: OptimizationSnapshot
    rounds: int = Field(ge=0)
    stop_reason: OptimizationStopReason
    final_usage: OptimizationUsage
    record_refs: tuple[ResourceRef, ...]
    holdout_consumed: bool = False
    final_report_digest: str | None = Field(default=None, pattern=_SHA256)

    @model_validator(mode="after")
    def validate_result_facts(self) -> "OptimizationResult":
        if self.rounds != self.final_usage.rounds or self.rounds != len(self.record_refs):
            raise ValueError("terminal round facts are inconsistent")
        if self.stop_reason is OptimizationStopReason.FINAL_GATE_NOT_MET:
            if not self.holdout_consumed or self.final_report_digest is None:
                raise ValueError("a failed final gate requires its report digest")
        if (
            self.stop_reason is OptimizationStopReason.ACCEPTANCE_MET
            and self.holdout_consumed
            and self.final_report_digest is None
        ):
            raise ValueError("a consumed passing Holdout requires its report digest")
        return self


WIRE_MODELS = (
    OptimizationRequest,
    CorrectionTarget,
    RoutingDecision,
    CandidateOverlay,
    OptimizationDataVersion,
    CorrectionExecution,
    RebuildOutcome,
    PromotionDecision,
    OptimizationRoundRecord,
    OptimizationResult,
)


def optimization_schema() -> dict[str, Any]:
    """Return one stable JSON Schema bundle for the module-local v1 contract."""

    definitions: dict[str, Any] = {}
    roots: list[dict[str, str]] = []
    for model in WIRE_MODELS:
        schema = model.model_json_schema(by_alias=True, ref_template="#/$defs/{model}")
        nested = schema.pop("$defs", {})
        for name, definition in nested.items():
            previous = definitions.setdefault(name, definition)
            if previous != definition:
                raise ValueError(f"schema definition conflict: {name}")
        definitions[model.__name__] = schema
        roots.append({"$ref": f"#/$defs/{model.__name__}"})
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://openjiuwen.dev/finance/solution-optimization.v1.schema.json",
        "title": "Finance Solution Builder Optimization v1",
        "anyOf": roots,
        "$defs": dict(sorted(definitions.items())),
    }
