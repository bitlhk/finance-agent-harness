from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace

import pytest
from finance_builder_base import ResourceKind

from finance_solution_builder.asset_governance import (
    AssetCatalog,
    AssetKind,
    ChangeProposalGateway,
    ChangeProposalRequest,
    ChangeProposalWriter,
    ExternalProposalTarget,
)
from finance_solution_builder.candidate_backend import CandidateResourceRef
from finance_solution_builder.evaluation import EvaluationFinding, FindingResponsibility

from .test_catalog import _document, _load_catalog, _write_harness


def _schema(path: Path) -> Path:
    schema = {
        "type": "object",
        "required": [
            "schemaVersion",
            "proposalId",
            "status",
            "target",
            "parentLock",
            "finding",
            "reproduction",
            "proposedChange",
            "verification",
            "builderTask",
            "createdAt",
        ],
    }
    path.write_text(json.dumps(schema), encoding="utf-8")
    return path


def _request(catalog: AssetCatalog) -> ChangeProposalRequest:
    finding = EvaluationFinding(
        finding_id="finding-001",
        responsibility=FindingResponsibility.SOLUTION_ASSETS,
        sample_ids=("sample-001",),
        summary="The selected domain asset missed a required business condition.",
        evidence_refs=(
            CandidateResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator="evaluations/report.json",
                sha256="1" * 64,
            ),
        ),
    )
    return ChangeProposalRequest(
        catalog=catalog,
        target=catalog.find(AssetKind.DOMAIN_PACK, "wealth-domain", "1.0"),
        target_digest="sha256:" + "2" * 64,
        solution_id="wealth-demo",
        parent_lock_digest="3" * 64,
        finding=finding,
        reproduction_conditions=("Run the frozen sample set with the parent Lock.",),
        proposed_summary="Review the domain implementation and add a regression test.",
        proposed_scopes=("implementation", "tests"),
        prior_result_refs=(),
        task_id="task-001",
        builder_source_revision="a" * 40,
        created_at=datetime(2026, 9, 10, tzinfo=UTC),
    )


def test_writes_immutable_idempotent_proposal(tmp_path: Path) -> None:
    harness = _write_harness(tmp_path, _document())
    catalog = _load_catalog(harness)
    writer = ChangeProposalWriter(tmp_path / "workspace", _schema(tmp_path / "schema.json"))

    first = writer.write(_request(catalog))
    second = writer.write(_request(catalog))

    assert first.created is True
    assert second.created is False
    assert first.proposal_id == second.proposal_id
    payload = json.loads(first.path.read_text(encoding="utf-8"))
    assert payload["catalog"] == catalog.snapshot.lock_record()
    assert payload["target"]["owner"] == "harness-maintainers"
    assert payload["status"] == "proposed"


def test_rejects_unapproved_scope_and_private_text(tmp_path: Path) -> None:
    harness = _write_harness(tmp_path, _document())
    catalog = _load_catalog(harness)
    writer = ChangeProposalWriter(tmp_path / "workspace", _schema(tmp_path / "schema.json"))
    request = _request(catalog)

    with pytest.raises(ValueError, match="scope_not_allowed"):
        writer.write(ChangeProposalRequest(**{**request.__dict__, "proposed_scopes": ("release",)}))
    with pytest.raises(ValueError, match="private_or_empty_text"):
        writer.write(
            ChangeProposalRequest(
                **{
                    **request.__dict__,
                    "proposed_summary": "Review C:\\customer\\private.json",
                }
            )
        )


def test_writes_explicit_external_environment_proposal(tmp_path: Path) -> None:
    harness = _write_harness(tmp_path, _document())
    catalog = _load_catalog(harness)
    writer = ChangeProposalWriter(tmp_path / "workspace", _schema(tmp_path / "schema.json"))
    base = _request(catalog)
    target = ExternalProposalTarget(
        id="sandbox-runtime",
        version="2026-09-10",
        source="environment://authorized-sandbox",
        governance_ref="sandbox-operations",
        owner="sandbox-operations",
        allowed_change_scopes=("configuration", "tests"),
        verification_entrypoint="run controlled sandbox smoke",
    )

    result = writer.write(
        ChangeProposalRequest(
            **{
                **base.__dict__,
                "catalog": None,
                "target": target,
                "target_digest": "sha256:" + "4" * 64,
                "proposed_scopes": ("configuration",),
            }
        )
    )

    payload = json.loads(result.path.read_text(encoding="utf-8"))
    assert payload["catalog"] is None
    assert payload["target"]["kind"] == "external-environment"
    assert payload["target"]["owner"] == "sandbox-operations"


def test_external_environment_requires_complete_governance(tmp_path: Path) -> None:
    harness = _write_harness(tmp_path, _document())
    catalog = _load_catalog(harness)
    writer = ChangeProposalWriter(tmp_path / "workspace", _schema(tmp_path / "schema.json"))
    base = _request(catalog)
    target = ExternalProposalTarget(
        id="sandbox-runtime",
        version="2026-09-10",
        source="environment://authorized-sandbox",
        governance_ref="sandbox-operations",
        owner="",
        allowed_change_scopes=("configuration",),
        verification_entrypoint="run controlled sandbox smoke",
    )

    with pytest.raises(ValueError, match="governance_incomplete"):
        writer.write(
            ChangeProposalRequest(
                **{
                    **base.__dict__,
                    "catalog": None,
                    "target": target,
                    "target_digest": "sha256:" + "4" * 64,
                    "proposed_scopes": ("configuration",),
                }
            )
        )


def test_gateway_binds_external_routing_to_parent_lock_and_proposal(tmp_path: Path) -> None:
    harness = _write_harness(tmp_path, _document())
    catalog = _load_catalog(harness)
    writer = ChangeProposalWriter(tmp_path / "workspace", _schema(tmp_path / "schema.json"))
    proposal_request = _request(catalog)
    target = catalog.governed_target(
        proposal_request.target,
        responsibility=proposal_request.finding.responsibility.value,
        priority=1,
    )
    gateway = ChangeProposalGateway(
        writer,
        {proposal_request.finding.finding_id: proposal_request},
    )

    reference = gateway.write(
        SimpleNamespace(baseline=SimpleNamespace(lock_digest=proposal_request.parent_lock_digest)),
        proposal_request.finding,
        target,
    )

    assert reference.kind is ResourceKind.WORKSPACE_PATH
    assert reference.locator.startswith("proposals/proposal-")
    assert (writer.workspace_root / reference.locator).is_file()
