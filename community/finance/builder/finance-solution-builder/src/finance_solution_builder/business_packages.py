"""Controlled exchange packages for published business methods; never execute archive content."""

from __future__ import annotations

import base64
import hashlib
import json
import subprocess
import tempfile
import types
from dataclasses import dataclass
from pathlib import Path

import yaml

from .experience_contracts import ApiError
from .method_assets import canonical, parse_content, sha

MAX_UPLOAD = 512 * 1024
SUPPORTED_RUNTIME_FAMILIES = {"openjiuwen"}
SUPPORTED_RUNTIME_LANGUAGES = {"java"}
SUPPORTED_OBSERVATION = {
    "AUTHORIZATION",
    "MODEL_CALL",
    "TOOL_CALL",
    "PROVIDER_ATTEMPT",
    "BUSINESS_RESULT",
    "INSTALLATION_LOCK",
}
SUPPORTED_PROTOCOL = "finance-provider-spi.v1"
_PACKAGERS = {}


@dataclass(frozen=True)
class HarnessPackageAnchor:
    """Trusted identity of executable Finance Harness package code."""

    source: str
    revision: str
    packager_sha256: str
    schema_sha256: str

    def to_wire(self) -> dict[str, str]:
        return {
            "source": self.source,
            "revision": self.revision,
            "packagerSha256": self.packager_sha256,
            "schemaSha256": self.schema_sha256,
        }


FINANCE_HARNESS_BUSINESS_PACKAGE_V01 = HarnessPackageAnchor(
    source="https://github.com/bitlhk/finance-agent-harness",
    revision="564c9869a42464670a7eed4b802979a76790c1c4",
    packager_sha256="8eae31d01f7914e4ea8fc3ddb1b61b6df0196faffababdad814b2df3be33f0c3",
    schema_sha256="446de7e61c8313c377986ec0bf425a1eb508a45c3bb6c090cca0a566f538617d",
)


def _source_identity(value: str) -> str:
    normalized = value.strip().replace("\\", "/")
    if normalized.startswith("git@github.com:"):
        normalized = "https://github.com/" + normalized.removeprefix("git@github.com:")
    return normalized.removesuffix(".git").rstrip("/").lower()


def _git(harness: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(harness), *arguments],
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )
    if result.returncode != 0:
        raise ApiError("BUSINESS_PACKAGE_HARNESS_UNTRUSTED", 409)
    return result.stdout.strip()


def _repository_text_sha256(path: Path) -> str:
    """Hash text with Git's platform checkout line endings normalized."""

    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def verify_harness_package_anchor(
    harness: Path,
    anchor: HarnessPackageAnchor = FINANCE_HARNESS_BUSINESS_PACKAGE_V01,
) -> dict[str, str]:
    """Verify the external executable package boundary before loading it."""

    harness = harness.resolve()
    try:
        root = Path(_git(harness, "rev-parse", "--show-toplevel")).resolve()
        source = _git(harness, "remote", "get-url", "origin")
        revision = _git(harness, "rev-parse", "HEAD")
        dirty = _git(harness, "status", "--porcelain=v1", "--untracked-files=no")
    except (OSError, ValueError):
        raise ApiError("BUSINESS_PACKAGE_HARNESS_UNTRUSTED", 409) from None
    if (
        root != harness
        or _source_identity(source) != _source_identity(anchor.source)
        or revision != anchor.revision
        or dirty
    ):
        raise ApiError("BUSINESS_PACKAGE_HARNESS_UNTRUSTED", 409)

    paths = {
        "packagerSha256": harness / "engineering/business_package.py",
        "schemaSha256": (
            harness
            / "contracts/json-schema/package/finance-business-component-package.v0.1.schema.json"
        ),
    }
    expected = {
        "packagerSha256": anchor.packager_sha256,
        "schemaSha256": anchor.schema_sha256,
    }
    for name, path in paths.items():
        if (
            not path.is_file()
            or path.is_symlink()
            or not path.resolve().is_relative_to(harness)
            or _repository_text_sha256(path) != expected[name]
        ):
            raise ApiError("BUSINESS_PACKAGE_HARNESS_UNTRUSTED", 409)
    return anchor.to_wire()


