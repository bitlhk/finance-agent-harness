"""Managed identity-based Java build entry; legacy business recipes remain compatible."""
import argparse
from copy import deepcopy
import json
from pathlib import Path
import re

import yaml

from .component_assembly import AssemblyRejected, ComponentAssembly, _unique_object
from .component_plan import qualify_solution, host_support_files


def resolve_selection(project, assembly, bindings):
    failure = None
    for path in host_support_files(project):
        support = json.loads(path.read_bytes(), object_pairs_hook=_unique_object)
        for connection_kind in support.get("connectionKinds", []):
            try:
                return support, assembly.select(bindings, host=support, connection_kind=connection_kind)
            except AssemblyRejected as rejected:
                failure = failure or rejected
    raise failure or AssemblyRejected("ASSEMBLY_MANAGED_HOST_REQUIRED")


def selection_support(project: Path):
    path = project / "templates/local-readonly-service/selection-support.v0.1.json"
    if path.is_symlink() or not path.is_file():
        raise AssemblyRejected("ASSEMBLY_MANAGED_HOST_REQUIRED")
    value = json.loads(path.read_bytes(), object_pairs_hook=_unique_object)
    if (set(value) != {"schemaVersion", "selectionVersion", "runtimeIds", "presentations", "privateMethods"}
            or value["schemaVersion"] != "finance-selection-support.v0.1"
            or value["selectionVersion"] != "0.8"
            or not isinstance(value["privateMethods"], bool)
            or any(not isinstance(value[k], list) or not value[k]
                   or any(not isinstance(v, str) or not v for v in value[k])
                   for k in ("runtimeIds", "presentations"))):
        raise AssemblyRejected("ASSEMBLY_HOST_DECLARATION_INVALID")
    return value


def evaluation_pack_ref(harness: Path, domain_refs: list[dict], fallback: dict) -> dict:
    """Read the evaluation identity declared by a selected local Domain Pack.

    Older packs have no declaration and retain the controlled legacy reference.
    The reference itself is subsequently admitted by the Harness asset catalog.
    """
    if len(domain_refs) != 1:
        return deepcopy(fallback)
    source = domain_refs[0].get("source")
    if not isinstance(source, str) or not source.startswith("repo://"):
        raise AssemblyRejected("ASSEMBLY_DOMAIN_EVALUATION_INVALID")
    pack = (harness / source.removeprefix("repo://") / "pack.yaml").resolve()
    if not pack.is_relative_to(harness.resolve()) or pack.is_symlink() or not pack.is_file():
        raise AssemblyRejected("ASSEMBLY_DOMAIN_EVALUATION_INVALID")
    try:
        declaration = yaml.safe_load(pack.read_text(encoding="utf-8"))
        value = declaration.get("evaluationPackRef")
    except (OSError, TypeError, yaml.YAMLError) as error:
        raise AssemblyRejected("ASSEMBLY_DOMAIN_EVALUATION_INVALID") from error
    if value is None:
        return deepcopy(fallback)
    if (not isinstance(value, dict) or set(value) != {"id", "version", "source"}
            or any(not isinstance(value[key], str) or not value[key]
                   for key in ("id", "version", "source"))):
        raise AssemblyRejected("ASSEMBLY_DOMAIN_EVALUATION_INVALID")
    return deepcopy(value)


def solution_from_selection(project: Path, harness: Path, selection):
    if (not isinstance(selection, dict) or set(selection) != {"schemaVersion", "solutionId", "packBindings"}
            or selection["schemaVersion"] != "finance-managed-selection.v0.1"
            or not isinstance(selection["solutionId"], str)
            or not re.fullmatch(r"[a-z][a-z0-9-]{2,63}", selection["solutionId"])):
        raise AssemblyRejected("ASSEMBLY_SELECTION_INVALID")
    support_path = project / "templates/local-readonly-service/host-support.v0.1.json"
    if not support_path.is_file() or support_path.is_symlink():
        raise AssemblyRejected("ASSEMBLY_MANAGED_HOST_REQUIRED")
    support, resolved = resolve_selection(project, ComponentAssembly(harness), selection["packBindings"])
    from .readonly import RECIPE
    solution = json.loads(RECIPE.read_bytes())
    solution.update(solutionId=selection["solutionId"], displayName="Local Financial Agent",
                    modelRef="config://local-model", runtime=deepcopy(support["runtime"]),
                    profileRef={"id": support["profile"], "version": "0.1.0",
                                "source": "repo://profiles/" + support["profile"] + ".md"})
    for name in ("capabilityPackRefs", "providerRefs", "capabilityBindings", "domainPackRefs"):
        solution[name] = resolved[name]
    solution["evaluationPackRef"] = evaluation_pack_ref(
        harness, solution["domainPackRefs"], solution["evaluationPackRef"]
    )
    qualify_solution(project, harness, solution)
    return solution


