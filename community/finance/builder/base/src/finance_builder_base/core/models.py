"""Domain-neutral state used by Builder orchestration."""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from pathlib import PurePosixPath

from pydantic import Field, SerializeAsAny, model_validator

from finance_builder_base.contracts import ContractModel, ExtensionModel, ResourceRef


class TaskStatus(str, Enum):
    CREATED = "created"
    RUNNING = "running"
    WAITING_INPUT = "waiting-input"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


class WorkspaceContext(ContractModel):
    session_id: str = Field(min_length=1)
    workspace: ResourceRef
    default_subdirectory: str | None = None

    @model_validator(mode="after")
    def require_workspace_ref(self) -> WorkspaceContext:
        if self.workspace.kind.value != "workspace-path":
            raise ValueError("workspace must use a workspace-path resource")
        return self

    @model_validator(mode="after")
    def require_safe_default_subdirectory(self) -> WorkspaceContext:
        if self.default_subdirectory is None:
            return self
        normalized = self.default_subdirectory.replace("\\", "/")
        parts = PurePosixPath(normalized).parts
        is_empty = not normalized.strip("/")
        has_absolute_prefix = normalized.startswith("/")
        has_drive_prefix = ":" in normalized.split("/")[0]
        has_parent_reference = ".." in parts
        if any((is_empty, has_absolute_prefix, has_drive_prefix, has_parent_reference)):
            raise ValueError("default_subdirectory must be a safe relative path")
        return self


class Artifact(ContractModel):
    artifact_id: str = Field(min_length=1)
    role: str = Field(min_length=1)
    resource: ResourceRef


class ArtifactManifest(ContractModel):
    manifest_id: str = Field(min_length=1)
    artifacts: tuple[Artifact, ...] = ()
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    @model_validator(mode="after")
    def unique_artifacts(self) -> ArtifactManifest:
        ids = [artifact.artifact_id for artifact in self.artifacts]
        if len(ids) != len(set(ids)):
            raise ValueError("artifact_id must be unique within a manifest")
        return self


class ArtifactRelation(ContractModel):
    source_artifact_id: str = Field(min_length=1)
    target_artifact_id: str = Field(min_length=1)
    relation: str = Field(min_length=1)


class CandidateAction(ContractModel):
    action_id: str = Field(min_length=1)
    agent_role: str = Field(min_length=1)
    action: str = Field(min_length=1)
    reason: str = Field(min_length=1)
    priority: int = 0
    inputs: tuple[ResourceRef, ...] = ()


class CandidateActions(ContractModel):
    task_id: str = Field(min_length=1)
    candidates: tuple[CandidateAction, ...]


class BuilderTask(ContractModel):
    task_id: str = Field(min_length=1)
    status: TaskStatus = TaskStatus.CREATED
    workspace: WorkspaceContext
    manifests: tuple[ArtifactManifest, ...] = ()
    relations: tuple[ArtifactRelation, ...] = ()
    extension: SerializeAsAny[ExtensionModel] | None = None
