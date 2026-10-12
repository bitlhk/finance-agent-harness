from __future__ import annotations

from pathlib import Path
from typing import Literal

import finance_builder_base
import pytest
from finance_builder_base.contracts import ExtensionModel
from finance_builder_base.core import BuilderTask, WorkspaceContext


_SCHEDULE_OPERATIONS = [
    "query_schedules",
    "get_schedule",
    "create_schedule",
    "update_schedule",
    "delete_schedule",
]


class FutureBuilderExtension(ExtensionModel):
    """Minimal contract proving that another Builder need not change Base."""

    extension_type: Literal["future.builder"] = "future.builder"
    capability_ref: str


def test_intent_and_scheduling_consume_the_installed_base_contracts(tmp_path: Path) -> None:
    intent_adapter = pytest.importorskip(
        "data_factory.base_adapter",
        reason="Intent is an optional real consumer in a Base-only test environment",
    )
    intent_context_module = pytest.importorskip("data_factory.context")
    scheduling_models = pytest.importorskip(
        "scheduling_agent_builder.models",
        reason="Scheduling is an optional real consumer in a Base-only test environment",
    )

    intent_context = intent_context_module.TaskContext(
        task_id="intent-task",
        run_mode="preview",
        work_dir=str(tmp_path),
    )
    intent_task = intent_adapter.to_builder_task(intent_context, session_id="session-1")

    scheduling_request = scheduling_models.BuilderRequest(
        build_goal="adapt scheduling",
        built_in_solution_ref=scheduling_models.ResourceRef(uri="workspace/built-in"),
        workspace_root="workspace/session-1",
        relationship_graph_descriptor=scheduling_models.RelationshipGraphDescriptor(
            base_url="https://knowledge.example.test",
            schema_ref="scheduling-knowledge/v1",
            scope_keys=["principal_id"],
        ),
        schedule_system_descriptor=scheduling_models.ScheduleSystemDescriptor(
            base_url="https://schedule.example.test",
            operations=_SCHEDULE_OPERATIONS,
        ),
    )
    scheduling_extension = scheduling_request.to_base_extension()

    assert isinstance(intent_task, BuilderTask)
    assert isinstance(intent_task.workspace, WorkspaceContext)
    assert isinstance(scheduling_extension, scheduling_models.SchedulingBuilderExtension)
    assert isinstance(scheduling_extension, ExtensionModel)
    assert scheduling_extension.model_dump(mode="json")["extension_type"] == (
        "scheduling.builder"
    )


def test_future_builder_uses_the_extension_boundary_without_modifying_base() -> None:
    extension = FutureBuilderExtension(capability_ref="capabilities/example-v1")
    dumped = extension.model_dump(mode="json")

    assert dumped == {
        "extension_type": "future.builder",
        "schema_version": "1.0",
        "capability_ref": "capabilities/example-v1",
    }


def test_base_source_does_not_import_real_consumer_packages() -> None:
    source_root = Path(finance_builder_base.__file__).resolve().parent
    source = "\n".join(
        path.read_text(encoding="utf-8")
        for path in source_root.rglob("*.py")
    )

    assert "scheduling_agent_builder" not in source
    assert "from data_factory" not in source
    assert "import data_factory" not in source
    lowered = source.lower()
    for dependency in ("openjiuwen", "maven", "finance_harness"):
        assert f"import {dependency}" not in lowered
        assert f"from {dependency}" not in lowered


def test_existing_core_contract_shape_is_unchanged() -> None:
    workspace = WorkspaceContext(
        session_id="session-compat",
        workspace=finance_builder_base.ResourceRef(
            kind=finance_builder_base.ResourceKind.WORKSPACE_PATH,
            locator="sessions/session-compat",
        ),
    )
    dumped = BuilderTask(task_id="task-compat", workspace=workspace).model_dump(mode="json")

    assert dumped == {
        "task_id": "task-compat",
        "status": "created",
        "workspace": {
            "session_id": "session-compat",
            "workspace": {
                "kind": "workspace-path",
                "locator": "sessions/session-compat",
                "name": None,
                "media_type": None,
                "sha256": None,
            },
            "default_subdirectory": None,
        },
        "manifests": [],
        "relations": [],
        "extension": None,
    }


def test_consumers_use_public_base_interfaces() -> None:
    builder_root = Path(__file__).resolve().parents[3]
    intent_source = builder_root / "intent-agent-builder" / "src"
    scheduling_source = builder_root / "scheduling-agent-builder" / "src"
    source = "\n".join(
        path.read_text(encoding="utf-8")
        for root in (intent_source, scheduling_source)
        for path in root.rglob("*.py")
    )

    assert "finance_builder_base.evaluation.engine import _" not in source
    assert "when the package exists" not in source
