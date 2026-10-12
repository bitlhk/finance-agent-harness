from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest

from finance_solution_builder.asset_governance import AssetCatalog, AssetKind
from finance_solution_builder.assets import (
    HarnessArchiveAnchor,
    archive_tree_digest,
    write_archive_integrity,
)

ARCHIVE_SOURCE = "https://github.com/example/finance-agent-harness.git"
ARCHIVE_REVISION = "a" * 40


def _archive_anchor(harness: Path) -> HarnessArchiveAnchor:
    return HarnessArchiveAnchor(
        source=ARCHIVE_SOURCE,
        revision=ARCHIVE_REVISION,
        tree_digest=archive_tree_digest(harness),
    )


def _load_catalog(
    harness: Path,
    revision: str = ARCHIVE_REVISION,
    *,
    anchor: HarnessArchiveAnchor | None = None,
) -> AssetCatalog:
    return AssetCatalog.load(
        harness,
        revision,
        archive_anchor=anchor or _archive_anchor(harness),
    )


def _write_harness(tmp_path: Path, document: dict) -> Path:
    harness = tmp_path / "harness"
    catalog = harness / "catalog/finance-assets.v1.json"
    schema = harness / "contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json"
    catalog.parent.mkdir(parents=True)
    schema.parent.mkdir(parents=True)
    catalog.write_text(json.dumps(document), encoding="utf-8")
    schema.write_text(
        json.dumps(
            {
                "type": "object",
                "required": [
                    "schemaVersion",
                    "catalogVersion",
                    "catalogRevision",
                    "governancePolicies",
                    *[
                        "runtimeIntegrations",
                        "profiles",
                        "domainPacks",
                        "capabilityPacks",
                        "providers",
                        "evalPacks",
                    ],
                ],
            }
        ),
        encoding="utf-8",
    )
    write_archive_integrity(
        harness,
        revision="a" * 40,
        source="https://github.com/example/finance-agent-harness.git",
    )
    return harness


def _asset(asset_id: str, source: str, **extra) -> dict:
    return {
        "id": asset_id,
        "version": "1.0",
        "displayName": asset_id,
        "source": source,
        "governanceRef": "owner",
        **extra,
    }


def _document() -> dict:
    return {
        "schemaVersion": "finance-asset-catalog.v1",
        "catalogVersion": "1.0",
        "catalogRevision": "r1",
        "governancePolicies": {
            "owner": {
                "owner": "harness-maintainers",
                "repository": "https://example.invalid/harness",
                "changeMode": "proposal-only",
                "allowedChangeScopes": ["implementation", "tests"],
                "approvalPolicy": "owner-review",
                "verification": {"kind": "command", "value": "mvn test"},
            }
        },
        "runtimeIntegrations": [
            _asset(
                "openjiuwen-java",
                "repo://integrations/openjiuwen-java",
                compatibility={"runtimeFamilies": ["openjiuwen"], "runtimeLanguages": ["java"]},
            )
        ],
        "profiles": [_asset("employee-assisted", "repo://profiles")],
        "domainPacks": [
            _asset(
                "wealth-domain",
                "repo://domain-packs/wealth",
                requiresCapabilities=["wealth.context.query"],
                dependencies=[
                    {"kind": "capability-pack", "id": "wealth-capabilities", "version": "1.0"}
                ],
                compatibility={"profiles": ["employee-assisted"]},
            )
        ],
        "capabilityPacks": [
            _asset(
                "wealth-capabilities",
                "repo://capability-packs/wealth",
                capabilities=["wealth.context.query"],
            )
        ],
        "providers": [
            _asset(
                "bank-provider",
                "repo://providers/bank",
                capabilities=["wealth.context.query"],
            )
        ],
        "evalPacks": [_asset("wealth-eval", "repo://eval/wealth")],
    }


def _solution() -> dict:
    return {
        "runtime": {
            "family": "openjiuwen",
            "language": "java",
            "integrationRef": "repo://integrations/openjiuwen-java",
        },
        "profileRef": {"id": "employee-assisted", "version": "1.0", "source": "repo://profiles"},
        "domainPackRefs": [
            {"id": "wealth-domain", "version": "1.0", "source": "repo://domain-packs/wealth"}
        ],
        "providerRefs": [
            {"id": "bank-provider", "version": "1.0", "source": "repo://providers/bank"}
        ],
        "evaluationPackRef": {
            "id": "wealth-eval",
            "version": "1.0",
            "source": "repo://eval/wealth",
        },
    }


def test_resolves_transitive_capability_pack_once(tmp_path: Path) -> None:
    catalog = _load_catalog(_write_harness(tmp_path, _document()))
    solution = _solution()
    solution["capabilityPackRefs"] = [
        {
            "id": "wealth-capabilities",
            "version": "1.0",
            "source": "repo://capability-packs/wealth",
        }
    ]

    closure = catalog.resolve_solution(solution)

    capabilities = closure.by_kind(AssetKind.CAPABILITY_PACK)
    assert [item.id for item in capabilities] == ["wealth-capabilities"]
    record = next(item for item in closure.entries if item.asset is capabilities[0])
    assert record.selected_by == (
        "dependency:domain-pack:wealth-domain@1.0",
        "solution.capabilityPackRefs",
    )


def test_resolves_provider_artifact_reference_to_its_catalog_asset(tmp_path: Path) -> None:
    """Providers are assembled by Maven coordinate but governed from their repo source."""
    catalog = _load_catalog(_write_harness(tmp_path, _document()))
    solution = _solution()
    solution["providerRefs"][0]["source"] = "maven://example:bank-provider:1.0"

    closure = catalog.resolve_solution(solution)

    providers = closure.by_kind(AssetKind.PROVIDER)
    assert [provider.reference() for provider in providers] == [
        {"id": "bank-provider", "version": "1.0", "source": "repo://providers/bank"}
    ]


