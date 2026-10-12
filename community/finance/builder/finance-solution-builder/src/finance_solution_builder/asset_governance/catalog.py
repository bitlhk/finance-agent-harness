"""Validate and resolve the Finance Harness asset catalog."""

from __future__ import annotations

import hashlib
import json
import subprocess
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Any, Iterable

from finance_builder_base import ResourceKind, ResourceRef
from finance_builder_base.contracts import sanitize_public_text
from jsonschema import Draft202012Validator

from finance_solution_builder.assets import HarnessArchiveAnchor, verify_archive_integrity
from finance_solution_builder.optimization.models import (
    ActionDisposition,
    CorrectionTargetKind,
    GovernedTarget,
)


class AssetKind(str, Enum):
    RUNTIME_INTEGRATION = "runtime-integration"
    PROFILE = "profile"
    DOMAIN_PACK = "domain-pack"
    CAPABILITY_PACK = "capability-pack"
    PROVIDER = "provider"
    EVAL_PACK = "eval-pack"


_COLLECTIONS = {
    "runtimeIntegrations": AssetKind.RUNTIME_INTEGRATION,
    "profiles": AssetKind.PROFILE,
    "domainPacks": AssetKind.DOMAIN_PACK,
    "capabilityPacks": AssetKind.CAPABILITY_PACK,
    "providers": AssetKind.PROVIDER,
    "evalPacks": AssetKind.EVAL_PACK,
}


@dataclass(frozen=True)
class CatalogAsset:
    kind: AssetKind
    id: str
    version: str
    display_name: str
    source: str
    governance_ref: str
    capabilities: tuple[str, ...] = ()
    requires_capabilities: tuple[str, ...] = ()
    dependencies: tuple[tuple[AssetKind, str, str], ...] = ()
    compatibility: dict[str, tuple[str, ...]] | None = None

    @property
    def identity(self) -> tuple[AssetKind, str, str]:
        return self.kind, self.id, self.version

    def reference(self) -> dict[str, str]:
        return {"id": self.id, "version": self.version, "source": self.source}


@dataclass(frozen=True)
class CatalogSnapshot:
    schema_version: str
    catalog_version: str
    catalog_revision: str
    harness_revision: str
    digest: str
    source: str = "catalog/finance-assets.v1.json"

    def lock_record(self) -> dict[str, str]:
        return {
            "schemaVersion": self.schema_version,
            "catalogVersion": self.catalog_version,
            "catalogRevision": self.catalog_revision,
            "harnessRevision": self.harness_revision,
            "source": self.source,
            "digest": self.digest,
        }


@dataclass(frozen=True)
class ClosureEntry:
    asset: CatalogAsset
    selected_by: tuple[str, ...]

    def lock_record(self) -> dict[str, Any]:
        return {
            "kind": self.asset.kind.value,
            "id": self.asset.id,
            "version": self.asset.version,
            "source": self.asset.source,
            "selectedBy": list(self.selected_by),
        }


@dataclass(frozen=True)
class AssetClosure:
    snapshot: CatalogSnapshot
    entries: tuple[ClosureEntry, ...]

    @property
    def assets(self) -> tuple[CatalogAsset, ...]:
        return tuple(item.asset for item in self.entries)

    def by_kind(self, kind: AssetKind) -> tuple[CatalogAsset, ...]:
        return tuple(item.asset for item in self.entries if item.asset.kind is kind)

    def installable_entries(self) -> tuple[ClosureEntry, ...]:
        return tuple(
            item
            for item in self.entries
            if item.asset.kind is not AssetKind.RUNTIME_INTEGRATION
            # External evaluation identities are governance-locked inputs. They
            # are not installed runtime content and therefore need no local or
            # network asset resolution during candidate assembly.
            and not (
                item.asset.kind is AssetKind.EVAL_PACK
                and not item.asset.source.startswith("repo://")
            )
        )

    def content_references(self) -> list[dict[str, str]]:
        return [item.asset.reference() for item in self.installable_entries()]

    def lock_records(self) -> list[dict[str, Any]]:
        return [item.lock_record() for item in self.entries]


