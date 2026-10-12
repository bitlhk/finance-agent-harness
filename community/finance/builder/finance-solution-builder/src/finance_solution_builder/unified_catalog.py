"""Presentation over managed identities, never an execution or recipe resolver.

Both directories share component records. packBindings contain a provider only
when unique; otherwise providers lists the choices requiring explicit selection.
Business IDs and module membership have no execution-routing meaning.
"""
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path

from . import managed_selection
from .component_assembly import ComponentAssembly
from .directory import load_directory
from .method_guides import describe_guide
from .managed_runtime_support import declarations, supported_selection, host_support, freeze_binding, candidate_enabled
from .python_service import RUNTIME_ID as PYTHON_RUNTIME, require_python_capabilities
from .template_modules import load_template_modules

PRESENTATION = Path(__file__).with_name("unified-catalog.v1.json")


def _revision(value):
    return sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                             separators=(",", ":")).encode()).hexdigest()


def _extend_unique(target, values):
    for value in values:
        if value not in target:
            target.append(deepcopy(value))


def _presentation(module_ids):
    try:
        value = json.loads(PRESENTATION.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError
        if (set(value) != {"schemaVersion", "version", "packs", "ungroupedModule", "customTemplate"}
                or value["schemaVersion"] != "finance-unified-catalog-presentation.v1"
                or not isinstance(value["version"], str) or not value["version"]
                or not isinstance(value["packs"], dict)):
            raise ValueError
        ungrouped, custom = value["ungroupedModule"], value["customTemplate"]
        for item, fields in ((ungrouped, ("id", "label", "scope", "source", "sourceStatus", "directory", "icon")),
                             (custom, ("id", "label", "scope", "icon", "audienceId", "status"))):
            if not isinstance(item, dict) or any(not isinstance(item[k], str) or not item[k] for k in fields):
                raise ValueError
        if (ungrouped["id"] != "agent.ungrouped" or ungrouped["id"] in module_ids
                or ungrouped["componentIds"] != [] or ungrouped["sourceOperations"] != []
                or ungrouped["operationsEvidence"] is not None or custom["id"] != "customer-custom"
                or custom["audienceId"] != "CUSTOMER" or custom["status"] != "PARTIAL"
                or custom["defaultModuleIds"] != [] or custom["featuredModuleIds"] != []):
            raise ValueError
        for metadata in value["packs"].values():
            if (not isinstance(metadata, dict) or not {"componentId", "moduleId", "label"} <= set(metadata)
                    or set(metadata) - {"componentId", "moduleId", "label", "scope"}
                    or any(not isinstance(v, str) or not v for v in metadata.values())):
                raise ValueError
            if metadata["moduleId"] not in {*module_ids, ungrouped["id"]}:
                raise ValueError("UNIFIED_CATALOG_UNKNOWN_MODULE")
    except (KeyError, TypeError, ValueError) as error:
        if str(error) == "UNIFIED_CATALOG_UNKNOWN_MODULE":
            raise
        raise ValueError("UNIFIED_CATALOG_PRESENTATION_INVALID") from None
    return value


def unified_catalog(project, harness, *, environment=None):
    """Return directory, templateModuleDirectory and enriched managed entries.

Compatibility is declaration evidence, not installation or authorization.
Python support is the existing readonly selection path, never managed TASK.
    """
    project, harness = Path(project), Path(harness)
    managed = deepcopy(managed_selection.selection_catalog(project, harness))
    directory = load_directory()
    taxonomy = load_template_modules()
    original_module_components = {m["id"]: m["componentIds"][:] for m in taxonomy["modules"]}
    legacy = {c["id"]: c for c in directory["components"]}
    modules = {m["id"]: m for m in taxonomy["modules"]}
    presentation = _presentation(set(modules))
    modules[presentation["ungroupedModule"]["id"]] = deepcopy(presentation["ungroupedModule"])
    for module in modules.values():
        module["componentIds"] = []
    assembly = ComponentAssembly(harness)
    components = {}
    java_ids = managed["selectionSupport"]["runtimeIds"]
    runtime_declarations = declarations(project)
    availability = {}
    for record in runtime_declarations:
        enabled = candidate_enabled(record, environment)
        availability[record['id']] = {
            'status': 'AVAILABLE' if enabled else ('CANDIDATE' if record['status'] == 'CANDIDATE' else 'UNAVAILABLE'),
            'enabled': enabled, 'declaredStatus': record['status'], 'declaredEnabled': record['enabled'],
            'candidateEnabled': enabled and record['status'] == 'CANDIDATE',
            'requiredObservation': 'BACKEND_PREFLIGHT_REQUIRED', 'installation': 'NOT_OBSERVED'}
    all_runtime_ids = [*java_ids, PYTHON_RUNTIME, *[r['id'] for r in runtime_declarations]]
    pack_versions = {}
    for entry in managed["entries"]:
        pack_versions.setdefault(entry["pack"]["id"], set()).add(entry["pack"]["version"])
    for entry in managed["entries"]:
        metadata = presentation["packs"].get(entry["pack"]["id"], {})
        component_id = metadata.get("componentId", entry["pack"]["id"])
        if len(pack_versions[entry["pack"]["id"]]) > 1:
            # Stable SOP ancestry must never silently point at a different pack version.
            component_id += "@" + entry["pack"]["id"] + "@" + entry["pack"]["version"]
        module_id = metadata.get("moduleId", "agent.ungrouped")
        decoration = legacy.get(component_id, {})
        caps = {c["capabilityId"] for c in entry["capabilities"]}
        readonly = bool(decoration) and caps == set(decoration["capabilityIds"]) and all(
            c["operation"] == "QUERY" for c in entry["capabilities"])
        python = False
        if readonly:
            try:
                require_python_capabilities(sorted(caps))
                python = True
            except ValueError:
                pass
        entry.update(componentId=component_id, moduleIds=[module_id],
                     methodCustomizationSupported=readonly,
                     runtimeIds=[*java_ids, *([PYTHON_RUNTIME] if python else [])],
                     runtimeCompatibility=[
                         *[{"runtimeId": rid, "supported": True, "selectionMode": "MANAGED",
                            "qualification": entry["qualification"]} for rid in java_ids],
                         {"runtimeId": PYTHON_RUNTIME, "supported": python,
                          "selectionMode": "LEGACY_READONLY" if python else None,
                          "reason": "EXISTING_READONLY_SET" if python else "PYTHON_COMPOSITION_UNSUPPORTED"}])
        entry['runtimeHostOptions'] = []
        entry['capabilitySelection'] = 'ATOMIC_PACK'
        for runtime in runtime_declarations:
            options = []
            for host in runtime['hosts']:
                for provider in entry['providers']:
                    try:
                        supported_selection(project, assembly, runtime, host, [{'pack': entry['pack'], 'provider': provider}])
                    except (OSError, ValueError, KeyError, TypeError):
                        continue
                    options.append({'runtimeId': runtime['id'], 'hostId': host['id'],
                                    'profileRef': deepcopy(host['profileRef']), 'provider': deepcopy(provider),
                                    'templateSource': host['templateSource'], 'qualification': 'DECLARATION_COMPATIBLE',
                                    'installation': 'NOT_OBSERVED', 'requiredObservation': 'BACKEND_PREFLIGHT_REQUIRED'})
            if options:
                entry['runtimeIds'].append(runtime['id'])
            entry['runtimeHostOptions'].extend(options)
            entry['runtimeCompatibility'].append({'runtimeId': runtime['id'], 'supported': bool(options),
                'selectionMode': 'MANAGED_EXPLICIT_HOST', 'qualification': 'DECLARATION_COMPATIBLE' if options else 'UNAVAILABLE',
                **availability[runtime['id']], 'selectionSchemaVersion': runtime['selectionSchemaVersion']})
        # Preserve upstream Chinese names; English reference labels are presentation-only overrides.
        if not any("\u4e00" <= char <= "\u9fff" for char in entry["label"]):
            entry["label"] = metadata.get("label", entry["label"])
        if "scope" in metadata:
            entry["scope"] = metadata["scope"]
        binding = {"pack": deepcopy(entry["pack"])}
        if len(entry["providers"]) == 1:
            binding["provider"] = deepcopy(entry["providers"][0])
        else:
            binding["providers"] = deepcopy(entry["providers"])
        entry["packBindings"] = [binding]
        entry["providerOptions"] = []
        for provider in entry["providers"]:
            support, resolved = managed_selection.resolve_selection(
                project, assembly, [{"pack": entry["pack"], "provider": provider}])
            entry["providerOptions"].append({"provider": deepcopy(provider),
                **{k: deepcopy(resolved[k]) for k in (
                    "capabilityPackRefs", "providerRefs", "domainPackRefs", "capabilityBindings")},
                "tools": deepcopy(resolved["capabilities"]), "runtime": deepcopy(support["runtime"]),
                "profileRef": {"id": support["profile"]}, "assemblyAdmission": resolved["evidence"]})
        first = entry["providerOptions"][0]
        if component_id not in components:
            audience_ids = ["EMPLOYEE"] if first["profileRef"]["id"] == "internal-control" else ["CUSTOMER"]
            component = {k: deepcopy(v) for k, v in decoration.items()
                         if k in {"description", "scope", "icon", "plannedCapabilities"}}
            component.update(id=component_id, kind="COMPONENT", version=entry["pack"]["version"],
                label=entry["label"], domain=decoration.get("domain", module_id), audienceIds=audience_ids,
                status="AVAILABLE", description=component.get("description", entry["label"]),
                scope=metadata.get("scope", component.get("scope", "")), capabilityIds=[], packBindings=[], moduleIds=[],
                capabilityPackRefs=[], providerRefs=[], domainPackRefs=[], capabilityBindings={}, tools=[],
                runtime=managed["runtime"], profileRef=first["profileRef"], bankConnectionIds=["local-simulator"],
                runtimeIds=entry["runtimeIds"][:], runtimeCompatibility=deepcopy(entry["runtimeCompatibility"]),
                runtimeHostOptions=[],
                methodCustomizationSupported=readonly, methodGuide=describe_guide(component_id, harness) if readonly else None,
                methodKind="HOST_METHOD", providerOptions=[],
                verification={"contract": "DECLARATION_COMPATIBLE", "installation": "NOT_OBSERVED",
                              "realAuthorization": "NOT_RUN", "environment": "PREFLIGHT_REQUIRED"})
            components[component_id] = component
        component = components[component_id]
        component["methodCustomizationSupported"] &= readonly
        component["runtimeIds"] = [rid for rid in component["runtimeIds"] if rid in entry["runtimeIds"]]
        for compatibility in component["runtimeCompatibility"]:
            compatibility["supported"] = compatibility["runtimeId"] in component["runtimeIds"]
        for key, values in (("capabilityIds", sorted(caps)), ("packBindings", [binding]),
                            ("moduleIds", [module_id]), ("providerOptions", entry["providerOptions"]),
                            ("runtimeHostOptions", entry['runtimeHostOptions']),
                            ("capabilityPackRefs", first["capabilityPackRefs"]),
                            ("domainPackRefs", first["domainPackRefs"]), ("tools", first["tools"])):
            _extend_unique(component[key], values)
        if len(entry["providers"]) == 1:
            _extend_unique(component["providerRefs"], first["providerRefs"])
            component["capabilityBindings"].update(first["capabilityBindings"])
        _extend_unique(modules[module_id]["componentIds"], [component_id])
    directory["components"] = list(components.values())
    for component in components.values():
        component["methodKind"] = "DOMAIN_PACK" if component["domainPackRefs"] else "HOST_METHOD"
        if not component["methodCustomizationSupported"]:
            component["methodGuide"] = None
        if component["domain"] not in {d["id"] for d in directory["domains"]}:
            directory["domains"].append({"id": component["domain"], "label": modules[component["domain"]]["label"]})
    for template in directory["templates"]:
        template["componentIds"] = [cid for cid in template["componentIds"] if cid in components]
    for module in modules.values():
        parts = [components[cid] for cid in module["componentIds"]]
        module.update(status="PARTIAL" if parts else "NOT_INTEGRATED",
                      capabilityIds=sorted({cid for c in parts for cid in c["capabilityIds"]}),
                      tools=[tool for c in parts for tool in c["tools"]],
                      runtimeIds=[rid for rid in all_runtime_ids
                                  if any(rid in c["runtimeIds"] for c in parts)],
                      fullSelectionRuntimeIds=[rid for rid in all_runtime_ids
                                               if parts and all(rid in c["runtimeIds"] for c in parts)])
        if parts:
            module["scope"] = " / ".join(c["label"] for c in parts)
    ungrouped_id = presentation["ungroupedModule"]["id"]
    if not modules[ungrouped_id]["componentIds"]:
        del modules[ungrouped_id]
    entries_by_pack = {}
    for entry in managed["entries"]:
        entries_by_pack.setdefault(entry["pack"]["id"], []).append(entry)
    for blueprint in taxonomy["taskBlueprints"]:
        valid = True
        for pack_id in blueprint["packIds"]:
            matches = entries_by_pack.get(pack_id, [])
            if (len(matches) != 1
                    or len(matches[0]["moduleIds"]) != 1
                    or matches[0]["moduleIds"][0] not in blueprint["moduleIds"]):
                valid = False
        blueprint["availability"] = "READY" if valid else "UNAVAILABLE"
        if not valid:
            blueprint["unavailableReason"] = "PACK_REFERENCE_UNAVAILABLE"
    for template in taxonomy["templates"]:
        if template["id"] == "mobile-banking":
            template["scope"] = "客户本人 · 模拟银行；操作以所选能力包声明为准"
    custom = deepcopy(presentation["customTemplate"])
    custom.update(moduleIds=list(modules), runtimeIds=all_runtime_ids[:],
                  bankConnectionIds=["local-simulator"], profileId=managed["profile"],
                  applicationTemplateId="banking-readonly-web-v1")
    taxonomy["templates"].append(custom)
    for template in taxonomy["templates"]:
        if template["id"] in {"mobile-banking", custom["id"]}:
            template["moduleIds"] = list(modules)
        if template["id"] in {"mobile-banking", "loan-review", custom["id"]}:
            default_ids = {cid for module_id in template["defaultModuleIds"]
                           for cid in original_module_components[module_id]}
            template["defaultComponentIds"] = [cid for cid in components if cid in default_ids]
            template["defaultPackBindings"] = [deepcopy(binding)
                for cid in template["defaultComponentIds"] for binding in components[cid]["packBindings"]]
            _extend_unique(template['runtimeIds'], [r['id'] for r in runtime_declarations
                if any(r['id'] in c['runtimeIds'] for c in components.values())])
            template["profileIds"] = sorted({option["profileRef"]["id"]
                for module_id in template["moduleIds"]
                for component_id in modules[module_id]["componentIds"]
                for option in components[component_id]["providerOptions"]})
            _extend_unique(template['profileIds'], sorted({option['profileRef']['id']
                for component in components.values() for option in component['runtimeHostOptions']}))
    taxonomy.update(modules=list(modules.values()), components=directory["components"])
    # These are explicit projection fields, not additions to the old strict source catalog.
    for view in (directory, taxonomy):
        if 'runtimes' not in view:
            view['runtimes'] = deepcopy(taxonomy['runtimes'])
        for runtime in view.get('runtimes', []):
            runtime['selectionSchemaVersion'] = '0.9'
            runtime['invocationProtocol'] = 'finance-chat'
            if runtime['id'] == 'openjiuwen-java-0.1.15':
                runtime['description'] = 'Java · 0.1.15 · 查询与办理'
                runtime['architectureLabel'] = '原生 Java Agent'
            elif runtime['id'] == PYTHON_RUNTIME:
                runtime['description'] = 'Python · 0.1.18 · 只读查询'
                runtime['architectureLabel'] = 'Python Agent · Harness 执行桥'
        for record in runtime_declarations:
            host_options = []
            for host in record['hosts']:
                option = {'hostId': host['id'], 'profileRef': deepcopy(host['profileRef']),
                          'applicationTemplateIds': host['applicationTemplateIds'], 'presentations': host['presentations']}
                try:
                    supported, support_path = host_support(project, record, host)
                    option['operations'] = list(supported['operations'])
                    frozen = freeze_binding(project, record, host, support_path, harness=harness)
                    option['managedTemplate'] = frozen['managedTemplate']
                    option['supportProfile'] = frozen['supportProfile']
                except (OSError, ValueError, KeyError, TypeError):
                    option['declarationStatus'] = 'UNAVAILABLE'
                host_options.append(option)
            view.setdefault('runtimes', []).append({
                **{k: deepcopy(record[k]) for k in ('id', 'label', 'family', 'language', 'version', 'status', 'enabled',
                                                   'selectionSchemaVersion', 'invocationProtocol', 'artifactKind')},
                **availability[record['id']],
                'description': record['description'],
                'architectureLabel': record['architectureLabel'],
                'declarationDigest': record['declarationDigest'], 'hostOptions': host_options, 'capabilitySelection': 'ATOMIC_PACK',
                'metadataRole': 'CATALOG_SELECTION_NOT_INSTALLATION'})
    for value in (directory, taxonomy):
        value["managedRevision"] = managed["revision"]
        value["presentationVersion"] = presentation["version"]
        value["revision"] = _revision(value)
    value = {**managed, "schemaVersion": "finance-unified-catalog.v0.1",
             "managedRevision": managed["revision"], "directory": directory,
             "templateModuleDirectory": taxonomy}
    value.pop("revision")
    value["revision"] = _revision(value)
    return value
