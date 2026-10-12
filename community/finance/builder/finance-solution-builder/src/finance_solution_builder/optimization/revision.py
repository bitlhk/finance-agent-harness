"""Verified Candidate overlay creation and application."""

from __future__ import annotations

import hashlib
import shutil
from pathlib import Path, PurePosixPath
from typing import Callable

from finance_builder_base import ResourceKind, ResourceRef
from finance_solution_builder.candidate_backend import CandidateDescriptor
from finance_solution_builder.construction_agent_backend import (
    CancelRequest,
    ConstructionAgentBackend,
    ConstructionTaskRequest,
    EventBatchRequest,
    ResultRequest,
    TaskState,
    path_is_allowed,
    verify_construction_result,
    verify_event_batch,
    verify_task_handle,
)

from .models import CandidateOverlay, CorrectionExecution, CorrectionTarget, OverlayFile

_FROZEN_PREFIXES = (
    "solution-lock.json",
    "solution.yaml",
    "solution.normalized.json",
    "candidate-descriptor.json",
    "build-report.json",
    "evaluation/",
    "regression/",
    "holdout/",
    ".env",
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _inside(root: Path, value: Path) -> Path:
    resolved_root = root.resolve()
    resolved = value.resolve()
    if resolved == resolved_root or not resolved.is_relative_to(resolved_root):
        raise ValueError("optimization.path.outside_workspace")
    return resolved


def _is_frozen(path: str) -> bool:
    return any(path == prefix.rstrip("/") or path.startswith(prefix) for prefix in _FROZEN_PREFIXES)


def _contains_symlink(root: Path, relative: str) -> bool:
    current = root
    for part in PurePosixPath(relative).parts:
        current /= part
        if current.is_symlink():
            return True
    return False


def materialize_candidate_overlay(
    *,
    workspace_root: Path,
    overlay_root: Path,
    resource_root: Path,
    task_request: ConstructionTaskRequest,
    result,
    target: CorrectionTarget,
    asset_closure_digest: str,
    round_index: int,
) -> CandidateOverlay:
    """Verify a Construction result and copy only declared, allowed files."""

    verify_construction_result(task_request, result)
    if result.status is not TaskState.SUCCEEDED:
        raise RuntimeError("optimization.construction.not_succeeded")
    if not result.changed_paths:
        raise ValueError("optimization.construction.empty_diff")
    if any(not path_is_allowed(path, target.allowed_write_paths) for path in result.changed_paths):
        raise ValueError("optimization.target.outside_allowed_scope")
    workspace_root = workspace_root.resolve()
    overlay_root = _inside(resource_root, overlay_root)
    if overlay_root.exists():
        raise FileExistsError("optimization.overlay.already_exists")
    overlay_root.mkdir(parents=True)
    files: list[OverlayFile] = []
    try:
        for relative in sorted(result.changed_paths):
            normalized = PurePosixPath(relative).as_posix()
            if _is_frozen(normalized):
                raise ValueError("optimization.overlay.frozen_path")
            if _contains_symlink(workspace_root, normalized):
                raise ValueError("optimization.overlay.requires_regular_file")
            source = _inside(workspace_root, workspace_root / normalized)
            if not source.is_file():
                raise ValueError("optimization.overlay.requires_regular_file")
            destination = overlay_root / normalized
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
            files.append(OverlayFile(path=normalized, digest=_sha256(destination)))
    except Exception:
        shutil.rmtree(overlay_root, ignore_errors=True)
        raise
    root_ref = ResourceRef(
        kind=ResourceKind.WORKSPACE_PATH,
        locator=overlay_root.relative_to(resource_root.resolve()).as_posix(),
    )
    return CandidateOverlay(
        overlay_id=f"overlay:{result.task_id}",
        round_index=round_index,
        parent_candidate_id=result.candidate_id,
        parent_lock_digest=result.parent_lock_digest,
        asset_closure_digest=asset_closure_digest,
        root_ref=root_ref,
        files=tuple(files),
    )


def apply_candidate_overlay(
    candidate_dir: Path, resource_root: Path, overlay: CandidateOverlay
) -> None:
    """Apply a digest-verified overlay to an assembled Candidate."""

    if overlay.root_ref.kind is not ResourceKind.WORKSPACE_PATH:
        raise ValueError("optimization.overlay.requires_workspace_ref")
    if not overlay.files:
        return
    if _contains_symlink(resource_root, overlay.root_ref.locator):
        raise ValueError("optimization.overlay.requires_regular_directory")
    root = _inside(resource_root, resource_root / overlay.root_ref.locator)
    for item in overlay.files:
        if _is_frozen(item.path):
            raise ValueError("optimization.overlay.frozen_path")
        if _contains_symlink(root, item.path):
            raise ValueError("optimization.overlay.requires_regular_file")
        source = _inside(root, root / item.path)
        if not source.is_file() or _sha256(source) != item.digest:
            raise ValueError("optimization.overlay.digest_mismatch")
        destination = _inside(candidate_dir, candidate_dir / item.path)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)