def build_managed(builder, selection, output, *, verify=True, reuse_candidate=None):
    from .service import build_service
    solution = solution_from_selection(builder.project_root, builder.harness_repo, selection)
    return build_service(builder, solution, output, verify, managed=True, reuse_candidate=reuse_candidate)


def unified_directory_selection(project, harness, selection, *, environment=None):
    from .unified_catalog import unified_catalog
    explicit = selection.schema_version == '0.10'
    if explicit:
        from .managed_runtime_support import resolve_binding
        record, host, _, _ = resolve_binding(project, selection.runtimeId, selection.hostId,
                                              selection.profileRef.model_dump(), environment=environment)
        if (selection.presentation not in host['presentations']
                or selection.applicationTemplateId not in host['applicationTemplateIds']):
            raise AssemblyRejected('MANAGED_HOST_BINDING_MISMATCH')
    value = (unified_catalog(project, harness) if environment is None
             else unified_catalog(project, harness, environment=environment))
    if selection.presentation not in value["selectionSupport"]["presentations"]:
        raise ValueError("MANAGED_HOST_UNSUPPORTED")
    directory = value["templateModuleDirectory"]
    if selection.directoryRevision != directory["revision"]:
        raise ValueError("DIRECTORY_CHANGED")
    template = next((t for t in directory["templates"] if t["id"] == selection.scenarioTemplateId), None)
    if not template or template["status"] not in {"AVAILABLE", "PARTIAL"}:
        raise ValueError("TEMPLATE_UNAVAILABLE")
    runtime = next((r for r in directory["runtimes"] if r["id"] == selection.runtimeId), None)
    if not runtime or (runtime["status"] != "AVAILABLE" and not (explicit and runtime['status'] == 'CANDIDATE')):
        raise ValueError("RUNTIME_NOT_SUPPORTED")
    if (selection.runtimeId not in template["runtimeIds"]
            or selection.bankConnectionId not in template["bankConnectionIds"]
            or selection.applicationTemplateId != template["applicationTemplateId"]):
        raise ValueError("TEMPLATE_BINDING_MISMATCH")
    by_id = {m["id"]: m for m in directory["modules"]}
    modules = []
    for identity in selection.moduleIds:
        if identity not in template["moduleIds"]:
            raise ValueError("MODULE_NOT_IN_TEMPLATE")
        module = by_id.get(identity)
        if not module or module["status"] not in {"AVAILABLE", "PARTIAL"}:
            raise ValueError("MODULE_UNAVAILABLE")
        modules.append(module)
    components = {c["id"]: c for c in directory["components"]}
    selected_entries = []
    for binding in selection.packBindings:
        matches = [entry for entry in value["entries"] if entry["pack"] == binding.pack.model_dump()]
        if len(matches) != 1 or binding.provider.model_dump() not in matches[0]["providers"]:
            raise ValueError("COMPONENT_SELECTION_MISMATCH")
        entry = matches[0]
        if selection.runtimeId not in entry["runtimeIds"]:
            raise ValueError("RUNTIME_NOT_SUPPORTED")
        selected_entries.append(entry)
    selected_components = {entry["componentId"] for entry in selected_entries}
    allowed_components = {ref for module in modules for ref in module["componentIds"]}
    if (not selected_components <= allowed_components
            or any(not selected_components.intersection(module["componentIds"]) for module in modules)
            or any(template["audienceId"] not in components[ref]["audienceIds"] for ref in selected_components)
            or {cap["capabilityId"] for entry in selected_entries for cap in entry["capabilities"]}
               != set(selection.capabilityIds)):
        raise ValueError("MODULE_SELECTION_MISMATCH")
    if selection.methodRef is not None and any(not entry["methodCustomizationSupported"] for entry in selected_entries):
        raise ValueError("MANAGED_METHOD_UNSUPPORTED")
    return {"schemaVersion": directory["schemaVersion"], "version": directory["version"],
            "revision": directory["revision"], "template": template, "modules": modules,
            "metadataRole": "CATALOG_SELECTION_NOT_INSTALLATION",
            "selectedPackBindings": [binding.model_dump() for binding in selection.packBindings],
            "selectedCapabilityIds": sorted(selection.capabilityIds),
            "components": [components[ref] for ref in sorted(selected_components)],
            "runtime": runtime, "audienceId": template["audienceId"],
            "templateCustomized": {(b.pack.id, b.pack.version) for b in selection.packBindings}
                != {(b["pack"]["id"], b["pack"]["version"]) for b in template["defaultPackBindings"]},
            "authorizationStatus": "RUNTIME_CHECK_REQUIRED"}