def _packager(
    harness: Path,
    anchor: HarnessPackageAnchor = FINANCE_HARNESS_BUSINESS_PACKAGE_V01,
):
    verify_harness_package_anchor(harness, anchor)
    path = (harness / "engineering/business_package.py").resolve()
    if not path.is_relative_to(harness.resolve()) or not path.is_file() or path.is_symlink():
        raise ApiError("BUSINESS_PACKAGE_PACKAGER_UNAVAILABLE", 409)
    source = path.read_bytes()
    key = (str(path), anchor.revision, _repository_text_sha256(path))
    if key not in _PACKAGERS:
        name = "finance_harness_business_package_" + sha(source)[7:23]
        module = types.ModuleType(name)
        module.__file__ = str(path)
        try:
            exec(compile(source, str(path), "exec"), module.__dict__)
        except Exception:
            raise ApiError("BUSINESS_PACKAGE_PACKAGER_INCOMPATIBLE", 409) from None
        if not all(
            hasattr(module, name) for name in ("PackageError", "build", "read", "safe_name")
        ):
            raise ApiError("BUSINESS_PACKAGE_PACKAGER_INCOMPATIBLE", 409)
        _PACKAGERS.clear()
        _PACKAGERS[key] = module
    return _PACKAGERS[key]


def _packager_error(error):
    code = str(error).split(":", 1)[0]
    return "BUSINESS_" + code if code.startswith("PACKAGE_") else "BUSINESS_PACKAGE_INVALID"


def inspect_archive(
    raw,
    harness,
    anchor: HarnessPackageAnchor = FINANCE_HARNESS_BUSINESS_PACKAGE_V01,
):
    if not isinstance(raw, bytes) or len(raw) > MAX_UPLOAD:
        raise ApiError("BUSINESS_PACKAGE_UPLOAD_INVALID", 422)
    packager = _packager(harness, anchor)
    with tempfile.TemporaryDirectory(prefix="finance-package-inspect-") as directory:
        package = Path(directory) / "component.zip"
        package.write_bytes(raw)
        try:
            manifest, files, _ = packager.read(package)
        except packager.PackageError as error:
            raise ApiError(_packager_error(error), 422) from None
    return manifest, files


