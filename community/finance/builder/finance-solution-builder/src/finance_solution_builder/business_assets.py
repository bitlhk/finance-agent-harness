"""Finite catalog projection and immutable method ancestry, never a grant or resolver."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import jsonschema
import yaml

from .experience_contracts import ApiError
from .assets import tree_digest
from .service import PROFILE


def catalog_bindings(harness, solution):
    """Check only the existing readonly recipe; do not relax AssetCatalog's other hosts."""
    path = harness / "catalog/finance-assets.v1.json"
    raw = path.read_bytes()
    catalog = json.loads(raw)
    schema = json.loads(
        (harness / "contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json").read_text(
            encoding="utf-8"
        )
    )
    jsonschema.Draft202012Validator(schema).validate(catalog)
    selected = {}
    for collection, refs in (
        ("profiles", [solution["profileRef"]]),
        ("capabilityPacks", solution["capabilityPackRefs"]),
        ("domainPacks", solution["domainPackRefs"]),
        ("providers", solution["providerRefs"]),
    ):
        for ref in refs:
            matches = [
                a
                for a in catalog[collection]
                if a["id"] == ref["id"] and a["version"] == ref["version"]
            ]
            if len(matches) != 1:
                raise ValueError("BUSINESS_ASSET_NOT_REGISTERED")
            asset = matches[0]
            profiles = asset.get("compatibility", {}).get("profiles", [])
            if profiles and PROFILE not in profiles:
                raise ValueError("BUSINESS_PROFILE_INCOMPATIBLE")
            if collection != "providers" and asset["source"] != ref["source"]:
                raise ValueError("BUSINESS_ASSET_SOURCE_CHANGED")
            selected[(collection, ref["id"])] = asset
    installed = set()
    for ref in solution["capabilityPackRefs"]:
        asset = selected[("capabilityPacks", ref["id"])]
        file = (harness / ref["source"].removeprefix("repo://") / "pack.yaml").resolve()
        if not file.is_relative_to(harness.resolve()):
            raise ValueError("BUSINESS_ASSET_OUTSIDE_ROOT")
        pack = yaml.safe_load(file.read_text(encoding="utf-8"))
        if pack["packId"] != ref["id"] or pack["version"] != ref["version"]:
            raise ValueError("BUSINESS_ASSET_VERSION_CHANGED")
        actual = [c["capabilityId"] for c in pack["capabilities"]]
        if set(actual) != set(asset["capabilities"]) or len(actual) != len(set(actual)):
            raise ValueError("BUSINESS_CAPABILITY_MISMATCH")
        if installed & set(actual) or any(c["operation"] != "QUERY" for c in pack["capabilities"]):
            raise ValueError("BUSINESS_READONLY_COMPOSITION_INVALID")
        installed.update(actual)
    if installed != set(solution["capabilityBindings"]):
        raise ValueError("BUSINESS_BINDINGS_INCOMPLETE")
    for cap, provider in solution["capabilityBindings"].items():
        if cap not in selected[("providers", provider)].get("capabilities", []):
            raise ValueError("BUSINESS_PROVIDER_CAPABILITY_MISSING")
    for ref in solution["domainPackRefs"]:
        asset = selected[("domainPacks", ref["id"])]
        root = (harness / ref["source"].removeprefix("repo://")).resolve()
        if not root.is_relative_to(harness.resolve()):
            raise ValueError("BUSINESS_ASSET_OUTSIDE_ROOT")
        manifest = yaml.safe_load((root / "pack.yaml").read_text(encoding="utf-8"))
        if manifest["packId"] != ref["id"] or manifest["version"] != ref["version"]:
            raise ValueError("BUSINESS_METHOD_VERSION_CHANGED")
        if not set(asset.get("requiresCapabilities", [])) <= installed:
            raise ValueError("BUSINESS_METHOD_DEPENDENCY_MISSING")
        for dep in asset.get("dependencies", []):
            if dep["kind"] != "capability-pack" or not any(
                p["id"] == dep["id"] and p["version"] == dep["version"]
                for p in solution["capabilityPackRefs"]
            ):
                raise ValueError("BUSINESS_METHOD_DEPENDENCY_INCOMPATIBLE")
    return {
        "version": catalog["catalogVersion"],
        "revision": catalog["catalogRevision"],
        "digest": "sha256:" + hashlib.sha256(raw).hexdigest(),
    }


def _ref(harness, ref, kind):
    path = (harness / ref["source"].removeprefix("repo://")).resolve()
    if not path.is_relative_to(harness.resolve()) or not path.is_dir():
        raise ApiError("ASSET_BASE_UNAVAILABLE", 409)
    if any(p.is_symlink() for p in path.rglob("*")):
        raise ApiError("ASSET_BASE_UNAVAILABLE", 409)
    return {**ref, "kind": kind, "digest": tree_digest(path)}


