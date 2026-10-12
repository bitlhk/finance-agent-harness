import hashlib
import json
from types import SimpleNamespace

import pytest

from finance_builder_base import ResourceKind
from finance_builder_base.solution_build import CheckStatus
from finance_solution_builder.construction_agent_backend import (
    BudgetUsage,
    ConstructionBudget,
    ConstructionResourceRef,
    ConstructionResult,
    ConstructionTaskRequest,
    TaskState,
    ValidationCommand,
    ValidationResult,
    WorkspaceBoundary,
)
from finance_solution_builder.construction_agent_backend.codex import (
    CodexAdapterConfig,
    CodexConstructionAgentBackend,
)
from finance_solution_builder.optimization import (
    ActionDisposition,
    CorrectionTarget,
    CorrectionTargetKind,
    CorrectionExecution,
    ConstructionRevisionExecutor,
    OptimizationDataVersion,
    SolutionBuilderRebuildGateway,
    apply_candidate_overlay,
)
from finance_solution_builder.builder import BuildResult
from tests.candidate_backend.support import descriptor
from finance_solution_builder.optimization.revision import materialize_candidate_overlay
from tests.construction_agent_backend.codex_protocol_double import CodexProtocolDouble

from . import SHA, resource, snapshot


def construction_request(workspace_locator: str) -> ConstructionTaskRequest:
    return ConstructionTaskRequest(
        task_id="repair-1",
        idempotency_key="repair-1",
        candidate_id="candidate-1",
        parent_lock_digest=SHA,
        goal="Repair the declared Candidate source file.",
        finding_refs=(
            ConstructionResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator="evidence/finding.json",
                sha256=SHA,
            ),
        ),
        workspace_boundary=WorkspaceBoundary(
            workspace=ConstructionResourceRef(
                kind=ResourceKind.WORKSPACE_PATH, locator=workspace_locator
            ),
            allowed_write_paths=("src",),
        ),
        validation_commands=(
            ValidationCommand(command_id="tests", argv=("python", "-m", "pytest"), timeout_ms=1000),
        ),
        budget=ConstructionBudget(
            max_duration_ms=1000,
            max_iterations=2,
            max_tool_calls=2,
            max_cost_microunits=10,
        ),
    )


def construction_result(path: str) -> ConstructionResult:
    return ConstructionResult(
        task_id="repair-1",
        candidate_id="candidate-1",
        parent_lock_digest=SHA,
        status=TaskState.SUCCEEDED,
        summary="The declared repair and validation completed.",
        changed_paths=(path,),
        validation_results=(
            ValidationResult(
                command_id="tests",
                status=CheckStatus.PASSED,
                duration_ms=1,
                summary="Tests passed.",
            ),
        ),
        budget_usage=BudgetUsage(duration_ms=1, iterations=1, tool_calls=1, cost_microunits=1),
    )


def target() -> CorrectionTarget:
    return CorrectionTarget(
        finding_id="finding-1",
        target_kind=CorrectionTargetKind.CANDIDATE_OVERLAY,
        target_ref="candidate/source",
        action="repair",
        disposition=ActionDisposition.AUTO,
        reason="Explicit governance permits this repair.",
        owner_id="owner",
        allowed_write_paths=("src",),
        validation_refs=(resource(),),
    )


def test_materializes_and_applies_digest_verified_overlay(tmp_path) -> None:
    workspace = tmp_path / "workspace"
    source = workspace / "src/main.py"
    source.parent.mkdir(parents=True)
    source.write_text("value = 2\n", encoding="utf-8")
    overlay = materialize_candidate_overlay(
        workspace_root=workspace,
        overlay_root=tmp_path / "overlays/round-001",
        resource_root=tmp_path,
        task_request=construction_request("workspace"),
        result=construction_result("src/main.py"),
        target=target(),
        asset_closure_digest=SHA,
        round_index=1,
    )
    candidate = tmp_path / "candidate"
    candidate.mkdir()
    apply_candidate_overlay(candidate, tmp_path, overlay)
    assert (candidate / "src/main.py").read_text(encoding="utf-8") == "value = 2\n"
    assert overlay.files[0].digest == hashlib.sha256(source.read_bytes()).hexdigest()


def test_frozen_or_tampered_overlay_fails_closed(tmp_path) -> None:
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    (workspace / "solution-lock.json").write_text("{}", encoding="utf-8")
    with pytest.raises(ValueError, match="frozen_path"):
        materialize_candidate_overlay(
            workspace_root=workspace,
            overlay_root=tmp_path / "overlays/round-001",
            resource_root=tmp_path,
            task_request=construction_request("workspace").model_copy(
                update={
                    "workspace_boundary": WorkspaceBoundary(
                        workspace=ConstructionResourceRef(
                            kind=ResourceKind.WORKSPACE_PATH, locator="workspace"
                        ),
                        allowed_write_paths=("solution-lock.json",),
                    )
                }
            ),
            result=construction_result("solution-lock.json"),
            target=target().model_copy(update={"allowed_write_paths": ("solution-lock.json",)}),
            asset_closure_digest=SHA,
            round_index=1,
        )