class AssetCatalog:
    """A validated, immutable view of one Harness Catalog revision."""

    def __init__(
        self,
        snapshot: CatalogSnapshot,
        assets: Iterable[CatalogAsset],
        governance: dict[str, dict[str, Any]],
    ) -> None:
        self.snapshot = snapshot
        self._assets: dict[tuple[AssetKind, str, str], CatalogAsset] = {}
        for asset in assets:
            if asset.identity in self._assets:
                raise ValueError(f"asset.catalog.duplicate_identity:{asset.id}@{asset.version}")
            self._assets[asset.identity] = asset
        self._governance = governance
        self._validate_references()

    @classmethod
    def load(
        cls,
        harness_repo: Path,
        harness_revision: str,
        *,
        archive_anchor: HarnessArchiveAnchor | None = None,
    ) -> "AssetCatalog":
        if not harness_revision or len(harness_revision) < 7:
            raise ValueError("asset.catalog.harness_revision_required")
        catalog_path = harness_repo / "catalog/finance-assets.v1.json"
        schema_path = (
            harness_repo / "contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json"
        )
        cls._verify_repository_snapshot(
            harness_repo,
            harness_revision,
            catalog_path,
            schema_path,
            archive_anchor=archive_anchor,
        )
        document = json.loads(catalog_path.read_text(encoding="utf-8"))
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        errors = sorted(
            Draft202012Validator(schema).iter_errors(document),
            key=lambda item: list(item.path),
        )
        if errors:
            detail = "; ".join(
                f"{'/'.join(map(str, item.path)) or '<root>'}: {item.message}" for item in errors
            )
            raise ValueError(f"asset.catalog.schema_invalid:{detail}")
        cls._require_public_safe(document)
        assets: list[CatalogAsset] = []
        for collection, kind in _COLLECTIONS.items():
            for raw in document[collection]:
                compatibility = {
                    key: tuple(value) for key, value in raw.get("compatibility", {}).items()
                }
                dependencies = tuple(
                    (AssetKind(item["kind"]), item["id"], item["version"])
                    for item in raw.get("dependencies", [])
                )
                assets.append(
                    CatalogAsset(
                        kind=kind,
                        id=raw["id"],
                        version=raw["version"],
                        display_name=raw["displayName"],
                        source=raw["source"],
                        governance_ref=raw["governanceRef"],
                        capabilities=tuple(raw.get("capabilities", [])),
                        requires_capabilities=tuple(raw.get("requiresCapabilities", [])),
                        dependencies=dependencies,
                        compatibility=compatibility or None,
                    )
                )
        digest = "sha256:" + hashlib.sha256(catalog_path.read_bytes()).hexdigest()
        return cls(
            CatalogSnapshot(
                schema_version=document["schemaVersion"],
                catalog_version=document["catalogVersion"],
                catalog_revision=document["catalogRevision"],
                harness_revision=harness_revision,
                digest=digest,
            ),
            assets,
            document["governancePolicies"],
        )

    @staticmethod
    def _verify_repository_snapshot(
        harness_repo: Path,
        harness_revision: str,
        *contract_paths: Path,
        archive_anchor: HarnessArchiveAnchor | None = None,
    ) -> None:
        if not (harness_repo / ".git").exists():
            if archive_anchor is None:
                raise ValueError("asset.archive.external_anchor_required")
            if archive_anchor.revision != harness_revision:
                raise ValueError("asset.archive.anchor_revision_mismatch")
            verify_archive_integrity(harness_repo, anchor=archive_anchor)
            return
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=harness_repo,
            text=True,
            capture_output=True,
            check=True,
        ).stdout.strip()
        if head != harness_revision:
            raise ValueError("asset.catalog.harness_revision_mismatch")
        relative_paths = [str(path.relative_to(harness_repo)) for path in contract_paths]
        dirty = subprocess.run(
            ["git", "diff", "--quiet", "HEAD", "--", *relative_paths],
            cwd=harness_repo,
            check=False,
        )
        if dirty.returncode != 0:
            raise ValueError("asset.catalog.uncommitted_contract_drift")

    @staticmethod
    def _require_public_safe(value: Any) -> None:
        if isinstance(value, dict):
            for item in value.values():
                AssetCatalog._require_public_safe(item)
        elif isinstance(value, list):
            for item in value:
                AssetCatalog._require_public_safe(item)
        elif isinstance(value, str) and sanitize_public_text(value) != value:
            raise ValueError("asset.catalog.private_content")

    def _validate_references(self) -> None:
        for asset in self._assets.values():
            if asset.governance_ref not in self._governance:
                raise ValueError(f"asset.governance.missing:{asset.governance_ref}")
            for dependency in asset.dependencies:
                if dependency not in self._assets:
                    _, asset_id, version = dependency
                    raise ValueError(f"asset.dependency.missing:{asset_id}@{version}")

    def _match(self, kind: AssetKind, ref: dict[str, Any]) -> CatalogAsset:
        key = (kind, str(ref["id"]), str(ref["version"]))
        asset = self._assets.get(key)
        if asset is None:
            raise ValueError(f"asset.catalog.not_found:{kind.value}:{key[1]}@{key[2]}")
        # A Provider is selected from its governed repository entry, then emitted
        # into the component plan by its Maven artifact coordinate.  The latter is
        # validated by ComponentAssembly; accepting only that coordinate here keeps
        # the catalog closure tied to the governed repository source.
        is_provider_artifact = kind is AssetKind.PROVIDER and str(ref["source"]).startswith(
            "maven://"
        )
        if asset.source != ref["source"] and not is_provider_artifact:
            raise ValueError(f"asset.catalog.source_mismatch:{kind.value}:{asset.id}")
        return asset

    def resolve_solution(self, solution: dict[str, Any]) -> AssetClosure:
        integration = [
            item
            for item in self._assets.values()
            if item.kind is AssetKind.RUNTIME_INTEGRATION
            and item.source == solution["runtime"]["integrationRef"]
        ]
        if len(integration) != 1:
            raise ValueError("asset.catalog.runtime_integration_not_unique")
        seeds = [
            (integration[0], "solution.runtime.integrationRef"),
            (self._match(AssetKind.PROFILE, solution["profileRef"]), "solution.profileRef"),
            *(
                (self._match(AssetKind.DOMAIN_PACK, ref), "solution.domainPackRefs")
                for ref in solution["domainPackRefs"]
            ),
            *(
                (self._match(AssetKind.CAPABILITY_PACK, ref), "solution.capabilityPackRefs")
                for ref in solution.get("capabilityPackRefs", [])
            ),
            *(
                (self._match(AssetKind.PROVIDER, ref), "solution.providerRefs")
                for ref in solution["providerRefs"]
            ),
            (
                self._match(AssetKind.EVAL_PACK, solution["evaluationPackRef"]),
                "solution.evaluationPackRef",
            ),
        ]
        selected_by: dict[tuple[AssetKind, str, str], set[str]] = {}
        visiting: set[tuple[AssetKind, str, str]] = set()
        visited: set[tuple[AssetKind, str, str]] = set()

        def visit(asset: CatalogAsset, origin: str) -> None:
            selected_by.setdefault(asset.identity, set()).add(origin)
            if asset.identity in visiting:
                raise ValueError(f"asset.dependency.cycle:{asset.id}@{asset.version}")
            if asset.identity in visited:
                return
            visiting.add(asset.identity)
            for dependency in asset.dependencies:
                child = self._assets[dependency]
                visit(child, f"dependency:{asset.kind.value}:{asset.id}@{asset.version}")
            visiting.remove(asset.identity)
            visited.add(asset.identity)

        for seed, origin in seeds:
            visit(seed, origin)
        self._reject_version_conflicts(visited)
        entries = tuple(
            ClosureEntry(self._assets[key], tuple(sorted(selected_by[key])))
            for key in sorted(visited, key=lambda item: (item[0].value, item[1], item[2]))
        )
        closure = AssetClosure(self.snapshot, entries)
        self._validate_compatibility(solution, closure)
        return closure

    @staticmethod
    def _reject_version_conflicts(keys: set[tuple[AssetKind, str, str]]) -> None:
        versions: dict[tuple[AssetKind, str], set[str]] = {}
        for kind, asset_id, version in keys:
            versions.setdefault((kind, asset_id), set()).add(version)
        conflicts = [
            f"{kind.value}:{asset_id}"
            for (kind, asset_id), values in versions.items()
            if len(values) > 1
        ]
        if conflicts:
            raise ValueError("asset.dependency.version_conflict:" + ",".join(sorted(conflicts)))

    @staticmethod
    def _validate_compatibility(solution: dict[str, Any], closure: AssetClosure) -> None:
        profile = solution["profileRef"]["id"]
        runtime_family = solution["runtime"]["family"]
        runtime_language = solution["runtime"]["language"]
        for asset in closure.assets:
            compatibility = asset.compatibility or {}
            if compatibility.get("profiles") and profile not in compatibility["profiles"]:
                raise ValueError(f"asset.compatibility.profile:{asset.kind.value}:{asset.id}")
            if (
                compatibility.get("runtimeFamilies")
                and runtime_family not in compatibility["runtimeFamilies"]
            ):
                raise ValueError(f"asset.compatibility.runtime_family:{asset.id}")
            if (
                compatibility.get("runtimeLanguages")
                and runtime_language not in compatibility["runtimeLanguages"]
            ):
                raise ValueError(f"asset.compatibility.runtime_language:{asset.id}")
        providers = closure.by_kind(AssetKind.PROVIDER)
        if len(providers) > 1:
            raise ValueError("asset.compatibility.multiple_providers_unsupported")
        required = {
            capability
            for item in closure.by_kind(AssetKind.DOMAIN_PACK)
            for capability in item.requires_capabilities
        }
        provider_capabilities = {
            capability for item in providers for capability in item.capabilities
        }
        if not required.issubset(provider_capabilities):
            missing = ",".join(sorted(required - provider_capabilities))
            raise ValueError(f"asset.compatibility.provider_capability_missing:{missing}")
        capability_packs = closure.by_kind(AssetKind.CAPABILITY_PACK)
        pack_capabilities = {
            capability for item in capability_packs for capability in item.capabilities
        }
        domains_with_pack_dependencies = [
            item
            for item in closure.by_kind(AssetKind.DOMAIN_PACK)
            if any(dep[0] is AssetKind.CAPABILITY_PACK for dep in item.dependencies)
        ]
        required_from_packs = {
            capability
            for item in domains_with_pack_dependencies
            for capability in item.requires_capabilities
        }
        if not required_from_packs.issubset(pack_capabilities):
            missing = ",".join(sorted(required_from_packs - pack_capabilities))
            raise ValueError(f"asset.compatibility.capability_pack_missing:{missing}")

    def governance_policy(self, asset: CatalogAsset) -> dict[str, Any]:
        return dict(self._governance[asset.governance_ref])

    def governed_target(
        self,
        asset: CatalogAsset,
        *,
        responsibility: str,
        priority: int = 50,
    ) -> GovernedTarget:
        policy = self.governance_policy(asset)
        return GovernedTarget(
            responsibility=responsibility,
            target_kind=CorrectionTargetKind.HARNESS_ASSET,
            target_ref=f"{asset.kind.value}:{asset.id}@{asset.version}",
            owner_id=policy["owner"],
            allowed_actions=tuple(policy["allowedChangeScopes"]),
            approval_policy=ActionDisposition.EXTERNAL_BLOCKED,
            validation_refs=(
                ResourceRef(
                    kind=ResourceKind.WORKSPACE_PATH,
                    locator="catalog/finance-assets.v1.json",
                    sha256=self.snapshot.digest.removeprefix("sha256:"),
                ),
            ),
            priority=priority,
        )

    def find(self, kind: AssetKind, asset_id: str, version: str) -> CatalogAsset:
        try:
            return self._assets[(kind, asset_id, version)]
        except KeyError as exc:
            raise ValueError(f"asset.catalog.not_found:{kind.value}:{asset_id}@{version}") from exc