def resolve_unified_selection(project, harness, selection, solution_id, method_snapshot=None, *, environment=None):
    """Resolve declared identities and scoped methods before any job is admitted."""
    from .method_assets import runtime_reference
    from .python_service import RUNTIME_ID, RUNTIME, require_python_capabilities
    directory = unified_directory_selection(project, harness, selection, environment=environment)
    runtime_binding = None
    if selection.schema_version == '0.10':
        from .managed_runtime_support import resolve_binding, supported_selection, freeze_binding
        from .readonly import RECIPE
        if not re.fullmatch(r'[a-z][a-z0-9-]{2,63}', solution_id):
            raise AssemblyRejected('ASSEMBLY_SELECTION_INVALID')
        record, host, support, support_path = resolve_binding(project, selection.runtimeId, selection.hostId,
            selection.profileRef.model_dump(), environment=environment)
        support, resolved = supported_selection(project, ComponentAssembly(harness), record, host,
            [binding.model_dump() for binding in selection.packBindings])
        runtime_binding = freeze_binding(project, record, host, support_path, harness=harness)
        solution = json.loads(RECIPE.read_bytes())
        solution.update(solutionId=solution_id, displayName='Local Financial Agent', modelRef='config://local-model',
                        runtime=deepcopy(record['runtime']), profileRef=deepcopy(host['profileRef']))
        for key in ('capabilityPackRefs', 'providerRefs', 'capabilityBindings', 'domainPackRefs'):
            solution[key] = resolved[key]
    else:
        solution = solution_from_selection(project, harness, {
            "schemaVersion": "finance-managed-selection.v0.1", "solutionId": solution_id,
            "packBindings": [binding.model_dump() for binding in selection.packBindings]})
    if set(solution["capabilityBindings"]) != set(selection.capabilityIds):
        raise ValueError("COMPONENT_SELECTION_MISMATCH")
    template = directory["template"]
    if solution["profileRef"]["id"] not in template.get("profileIds", [template.get("profileId")]):
        raise ValueError("TEMPLATE_BINDING_MISMATCH")
    if selection.methodRef is not None:
        if (method_snapshot is None or selection.methodRef.reference() != {
                key: method_snapshot[key] for key in ("assetId", "version", "releaseDigest")}):
            raise ValueError("ASSEMBLY_METHOD_SNAPSHOT_MISMATCH")
        scope = set(method_snapshot["data"]["capabilityIds"])
        if not scope or not scope <= set(selection.capabilityIds):
            raise ValueError("ASSET_CAPABILITY_NOT_INSTALLED")
        if runtime_binding is None:
            support, resolved = resolve_selection(project, ComponentAssembly(harness),
                [binding.model_dump() for binding in selection.packBindings])
        retained = [default["identity"] for default in resolved["defaultMethods"]
                    if not scope.intersection(default["capabilityIds"])]
        solution["domainPackRefs"] = [ref for ref in solution["domainPackRefs"]
            if {key: ref[key] for key in ("id", "version")} in retained]
        solution["domainPackRefs"].append(runtime_reference(method_snapshot))
    elif method_snapshot is not None:
        raise ValueError("ASSEMBLY_METHOD_SNAPSHOT_MISMATCH")
    plan = (qualify_solution(project, harness, solution, method_snapshot, runtime_binding=runtime_binding)
            if runtime_binding else qualify_solution(project, harness, solution, method_snapshot))
    if runtime_binding:
        plan['evidence']['files'].update(resolved['evidence']['files'])
        directory['runtimeBinding'] = deepcopy(runtime_binding)
        directory['runtimePresentation'] = deepcopy(runtime_binding['runtimePresentation'])
    if method_snapshot is not None and any(cap["operation"] != "QUERY" for cap in plan["capabilities"]):
        raise ValueError("MANAGED_METHOD_UNSUPPORTED")
    if selection.runtimeId == RUNTIME_ID:
        require_python_capabilities(selection.capabilityIds)
        if any(cap["operation"] != "QUERY" for cap in plan["capabilities"]):
            raise ValueError("PYTHON_COMPOSITION_UNSUPPORTED")
        solution["runtime"] = deepcopy(RUNTIME)
    directory["profileRef"] = deepcopy(solution["profileRef"])
    return solution, directory, plan


