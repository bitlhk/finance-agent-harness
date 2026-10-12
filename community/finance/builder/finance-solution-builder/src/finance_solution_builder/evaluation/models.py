"""Versioned Sandbox Evaluation contracts owned by Finance Solution Builder."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime
from enum import Enum
from typing import Any, Literal

from pydantic import ConfigDict, Field, field_validator, model_validator

from finance_builder_base.contracts import ContractModel, ErrorDetail
from finance_builder_base.evaluation import (
    EvaluationConclusion,
    EvaluationProgress,
    ExpectedKind,
    MetricValue,
    RuleGroup,
)
from finance_solution_builder.candidate_backend import CandidateResourceRef


SCHEMA_VERSION = "1.0"
_IDENTIFIER = r"^[A-Za-z0-9][A-Za-z0-9_.:-]*$"
_SHA256 = r"^[0-9a-f]{64}$"
_SENSITIVE_KEY = re.compile(
    r"(?i)^(?:authorization|api[_-]?key|access[_-]?token|password|"
    r"client[_-]?secret|private[_-]?key|token|secret)$"
)


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


def canonical_digest(value: Any) -> str:
    """Return a stable digest for JSON-compatible contract content."""

    payload = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        default=str,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _reject_credential_fields(value: Any) -> Any:
    if isinstance(value, dict):
        for key, item in value.items():
            if _SENSITIVE_KEY.fullmatch(str(key)):
                raise ValueError("evaluation payload may not contain credential fields")
            _reject_credential_fields(item)
    elif isinstance(value, (list, tuple)):
        for item in value:
            _reject_credential_fields(item)
    return value


class EvaluationWireModel(ContractModel):
    """Strict model with stable camelCase wire names."""

    model_config = ConfigDict(
        extra="forbid",
        validate_assignment=True,
        populate_by_name=True,
        alias_generator=_to_camel,
    )

    def to_wire(self, *, exclude_none: bool = True) -> dict[str, Any]:
        return self.model_dump(mode="json", by_alias=True, exclude_none=exclude_none)

    def sha256(self) -> str:
        return canonical_digest(self.to_wire())


class DatasetPurpose(str, Enum):
    OPTIMIZATION = "optimization"
    REGRESSION = "regression"
    HOLDOUT = "holdout"


class EvaluationMode(str, Enum):
    TUNING = "tuning"
    FINAL_GATE = "final-gate"


class EvidenceLevel(str, Enum):
    CONTRACT_SCHEMA = "contract-schema"
    CONFORMANCE = "conformance"
    REPLAY = "replay"
    SANDBOX = "sandbox"
    LIVE_SMOKE = "live-smoke"
    PRODUCTION_ONLINE = "production-online"


class EvidenceStatus(str, Enum):
    NOT_EXECUTED = "not-executed"
    PASSED = "passed"
    FAILED = "failed"
    INDETERMINATE = "indeterminate"


class MetricKind(str, Enum):
    ACCURACY = "accuracy"
    SUCCESS_RATE = "success-rate"
    LATENCY_P95_MS = "latency-p95-ms"


class FindingResponsibility(str, Enum):
    CANDIDATE = "candidate"
    SOLUTION_ASSETS = "solution-assets"
    PROVIDER_HARNESS_ENVIRONMENT = "provider-harness-environment"
    DATA = "data"
    ACCEPTANCE_RULE = "acceptance-rule"


class EvaluationSample(EvaluationWireModel):
    sample_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    payload: dict[str, Any]
    expected: Any
    expected_kind: ExpectedKind = ExpectedKind.CLOSED
    expected_path: str = ""

    _safe_payload = field_validator("payload")(_reject_credential_fields)
    _safe_expected = field_validator("expected")(_reject_credential_fields)

    @field_validator("expected_path")
    @classmethod
    def validate_expected_path(cls, value: str) -> str:
        if value and not value.startswith("/"):
            raise ValueError("expected_path must be an empty string or JSON Pointer")
        return value

    def content_digest(self) -> str:
        return canonical_digest(
            {
                "payload": self.payload,
                "expected": self.expected,
                "expectedKind": self.expected_kind.value,
                "expectedPath": self.expected_path,
            }
        )


class DatasetPartition(EvaluationWireModel):
    dataset_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    version: str = Field(min_length=1, max_length=100)
    purpose: DatasetPurpose
    source: CandidateResourceRef
    authorized: bool
    authorization_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    declared_sample_count: int = Field(ge=0)
    samples: tuple[EvaluationSample, ...] = ()

    @model_validator(mode="after")
    def validate_partition(self) -> "DatasetPartition":
        if not self.authorized:
            raise ValueError("dataset must be explicitly authorized")
        if self.source.sha256 is None:
            raise ValueError("dataset source requires a sha256 digest")
        if self.samples and len(self.samples) != self.declared_sample_count:
            raise ValueError("declared_sample_count must match loaded samples")
        if not self.samples and self.purpose is not DatasetPurpose.HOLDOUT:
            raise ValueError("non-holdout dataset must load its declared samples")
        sample_ids = [sample.sample_id for sample in self.samples]
        if len(sample_ids) != len(set(sample_ids)):
            raise ValueError("sample_id must be unique within one dataset")
        return self


class MetricSpec(EvaluationWireModel):
    name: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    kind: MetricKind


class EvaluationPack(EvaluationWireModel):
    pack_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    version: str = Field(min_length=1, max_length=100)
    partitions: tuple[DatasetPartition, ...]
    metrics: tuple[MetricSpec, ...]
    acceptance: RuleGroup

    @model_validator(mode="after")
    def validate_pack(self) -> "EvaluationPack":
        if not self.partitions:
            raise ValueError("evaluation pack requires at least one dataset partition")
        if not self.metrics:
            raise ValueError("evaluation pack requires at least one metric")
        dataset_ids = [partition.dataset_id for partition in self.partitions]
        if len(dataset_ids) != len(set(dataset_ids)):
            raise ValueError("dataset_id must be unique")
        metric_names = [metric.name for metric in self.metrics]
        if len(metric_names) != len(set(metric_names)):
            raise ValueError("metric name must be unique")
        referenced_metrics = _rule_metric_names(self.acceptance)
        unknown = referenced_metrics - set(metric_names)
        if unknown:
            raise ValueError("acceptance rule references an unknown metric")
        return self


def _rule_metric_names(group: RuleGroup) -> set[str]:
    names = {rule.metric for rule in group.rules}
    for child in group.groups:
        names.update(_rule_metric_names(child))
    return names


class SandboxEvaluationRequest(EvaluationWireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    request_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    mode: EvaluationMode
    actor_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    purpose: str = Field(min_length=1, max_length=500)
    evidence_level: EvidenceLevel = EvidenceLevel.SANDBOX
    pack: EvaluationPack

    @model_validator(mode="after")
    def require_sandbox_level(self) -> "SandboxEvaluationRequest":
        if self.evidence_level is not EvidenceLevel.SANDBOX:
            raise ValueError("Sandbox Evaluation request requires sandbox evidence level")
        return self


class DatasetSnapshot(EvaluationWireModel):
    dataset_id: str
    version: str
    purpose: DatasetPurpose
    source_digest: str = Field(pattern=_SHA256)
    authorization_id: str
    declared_sample_count: int = Field(ge=0)
    loaded_sample_count: int = Field(ge=0)
    content_digest: str = Field(pattern=_SHA256)


class HoldoutAccessFact(EvaluationWireModel):
    actor_id: str
    purpose: str
    pack_digest: str = Field(pattern=_SHA256)
    accessed_at: datetime


class EvaluationSnapshot(EvaluationWireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    snapshot_id: str = Field(pattern=_SHA256)
    request_id: str
    mode: EvaluationMode
    candidate_id: str
    descriptor_digest: str = Field(pattern=_SHA256)
    lock_digest: str = Field(pattern=_SHA256)
    artifact_digest: str = Field(pattern=_SHA256)
    pack_id: str
    pack_version: str
    pack_digest: str = Field(pattern=_SHA256)
    rules_digest: str = Field(pattern=_SHA256)
    partitions: tuple[DatasetSnapshot, ...]
    sample_order: tuple[str, ...]
    holdout_access: HoldoutAccessFact | None = None


class SampleExecutionStatus(str, Enum):
    PASSED = "passed"
    FAILED = "failed"
    NOT_EXECUTED = "not-executed"


class EvaluationSampleEvidence(EvaluationWireModel):
    sample_id: str
    dataset_id: str
    status: SampleExecutionStatus
    matched: bool | None = None
    duration_ms: int | None = Field(default=None, ge=0)
    input_digest: str = Field(pattern=_SHA256)
    expected_digest: str = Field(pattern=_SHA256)
    actual_digest: str | None = Field(default=None, pattern=_SHA256)
    response_ref: CandidateResourceRef | None = None
    error_stage: str | None = None
    error: ErrorDetail | None = None


class EvaluationFinding(EvaluationWireModel):
    finding_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    responsibility: FindingResponsibility
    sample_ids: tuple[str, ...]
    summary: str = Field(min_length=1, max_length=1000)
    evidence_refs: tuple[CandidateResourceRef, ...] = ()


class EvidenceLayerRecord(EvaluationWireModel):
    level: EvidenceLevel
    executed: bool
    status: EvidenceStatus
    evidence_refs: tuple[CandidateResourceRef, ...] = ()

    @model_validator(mode="after")
    def validate_execution_status(self) -> "EvidenceLayerRecord":
        if self.executed == (self.status is EvidenceStatus.NOT_EXECUTED):
            raise ValueError("executed and status are inconsistent")
        return self


class SandboxEvaluationReport(EvaluationWireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    snapshot: EvaluationSnapshot
    conclusion: EvaluationConclusion
    progress: EvaluationProgress
    metrics: tuple[MetricValue, ...]
    samples: tuple[EvaluationSampleEvidence, ...]
    findings: tuple[EvaluationFinding, ...]
    evidence_levels: tuple[EvidenceLayerRecord, ...]
    variance_digests: tuple[str, ...] = ()

    @model_validator(mode="after")
    def validate_report(self) -> "SandboxEvaluationReport":
        levels = [item.level for item in self.evidence_levels]
        if len(levels) != len(set(levels)):
            raise ValueError("evidence level must be unique")
        if EvidenceLevel.SANDBOX not in levels:
            raise ValueError("evaluation report requires a sandbox evidence record")
        sample_ids = [item.sample_id for item in self.samples]
        if len(sample_ids) != len(set(sample_ids)):
            raise ValueError("evaluation sample evidence must be unique")
        return self
