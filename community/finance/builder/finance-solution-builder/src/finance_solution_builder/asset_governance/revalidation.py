"""Bind a published Harness asset revision to comparable optimization evidence."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from finance_solution_builder.optimization.models import OptimizationSnapshot


@dataclass(frozen=True)
class RevalidationBinding:
    finding_id: str
    parent_lock_digest: str
    candidate_lock_digest: str
    comparable: bool
    reason: str


@dataclass(frozen=True, order=True)
class _BoundAsset:
    kind: str
    id: str
    version: str
    source: str
    digest: str
    selected_by: tuple[str, ...]

    @property
    def key(self) -> tuple[str, str]:
        return self.kind, self.id

    def record(self) -> dict[str, Any]:
        return {
            "kind": self.kind,
            "id": self.id,
            "version": self.version,
            "source": self.source,
            "digest": self.digest,
            "selectedBy": list(self.selected_by),
        }


@dataclass(frozen=True)
class _LockAssets:
    solution_id: str
    lock_digest: str
    catalog_digest: str
    closure_digest: str
    assets: tuple[_BoundAsset, ...]


def _not_comparable(
    finding_id: str,
    parent: OptimizationSnapshot,
    candidate: OptimizationSnapshot,
    reason: str,
) -> RevalidationBinding:
    return RevalidationBinding(
        finding_id,
        parent.lock_digest,
        candidate.lock_digest,
        False,
        reason,
    )


def _as_mapping(value: Any, field: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ValueError(f"{field} must be an object")
    return value


def _required_text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-empty string")
    return value.strip()


def _finding_id(proposal: Mapping[str, Any]) -> str:
    finding = proposal.get("finding")
    if not isinstance(finding, Mapping):
        return ""
    value = finding.get("id")
    return value.strip() if isinstance(value, str) else ""


def _asset_records(lock: Mapping[str, Any]) -> list[Mapping[str, Any]]:
    records: list[Mapping[str, Any]] = []
    runtime = lock.get("runtimeIntegration")
    if isinstance(runtime, Mapping):
        records.append(runtime)
    raw_assets = lock.get("assets")
    if not isinstance(raw_assets, list):
        raise ValueError("assets must be an array")
    records.extend(_as_mapping(item, "assets[]") for item in raw_assets)
    return records


def _resolved_digest(
    closure: Mapping[str, Any],
    resolved: list[Mapping[str, Any]],
) -> str:
    source = _required_text(closure.get("source"), "assetClosure[].source")
    kind = _required_text(closure.get("kind"), "assetClosure[].kind")
    asset_id = _required_text(closure.get("id"), "assetClosure[].id")
    version = _required_text(closure.get("version"), "assetClosure[].version")
    candidates = [
        item
        for item in resolved
        if item.get("source") == source
        and (
            kind == "runtime-integration"
            or (item.get("id") == asset_id and item.get("version") == version)
        )
    ]
    if len(candidates) != 1:
        raise ValueError(f"asset {kind}:{asset_id}@{version} is not uniquely resolved")
    return _required_text(candidates[0].get("digest"), "resolved asset digest")


def _load_assets(lock: Mapping[str, Any]) -> _LockAssets:
    solution_id = _required_text(lock.get("solutionId"), "solutionId")
    catalog = _as_mapping(lock.get("assetCatalog"), "assetCatalog")
    catalog_digest = _required_text(catalog.get("digest"), "assetCatalog.digest")
    raw_closure = lock.get("assetClosure")
    if not isinstance(raw_closure, list) or not raw_closure:
        raise ValueError("assetClosure must be a non-empty array")
    resolved = _asset_records(lock)
    assets: list[_BoundAsset] = []
    for raw in raw_closure:
        item = _as_mapping(raw, "assetClosure[]")
        selected_by = item.get("selectedBy")
        if not isinstance(selected_by, list) or not all(
            isinstance(value, str) and value for value in selected_by
        ):
            raise ValueError("assetClosure[].selectedBy must be a non-empty string array")
        assets.append(
            _BoundAsset(
                kind=_required_text(item.get("kind"), "assetClosure[].kind"),
                id=_required_text(item.get("id"), "assetClosure[].id"),
                version=_required_text(item.get("version"), "assetClosure[].version"),
                source=_required_text(item.get("source"), "assetClosure[].source"),
                digest=_resolved_digest(item, resolved),
                selected_by=tuple(sorted(selected_by)),
            )
        )
    ordered = tuple(sorted(assets))
    if len({item.key for item in ordered}) != len(ordered):
        raise ValueError("assetClosure contains duplicate asset identities")
    closure_payload = [item.record() for item in ordered]
    closure_digest = "sha256:" + hashlib.sha256(
        json.dumps(closure_payload, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    lock_digest = hashlib.sha256(
        (json.dumps(lock, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    ).hexdigest()
    return _LockAssets(solution_id, lock_digest, catalog_digest, closure_digest, ordered)


def _proposal_target(proposal: Mapping[str, Any]) -> _BoundAsset:
    target = _as_mapping(proposal.get("target"), "proposal.target")
    kind = _required_text(target.get("kind"), "proposal.target.kind")
    if kind == "external-environment":
        raise ValueError("external targets are not Harness-published assets")
    return _BoundAsset(
        kind=kind,
        id=_required_text(target.get("id"), "proposal.target.id"),
        version=_required_text(target.get("version"), "proposal.target.version"),
        source=_required_text(target.get("source"), "proposal.target.source"),
        digest=_required_text(target.get("digest"), "proposal.target.digest"),
        selected_by=(),
    )


def _find_asset(assets: tuple[_BoundAsset, ...], key: tuple[str, str]) -> _BoundAsset | None:
    return next((item for item in assets if item.key == key), None)


def bind_revalidation(
    proposal: Mapping[str, Any],
    parent: OptimizationSnapshot,
    candidate: OptimizationSnapshot,
    *,
    parent_lock: Mapping[str, Any],
    candidate_lock: Mapping[str, Any],
) -> RevalidationBinding:
    """Bind revalidation to the proposed asset and two complete Catalog closures."""

    finding_id = _finding_id(proposal)
    if not finding_id:
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            "The Change Proposal does not identify a finding.",
        )
    if parent.lock_digest == candidate.lock_digest:
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            "The published asset revision did not produce a new Lock.",
        )
    try:
        proposal_parent = _as_mapping(proposal.get("parentLock"), "proposal.parentLock")
        proposal_catalog = _as_mapping(proposal.get("catalog"), "proposal.catalog")
        proposal_parent_digest = _required_text(
            proposal_parent.get("lockDigest"),
            "proposal.parentLock.lockDigest",
        )
        proposal_solution_id = _required_text(
            proposal_parent.get("solutionId"),
            "proposal.parentLock.solutionId",
        )
        proposal_catalog_digest = _required_text(
            proposal_catalog.get("digest"),
            "proposal.catalog.digest",
        )
        target = _proposal_target(proposal)
        parent_assets = _load_assets(parent_lock)
        candidate_assets = _load_assets(candidate_lock)
    except (TypeError, ValueError) as exc:
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            f"The revalidation binding is incomplete: {exc}.",
        )
    if (
        parent_assets.lock_digest != parent.lock_digest
        or candidate_assets.lock_digest != candidate.lock_digest
    ):
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            "The supplied Lock content does not match its Optimization snapshot.",
        )
    if proposal_parent_digest != parent.lock_digest:
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            "The Change Proposal does not belong to the parent Lock.",
        )
    if (
        proposal_solution_id != parent_assets.solution_id
        or candidate_assets.solution_id != parent_assets.solution_id
    ):
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            "The Change Proposal and Locks do not belong to the same Solution.",
        )
    if proposal_catalog_digest != parent_assets.catalog_digest:
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            "The Change Proposal does not belong to the parent Catalog snapshot.",
        )
    parent_target = _find_asset(parent_assets.assets, target.key)
    if parent_target is None or (
        parent_target.version,
        parent_target.source,
        parent_target.digest,
    ) != (target.version, target.source, target.digest):
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            "The proposed target does not match the parent asset closure.",
        )
    candidate_target = _find_asset(candidate_assets.assets, target.key)
    if candidate_target is None or (
        candidate_target.version == parent_target.version
        and candidate_target.digest == parent_target.digest
    ):
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            "The proposed Harness asset did not change version or content digest.",
        )
    parent_other = tuple(item for item in parent_assets.assets if item.key != target.key)
    candidate_other = tuple(item for item in candidate_assets.assets if item.key != target.key)
    if parent_other != candidate_other:
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            "Assets outside the proposed target changed, so the result is not attributable.",
        )
    if parent_assets.catalog_digest == candidate_assets.catalog_digest:
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            "The candidate does not reference a new Catalog snapshot.",
        )
    if parent_assets.closure_digest == candidate_assets.closure_digest:
        return _not_comparable(
            finding_id,
            parent,
            candidate,
            "The complete asset closure did not change.",
        )
    frozen = all(
        (
            parent.evaluation_pack_digest == candidate.evaluation_pack_digest,
            parent.dataset_digest == candidate.dataset_digest,
            parent.rules_digest == candidate.rules_digest,
            parent.runtime_digest == candidate.runtime_digest,
            parent.evidence_level == candidate.evidence_level,
        )
    )
    return RevalidationBinding(
        finding_id,
        parent.lock_digest,
        candidate.lock_digest,
        frozen,
        (
            "The proposed asset revision can be evaluated under frozen comparison conditions."
            if frozen
            else "Evaluation data, rules, runtime, or evidence level changed."
        ),
    )