def _build_archive(
    source,
    files,
    harness,
    anchor: HarnessPackageAnchor = FINANCE_HARNESS_BUSINESS_PACKAGE_V01,
):
    packager = _packager(harness, anchor)
    with tempfile.TemporaryDirectory(prefix="finance-package-build-") as directory:
        root = Path(directory) / "source"
        root.mkdir()
        try:
            for name, raw in files.items():
                packager.safe_name(name)
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(raw)
            # Harness V0.1 reads this file with the platform default encoding.
            # ASCII escapes keep the fixed implementation portable without changing
            # the external package contract or mutating the Harness checkout.
            package_source = json.dumps(
                {**source, "files": sorted(files)},
                ensure_ascii=True,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("ascii")
            (root / "package-source.json").write_bytes(package_source)
            output = Path(directory) / "component.zip"
            result = packager.build(root, output)
        except packager.PackageError as error:
            raise ApiError(_packager_error(error), 422) from None
        return output.read_bytes(), result


def _catalog_binding(harness, capability_id, preferred=None):
    catalog = json.loads((harness / "catalog/finance-assets.v1.json").read_text(encoding="utf-8"))
    matches = [
        item for item in catalog["capabilityPacks"] if capability_id in item.get("capabilities", [])
    ]
    if preferred:
        matches = [item for item in matches if (item["id"], item["version"]) == preferred]
    if len(matches) != 1:
        raise ApiError("BUSINESS_PACKAGE_CAPABILITY_AMBIGUOUS", 409)
    asset = matches[0]
    pack = yaml.safe_load(
        (harness / asset["source"].removeprefix("repo://") / "pack.yaml").read_text(
            encoding="utf-8"
        )
    )
    definition = next(
        item for item in pack["capabilities"] if item["capabilityId"] == capability_id
    )
    return asset, definition


def dependency_status(
    manifest,
    harness,
    anchor: HarnessPackageAnchor = FINANCE_HARNESS_BUSINESS_PACKAGE_V01,
):
    verify_harness_package_anchor(harness, anchor)
    missing, mismatched, requirement_mismatches = [], [], []
    for binding in manifest["bindings"]:
        ref = binding["capabilityPack"]
        try:
            asset, definition = _catalog_binding(
                harness, binding["capabilityId"], (ref["id"], ref["version"])
            )
            expected = {
                "toolName": definition["toolName"],
                "operation": definition["operation"],
                "protocol": SUPPORTED_PROTOCOL,
                "inputSchemaRef": "capability-pack://"
                + asset["id"]
                + "@"
                + asset["version"]
                + "/"
                + binding["capabilityId"]
                + "/input",
                "outputSchemaRef": "schema://capability-result.v1",
            }
            if any(binding[key] != value for key, value in expected.items()):
                mismatched.append(binding["capabilityId"])
        except ApiError as error:
            (
                missing if error.code == "BUSINESS_PACKAGE_CAPABILITY_AMBIGUOUS" else mismatched
            ).append(binding["capabilityId"])
    requirements = manifest["requirements"]
    unknown_families = sorted(set(requirements["runtimeFamilies"]) - SUPPORTED_RUNTIME_FAMILIES)
    unknown_languages = sorted(set(requirements["runtimeLanguages"]) - SUPPORTED_RUNTIME_LANGUAGES)
    if set(requirements["runtimeFamilies"]) != SUPPORTED_RUNTIME_FAMILIES:
        requirement_mismatches.append("runtimeFamilies")
    if set(requirements["runtimeLanguages"]) != SUPPORTED_RUNTIME_LANGUAGES:
        requirement_mismatches.append("runtimeLanguages")
    actual_execution = {binding["operation"] for binding in manifest["bindings"]}
    if set(requirements["execution"]) != actual_execution or actual_execution != {"QUERY"}:
        requirement_mismatches.append("execution")
    unknown_observation = sorted(set(requirements["observation"]) - SUPPORTED_OBSERVATION)
    if unknown_observation:
        requirement_mismatches.append("observation")
    declared_scopes = {
        scope
        for _, definition in (
            _catalog_binding(
                harness,
                binding["capabilityId"],
                (binding["capabilityPack"]["id"], binding["capabilityPack"]["version"]),
            )
            for binding in manifest["bindings"]
            if binding["capabilityId"] not in missing + mismatched
        )
        for scope in definition.get("requiredScopes", [])
    }
    if set(requirements["requiredScopes"]) != declared_scopes:
        requirement_mismatches.append("requiredScopes")
    unsupported = [*unknown_families, *unknown_languages, *unknown_observation]
    pending = [
        item["capabilityId"] for item in manifest["bindings"] if item["confirmation"] != "CONFIRMED"
    ]
    ready = (
        not missing
        and not mismatched
        and not unsupported
        and not pending
        and not requirement_mismatches
    )
    return {
        "status": "ADMISSIBLE" if ready else "MISSING_DEPENDENCIES",
        "missing": sorted(set(missing)),
        "mismatched": sorted(set(mismatched)),
        "unsupportedRuntime": unsupported,
        "requirementMismatches": sorted(set(requirement_mismatches)),
        "pendingBindings": sorted(set(pending)),
    }


def draft_from_archive(
    raw,
    name,
    harness,
    data=None,
    anchor: HarnessPackageAnchor = FINANCE_HARNESS_BUSINESS_PACKAGE_V01,
):
    manifest, files = inspect_archive(raw, harness, anchor)
    status = dependency_status(manifest, harness, anchor)
    resources = {
        path: value.decode("utf-8")
        for path, value in files.items()
        if path.startswith("references/")
    }
    cases = []
    for item in manifest["cases"]:
        try:
            case = json.loads(files[item["path"]])
        except (ValueError, UnicodeError):
            raise ApiError("BUSINESS_PACKAGE_CASE_INVALID", 422) from None
        query = case.get("query")
        if not isinstance(query, str) or not query.strip():
            raise ApiError("BUSINESS_PACKAGE_CASE_INVALID", 422)
        terms = case.get("expectedTerms", [])
        cases.append({"name": case.get("name", item["id"]), "query": query, "expectedTerms": terms})
    try:
        content = files[manifest["skill"]["entry"]].decode("utf-8")
    except UnicodeError:
        raise ApiError("BUSINESS_PACKAGE_SKILL_INVALID", 422) from None
    imported_data = parse_content(
        {
            "name": name or manifest["package"]["displayName"],
            "content": content,
            "resources": resources,
            "requiredResources": sorted(resources),
            "capabilityIds": sorted(binding["capabilityId"] for binding in manifest["bindings"]),
            "cases": cases,
        }
    )
    if data is not None:
        imported_data = parse_content(data)
        if sorted(imported_data["capabilityIds"]) != sorted(
            binding["capabilityId"] for binding in manifest["bindings"]
        ):
            raise ApiError("ASSET_BASE_CHANGED", 409)
    source = {
        "kind": "BUSINESS_PACKAGE_IMPORTED",
        "package": manifest["package"],
        "manifest": manifest,
        "archiveSha256": sha(raw),
        "dependencyStatus": status,
    }
    return {
        "manifest": manifest,
        "data": imported_data,
        "source": source,
        "dependencyStatus": status,
    }


def _preferred_refs(release):
    binding = release.get("source", {}).get("binding", {})
    return {item["id"]: (item["id"], item["version"]) for item in binding.get("basePackages", [])}


def binding_plan(
    value,
    trial,
    harness,
    anchor: HarnessPackageAnchor = FINANCE_HARNESS_BUSINESS_PACKAGE_V01,
):
    verify_harness_package_anchor(harness, anchor)
    capability_ids = value["data"]["capabilityIds"]
    preferred = _preferred_refs(value)
    bindings, scopes, execution = [], set(), set()
    for capability_id in capability_ids:
        preferred_ref = next(
            (
                value
                for value in preferred.values()
                if value[0]
                in {
                    item["id"]
                    for item in json.loads(
                        (harness / "catalog/finance-assets.v1.json").read_text(encoding="utf-8")
                    )["capabilityPacks"]
                    if capability_id in item.get("capabilities", [])
                }
            ),
            None,
        )
        asset, definition = _catalog_binding(harness, capability_id, preferred_ref)
        operation = definition["operation"]
        execution.add(operation)
        scopes.update(definition.get("requiredScopes", []))
        bindings.append(
            {
                "capabilityId": capability_id,
                "capabilityPack": {"id": asset["id"], "version": asset["version"]},
                "toolName": definition["toolName"],
                "operation": operation,
                "protocol": SUPPORTED_PROTOCOL,
                "inputSchemaRef": "capability-pack://"
                + asset["id"]
                + "@"
                + asset["version"]
                + "/"
                + capability_id
                + "/input",
                "outputSchemaRef": "schema://capability-result.v1",
                "confirmation": "CONFIRMED",
            }
        )
    trial_runtime = trial.get("report", {}).get("trialRuntime", {})
    if (
        trial.get("report", {}).get("runtimeVerified") is not True
        or trial_runtime.get("family") not in SUPPORTED_RUNTIME_FAMILIES
        or trial_runtime.get("language") not in SUPPORTED_RUNTIME_LANGUAGES
    ):
        raise ApiError("BUSINESS_PACKAGE_RUNTIME_NOT_VERIFIED", 409)
    requirements = {
        "runtimeFamilies": [trial_runtime["family"]],
        "runtimeLanguages": [trial_runtime["language"]],
        "execution": sorted(execution),
        "observation": sorted(SUPPORTED_OBSERVATION),
        "requiredScopes": sorted(scopes),
    }
    validation = {
        "status": trial["report"]["status"],
        "summaryPath": "validation-summary.json",
        "professionalQuality": trial["report"].get("professionalQuality", "NOT_EVALUATED"),
    }
    return {
        "bindings": bindings,
        "requirements": requirements,
        "validation": validation,
        "evidence": {
            "trialId": trial["trialId"],
            "runtimeVerified": True,
            "professionalQuality": validation["professionalQuality"],
        },
    }


def export_release(
    release,
    trial,
    harness,
    anchor: HarnessPackageAnchor = FINANCE_HARNESS_BUSINESS_PACKAGE_V01,
):
    confirmed = set(release.get("bindingConfirmations", []))
    capability_ids = release["data"]["capabilityIds"]
    if confirmed != set(capability_ids):
        raise ApiError("BUSINESS_PACKAGE_BINDING_CONFIRMATION_REQUIRED", 409)
    plan = binding_plan(release, trial, harness, anchor)
    bindings = plan["bindings"]
    files = {"skill/SKILL.md": release["data"]["content"].encode()}
    for path in release["data"]["requiredResources"]:
        files[path] = release["data"]["resources"][path].encode()
    cases = []
    for index, case in enumerate(release["data"]["cases"], 1):
        path = f"cases/case-{index:02d}.json"
        files[path] = canonical(case)
        cases.append({"id": f"case-{index:02d}", "path": path})
    summary = {
        "schemaVersion": "finance-business-package-validation.v0.1",
        "trialId": trial["trialId"],
        "trialDigest": sha(canonical(trial)),
        "status": trial["report"]["status"],
        "runtimeVerified": trial["report"].get("runtimeVerified") is True,
        "professionalQuality": plan["validation"]["professionalQuality"],
    }
    files["validation-summary.json"] = canonical(summary)
    source_kind = (
        "BUSINESS_DERIVED"
        if release.get("source", {}).get("kind") == "BUSINESS_DERIVED"
        else "IMPORTED"
        if "IMPORTED" in release.get("source", {}).get("kind", "")
        else "AUTHORED"
    )
    source = {
        "schemaVersion": "finance-business-component-package-source.v0.1",
        "package": {
            "id": release["assetId"],
            "version": release["version"],
            "displayName": release["data"]["name"],
            "maintainer": release["data"].get("maintainer") or "workbench-owner",
            "visibility": "PRIVATE",
        },
        "skill": {
            "entry": "skill/SKILL.md",
            "sourceKind": source_kind,
            "basePackageRefs": sorted(
                (
                    {"id": pack_id, "version": version}
                    for pack_id, version in {
                        (binding["capabilityPack"]["id"], binding["capabilityPack"]["version"])
                        for binding in bindings
                    }
                ),
                key=lambda item: (item["id"], item["version"]),
            ),
        },
        "bindings": bindings,
        "requirements": plan["requirements"],
        "cases": cases,
        "validation": plan["validation"],
    }
    raw, result = _build_archive(source, files, harness, anchor)
    return {
        "filename": release["assetId"] + "-" + release["version"] + ".zip",
        "base64": base64.b64encode(raw).decode(),
        "sha256": result["archiveSha256"],
        "manifest": result["manifest"],
    }
