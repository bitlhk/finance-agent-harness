"""Immutable, public-safe Change Proposal records for externally owned assets."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from finance_builder_base import ResourceKind, ResourceRef
from finance_builder_base.contracts import sanitize_public_text
from jsonschema import Draft202012Validator

from finance_solution_builder.evaluation import EvaluationFinding
from finance_solution_builder.optimization.models import GovernedTarget, OptimizationRequest

from .catalog import AssetCatalog, CatalogAsset


def _safe(value: str) -> str:
    if not value.strip() or sanitize_public_text(value) != value:
        raise ValueError("asset.proposal.private_or_empty_text")
    return value


@dataclass(frozen=True)
class ExternalProposalTarget:
    """Explicit governance facts for an environment outside the Harness Catalog."""

    id: str
    version: str
    source: str
    governance_ref: str
    owner: str
    allowed_change_scopes: tuple[str, ...]
    verification_entrypoint: str


@dataclass(frozen=True)
class ChangeProposalRequest:
    catalog: AssetCatalog | None
    target: CatalogAsset | ExternalProposalTarget
    target_digest: str
    solution_id: str
    parent_lock_digest: str
    finding: EvaluationFinding
    reproduction_conditions: tuple[str, ...]
    proposed_summary: str
    proposed_scopes: tuple[str, ...]
    prior_result_refs: tuple[dict[str, Any], ...]
    task_id: str
    builder_source_revision: str
    created_at: datetime | None = None


@dataclass(frozen=True)
class ProposalResult:
    proposal_id: str
    path: Path
    digest: str
    created: bool


class ChangeProposalWriter:
    def __init__(self, workspace_root: Path, schema_path: Path) -> None:
        self.workspace_root = workspace_root.resolve()
        self.schema = json.loads(schema_path.read_text(encoding="utf-8"))

    def write(self, request: ChangeProposalRequest) -> ProposalResult:
        if isinstance(request.target, CatalogAsset):
            if request.catalog is None:
                raise ValueError("asset.proposal.catalog_required")
            policy = request.catalog.governance_policy(request.target)
            target_kind = request.target.kind.value
        else:
            if request.catalog is not None:
                raise ValueError("asset.proposal.external_target_must_not_use_catalog")
            if (
                not request.target.owner.strip()
                or not request.target.allowed_change_scopes
                or not request.target.verification_entrypoint.strip()
            ):
                raise ValueError("asset.proposal.governance_incomplete")
            policy = {
                "owner": _safe(request.target.owner),
                "allowedChangeScopes": list(request.target.allowed_change_scopes),
                "verification": {"value": _safe(request.target.verification_entrypoint)},
            }
            target_kind = "external-environment"
        scopes = tuple(dict.fromkeys(request.proposed_scopes))
        if not scopes or not set(scopes).issubset(policy["allowedChangeScopes"]):
            raise ValueError("asset.proposal.scope_not_allowed")
        if not request.reproduction_conditions:
            raise ValueError("asset.proposal.reproduction_required")
        evidence_refs = [item.model_dump(mode="json") for item in request.finding.evidence_refs]
        prior_refs = [dict(item) for item in request.prior_result_refs]
        stable = {
            "catalog": (
                request.catalog.snapshot.lock_record()
                if request.catalog is not None
                else None
            ),
            "target": {
                "kind": target_kind,
                "id": request.target.id,
                "version": request.target.version,
                "source": request.target.source,
                "digest": request.target_digest,
                "governanceRef": request.target.governance_ref,
                "owner": policy["owner"],
            },
            "parentLock": {
                "solutionId": _safe(request.solution_id),
                "lockDigest": request.parent_lock_digest,
            },
            "finding": {
                "id": request.finding.finding_id,
                "category": request.finding.responsibility.value,
                "summary": _safe(request.finding.summary),
                "evidenceRefs": evidence_refs,
            },
            "reproduction": {
                "conditions": [_safe(item) for item in request.reproduction_conditions],
                "redaction": "public-safe",
            },
            "proposedChange": {
                "summary": _safe(request.proposed_summary),
                "scopes": list(scopes),
            },
            "verification": {
                "entrypoint": _safe(policy["verification"]["value"]),
                "priorResultRefs": prior_refs,
            },
            "builderTask": {
                "taskId": _safe(request.task_id),
                "sourceRevision": request.builder_source_revision,
            },
        }
        self._require_public_safe(stable)
        stable_bytes = json.dumps(
            stable, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        proposal_id = "proposal-" + hashlib.sha256(stable_bytes).hexdigest()[:16]
        created_at = request.created_at or datetime.now(UTC)
        payload = {
            "schemaVersion": "finance-harness-asset-change-proposal.v1",
            "proposalId": proposal_id,
            "status": "proposed",
            **stable,
            "createdAt": created_at.isoformat().replace("+00:00", "Z"),
        }
        errors = sorted(
            Draft202012Validator(self.schema).iter_errors(payload),
            key=lambda item: list(item.path),
        )
        if errors:
            detail = "; ".join(item.message for item in errors)
            raise ValueError(f"asset.proposal.schema_invalid:{detail}")
        content = json.dumps(payload, ensure_ascii=False, indent=2) + "\n"
        directory = self.workspace_root / "proposals"
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"{proposal_id}.json"
        if path.exists():
            existing = json.loads(path.read_text(encoding="utf-8"))
            comparable = dict(existing)
            comparable.pop("createdAt", None)
            expected = dict(payload)
            expected.pop("createdAt", None)
            if comparable != expected:
                raise ValueError("asset.proposal.immutable_conflict")
            content = path.read_text(encoding="utf-8")
            return ProposalResult(
                proposal_id,
                path,
                hashlib.sha256(content.encode("utf-8")).hexdigest(),
                False,
            )
        path.write_text(content, encoding="utf-8")
        return ProposalResult(
            proposal_id,
            path,
            hashlib.sha256(content.encode("utf-8")).hexdigest(),
            True,
        )

    @staticmethod
    def _require_public_safe(value: Any) -> None:
        if isinstance(value, dict):
            for key, item in value.items():
                if key.lower() in {"prompt", "reasoning", "password", "secret", "token"}:
                    raise ValueError("asset.proposal.private_field")
                ChangeProposalWriter._require_public_safe(item)
        elif isinstance(value, list):
            for item in value:
                ChangeProposalWriter._require_public_safe(item)
        elif isinstance(value, str) and sanitize_public_text(value) != value:
            raise ValueError("asset.proposal.private_content")

class ChangeProposalGateway:
    """Bind Optimization external routing to immutable Proposal requests."""

    def __init__(
        self,
        writer: ChangeProposalWriter,
        requests_by_finding_id: dict[str, ChangeProposalRequest],
    ) -> None:
        self._writer = writer
        self._requests = dict(requests_by_finding_id)

    def write(
        self,
        request: OptimizationRequest,
        finding: EvaluationFinding,
        target: GovernedTarget,
    ) -> ResourceRef:
        proposal_request = self._requests.get(finding.finding_id)
        if proposal_request is None:
            raise ValueError("asset.proposal.context_missing")
        if proposal_request.finding != finding:
            raise ValueError("asset.proposal.finding_mismatch")
        if proposal_request.parent_lock_digest != request.baseline.lock_digest:
            raise ValueError("asset.proposal.parent_lock_mismatch")
        proposal_target = proposal_request.target
        if isinstance(proposal_target, CatalogAsset):
            expected_ref = (
                f"{proposal_target.kind.value}:{proposal_target.id}@{proposal_target.version}"
            )
            if proposal_request.catalog is None:
                raise ValueError("asset.proposal.catalog_required")
            owner = proposal_request.catalog.governance_policy(proposal_target)["owner"]
        else:
            expected_ref = (
                f"external-environment:{proposal_target.id}@{proposal_target.version}"
            )
            owner = proposal_target.owner
        if target.target_ref != expected_ref or target.owner_id != owner:
            raise ValueError("asset.proposal.governance_target_mismatch")
        if not set(proposal_request.proposed_scopes).issubset(target.allowed_actions):
            raise ValueError("asset.proposal.governance_scope_mismatch")
        result = self._writer.write(proposal_request)
        return ResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator=result.path.relative_to(self._writer.workspace_root).as_posix(),
            sha256=result.digest,
        )