def selection_catalog(project: Path, harness: Path):
    """Describe only declaration-qualified bindings; this is not installed-code evidence."""
    from hashlib import sha256
    import yaml
    assembly = ComponentAssembly(harness)
    support = json.loads((project / "templates/local-readonly-service/host-support.v0.1.json").read_bytes(),
                         object_pairs_hook=_unique_object)
    entries = []
    for pack in assembly.catalog["capabilityPacks"]:
        identity = {key: pack[key] for key in ("id", "version")}
        providers = []
        capabilities = None
        for provider in assembly.catalog["providers"]:
            provider_identity = {key: provider[key] for key in ("id", "version")}
            try:
                selected_support, result = resolve_selection(project, assembly, [{"pack": identity, "provider": provider_identity}])
            except AssemblyRejected:
                continue
            providers.append(provider_identity)
            capabilities = result["capabilities"]
        if not providers:
            continue
        contents = yaml.safe_load(assembly._read(assembly._source(pack) + "/pack.yaml"))
        entries.append({"pack": identity, "label": contents.get("displayName") or " / ".join(c["name"] for c in contents["capabilities"]),
                        "providers": providers,
                        "capabilities": [{key: cap[key] for key in ("capabilityId", "operation", "requiredScopes")}
                                         for cap in capabilities],
                        "qualification": "DECLARATION_COMPATIBLE", "installation": "NOT_OBSERVED",
                        "authorization": "RUNTIME_CHECK_REQUIRED"})
    value = {"schemaVersion": "finance-managed-directory.v0.1", "entries": entries,
             "runtime": support["runtime"], "profile": support["profile"],
             "selectionSupport": selection_support(project),
             "connectionKind": support["connectionKinds"][0]}
    value["revision"] = sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return value


def main():
    parser = argparse.ArgumentParser(description="Build reviewed component identities with the managed Java Host")
    parser.add_argument("--harness-repo", type=Path, required=True)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output-root", type=Path, required=True)
    parser.add_argument("--maven", default="mvn")
    args = parser.parse_args()
    if args.input.stat().st_size > 65536:
        raise AssemblyRejected("ASSEMBLY_SELECTION_TOO_LARGE")
    selection = json.loads(args.input.read_bytes(), object_pairs_hook=_unique_object)
    project = Path(__file__).resolve().parents[2]
    from .builder import SolutionBuilder
    # Validate before using the supplied identifier as an output directory segment.
    solution_from_selection(project, args.harness_repo, selection)
    builder = SolutionBuilder(project, args.harness_repo, maven=args.maven, service_output_root=args.output_root)
    result = build_managed(builder, selection, args.output_root / selection["solutionId"])
    print(json.dumps({"status": result.status, "report": str(result.report_path)}))


if __name__ == "__main__":
    main()
