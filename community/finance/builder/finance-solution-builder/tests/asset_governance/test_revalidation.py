import hashlib
import json
from copy import deepcopy

import pytest

from finance_solution_builder.asset_governance import bind_revalidation
from finance_solution_builder.optimization import OptimizationSnapshot


def _snapshot(**updates) -> OptimizationSnapshot:
    values = {
        "candidate_id": "candidate-1",
        "descriptor_digest": "1" * 64,
        "lock_digest": "2" * 64,
        "artifact_digest": "3" * 64,
        "evaluation_pack_digest": "4" * 64,
        "dataset_digest": "5" * 64,
        "rules_digest": "6" * 64,
        "runtime_digest": "7" * 64,
        "evidence_level": "sandbox",
    }
    values.update(updates)
    return OptimizationSnapshot(**values)


def _lock(*, catalog_digest: str, target_version: str, target_digest: str) -> dict:
    runtime_source = "git+https://github.com/example/runtime.git@rev#integration"
    target_source = (
        f"git+https://github.com/example/domain.git@{target_version}#packs/wealth"
    )
    provider_source = "repo://providers/reference"
    return {
        "solutionId": "wealth-solution",
        "assetCatalog": {"digest": catalog_digest},
        "runtimeIntegration": {
            "id": "runtime-integration",
            "version": "pinned",
            "source": runtime_source,
            "status": "resolved",
            "digest": "sha256:runtime",
        },
        "assets": [
            {
                "id": "wealth-domain",
                "version": target_version,
                "source": target_source,
                "status": "resolved",
                "digest": target_digest,
            },
            {
                "id": "reference-provider",
                "version": "1.0",
                "source": provider_source,
                "status": "resolved",
                "digest": "sha256:provider",
            },
        ],
        "assetClosure": [
            {
                "kind": "runtime-integration",
                "id": "openjiuwen-java",
                "version": "1.0",
                "source": runtime_source,
                "selectedBy": ["solution.runtime.integrationRef"],
            },
            {
                "kind": "domain-pack",
                "id": "wealth-domain",
                "version": target_version,
                "source": target_source,
                "selectedBy": ["solution.domainPackRefs"],
            },
            {
                "kind": "provider",
                "id": "reference-provider",
                "version": "1.0",
                "source": provider_source,
                "selectedBy": ["solution.providerRefs"],
            },
        ],
    }


def _lock_digest(lock: dict) -> str:
    payload = (json.dumps(lock, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _proposal(parent_lock: dict) -> dict:
    target = parent_lock["assets"][0]
    return {
        "finding": {"id": "finding-1"},
        "parentLock": {
            "solutionId": parent_lock["solutionId"],
            "lockDigest": _lock_digest(parent_lock),
        },
        "catalog": {"digest": parent_lock["assetCatalog"]["digest"]},
        "target": {
            "kind": "domain-pack",
            "id": target["id"],
            "version": target["version"],
            "source": target["source"],
            "digest": target["digest"],
        },
    }


def _binding(
    *,
    proposal: dict | None = None,
    parent: OptimizationSnapshot | None = None,
    candidate: OptimizationSnapshot | None = None,
    parent_lock: dict | None = None,
    candidate_lock: dict | None = None,
):
    old_lock = parent_lock or _lock(
        catalog_digest="sha256:catalog-v1",
        target_version="1.0",
        target_digest="sha256:domain-v1",
    )
    new_lock = candidate_lock or _lock(
        catalog_digest="sha256:catalog-v2",
        target_version="1.1",
        target_digest="sha256:domain-v2",
    )
    return bind_revalidation(
        proposal or _proposal(old_lock),
        parent or _snapshot(lock_digest=_lock_digest(old_lock)),
        candidate
        or _snapshot(candidate_id="candidate-2", lock_digest=_lock_digest(new_lock)),
        parent_lock=old_lock,
        candidate_lock=new_lock,
    )


def test_target_asset_revision_is_comparable_only_under_frozen_conditions() -> None:
    assert _binding().comparable is True

    candidate_lock = _lock(
        catalog_digest="sha256:catalog-v2",
        target_version="1.1",
        target_digest="sha256:domain-v2",
    )
    changed_data = _binding(
        candidate=_snapshot(
            candidate_id="candidate-2",
            lock_digest=_lock_digest(candidate_lock),
            dataset_digest="9" * 64,
        ),
        candidate_lock=candidate_lock,
    )
    assert changed_data.comparable is False
    assert "data, rules, runtime" in changed_data.reason


def test_unchanged_lock_cannot_claim_published_asset_revalidation() -> None:
    unchanged_lock = _lock(
        catalog_digest="sha256:catalog-v1",
        target_version="1.0",
        target_digest="sha256:domain-v1",
    )
    result = _binding(
        candidate=_snapshot(
            candidate_id="candidate-2",
            lock_digest=_lock_digest(unchanged_lock),
        ),
        candidate_lock=unchanged_lock,
    )

    assert result.comparable is False
    assert "new Lock" in result.reason


def test_unrelated_asset_change_cannot_claim_target_revalidation() -> None:
    parent_lock = _lock(
        catalog_digest="sha256:catalog-v1",
        target_version="1.0",
        target_digest="sha256:domain-v1",
    )
    candidate_lock = deepcopy(parent_lock)
    candidate_lock["assetCatalog"]["digest"] = "sha256:catalog-v2"
    candidate_lock["assets"][1]["digest"] = "sha256:provider-v2"

    result = _binding(parent_lock=parent_lock, candidate_lock=candidate_lock)

    assert result.comparable is False
    assert "proposed Harness asset did not change" in result.reason


def test_mixed_target_and_unrelated_changes_are_not_attributable() -> None:
    candidate_lock = _lock(
        catalog_digest="sha256:catalog-v2",
        target_version="1.1",
        target_digest="sha256:domain-v2",
    )
    candidate_lock["assets"][1]["digest"] = "sha256:provider-v2"

    result = _binding(candidate_lock=candidate_lock)

    assert result.comparable is False
    assert "outside the proposed target changed" in result.reason


@pytest.mark.parametrize(
    ("proposal_change", "reason"),
    [
        (("parentLock", "lockDigest", "9" * 64), "parent Lock"),
        (("catalog", "digest", "sha256:other"), "parent Catalog"),
        (("target", "digest", "sha256:other"), "parent asset closure"),
    ],
)
def test_proposal_must_bind_parent_lock_catalog_and_target(
    proposal_change: tuple[str, str, str],
    reason: str,
) -> None:
    parent_lock = _lock(
        catalog_digest="sha256:catalog-v1",
        target_version="1.0",
        target_digest="sha256:domain-v1",
    )
    proposal = _proposal(parent_lock)
    section, field, value = proposal_change
    proposal[section][field] = value

    result = _binding(proposal=proposal, parent_lock=parent_lock)

    assert result.comparable is False
    assert reason in result.reason


def test_target_change_requires_new_catalog_snapshot() -> None:
    candidate_lock = _lock(
        catalog_digest="sha256:catalog-v1",
        target_version="1.1",
        target_digest="sha256:domain-v2",
    )

    result = _binding(candidate_lock=candidate_lock)

    assert result.comparable is False
    assert "new Catalog" in result.reason

def test_lock_content_must_match_optimization_snapshot() -> None:
    result = _binding(
        candidate=_snapshot(candidate_id="candidate-2", lock_digest="8" * 64)
    )

    assert result.comparable is False
    assert "Optimization snapshot" in result.reason
