"""Scenario directory, not an authority or a new capability execution engine."""
from __future__ import annotations

from hashlib import sha256
import json
from pathlib import Path

import yaml
import jsonschema

from .service import CAPABILITIES, solution_for

DIRECTORY = Path(__file__).with_name("directory.v1.json")


def load_directory():
    value = json.loads(DIRECTORY.read_text(encoding="utf-8"))
    if value["schemaVersion"] != "finance-studio-directory.v1":
        raise ValueError("DIRECTORY_INVALID")
    for group in ("audiences", "domains", "components", "templates"):
        ids = [item["id"] for item in value[group]]
        if len(ids) != len(set(ids)):
            raise ValueError("DIRECTORY_INVALID")
    audiences = {item["id"] for item in value["audiences"]}
    domains = {item["id"] for item in value["domains"]}
    components = {item["id"]: item for item in value["components"]}
    for item in [*value["components"], *value["templates"]]:
        if not set(item["audienceIds"]) <= audiences or item["domain"] not in domains:
            raise ValueError("DIRECTORY_INVALID")
        if item["kind"] == "COMPONENT":
            if not item["capabilityIds"] or not set(item["capabilityIds"]) <= CAPABILITIES:
                raise ValueError("DIRECTORY_UNSUPPORTED_CAPABILITY")
            for planned in item.get("plannedCapabilities", []):
                if (set(planned) != {"id", "label", "status"} or planned["status"] != "NOT_INTEGRATED"
                        or planned["id"] in CAPABILITIES or not planned["label"]):
                    raise ValueError("DIRECTORY_INVALID_PLANNED_CAPABILITY")
        elif item["kind"] == "TEMPLATE":
            if not item["componentIds"] or not set(item["componentIds"]) <= components.keys():
                raise ValueError("DIRECTORY_INVALID")
            if any(not set(item["audienceIds"]) <= set(components[ref]["audienceIds"]) for ref in item["componentIds"]):
                raise ValueError("DIRECTORY_INVALID")
        else:
            raise ValueError("DIRECTORY_INVALID")
    return value


def describe_directory(harness: Path | None = None):
    value = load_directory()
    for item in value["components"]:
        from .method_guides import describe_guide
        item["methodGuide"] = describe_guide(item["id"], harness)
        item["label"] = item["methodGuide"]["label"]
        solution = solution_for(item["capabilityIds"], "directory-inspection")
        item.update(runtime=solution["runtime"], profileRef=solution["profileRef"],
                    domainPackRefs=solution["domainPackRefs"], capabilityPackRefs=solution["capabilityPackRefs"],
                    providerRefs=solution["providerRefs"], capabilityBindings=solution["capabilityBindings"],
                    bankConnectionIds=["local-simulator"], tools=[], status="NOT_INSPECTED")
        if harness is not None:
            try:
                from .business_assets import catalog_bindings
                from .assets import tree_digest
                from .component_plan import qualify_solution
                admission = qualify_solution(Path(__file__).resolve().parents[2], harness, solution)
                item["assemblyAdmission"] = admission["evidence"]
                item["catalogBinding"] = catalog_bindings(harness, solution)
                item["descriptionSource"] = {"source": "builder://directory.v1.json", "version": value["version"]}
                item["methodKind"] = "DOMAIN_PACK" if solution["domainPackRefs"] else "HOST_METHOD"
                for ref in solution["capabilityPackRefs"]:
                    file = (harness / ref["source"].removeprefix("repo://") / "pack.yaml").resolve()
                    if not file.is_relative_to(harness.resolve()):
                        raise ValueError("ASSET_OUTSIDE_ROOT")
                    raw = file.read_bytes()
                    pack = yaml.safe_load(raw)
                    if pack["packId"] != ref["id"] or pack["version"] != ref["version"]:
                        raise ValueError("ASSET_VERSION_MISMATCH")
                    ref["observedSha256"] = sha256(raw).hexdigest()
                    ref["digest"] = tree_digest(file.parent)
                    for cap in pack["capabilities"]:
                        if cap["operation"] != "QUERY" or cap["capabilityId"] not in item["capabilityIds"]:
                            raise ValueError("READONLY_BOUNDARY_CHANGED")
                        item["tools"].append({key: cap[key] for key in ("capabilityId", "toolName", "operation", "inputSchema", "requiredScopes")})
                if sorted(t["capabilityId"] for t in item["tools"]) != sorted(item["capabilityIds"]):
                    raise ValueError("CAPABILITY_MISSING")
                item["status"] = "AVAILABLE"
            except (OSError, ValueError, KeyError, TypeError, yaml.YAMLError, jsonschema.ValidationError):
                item["tools"] = []
                item["status"] = "ASSET_UNAVAILABLE"
        item["verification"] = {"contract": "INSPECTED" if item["status"] == "AVAILABLE" else "NOT_CHECKED",
                                "source": "SYNTHETIC_REFERENCE", "realAuthorization": "NOT_RUN",
                                "environment": "PREFLIGHT_REQUIRED", "installation": "NOT_OBSERVED",
                                "methodTrialRuntime": "JAVA_ONLY", "professionalQuality": "NOT_EVALUATED"}
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    value["revision"] = sha256(encoded).hexdigest()
    return value


def resolve_directory(selection, harness):
    """Re-resolve client IDs against server configuration before any build side effect."""
    value = describe_directory(harness)
    if selection.directoryRevision != value["revision"]:
        raise ValueError("DIRECTORY_CHANGED")
    audience = next((a for a in value["audiences"] if a["id"] == selection.audienceId), None)
    # The installed host recipe is CUSTOMER-only, irrespective of directory labels.
    if selection.audienceId != "CUSTOMER" or not audience or not audience["enabled"] or not audience["buildEnabled"]:
        raise ValueError("AUDIENCE_NOT_BUILDABLE")
    components = {c["id"]: c for c in value["components"]}
    selected = []
    for ref in selection.componentIds:
        item = components.get(ref)
        if not item or selection.audienceId not in item["audienceIds"] or item["status"] != "AVAILABLE":
            raise ValueError("COMPONENT_UNAVAILABLE")
        selected.append(item)
    expected = sorted({cap for item in selected for cap in item["capabilityIds"]})
    if expected != sorted(selection.capabilityIds):
        raise ValueError("COMPONENT_SELECTION_MISMATCH")
    template = None
    if selection.entryMode == "TEMPLATE":
        template = next((t for t in value["templates"] if t["id"] == selection.scenarioTemplateId), None)
        if not template or selection.audienceId not in template["audienceIds"]:
            raise ValueError("TEMPLATE_UNAVAILABLE")
    elif selection.scenarioTemplateId is not None:
        raise ValueError("TEMPLATE_SELECTION_INVALID")
    # Both entry modes compile through the same finite, read-only recipe.
    solution = solution_for(expected, "directory-validation")
    return {"schemaVersion": value["schemaVersion"], "version": value["version"], "revision": value["revision"],
            "audienceId": selection.audienceId, "entryMode": selection.entryMode,
            "template": template, "templateCustomized": bool(template and set(template["componentIds"]) != set(selection.componentIds)),
            "components": selected, "profileRef": solution["profileRef"],
            "authorizationStatus": "RUNTIME_CHECK_REQUIRED"}