def ancestry(project: Path, harness: Path, component_id, revision):
    from .directory import describe_directory

    directory = describe_directory(harness)
    if revision != directory["revision"]:
        from .unified_catalog import unified_catalog
        directory = unified_catalog(project, harness)["directory"]
    if revision != directory["revision"]:
        raise ApiError("DIRECTORY_CHANGED", 409)
    part = next((p for p in directory["components"] if p["id"] == component_id), None)
    if not part or part["status"] != "AVAILABLE":
        raise ApiError("ASSET_BASE_UNAVAILABLE", 409)
    bases = [
        _ref(harness, {k: ref[k] for k in ("id", "version", "source")}, "capability-pack")
        for ref in part["capabilityPackRefs"]
    ]
    copied = [_ref(harness, ref, "domain-pack") for ref in part["domainPackRefs"]]
    texts = []
    if component_id in {"account.balance.query", "wealth.facts.describe"}:
        name = (
            "account-balance.md" if component_id == "account.balance.query" else "wealth-facts.md"
        )
        raw = (project / "templates/local-readonly-service/methods" / name).read_bytes()
        catalog = json.loads(
            (project / "templates/local-readonly-service/methods/catalog.json").read_text(
                encoding="utf-8"
            )
        )
        method = next(
            m for m in catalog["methods"] if m["capabilityId"] == component_id and m["file"] == name
        )
        copied.append(
            {
                "kind": "host-method",
                "id": name.removesuffix(".md"),
                "version": method.get("version", "content-addressed"),
                "source": "builder-template://methods/" + name,
                "digest": "sha256:" + hashlib.sha256(raw).hexdigest(),
            }
        )
        texts.append(raw.decode())
    for ref in part["domainPackRefs"]:
        root = harness / ref["source"].removeprefix("repo://")
        pack = yaml.safe_load((root / "pack.yaml").read_text(encoding="utf-8"))
        for skill in pack["skills"]:
            entry = (root / skill["entry"]).resolve()
            if not entry.is_relative_to(root.resolve()):
                raise ApiError("ASSET_BASE_UNAVAILABLE", 409)
            texts.append(entry.read_text(encoding="utf-8"))
    binding = {
        "schemaVersion": "finance-method-derivation.v0.1",
        "componentId": part["id"],
        "componentVersion": part["version"],
        "directoryRevision": revision,
        "capabilityIds": sorted(part["capabilityIds"]),
        "basePackages": bases,
        "copiedFrom": copied,
        "replacement": "REPLACE_SCOPED_METHOD_NOT_CAPABILITY",
    }
    validate_binding(harness, binding)
    return binding, "\n\n".join(texts)


def validate_binding(harness, binding):
    schema = json.loads(
        (
            harness / "contracts/json-schema/catalog/finance-method-derivation.v0.1.schema.json"
        ).read_text(encoding="utf-8")
    )
    try:
        jsonschema.Draft202012Validator(schema).validate(binding)
    except jsonschema.ValidationError:
        raise ApiError("ASSET_BASE_REFERENCE_INVALID", 409) from None


def development_support_for_binding(harness, binding, scope=None, *, records=()):
    """Resolve defaults from fixed packs, never from a component-name fallback."""
    from .development_support import load_subjects

    if scope is not None and not isinstance(scope, dict):
        raise ApiError("DEVELOPMENT_SUPPORT_INVALID", 409)
    selected = dict(scope) if scope is not None else {}
    capabilities = binding["capabilityIds"]
    if "capabilityIds" in selected and selected["capabilityIds"] != sorted(capabilities):
        raise ApiError("DEVELOPMENT_SUPPORT_SCOPE_MISMATCH", 409)
    selected["capabilityIds"] = sorted(capabilities)
    subjects = [ref for ref in binding["copiedFrom"] if ref["kind"] == "domain-pack"]
    if not subjects:
        subjects = binding["basePackages"]
    return load_subjects(harness, subjects, selected, records=records)


def component_development_support(project, harness, component_id, revision, *, scope=None, records=(), store=None, owner=None):
    """UI projection for the exact directory revision and current immutable pack refs."""
    binding, _ = ancestry(project, harness, component_id, revision)
    result = development_support_for_binding(harness, binding, scope, records=records)
    if store is not None:
        from .method_verification import owner_method_verifications
        reports, scan = owner_method_verifications(store, owner, component_id, result["subjects"], scope)
        result["derivedMethodVerifications"] = reports
        result["reportScan"] = scan
        if result["validation"]["status"] == "NOT_RUN":
            result["validation"]["reason"] = "目录组件本体尚无直接运行验证；派生方法报告单独列示"
    return result


def check_source(project, harness, value, *, package_anchor=None):
    source = value.get("source", {})
    if source.get("kind") == "BUSINESS_PACKAGE_IMPORTED":
        from .business_packages import dependency_status

        if package_anchor is None:
            from .business_packages import FINANCE_HARNESS_BUSINESS_PACKAGE_V01

            package_anchor = FINANCE_HARNESS_BUSINESS_PACKAGE_V01
        status = dependency_status(source.get("manifest", {}), harness, package_anchor)
        if status["status"] != "ADMISSIBLE":
            raise ApiError("ASSET_PACKAGE_DEPENDENCIES_UNAVAILABLE", 409)
        return
    if source.get("kind") != "BUSINESS_DERIVED":
        return
    binding = source.get("binding")
    validate_binding(harness, binding)
    expected, _ = ancestry(project, harness, binding["componentId"], binding["directoryRevision"])
    if binding != expected or sorted(value["data"]["capabilityIds"]) != binding["capabilityIds"]:
        raise ApiError("ASSET_BASE_CHANGED", 409)