def test_rejects_provider_reference_with_an_unregistered_repo_source(tmp_path: Path) -> None:
    catalog = _load_catalog(_write_harness(tmp_path, _document()))
    solution = _solution()
    solution["providerRefs"][0]["source"] = "repo://providers/unregistered"

    with pytest.raises(ValueError, match="asset.catalog.source_mismatch:provider:bank-provider"):
        catalog.resolve_solution(solution)


def test_external_evaluation_pack_is_catalog_locked_but_not_an_installable_asset(tmp_path: Path) -> None:
    document = _document()
    document["evalPacks"][0]["source"] = "git+https://example.invalid/eval.git@abc1234#packs/audit"
    catalog = _load_catalog(_write_harness(tmp_path, document))
    solution = _solution()
    solution["evaluationPackRef"]["source"] = document["evalPacks"][0]["source"]

    closure = catalog.resolve_solution(solution)

    assert "wealth-eval" not in [ref["id"] for ref in closure.content_references()]
    assert any(record["id"] == "wealth-eval" for record in closure.lock_records())


@pytest.mark.parametrize(
    ("mutation", "error"),
    [
        (
            lambda document: document["domainPacks"][0].update(
                dependencies=[{"kind": "capability-pack", "id": "missing", "version": "1.0"}]
            ),
            "asset.dependency.missing",
        ),
        (
            lambda document: document["providers"][0].update(capabilities=[]),
            "asset.compatibility.provider_capability_missing",
        ),
    ],
)
def test_catalog_fails_closed(tmp_path: Path, mutation, error: str) -> None:
    document = _document()
    mutation(document)
    harness = _write_harness(tmp_path, document)

    if error == "asset.dependency.missing":
        with pytest.raises(ValueError, match=error):
            _load_catalog(harness)
    else:
        catalog = _load_catalog(harness)
        with pytest.raises(ValueError, match=error):
            catalog.resolve_solution(_solution())


def test_governance_projection_is_external_and_owner_bound(tmp_path: Path) -> None:
    catalog = _load_catalog(_write_harness(tmp_path, _document()))
    asset = catalog.find(AssetKind.DOMAIN_PACK, "wealth-domain", "1.0")

    target = catalog.governed_target(asset, responsibility="solution-assets")

    assert target.target_kind.value == "harness-asset"
    assert target.approval_policy.value == "external-blocked"
    assert target.owner_id == "harness-maintainers"


def test_rejects_dependency_cycle(tmp_path: Path) -> None:
    document = _document()
    document["capabilityPacks"][0]["dependencies"] = [
        {"kind": "domain-pack", "id": "wealth-domain", "version": "1.0"}
    ]
    catalog = _load_catalog(_write_harness(tmp_path, document))

    with pytest.raises(ValueError, match="asset.dependency.cycle"):
        catalog.resolve_solution(_solution())


def test_rejects_multiple_provider_backend_before_assembly(tmp_path: Path) -> None:
    document = _document()
    document["providers"].append(
        _asset(
            "second-provider",
            "repo://providers/second",
            capabilities=["wealth.context.query"],
        )
    )
    catalog = _load_catalog(_write_harness(tmp_path, document))
    solution = _solution()
    solution["providerRefs"].append(
        {"id": "second-provider", "version": "1.0", "source": "repo://providers/second"}
    )

    with pytest.raises(ValueError, match="multiple_providers_unsupported"):
        catalog.resolve_solution(solution)


def test_rejects_uncommitted_catalog_drift(tmp_path: Path) -> None:
    harness = _write_harness(tmp_path, _document())
    subprocess.run(["git", "init", "-q"], cwd=harness, check=True)
    subprocess.run(["git", "config", "user.name", "Fixture"], cwd=harness, check=True)
    subprocess.run(
        ["git", "config", "user.email", "fixture@example.invalid"],
        cwd=harness,
        check=True,
    )
    subprocess.run(["git", "add", "."], cwd=harness, check=True)
    subprocess.run(["git", "commit", "-qm", "fixture"], cwd=harness, check=True)
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=harness,
        text=True,
        capture_output=True,
        check=True,
    ).stdout.strip()
    path = harness / "catalog/finance-assets.v1.json"
    path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")

    with pytest.raises(ValueError, match="uncommitted_contract_drift"):
        AssetCatalog.load(harness, revision)


def test_rejects_archive_revision_mismatch(tmp_path: Path) -> None:
    harness = _write_harness(tmp_path, _document())

    with pytest.raises(ValueError, match="asset.archive.anchor_revision_mismatch"):
        _load_catalog(harness, "b" * 40)


def test_non_git_archive_requires_external_anchor(tmp_path: Path) -> None:
    harness = _write_harness(tmp_path, _document())

    with pytest.raises(ValueError, match="asset.archive.external_anchor_required"):
        AssetCatalog.load(harness, ARCHIVE_REVISION)


def test_rejects_archive_that_rewrites_content_and_self_metadata(tmp_path: Path) -> None:
    harness = _write_harness(tmp_path, _document())
    trusted_anchor = _archive_anchor(harness)
    path = harness / "catalog/finance-assets.v1.json"
    path.write_text(path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
    write_archive_integrity(
        harness,
        revision=ARCHIVE_REVISION,
        source=ARCHIVE_SOURCE,
    )

    with pytest.raises(ValueError, match="asset.archive.anchor_digest_mismatch"):
        _load_catalog(harness, anchor=trusted_anchor)
