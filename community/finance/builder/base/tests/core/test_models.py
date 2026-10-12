from __future__ import annotations

from finance_builder_base.contracts import ExtensionModel, ResourceKind, ResourceRef
from finance_builder_base.core import (
    Artifact,
    ArtifactManifest,
    BuilderTask,
    CandidateAction,
    CandidateActions,
    WorkspaceContext,
)


class IntentExtension(ExtensionModel):
    extension_type: str = "intent"
    intent_count: int


class SchedulingExtension(ExtensionModel):
    extension_type: str = "scheduling"
    timezone: str


class FutureExtension(ExtensionModel):
    extension_type: str = "graph"
    graph_ref: str


def _workspace() -> WorkspaceContext:
    return WorkspaceContext(
        session_id="S1",
        workspace=ResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="."),
    )


def test_three_builders_use_same_core_without_changing_it() -> None:
    extensions = (
        IntentExtension(intent_count=3),
        SchedulingExtension(timezone="Asia/Hong_Kong"),
        FutureExtension(graph_ref="graphs/g1.json"),
    )
    serialized = [
        BuilderTask(task_id=f"T{index}", workspace=_workspace(), extension=value)
        .model_dump(mode="json")["extension"]
        for index, value in enumerate(extensions)
    ]
    assert [item["extension_type"] for item in serialized] == [
        "intent",
        "scheduling",
        "graph",
    ]


def test_core_expresses_manifest_and_candidate_actions_without_choosing() -> None:
    artifact = Artifact(
        artifact_id="a1",
        role="candidate-agent",
        resource=ResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="agents/a1"),
    )
    task = BuilderTask(
        task_id="T1",
        workspace=_workspace(),
        manifests=(ArtifactManifest(manifest_id="M1", artifacts=(artifact,)),),
    )
    candidates = CandidateActions(
        task_id=task.task_id,
        candidates=(
            CandidateAction(
                action_id="evaluate",
                agent_role="evaluation",
                action="evaluate candidate",
                reason="candidate is available",
                priority=10,
            ),
            CandidateAction(
                action_id="revise",
                agent_role="developer",
                action="revise candidate",
                reason="user requested changes",
                priority=20,
            ),
        ),
    )
    highest_priority = max(candidates.candidates, key=lambda item: item.priority)
    first_declared = candidates.candidates[0]
    assert highest_priority.action_id == "revise"
    assert first_declared.action_id == "evaluate"
