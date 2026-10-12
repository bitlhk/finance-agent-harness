"""Portable Candidate Backend contract models.

Backend-specific facts belong in a versioned extension. Orchestration and
evaluation consume only the public models in this module.
"""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Literal
from urllib.parse import parse_qsl, urlsplit

from pydantic import ConfigDict, Field, field_validator, model_validator

from finance_builder_base.contracts import (
    ContractModel,
    ErrorDetail,
    ResourceKind,
    ResourceRef,
    sanitize_public_text,
)
from finance_builder_base.solution_build import CheckStatus


SCHEMA_VERSION = "1.0"
_IDENTIFIER = r"^[A-Za-z0-9][A-Za-z0-9_.:-]*$"
_NAMESPACED = r"^[a-z][a-z0-9.-]*$"
_SHA256 = r"^[0-9a-f]{64}$"
_SENSITIVE_KEY = re.compile(
    r"(?i)^(?:authorization|api[_-]?key|access[_-]?token|password|"
    r"client[_-]?secret|private[_-]?key|token|secret)$"
)


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


def _public_text(value: str) -> str:
    ErrorDetail(code="candidate.contract.public_text", message=value)
    return value


def _unique(values: tuple[str, ...], field_name: str) -> tuple[str, ...]:
    if len(values) != len(set(values)):
        raise ValueError(f"{field_name} must contain unique values")
    return values


def _validate_public_payload(value: Any) -> Any:
    if isinstance(value, dict):
        for key, item in value.items():
            if _SENSITIVE_KEY.fullmatch(str(key)):
                raise ValueError("extension payload may not contain credential fields")
            _validate_public_payload(item)
    elif isinstance(value, (list, tuple)):
        for item in value:
            _validate_public_payload(item)
    elif isinstance(value, str) and sanitize_public_text(value) != value:
        raise ValueError("extension payload may not contain sensitive text")
    return value


def _contains_url_credentials(value: str) -> bool:
    parsed = urlsplit(value)
    contains_userinfo = parsed.username is not None or parsed.password is not None
    contains_sensitive_query = any(
        _SENSITIVE_KEY.fullmatch(key) for key, _ in parse_qsl(parsed.query, keep_blank_values=True)
    )
    return contains_userinfo or contains_sensitive_query