class ConstructionRevisionExecutor:
    """Drive one existing Construction Backend task into a verified overlay."""

    def __init__(
        self,
        *,
        backend: ConstructionAgentBackend,
        request_factory: Callable[[CorrectionTarget, int], ConstructionTaskRequest],
        workspace_root: Path,
        overlay_parent: Path,
        resource_root: Path,
        asset_closure_digest: str,
    ) -> None:
        self._backend = backend
        self._request_factory = request_factory
        self._workspace_root = workspace_root
        self._overlay_parent = overlay_parent
        self._resource_root = resource_root
        self._asset_closure_digest = asset_closure_digest

    def execute(self, target: CorrectionTarget, round_index: int) -> CorrectionExecution:
        request = self._request_factory(target, round_index)
        handle = self._backend.start(request)
        verify_task_handle(request, handle)
        cursor = handle.last_sequence
        polls = 0
        while handle.state not in {
            TaskState.SUCCEEDED,
            TaskState.FAILED,
            TaskState.CANCELLED,
            TaskState.TIMED_OUT,
        }:
            if handle.state is TaskState.WAITING_APPROVAL:
                self._backend.cancel(
                    CancelRequest(task_id=request.task_id, reason="Approval is required.")
                )
                raise RuntimeError("optimization.construction.approval_required")
            polls += 1
            if polls > max(1, request.budget.max_iterations + 1):
                self._backend.cancel(
                    CancelRequest(task_id=request.task_id, reason="Event budget was exhausted.")
                )
                raise RuntimeError("optimization.construction.event_budget_exhausted")
            batch = self._backend.events(
                EventBatchRequest(task_id=request.task_id, after_sequence=cursor)
            )
            verify_event_batch(request, batch)
            cursor = batch.next_sequence
            if batch.events:
                handle = handle.model_copy(
                    update={"state": batch.events[-1].state, "last_sequence": cursor}
                )
            elif batch.terminal:
                break
        result = self._backend.result(ResultRequest(task_id=request.task_id))
        overlay = materialize_candidate_overlay(
            workspace_root=self._workspace_root,
            overlay_root=self._overlay_parent / f"round-{round_index:03d}",
            resource_root=self._resource_root,
            task_request=request,
            result=result,
            target=target,
            asset_closure_digest=self._asset_closure_digest,
            round_index=round_index,
        )
        return CorrectionExecution(
            change_digest=overlay.sha256(),
            evidence_refs=result.evidence_refs,
            duration_ms=result.budget_usage.duration_ms,
            cost_microunits=result.budget_usage.cost_microunits,
            overlay=overlay,
        )


class SolutionBuilderRebuildGateway:
    """Apply one verified revision through the existing SolutionBuilder pipeline."""

    def __init__(
        self,
        *,
        builder,
        solution_path: Path,
        output_parent: Path,
        asset_closure_digest: str,
        verify: bool = True,
    ) -> None:
        self._builder = builder
        self._solution_path = solution_path
        self._output_parent = output_parent
        self._asset_closure_digest = asset_closure_digest
        self._verify = verify

    def rebuild(self, parent, correction: CorrectionExecution, round_index: int):
        from .models import OptimizationSnapshot, RebuildOutcome

        overlay = correction.overlay
        if overlay is None:
            data = correction.data_version
            if data is None:
                raise ValueError("optimization.rebuild.missing_revision")
            overlay = CandidateOverlay(
                overlay_id=f"data:{data.version_id}",
                round_index=round_index,
                parent_candidate_id=parent.candidate_id,
                parent_lock_digest=parent.lock_digest,
                asset_closure_digest=self._asset_closure_digest,
                root_ref=ResourceRef(
                    kind=ResourceKind.WORKSPACE_PATH,
                    locator="optimization/data-only",
                ),
                data_digests=(data.output_digest,),
            )
        if (
            overlay.parent_candidate_id != parent.candidate_id
            or overlay.parent_lock_digest != parent.lock_digest
        ):
            raise ValueError("optimization.rebuild.parent_mismatch")
        result = self._builder.build(
            self._solution_path,
            self._output_parent / f"round-{round_index:03d}",
            verify=self._verify,
            candidate_overlay=overlay,
        )
        if result.status not in {"passed", "passed_with_risk"}:
            raise RuntimeError("optimization.rebuild.failed")
        descriptor_path = result.candidate_dir / "candidate-descriptor.json"
        if not descriptor_path.is_file():
            raise RuntimeError("optimization.rebuild.descriptor_missing")
        descriptor = CandidateDescriptor.model_validate_json(
            descriptor_path.read_text(encoding="utf-8")
        )
        if descriptor.candidate_id != parent.candidate_id:
            raise ValueError("optimization.rebuild.candidate_identity_mismatch")
        artifact_digest = descriptor.artifact.resource.sha256
        if artifact_digest is None:
            raise ValueError("optimization.rebuild.artifact_digest_missing")
        lock_bytes = result.lock_path.read_bytes()
        lock_digest = hashlib.sha256(lock_bytes).hexdigest()
        if descriptor.lock_digest != lock_digest:
            raise ValueError("optimization.rebuild.lock_digest_mismatch")
        report_bytes = result.report_path.read_bytes()
        snapshot = OptimizationSnapshot(
            candidate_id=descriptor.candidate_id,
            descriptor_digest=descriptor.sha256(),
            lock_digest=lock_digest,
            artifact_digest=artifact_digest,
            evaluation_pack_digest=parent.evaluation_pack_digest,
            dataset_digest=parent.dataset_digest,
            rules_digest=parent.rules_digest,
            runtime_digest=parent.runtime_digest,
            evidence_level=parent.evidence_level,
        )
        return RebuildOutcome(
            snapshot=snapshot,
            lock_ref=ResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator=result.lock_path.relative_to(self._builder.project_root).as_posix(),
                sha256=lock_digest,
            ),
            report_ref=ResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator=result.report_path.relative_to(self._builder.project_root).as_posix(),
                sha256=hashlib.sha256(report_bytes).hexdigest(),
            ),
        )