def test_construction_contract_rejects_out_of_scope_diff(tmp_path) -> None:
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    (workspace / "README.md").write_text("changed", encoding="utf-8")
    with pytest.raises(ValueError, match="outside_allowed_scope"):
        materialize_candidate_overlay(
            workspace_root=workspace,
            overlay_root=tmp_path / "overlays/round-001",
            resource_root=tmp_path,
            task_request=construction_request("workspace"),
            result=construction_result("README.md"),
            target=target(),
            asset_closure_digest=SHA,
            round_index=1,
        )


def test_codex_backend_is_consumed_through_public_revision_executor(tmp_path) -> None:
    workspace = tmp_path / "workspace"
    source = workspace / "src/main.py"
    source.parent.mkdir(parents=True)
    source.write_text("value = 1\n", encoding="utf-8")
    backend = CodexConstructionAgentBackend(
        CodexAdapterConfig(
            workspace_root=tmp_path,
            input_cost_microunits_per_million_tokens=0,
            output_cost_microunits_per_million_tokens=0,
        ),
        runtime_factory=lambda config: CodexProtocolDouble(
            config,
            approval_method=None,
            changed_path="src/main.py",
        ),
        command_runner=lambda *_: SimpleNamespace(returncode=0, duration_ms=1),
        background_runner=lambda run: run(),
    )
    executor = ConstructionRevisionExecutor(
        backend=backend,
        request_factory=lambda *_: construction_request("workspace"),
        workspace_root=workspace,
        overlay_parent=tmp_path / "overlays",
        resource_root=tmp_path,
        asset_closure_digest=SHA,
    )
    execution = executor.execute(target(), 1)
    assert execution.overlay is not None
    assert execution.overlay.files[0].path == "src/main.py"
    assert (tmp_path / execution.overlay.root_ref.locator / "src/main.py").is_file()


def test_routed_target_boundary_cannot_be_widened_by_request_factory(tmp_path) -> None:
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    (workspace / "README.md").write_text("changed", encoding="utf-8")
    widened = construction_request("workspace").model_copy(
        update={
            "workspace_boundary": WorkspaceBoundary(
                workspace=ConstructionResourceRef(
                    kind=ResourceKind.WORKSPACE_PATH, locator="workspace"
                ),
                allowed_write_paths=("README.md",),
            )
        }
    )
    with pytest.raises(ValueError, match="target.outside_allowed_scope"):
        materialize_candidate_overlay(
            workspace_root=workspace,
            overlay_root=tmp_path / "overlays/round-001",
            resource_root=tmp_path,
            task_request=widened,
            result=construction_result("README.md"),
            target=target(),
            asset_closure_digest=SHA,
            round_index=1,
        )


@pytest.mark.parametrize(
    ("rebuilt_candidate_id", "accepted"),
    ((snapshot().candidate_id, True), ("another-candidate", False)),
)
def test_solution_builder_gateway_enforces_locked_candidate_identity(
    tmp_path, rebuilt_candidate_id, accepted
) -> None:
    class FakeBuilder:
        project_root = tmp_path

        def build(self, solution_path, output_dir, verify, *, candidate_overlay):
            assert solution_path.name == "solution.json"
            assert verify is True
            assert candidate_overlay.data_digests == ("c" * 64,)
            candidate_dir = output_dir / "candidate"
            candidate_dir.mkdir(parents=True)
            lock_path = output_dir / "solution-lock.json"
            lock_path.write_text('{"revision":2}\n', encoding="utf-8")
            lock_digest = hashlib.sha256(lock_path.read_bytes()).hexdigest()
            revised = descriptor().model_copy(
                update={
                    "candidate_id": rebuilt_candidate_id,
                    "lock_digest": lock_digest,
                }
            )
            (candidate_dir / "candidate-descriptor.json").write_text(
                json.dumps(revised.to_wire()), encoding="utf-8"
            )
            report_path = output_dir / "build-report.json"
            report_path.write_text('{"status":"passed"}\n', encoding="utf-8")
            return BuildResult(
                output_dir=output_dir,
                lock_path=lock_path,
                report_path=report_path,
                candidate_dir=candidate_dir,
                status="passed",
            )

    data = OptimizationDataVersion(
        version_id="data-1",
        task_id="data-1",
        source_digest=SHA,
        output_digest="c" * 64,
        resource_refs=(resource("data/optimization.jsonl"),),
        item_count=1,
    )
    correction = CorrectionExecution(
        change_digest=data.sha256(),
        evidence_refs=data.resource_refs,
        duration_ms=0,
        cost_microunits=0,
        data_version=data,
    )
    gateway = SolutionBuilderRebuildGateway(
        builder=FakeBuilder(),
        solution_path=tmp_path / "solution.json",
        output_parent=tmp_path / "rounds",
        asset_closure_digest=SHA,
    )
    if not accepted:
        with pytest.raises(ValueError, match="candidate_identity_mismatch"):
            gateway.rebuild(snapshot(), correction, 1)
        return

    outcome = gateway.rebuild(snapshot(), correction, 1)
    assert outcome.snapshot.candidate_id == snapshot().candidate_id
    assert outcome.snapshot.lock_digest != SHA
    assert outcome.lock_ref.sha256 == outcome.snapshot.lock_digest
