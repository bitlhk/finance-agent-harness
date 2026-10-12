"""Template-owned business modules over the existing finite read-only recipe."""
from hashlib import sha256
import json
from pathlib import Path

from .directory import describe_directory
from .service import solution_for
from .python_service import RUNTIME_ID as PYTHON_RUNTIME, python_solution

CATALOG = Path(__file__).with_name("template-modules.v1.json")
RUNTIME = "openjiuwen-java-0.1.15"


def load_template_modules():
    value = json.loads(CATALOG.read_text(encoding="utf-8"))
    if value["schemaVersion"] != "finance-studio-template-modules.v1":
        raise ValueError("MODULE_DIRECTORY_INVALID")
    for key in ("businessDomains", "channels", "taskBlueprints", "templates", "modules", "runtimes"):
        ids = [item["id"] for item in value[key]]
        if len(ids) != len(set(ids)):
            raise ValueError("MODULE_DIRECTORY_INVALID")
    modules = {item["id"] for item in value["modules"]}
    domains = {item["id"] for item in value["businessDomains"]}
    channels = {item["id"] for item in value["channels"]}
    templates = {item["id"] for item in value["templates"]}
    assignments = value["moduleDomainAssignments"]
    if ({item["moduleId"] for item in assignments} != modules
            or len(assignments) != len(modules)
            or any(item["businessDomainId"] not in domains
                   or len(item.get("relatedBusinessDomainIds", [])) != len(set(item.get("relatedBusinessDomainIds", [])))
                   or not set(item.get("relatedBusinessDomainIds", [])) <= domains - {item["businessDomainId"]}
                   for item in assignments)):
        raise ValueError("MODULE_DIRECTORY_INVALID")
    blueprint_pack_ids = []
    for blueprint in value["taskBlueprints"]:
        if (blueprint["businessDomainId"] not in domains
                or blueprint.get("kind") not in {"BUSINESS_TASK", "APPLICATION_CONFIGURATION"}
                or len(blueprint.get("relatedBusinessDomainIds", [])) != len(set(blueprint.get("relatedBusinessDomainIds", [])))
                or not set(blueprint.get("relatedBusinessDomainIds", [])) <= domains - {blueprint["businessDomainId"]}
                or not blueprint["moduleIds"]
                or len(blueprint["moduleIds"]) != len(set(blueprint["moduleIds"]))
                or not set(blueprint["moduleIds"]) <= modules
                or not blueprint["packIds"]
                or len(blueprint["packIds"]) != len(set(blueprint["packIds"]))
                or not set(blueprint["channelIds"]) <= channels
                or blueprint["legacyTemplateId"] not in templates):
            raise ValueError("MODULE_DIRECTORY_INVALID")
        blueprint_pack_ids.extend(blueprint["packIds"])
    if len(blueprint_pack_ids) != len(set(blueprint_pack_ids)):
        raise ValueError("MODULE_DIRECTORY_INVALID")
    for template in value["templates"]:
        refs = template["moduleIds"]
        if (len(refs) != len(set(refs)) or not set(refs) <= modules
                or not set(template["defaultModuleIds"]) <= set(refs)
                or not set(template["featuredModuleIds"]) <= set(refs)):
            raise ValueError("MODULE_DIRECTORY_INVALID")
    return value


def describe_template_modules(harness=None):
    value = load_template_modules()
    components = describe_directory(harness)["components"]
    by_id = {c["id"]: c for c in components}
    for module in value["modules"]:
        refs = module["componentIds"]
        module["status"] = ("PARTIAL" if refs and all(by_id.get(ref, {}).get("status") == "AVAILABLE" for ref in refs)
                            else "ASSET_UNAVAILABLE" if refs else "NOT_INTEGRATED")
        module["capabilityIds"] = sorted({cap for ref in refs for cap in by_id.get(ref, {}).get("capabilityIds", [])})
        module["tools"] = [tool for ref in refs for tool in by_id.get(ref, {}).get("tools", [])]
    value["components"] = components
    value["revision"] = sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()
    return value


def resolve_template_modules(selection, harness):
    value = describe_template_modules(harness)
    if selection.directoryRevision != value["revision"]:
        raise ValueError("DIRECTORY_CHANGED")
    runtime = next((r for r in value["runtimes"] if r["id"] == selection.runtimeId), None)
    # A catalog label cannot enable a backend that has not been implemented.
    if selection.runtimeId not in {RUNTIME, PYTHON_RUNTIME} or not runtime or runtime["status"] != "AVAILABLE":
        raise ValueError("RUNTIME_NOT_SUPPORTED")
    template = next((t for t in value["templates"] if t["id"] == selection.scenarioTemplateId), None)
    if (not template or template["id"] != "mobile-banking" or template["audienceId"] != "CUSTOMER"
            or template["status"] != "PARTIAL"):
        raise ValueError("TEMPLATE_UNAVAILABLE")
    if (selection.runtimeId not in template["runtimeIds"] or selection.bankConnectionId not in template["bankConnectionIds"]
            or selection.applicationTemplateId != template["applicationTemplateId"]):
        raise ValueError("TEMPLATE_BINDING_MISMATCH")
    by_id = {m["id"]: m for m in value["modules"]}
    modules = []
    for ref in selection.moduleIds:
        if ref not in template["moduleIds"]:
            raise ValueError("MODULE_NOT_IN_TEMPLATE")
        module = by_id[ref]
        if module["status"] != "PARTIAL":
            raise ValueError("MODULE_UNAVAILABLE")
        modules.append(module)
    expected = sorted({cap for module in modules for cap in module["capabilityIds"]})
    if expected != sorted(selection.capabilityIds):
        raise ValueError("MODULE_SELECTION_MISMATCH")
    solution = (python_solution(expected, 'module-validation') if selection.runtimeId == PYTHON_RUNTIME
                else solution_for(expected, "module-validation"))
    if solution["profileRef"]["id"] != template["profileId"]:
        raise ValueError("TEMPLATE_BINDING_MISMATCH")
    refs = {ref for module in modules for ref in module["componentIds"]}
    components = [c for c in value["components"] if c["id"] in refs]
    if any("CUSTOMER" not in c["audienceIds"] for c in components):
        raise ValueError("TEMPLATE_BINDING_MISMATCH")
    return {"schemaVersion": value["schemaVersion"], "version": value["version"], "revision": value["revision"],
            "template": template, "modules": modules, "runtime": runtime, "components": components,
            "audienceId": template["audienceId"], "profileRef": solution["profileRef"],
            "templateCustomized": set(selection.moduleIds) != set(template["defaultModuleIds"]),
            "authorizationStatus": "RUNTIME_CHECK_REQUIRED"}
