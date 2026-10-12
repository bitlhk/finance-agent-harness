"""Shared, domain-neutral building blocks for finance Agent Builders."""

from finance_builder_base.contracts import (
    ContractModel,
    ErrorDetail,
    ExtensionModel,
    ResourceKind,
    ResourceRef,
)
from finance_builder_base.core import (
    Artifact,
    ArtifactManifest,
    ArtifactRelation,
    BuilderTask,
    CandidateAction,
    CandidateActions,
    TaskStatus,
    WorkspaceContext,
)
from finance_builder_base.solution_build import (
    BuildDefinition,
    BuildPlan,
    CancellationToken,
    InMemoryCheckpointStore,
    ResolvedBuildAsset,
    SolutionBuildCoordinator,
    SolutionBuildResult,
    SolutionBuildStatus,
    StageExecutionResult,
    StageStatus,
    StandardBuildStage,
    standard_build_plan,
)

__all__ = [
    "Artifact",
    "ArtifactManifest",
    "ArtifactRelation",
    "BuilderTask",
    "BuildDefinition",
    "BuildPlan",
    "CancellationToken",
    "CandidateAction",
    "CandidateActions",
    "ContractModel",
    "ErrorDetail",
    "ExtensionModel",
    "InMemoryCheckpointStore",
    "ResourceKind",
    "ResourceRef",
    "ResolvedBuildAsset",
    "SolutionBuildCoordinator",
    "SolutionBuildResult",
    "SolutionBuildStatus",
    "StageExecutionResult",
    "StageStatus",
    "StandardBuildStage",
    "TaskStatus",
    "WorkspaceContext",
    "standard_build_plan",
]

__version__ = "0.1.0"