class WireModel(ContractModel):
    """Strict model with a stable camelCase JSON representation."""

    model_config = ConfigDict(
        # Consumers must tolerate additive optional v1 fields. Required fields,
        # enums and all known values remain strictly validated.
        extra="ignore",
        validate_assignment=True,
        populate_by_name=True,
        alias_generator=_to_camel,
    )

    def to_wire(self, *, exclude_none: bool = True) -> dict[str, Any]:
        return self.model_dump(mode="json", by_alias=True, exclude_none=exclude_none)

    def canonical_bytes(self) -> bytes:
        return json.dumps(
            self.to_wire(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")

    def sha256(self) -> str:
        return hashlib.sha256(self.canonical_bytes()).hexdigest()


class CandidateResourceRef(ResourceRef):
    """ResourceRef using the Candidate wire naming convention."""

    model_config = WireModel.model_config

    @model_validator(mode="after")
    def validate_public_locator(self) -> "CandidateResourceRef":
        if self.kind is ResourceKind.HTTP:
            contains_assignment = sanitize_public_text(self.locator) != self.locator
            if _contains_url_credentials(self.locator) or contains_assignment:
                raise ValueError("http resource may not contain credential material")
        return self


class LifecycleOperation(str, Enum):
    PREFLIGHT = "preflight"
    ASSEMBLE = "assemble"
    BUILD = "build"
    START = "start"
    HEALTH = "health"
    INVOKE = "invoke"
    COLLECT_EVIDENCE = "collect-evidence"
    STOP = "stop"


class LifecycleStatus(str, Enum):
    PASSED = "passed"
    FAILED = "failed"
    BLOCKED = "blocked"
    SKIPPED = "skipped"
    CANCELLED = "cancelled"


class RunState(str, Enum):
    STARTING = "starting"
    RUNNING = "running"
    STOPPED = "stopped"


class BackendIdentity(WireModel):
    backend_id: str = Field(min_length=1, max_length=200, pattern=_NAMESPACED)
    version: str = Field(min_length=1, max_length=100)

    _public_version = field_validator("version")(_public_text)


class RuntimeIdentity(WireModel):
    family: str = Field(min_length=1, max_length=100, pattern=_NAMESPACED)
    version: str = Field(min_length=1, max_length=100)

    _public_version = field_validator("version")(_public_text)


class TypedExtension(WireModel):
    extension_type: str = Field(min_length=1, max_length=200, pattern=_NAMESPACED)
    schema_version: str = Field(default=SCHEMA_VERSION, pattern=r"^\d+\.\d+$")
    payload: dict[str, Any] = Field(default_factory=dict)

    _safe_payload = field_validator("payload")(_validate_public_payload)


class CandidateArtifact(WireModel):
    artifact_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    artifact_kind: str = Field(min_length=1, max_length=100, pattern=_NAMESPACED)
    resource: CandidateResourceRef

    @model_validator(mode="after")
    def require_digest(self) -> "CandidateArtifact":
        if self.resource.sha256 is None:
            raise ValueError("candidate artifact requires sha256")
        return self


class ProtocolBinding(WireModel):
    protocol: str = Field(min_length=1, max_length=100, pattern=_NAMESPACED)
    path: str = Field(min_length=1, max_length=300)
    media_type: str | None = Field(default=None, max_length=200)

    @field_validator("path")
    @classmethod
    def validate_path(cls, value: str) -> str:
        if not value.startswith("/") or ".." in value.split("/"):
            raise ValueError("protocol path must be absolute and contain no parent segments")
        return value

    @field_validator("media_type")
    @classmethod
    def validate_media_type(cls, value: str | None) -> str | None:
        return None if value is None else _public_text(value)


class CandidateEntrypoint(WireModel):
    mode: Literal["managed-artifact", "external-service"]
    resource: CandidateResourceRef


class CapabilityProviderBinding(WireModel):
    capability_id: str = Field(min_length=1, max_length=200, pattern=_NAMESPACED)
    provider_id: str = Field(min_length=1, max_length=200, pattern=_NAMESPACED)
    operation_types: tuple[str, ...] = ()

    @field_validator("operation_types")
    @classmethod
    def validate_operations(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if any(re.fullmatch(_NAMESPACED, item) is None for item in value):
            raise ValueError("operation_types contain an invalid value")
        return _unique(value, "operation_types")


class CandidateDescriptor(WireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    candidate_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    backend: BackendIdentity
    language: str = Field(min_length=1, max_length=100, pattern=_NAMESPACED)
    runtime: RuntimeIdentity
    artifact: CandidateArtifact
    entrypoint: CandidateEntrypoint
    invocation: ProtocolBinding
    health: ProtocolBinding
    lock_digest: str = Field(pattern=_SHA256)
    installed_asset_ids: tuple[str, ...]
    installed_capability_ids: tuple[str, ...] = ()
    installed_tool_ids: tuple[str, ...] = ()
    provider_bindings: tuple[CapabilityProviderBinding, ...] = ()
    provenance_refs: tuple[CandidateResourceRef, ...] = ()
    extensions: tuple[TypedExtension, ...] = ()

    @field_validator("installed_asset_ids", "installed_capability_ids", "installed_tool_ids")
    @classmethod
    def validate_ids(cls, value: tuple[str, ...], info: Any) -> tuple[str, ...]:
        if any(re.fullmatch(_IDENTIFIER, item) is None for item in value):
            raise ValueError(f"{info.field_name} contains an invalid identifier")
        return _unique(value, info.field_name)

    @model_validator(mode="after")
    def validate_bindings(self) -> "CandidateDescriptor":
        if not self.installed_asset_ids:
            raise ValueError("installed_asset_ids must not be empty")
        capability_ids = [item.capability_id for item in self.provider_bindings]
        if len(capability_ids) != len(set(capability_ids)):
            raise ValueError("provider binding capability_id must be unique")
        if set(capability_ids) - set(self.installed_capability_ids):
            raise ValueError("provider binding requires an installed capability")
        if (
            self.entrypoint.mode == "managed-artifact"
            and self.entrypoint.resource != self.artifact.resource
        ):
            raise ValueError("managed entrypoint must reference the verified artifact")
        if (
            self.entrypoint.mode == "external-service"
            and self.entrypoint.resource.kind is not ResourceKind.HTTP
        ):
            raise ValueError("external service entrypoint requires an http resource")
        extension_ids = [(item.extension_type, item.schema_version) for item in self.extensions]
        if len(extension_ids) != len(set(extension_ids)):
            raise ValueError("extension type and version must be unique")
        return self


class BackendCapability(WireModel):
    backend: BackendIdentity
    languages: tuple[str, ...]
    runtime_families: tuple[str, ...]
    artifact_kinds: tuple[str, ...]
    invocation_protocols: tuple[str, ...]
    operations: tuple[LifecycleOperation, ...]

    @field_validator(
        "languages", "runtime_families", "artifact_kinds", "invocation_protocols"
    )
    @classmethod
    def validate_names(cls, value: tuple[str, ...], info: Any) -> tuple[str, ...]:
        if not value:
            raise ValueError(f"{info.field_name} must not be empty")
        if any(re.fullmatch(_NAMESPACED, item) is None for item in value):
            raise ValueError(f"{info.field_name} contains an invalid value")
        return _unique(value, info.field_name)

    @field_validator("operations")
    @classmethod
    def validate_lifecycle_operations(
        cls, value: tuple[LifecycleOperation, ...]
    ) -> tuple[LifecycleOperation, ...]:
        if not value:
            raise ValueError("operations must not be empty")
        if len(value) != len(set(value)):
            raise ValueError("operations must contain unique values")
        return value


class CandidateTarget(WireModel):
    language: str = Field(min_length=1, max_length=100, pattern=_NAMESPACED)
    runtime_family: str = Field(min_length=1, max_length=100, pattern=_NAMESPACED)
    runtime_version: str = Field(min_length=1, max_length=100)
    artifact_kind: str = Field(min_length=1, max_length=100, pattern=_NAMESPACED)
    invocation_protocol: str = Field(min_length=1, max_length=100, pattern=_NAMESPACED)
    required_operations: tuple[LifecycleOperation, ...]
    required_asset_ids: tuple[str, ...]
    required_capability_ids: tuple[str, ...] = ()
    required_provider_operation_types: tuple[str, ...] = ()
    profile_id: str = Field(min_length=1, max_length=200, pattern=_NAMESPACED)
    host_wiring_ids: tuple[str, ...] = ()

    _public_runtime_version = field_validator("runtime_version")(_public_text)

    @field_validator(
        "required_asset_ids",
        "required_capability_ids",
        "required_provider_operation_types",
        "host_wiring_ids",
    )
    @classmethod
    def validate_target_ids(cls, value: tuple[str, ...], info: Any) -> tuple[str, ...]:
        if any(re.fullmatch(_IDENTIFIER, item) is None for item in value):
            raise ValueError(f"{info.field_name} contains an invalid identifier")
        if info.field_name == "required_asset_ids" and not value:
            raise ValueError("required_asset_ids must not be empty")
        return _unique(value, info.field_name)

    @field_validator("required_operations")
    @classmethod
    def validate_required_operations(
        cls, value: tuple[LifecycleOperation, ...]
    ) -> tuple[LifecycleOperation, ...]:
        if not value:
            raise ValueError("required_operations must not be empty")
        if len(value) != len(set(value)):
            raise ValueError("required_operations must contain unique values")
        return value


class CandidateCheck(WireModel):
    check_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    status: CheckStatus
    summary: str = Field(min_length=1, max_length=1000)
    evidence_refs: tuple[CandidateResourceRef, ...] = ()

    _public_summary = field_validator("summary")(_public_text)


class LifecycleResult(WireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    operation: LifecycleOperation
    status: LifecycleStatus
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    candidate_id: str | None = Field(
        default=None, min_length=1, max_length=200, pattern=_IDENTIFIER
    )
    duration_ms: int = Field(ge=0)
    summary: str = Field(min_length=1, max_length=1000)
    evidence_refs: tuple[CandidateResourceRef, ...] = ()
    error: ErrorDetail | None = None

    _public_summary = field_validator("summary")(_public_text)

    @model_validator(mode="after")
    def validate_terminal_shape(self) -> "LifecycleResult":
        requires_error = self.status in {LifecycleStatus.FAILED, LifecycleStatus.BLOCKED}
        if requires_error != (self.error is not None):
            raise ValueError("failed or blocked lifecycle result requires exactly one error")
        return self


class PreflightRequest(WireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    target: CandidateTarget
    resolved_asset_ids: tuple[str, ...]

    @field_validator("resolved_asset_ids")
    @classmethod
    def validate_resolved_assets(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if any(re.fullmatch(_IDENTIFIER, item) is None for item in value):
            raise ValueError("resolved_asset_ids contains an invalid identifier")
        return _unique(value, "resolved_asset_ids")

    @model_validator(mode="after")
    def validate_asset_closure(self) -> "PreflightRequest":
        if set(self.target.required_asset_ids) - set(self.resolved_asset_ids):
            raise ValueError("resolved_asset_ids must cover every required asset")
        return self


class PreflightResult(WireModel):
    outcome: LifecycleResult
    backend: BackendIdentity
    checks: tuple[CandidateCheck, ...]

    @model_validator(mode="after")
    def validate_preflight(self) -> "PreflightResult":
        if self.outcome.operation is not LifecycleOperation.PREFLIGHT:
            raise ValueError("preflight outcome must use the preflight operation")
        if not self.checks:
            raise ValueError("preflight result requires at least one check")
        check_ids = [item.check_id for item in self.checks]
        if len(check_ids) != len(set(check_ids)):
            raise ValueError("preflight check_id must be unique")
        if self.outcome.status is LifecycleStatus.PASSED and any(
            item.status in {CheckStatus.FAILED, CheckStatus.BLOCKED} for item in self.checks
        ):
            raise ValueError("passed preflight cannot contain failed or blocked checks")
        return self


class AssemblyRequest(WireModel):
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    target: CandidateTarget
    lock_digest: str = Field(pattern=_SHA256)
    workspace: CandidateResourceRef
    asset_refs: tuple[CandidateResourceRef, ...]


class AssemblyResult(WireModel):
    outcome: LifecycleResult
    candidate_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    assembly_refs: tuple[CandidateResourceRef, ...]

    @model_validator(mode="after")
    def validate_assembly(self) -> "AssemblyResult":
        if self.outcome.operation is not LifecycleOperation.ASSEMBLE:
            raise ValueError("assembly outcome must use the assemble operation")
        if self.outcome.candidate_id not in {None, self.candidate_id}:
            raise ValueError("assembly outcome candidate identity does not match")
        return self


class BuildRequest(WireModel):
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    candidate_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    lock_digest: str = Field(pattern=_SHA256)
    assembly_refs: tuple[CandidateResourceRef, ...]


class BuildResult(WireModel):
    outcome: LifecycleResult
    descriptor: CandidateDescriptor | None = None

    @model_validator(mode="after")
    def validate_build(self) -> "BuildResult":
        if self.outcome.operation is not LifecycleOperation.BUILD:
            raise ValueError("build outcome must use the build operation")
        passed = self.outcome.status is LifecycleStatus.PASSED
        if passed != (self.descriptor is not None):
            raise ValueError("passed build requires exactly one descriptor")
        if self.descriptor is not None and self.outcome.candidate_id not in {
            None,
            self.descriptor.candidate_id,
        }:
            raise ValueError("build outcome candidate identity does not match")
        if self.descriptor is not None and self.outcome.task_id != self.descriptor.task_id:
            raise ValueError("build outcome task identity does not match")
        return self


class CandidateArtifactVerification(WireModel):
    """Public-safe proof that the delivery artifact matches one descriptor."""

    descriptor_digest: str = Field(pattern=_SHA256)
    artifact_digest: str = Field(pattern=_SHA256)


class StartRequest(WireModel):
    descriptor: CandidateDescriptor
    artifact_verification: CandidateArtifactVerification

    @model_validator(mode="after")
    def validate_artifact_verification(self) -> "StartRequest":
        if self.artifact_verification.descriptor_digest != self.descriptor.sha256():
            raise ValueError("start request verification does not match descriptor")
        if (
            self.artifact_verification.artifact_digest
            != self.descriptor.artifact.resource.sha256
        ):
            raise ValueError("start request verification does not match artifact")
        return self


class RuntimeEndpoint(WireModel):
    name: str = Field(min_length=1, max_length=100, pattern=_NAMESPACED)
    protocol: str = Field(min_length=1, max_length=100, pattern=_NAMESPACED)
    uri: str = Field(min_length=1, max_length=500)

    @field_validator("uri")
    @classmethod
    def validate_uri(cls, value: str) -> str:
        parsed = urlsplit(value)
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("runtime endpoint requires an http(s) URI")
        if _contains_url_credentials(value) or sanitize_public_text(value) != value:
            raise ValueError("runtime endpoint may not contain credential material")
        return _public_text(value)


class RunHandle(WireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    candidate_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    backend: BackendIdentity
    descriptor_digest: str = Field(pattern=_SHA256)
    lock_digest: str = Field(pattern=_SHA256)
    state: RunState
    endpoints: tuple[RuntimeEndpoint, ...]
    started_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    evidence_refs: tuple[CandidateResourceRef, ...] = ()

    @model_validator(mode="after")
    def validate_endpoints(self) -> "RunHandle":
        names = [item.name for item in self.endpoints]
        if len(names) != len(set(names)):
            raise ValueError("runtime endpoint names must be unique")
        if self.state is RunState.RUNNING and not self.endpoints:
            raise ValueError("running handle requires at least one endpoint")
        return self


class StartResult(WireModel):
    outcome: LifecycleResult
    handle: RunHandle | None = None

    @model_validator(mode="after")
    def validate_start(self) -> "StartResult":
        if self.outcome.operation is not LifecycleOperation.START:
            raise ValueError("start outcome must use the start operation")
        passed = self.outcome.status is LifecycleStatus.PASSED
        if passed != (self.handle is not None):
            raise ValueError("passed start requires exactly one run handle")
        if self.handle is not None:
            if self.handle.state is not RunState.RUNNING:
                raise ValueError("passed start requires a running handle")
            if self.outcome.task_id != self.handle.task_id:
                raise ValueError("start outcome task identity does not match")
            if self.outcome.candidate_id != self.handle.candidate_id:
                raise ValueError("start outcome candidate identity does not match")
        return self


class HealthRequest(WireModel):
    handle: RunHandle


class HealthResult(WireModel):
    outcome: LifecycleResult
    ready: bool

    @model_validator(mode="after")
    def validate_health(self) -> "HealthResult":
        if self.outcome.operation is not LifecycleOperation.HEALTH:
            raise ValueError("health outcome must use the health operation")
        if (self.outcome.status is LifecycleStatus.PASSED) != self.ready:
            raise ValueError("only a passed health result can be ready")
        return self


class InvocationRequest(WireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    request_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    handle: RunHandle
    protocol: str = Field(min_length=1, max_length=100, pattern=_NAMESPACED)
    payload_ref: CandidateResourceRef


class InvocationResult(WireModel):
    outcome: LifecycleResult
    request_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    response_ref: CandidateResourceRef | None = None

    @model_validator(mode="after")
    def validate_invocation(self) -> "InvocationResult":
        if self.outcome.operation is not LifecycleOperation.INVOKE:
            raise ValueError("invocation outcome must use the invoke operation")
        passed = self.outcome.status is LifecycleStatus.PASSED
        if passed != (self.response_ref is not None):
            raise ValueError("passed invocation requires exactly one response_ref")
        return self


class EvidenceRequest(WireModel):
    handle: RunHandle


class EvidenceResult(WireModel):
    outcome: LifecycleResult

    @model_validator(mode="after")
    def validate_evidence(self) -> "EvidenceResult":
        if self.outcome.operation is not LifecycleOperation.COLLECT_EVIDENCE:
            raise ValueError("evidence outcome must use the collect-evidence operation")
        return self


class StopRequest(WireModel):
    handle: RunHandle


class StopResult(WireModel):
    outcome: LifecycleResult
    stopped: bool

    @model_validator(mode="after")
    def validate_stop(self) -> "StopResult":
        if self.outcome.operation is not LifecycleOperation.STOP:
            raise ValueError("stop outcome must use the stop operation")
        if self.outcome.status is LifecycleStatus.PASSED and not self.stopped:
            raise ValueError("passed stop result must confirm stopped")
        return self


def verify_candidate_artifact(
    descriptor: CandidateDescriptor, delivery_root: Path
) -> CandidateArtifactVerification:
    """Verify descriptor binding without exposing the host path."""

    resource = descriptor.artifact.resource
    if resource.kind is not ResourceKind.WORKSPACE_PATH:
        raise ValueError("candidate.artifact.unsupported_resource")
    root = delivery_root.resolve()
    artifact = (root / resource.locator).resolve()
    try:
        artifact.relative_to(root)
    except ValueError as exc:
        raise ValueError("candidate.artifact.outside_delivery") from exc
    if not artifact.is_file():
        raise ValueError("candidate.artifact.missing")
    with artifact.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != resource.sha256:
        raise ValueError("candidate.artifact.digest_mismatch")
    return CandidateArtifactVerification(
        descriptor_digest=descriptor.sha256(),
        artifact_digest=digest,
    )


def verify_run_handle(descriptor: CandidateDescriptor, handle: RunHandle) -> None:
    """Reject a handle that does not belong to the supplied descriptor."""

    if handle.task_id != descriptor.task_id:
        raise ValueError("candidate.evidence.task_mismatch")
    if handle.candidate_id != descriptor.candidate_id:
        raise ValueError("candidate.evidence.candidate_mismatch")
    if handle.backend != descriptor.backend:
        raise ValueError("candidate.evidence.backend_mismatch")
    if handle.lock_digest != descriptor.lock_digest:
        raise ValueError("candidate.evidence.lock_mismatch")
    if handle.descriptor_digest != descriptor.sha256():
        raise ValueError("candidate.evidence.descriptor_mismatch")


def verify_lifecycle_identity(handle: RunHandle, result: LifecycleResult) -> None:
    """Reject lifecycle evidence from another task or Candidate."""

    if result.task_id != handle.task_id:
        raise ValueError("candidate.evidence.task_mismatch")
    if result.candidate_id != handle.candidate_id:
        raise ValueError("candidate.evidence.candidate_mismatch")


WIRE_MODELS = (
    CandidateDescriptor,
    CandidateArtifactVerification,
    BackendCapability,
    PreflightRequest,
    PreflightResult,
    AssemblyRequest,
    AssemblyResult,
    BuildRequest,
    BuildResult,
    StartRequest,
    StartResult,
    HealthRequest,
    HealthResult,
    InvocationRequest,
    InvocationResult,
    EvidenceRequest,
    EvidenceResult,
    StopRequest,
    StopResult,
)


def candidate_backend_schema() -> dict[str, Any]:
    """Build the canonical JSON Schema bundle for every public wire model."""

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
        "$id": "https://openjiuwen.dev/finance/candidate-backend.v1.schema.json",
        "title": "Finance Solution Builder Candidate Backend v1",
        "oneOf": roots,
        "$defs": dict(sorted(definitions.items())),
    }
