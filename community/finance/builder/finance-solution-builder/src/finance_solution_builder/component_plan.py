"""Compile the managed local Host's admission plan, independently of UI claims."""
from copy import deepcopy
from hashlib import sha256
import json
from pathlib import Path

from .assets import tree_digest
from .component_assembly import AssemblyRejected, ComponentAssembly


def host_support_files(project):
    template = project / "templates/local-readonly-service"
    files = [template / "host-support.v0.1.json"]
    tasks = template / "task-host-support.v0.1.json"
    if tasks.is_file() and not tasks.is_symlink(): files.append(tasks)
    internal_control = template / "internal-control-host-support.v0.1.json"
    if internal_control.is_file() and not internal_control.is_symlink(): files.append(internal_control)
    return files


def support_file_for(project, profile):
    if not (project / "templates/local-readonly-service/host-support.v0.1.json").is_file():
        raise AssemblyRejected("ASSEMBLY_MANAGED_HOST_REQUIRED")
    matches = [path for path in host_support_files(project)
               if path.is_file() and not path.is_symlink() and json.loads(path.read_bytes())["profile"] == profile]
    if len(matches) > 1:
        raise AssemblyRejected("MANAGED_HOST_AMBIGUOUS")
    if matches:
        return matches[0]
    raise AssemblyRejected("ASSEMBLY_HOST_PROFILE_MISMATCH")


def qualify_solution(project: Path, harness: Path, solution, method_snapshot=None, *, runtime_binding=None):
    template = project / "templates/local-readonly-service"
    if runtime_binding is not None:
        from .managed_runtime_support import resolve_binding, freeze_binding, template_path
        record, host, _, support_file = resolve_binding(project, runtime_binding.get('runtimeId'),
            runtime_binding.get('hostId'), solution['profileRef'], require_enabled=False)
        if runtime_binding != freeze_binding(project, record, host, support_file, harness=harness):
            raise AssemblyRejected('MANAGED_HOST_BINDING_MISMATCH')
        template = template_path(project, host)
    else:
        support_file = support_file_for(project, solution["profileRef"]["id"])
    if not support_file.is_file() or support_file.is_symlink():
        raise AssemblyRejected("ASSEMBLY_MANAGED_HOST_REQUIRED")
    support = json.loads(support_file.read_bytes())
    if solution["profileRef"] != {"id": support["profile"], "version": "0.1.0",
                                   "source": "repo://profiles/" + support["profile"] + ".md"}:
        raise AssemblyRejected("ASSEMBLY_HOST_PROFILE_MISMATCH")
    methods = solution["domainPackRefs"]
    private = [ref for ref in methods if str(ref["id"]).startswith("method-")]
    if private:
        from .method_assets import runtime_reference
        if (len(private) != 1 or method_snapshot is None or runtime_reference(method_snapshot) != private[0]
                or not set(method_snapshot["data"]["capabilityIds"]) <= set(solution["capabilityBindings"])):
            raise AssemblyRejected("ASSEMBLY_METHOD_SNAPSHOT_MISMATCH")
        if runtime_binding is not None:
            from .managed_runtime_support import validate_method_runtime
            validate_method_runtime(method_snapshot, solution['runtime'])
    elif method_snapshot is not None:
        raise AssemblyRejected("ASSEMBLY_METHOD_SNAPSHOT_MISMATCH")
    assembly = ComponentAssembly(harness)
    connection_kind = support["connectionKinds"][0]
    result = assembly.resolve(solution["capabilityPackRefs"], solution["providerRefs"],
        solution["capabilityBindings"], host=support, runtime=solution["runtime"],
        connection_kind=connection_kind, domain_refs=[ref for ref in methods if ref not in private])
    if runtime_binding is not None:
        from .managed_runtime_support import validate_runtime_support
        validate_runtime_support(assembly, record, host, result, project=project)
    scope = set(method_snapshot["data"]["capabilityIds"]) if private else set()
    expected = []
    for default in result["defaultMethods"]:
        if not scope.intersection(default["capabilityIds"]) and default["identity"] not in expected:
            expected.append(default["identity"])
    observed = [{"id": r["id"], "version": r["version"]} for r in result["domainPackRefs"]]
    if observed != expected:
        raise AssemblyRejected("ASSEMBLY_METHOD_SELECTION_MISMATCH")
    routing = support.get("routingAssets", {})
    pattern = routing.get("descriptionPattern", "")
    terminology = routing.get("terminologyRefs", [])
    if (not isinstance(pattern, str) or "{packId}" not in pattern
            or not isinstance(terminology, list) or not all(isinstance(item, str) for item in terminology)):
        raise AssemblyRejected("ASSEMBLY_ROUTING_ASSETS_INVALID")
    description_refs = []
    for pack in result["capabilityPackRefs"]:
        ref = pattern.replace("{packId}", pack["id"])
        relative = Path(ref.removeprefix("repo://"))
        if not ref.startswith("repo://") or relative.is_absolute() or ".." in relative.parts:
            raise AssemblyRejected("ASSEMBLY_ROUTING_ASSETS_INVALID")
        if (harness / relative).is_file():
            description_refs.append(ref)
    terminology_refs = []
    for ref in terminology:
        relative = Path(ref.removeprefix("repo://"))
        if not ref.startswith("repo://") or relative.is_absolute() or ".." in relative.parts:
            raise AssemblyRejected("ASSEMBLY_ROUTING_ASSETS_INVALID")
        if (harness / relative).is_file():
            terminology_refs.append(ref)
    plan = {"schemaVersion": "finance-local-component-plan.v0.1",
            "runtime": deepcopy(solution["runtime"]), "profileRef": deepcopy(solution["profileRef"]),
            "capabilityBindings": result["capabilityBindings"], "capabilities": result["capabilities"],
            "capabilityPackRefs": result["capabilityPackRefs"], "providerRefs": result["providerRefs"],
            "requiredScopes": result["requiredScopes"], "domainPackRefs": deepcopy(methods),
            "managedTemplate": {"source": "builder-template://local-readonly-service",
                                "digest": tree_digest(template),
                                "supportDigest": "sha256:" + sha256(support_file.read_bytes()).hexdigest()},
            "evidence": result["evidence"], "connectionKind": connection_kind,
            "routingAssets": {"descriptionRefs": description_refs, "terminologyRefs": terminology_refs},
            "limitations": ["SINGLE_CONNECTION_KIND", "MANAGED_CLASSPATH_FACTORIES_ONLY", "LEGACY_WEB_SELECTIONS_REMAIN_FINITE"]}
    if runtime_binding is not None:
        plan['managedTemplate'] = deepcopy(runtime_binding['managedTemplate'])
        plan['runtimeBinding'] = deepcopy(runtime_binding)
    return plan
