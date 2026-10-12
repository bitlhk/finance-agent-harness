"""Materialize and compile the declared native AgentScope Host, without legacy runtime reuse."""
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

from finance_builder_base.runtime import copy_directory_tree, require_path_within, spawn_process

from .build_process import build_process_options
from .component_assembly import AssemblyRejected, ComponentAssembly
from .component_plan import qualify_solution
from .managed_launcher import AGENTSCOPE_ENTRYPOINT, checked_file, jar_inventory
from .readonly import copy_asset, revision
from .service import digest, stop_owned_process, write_json


def canonical_digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def host_source_inventory(project, binding):
    name = binding["managedTemplate"]["source"].removeprefix("builder-template://")
    root = project / "templates" / name
    files = {name + "/" + p.relative_to(root).as_posix(): digest(p)
             for p in sorted(root.rglob("*")) if p.is_file()}
    files.update({source.removeprefix("builder-template://"): sha.removeprefix("sha256:")
                  for source, sha in binding["managedTemplate"].get("sharedSources", {}).items()})
    return dict(sorted(files.items()))


def host_template_digest(project, binding):
    return "sha256:" + canonical_digest(host_source_inventory(project, binding))


def support_profile_bytes(project, harness, binding):
    from .managed_runtime_support import SUPPORT_PROFILE_SOURCE
    reference = binding["supportProfile"]
    source = reference["source"]
    template = binding["managedTemplate"]["source"]
    try:
        if source == SUPPORT_PROFILE_SOURCE and source.startswith(template + "/"):
            root = Path(project) / "templates" / template.removeprefix("builder-template://")
            path = checked_file(root, source.removeprefix(template + "/"))
        else:
            raise ValueError
        with path.open("rb") as stream:
            value = stream.read(65537)
        if len(value) > 65536 or "sha256:" + hashlib.sha256(value).hexdigest() != reference["digest"]:
            raise ValueError
        return value
    except (OSError, ValueError):
        raise AssemblyRejected("MANAGED_HOST_BINDING_MISMATCH") from None


def _harness_tree_digest(root):
    value = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            raise AssemblyRejected("ASSEMBLY_SOURCE_INVALID")
        if path.is_file() and not {"target", "build", "logs", ".git"}.intersection(path.relative_to(root).parts):
            value.update(path.relative_to(root).as_posix().encode())
            value.update(b"\0")
            value.update(path.read_bytes())
            value.update(b"\0")
    return "sha256:" + value.hexdigest()


def project_observation_refs(assembly, references, capabilities):
    """Select whole applicable declarations; never clip or weaken a requirement."""
    if not references:
        return [], []
    schema = assembly._json("contracts/json-schema/observation/observation-requirements.v0.1.schema.json")
    installed = set(capabilities)
    selected, decisions = [], []
    for ref in references:
        source = ref["source"]
        if not source.startswith("repo://"):
            raise AssemblyRejected("ASSEMBLY_LOCAL_SOURCE_REQUIRED")
        relative = source.removeprefix("repo://")
        document = assembly._json(relative)
        assembly._validate(document, schema)
        if (assembly.evidence[relative] != ref["digest"]
                or (document["id"], document["version"]) != (ref["id"], ref["version"])):
            raise AssemblyRejected("ASSEMBLY_SOURCE_INVALID")
        scopes = [set(item["capabilityIds"]) for item in document["requirements"]]
        scope = set().union(*scopes)
        common = any(not item for item in scopes)
        applicable = common or not scope or bool(scope & installed)
        if applicable and not scope <= installed:
            raise AssemblyRejected("ASSEMBLY_OBSERVATION_SCOPE_INCOMPLETE")
        if applicable:
            selected.append(deepcopy(ref))
        decisions.append({"reference": deepcopy(ref), "capabilityIds": sorted(scope),
                          "containsCommonRequirements": common,
                          "status": "SELECTED" if applicable else "NOT_APPLICABLE"})
    return selected, decisions


def project_runtime_assets(harness, candidate, solution, plan, support, method_snapshot=None):
    """Pin the repo-file view consumed by Harness, retaining the uploaded build input separately."""
    assets = candidate / "assets"
    assets.mkdir(exist_ok=True)
    assembly = ComponentAssembly(harness)
    private = {method_snapshot["assetId"]} if method_snapshot is not None else set()
    if method_snapshot is not None:
        from .method_assets import materialize_snapshot
        materialize_snapshot(method_snapshot, assets)
    result = deepcopy(solution)
    for group in ("domainPackRefs", "capabilityPackRefs", "providerRefs"):
        projected = []
        for ref in solution[group]:
            source = ref["source"]
            if group == "providerRefs":
                declared = assembly.assets[("provider", ref["id"], ref["version"])]
                source = declared["source"] + "/assembly.v0.1.json"
            if not source.startswith("repo://"):
                raise AssemblyRejected("ASSEMBLY_LOCAL_SOURCE_REQUIRED")
            relative = Path(source.removeprefix("repo://"))
            if ref["id"] not in private:
                copy_asset(harness, relative, assets)
            target = require_path_within(assets / relative, assets)
            if target.is_dir():
                relative /= "pack.yaml"
                target = checked_file(assets, relative)
            checksum = _harness_tree_digest(target.parent) if group == "domainPackRefs" else "sha256:" + digest(target)
            projected.append({"id": ref["id"], "version": ref["version"],
                              "source": "repo://" + relative.as_posix(), "digest": checksum})
        result[group] = projected
    profile = solution["profileRef"]
    copy_asset(harness, Path(profile["source"].removeprefix("repo://")), assets)
    result["profileRef"] = dict(profile, digest="sha256:" + digest(checked_file(assets, profile["source"].removeprefix("repo://"))))
    copy_asset(harness, Path("catalog/finance-assets.v1.json"), assets)
    # Evaluation is a build input, not an installed runtime capability or a claimed run.
    evaluation = result.pop("evaluationPackRef", None)
    if evaluation is not None:
        copy_asset(harness, Path(evaluation["source"].removeprefix("repo://")), assets)
    selected_descriptions = set(plan["routingAssets"]["descriptionRefs"])
    projected_support = deepcopy(support)
    projected_support["descriptions"] = [ref for ref in support["descriptions"] if ref["source"] in selected_descriptions]
    if {ref["source"] for ref in projected_support["descriptions"]} != selected_descriptions:
        raise AssemblyRejected("ASSEMBLY_ROUTING_ASSETS_INVALID")
    projected_support["observationRequirements"], observation_projection = project_observation_refs(
        assembly, support["observationRequirements"], solution["capabilityBindings"])
    for ref in [*projected_support["descriptions"], *support["terminology"], *projected_support["observationRequirements"]]:
        relative = ref["source"].removeprefix("repo://")
        copy_asset(harness, Path(relative), assets)
        if "sha256:" + digest(checked_file(assets, relative)) != ref["digest"]:
            raise AssemblyRejected("ASSEMBLY_SOURCE_INVALID")
    write_json(candidate / "solution-input.json", solution)
    write_json(candidate / "solution.json", result)
    write_json(candidate / "agentscope-support.json", projected_support)
    write_json(candidate / "runtime-asset-projection.json", {
        "schemaVersion": "finance-runtime-asset-projection.v0.1",
        "input": "solution-input.json", "inputSha256": digest(candidate / "solution-input.json"),
        "runtimeInput": "solution.json", "runtimeInputSha256": digest(candidate / "solution.json"),
        "supportInputSha256": canonical_digest(support),
        "runtimeSupportSha256": digest(candidate / "agentscope-support.json"),
        "observationRequirements": observation_projection,
        "evaluation": {"status": "NOT_RUN", "reference": evaluation},
    })


class AgentScopeServiceBuild:
    def __init__(self, builder, solution, output, runtime_binding, runtime_admission, **options):
        self.builder, self.solution, self.output = builder, deepcopy(solution), Path(output)
        self.binding, self.admission = deepcopy(runtime_binding), deepcopy(runtime_admission)
        self.options = options
        self.candidate = self.output / "candidate"
        self.report_path = self.output / "build-report.json"
        self.report = {"schemaVersion": "local-agent-build.v0.1", "buildId": solution["solutionId"],
                       "status": "BUILDING", "stages": [], "realModel": "NOT_RUN", "fullSimulator": "NOT_RUN",
                       "plannedStages": ["candidate_package", "component_installation"],
                       "humanOnboarding": "PENDING", "backendId": "agentscope-java"}

    def assemble(self):
        if self.options.get("reuse_candidate") is not None:
            raise AssemblyRejected("MANAGED_RUNTIME_REUSE_UNSUPPORTED")
        builder, output, candidate = self.builder, self.output, self.candidate
        self.plan = qualify_solution(builder.project_root, builder.harness_repo, self.solution,
            self.options.get("method_snapshot"), runtime_binding=self.binding)
        self.source_inventory = host_source_inventory(builder.project_root, self.binding)
        if self.plan["managedTemplate"] != self.binding["managedTemplate"]:
            raise AssemblyRejected("MANAGED_HOST_BINDING_MISMATCH")
        allowed = builder.service_output_root or builder.project_root / "build"
        if allowed.is_symlink():
            raise ValueError("OUTPUT_ROOT_INVALID")
        allowed.mkdir(parents=True, exist_ok=True)
        require_path_within(output, allowed)
        if output == allowed or output.exists():
            raise ValueError("OUTPUT_EXISTS")
        output.mkdir(parents=True)
        write_json(output / "template-sources.json", self.source_inventory)
        self.sources = {"harness": revision(builder.harness_repo), "builder": revision(builder.project_root)}
        self.report["sources"] = self.sources
        name = self.binding["managedTemplate"]["source"].removeprefix("builder-template://")
        copy_directory_tree(builder.project_root, "templates/" + name, output, "candidate")
        host_source = self.binding["managedTemplate"]["supportSource"]
        prefix = self.binding["managedTemplate"]["source"] + "/"
        if not host_source.startswith(prefix):
            raise AssemblyRejected("MANAGED_HOST_BINDING_MISMATCH")
        host_file = checked_file(builder.project_root / "templates" / name, host_source.removeprefix(prefix))
        if "sha256:" + digest(host_file) != self.binding["managedTemplate"]["supportDigest"]:
            raise AssemblyRejected("MANAGED_HOST_BINDING_MISMATCH")
        shutil.copy2(host_file, candidate / "host-support.v0.1.json")
        for source, expected in self.binding["managedTemplate"].get("sharedSources", {}).items():
            relative = Path(source.removeprefix("builder-template://"))
            path = checked_file(builder.project_root / "templates", relative)
            if "sha256:" + digest(path) != expected:
                raise AssemblyRejected("MANAGED_HOST_BINDING_MISMATCH")
            target = candidate.joinpath(*relative.parts[1:])
            if target.exists():
                raise AssemblyRejected("MANAGED_HOST_SOURCE_INVALID")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
        support_bytes = support_profile_bytes(builder.project_root, builder.harness_repo, self.binding)
        (candidate / "agentscope-support-source.json").write_bytes(support_bytes)
        project_runtime_assets(builder.harness_repo, candidate, self.solution, self.plan,
            json.loads(support_bytes), self.options.get("method_snapshot"))
        method = self.options.get("method_snapshot")
        if method is not None:
            write_json(candidate / "method-selection.json", {
                **{key: method[key] for key in ("assetId", "version", "releaseDigest", "installationDigest")},
                "capabilityIds": method["data"]["capabilityIds"]})
        write_json(candidate / "component-plan.json", self.plan)
        pom = candidate / "pom.xml"
        pom.write_text(pom.read_text().replace("@@SOLUTION_ID@@", self.solution["solutionId"]))
        observation = self.options.get("observation_requirements")
        if observation is not None:
            from .observation_assets import materialize
            selected = require_path_within(Path(observation), builder.harness_repo)
            write_json(candidate / "observation-reference.json", materialize(builder.harness_repo,
                selected, candidate, sorted(self.solution["capabilityBindings"])))
        interface = self.options.get("interface_config")
        if interface is not None:
            write_json(candidate / "ui-config.json", interface)
            copy_directory_tree(builder.project_root, "examples/agent-experience", candidate, "ui")
            portable = candidate / "portable/finance_solution_builder"
            portable.mkdir(parents=True)
            (portable / "__init__.py").write_text("")
            for name in ("experience.py", "experience_contracts.py", "speech.py", "portable_ui.py", "task_routes.py"):
                shutil.copy2(builder.project_root / "src/finance_solution_builder" / name, portable / name)
        if self.options.get("model_selection") is not None:
            write_json(candidate / "model-selection.json", self.options["model_selection"])
        self.assembled_files = self.files()
        write_json(self.report_path, self.report)

    def files(self):
        return {p.relative_to(self.candidate).as_posix(): digest(p) for p in sorted(self.candidate.rglob("*"))
                if p.is_file() and ("target" not in p.relative_to(self.candidate).parts or p.suffix.lower() == ".jar")
                and p.name != "service-lock.json"}

    def stage(self, name, command, cwd, *, maven=False):
        log = self.output / "logs" / (name + ".log")
        log.parent.mkdir(exist_ok=True)
        item = {"name": name, "status": "RUNNING", "log": "logs/" + log.name}
        self.report["stages"].append(item)
        write_json(self.report_path, self.report)
        started = time.monotonic()
        cancel = getattr(self.builder, "service_cancel", None)
        child = None
        code = 130
        try:
            if cancel is not None and cancel.is_set():
                raise RuntimeError("BUILD_CANCELLED")
            with log.open("wb") as stream:
                log.chmod(0o600)
                child = spawn_process(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT,
                                      start_new_session=os.name == "posix", **build_process_options(maven=maven))
                self.builder.service_process = child
                if cancel is not None and cancel.is_set():
                    stop_owned_process(child, group=True)
                code = child.wait(timeout=900)
        except (OSError, subprocess.TimeoutExpired, RuntimeError):
            code = 130 if cancel is not None and cancel.is_set() else 1
        finally:
            if child is not None and child.poll() is None:
                stop_owned_process(child, group=True)
            self.builder.service_process = None
            item.update(status="PASS" if code == 0 else "FAIL", exitCode=code,
                        elapsedMillis=round((time.monotonic() - started) * 1000))
            if log.exists():
                item["logSha256"] = digest(log)
            write_json(self.report_path, self.report)
        if code:
            raise RuntimeError("BUILD_STAGE_FAILED: " + name)

    def build(self, verify=True):
        from .builder import BuildResult
        started = time.monotonic()
        try:
            if self.files() != self.assembled_files:
                raise ValueError("ASSEMBLY_CHANGED")
            qualify_solution(self.builder.project_root, self.builder.harness_repo, self.solution,
                self.options.get("method_snapshot"), runtime_binding=self.binding)
            if verify:
                command = [self.builder.maven, "-B", "-ntp"]
                repository = os.environ.get("FH_MAVEN_REPO")
                if repository:
                    command.append("-Dmaven.repo.local=" + str(Path(repository).resolve()))
                self.stage("candidate_package", [*command, "package"], self.candidate, maven=True)
                jars = jar_inventory(self.candidate)
                dependencies = {Path(name).name: sha for name, sha in jars.items() if name.startswith("target/dependency/")}
                if not dependencies or dependencies != self.admission.get("runtimeDependencies"):
                    raise ValueError("QUALIFIED_RUNTIME_DEPENDENCIES_CHANGED")
            qualify_solution(self.builder.project_root, self.builder.harness_repo, self.solution,
                self.options.get("method_snapshot"), runtime_binding=self.binding)
            for relative, expected in self.plan["evidence"]["files"].items():
                if "sha256:" + digest(checked_file(self.builder.harness_repo, relative)) != expected:
                    raise ValueError("SOURCE_CHANGED_DURING_BUILD")
            if any(self.files().get(name) != sha for name, sha in self.assembled_files.items()):
                raise ValueError("ASSEMBLY_CHANGED")
            lock = {"schemaVersion": "local-agent-service-lock.v0.1", "buildId": self.solution["solutionId"],
                    "sources": self.sources, "runtime": self.solution["runtime"],
                    "capabilities": sorted(self.solution["capabilityBindings"]), "files": self.files(),
                    "verification": "COMPILED" if verify else "NOT_RUN",
                    "modelConnection": "EXTERNAL_HOST_CONFIGURATION", "runtimeBinding": self.binding,
                    "runtimeAdmission": self.admission, "runtimePresentation": self.binding["runtimePresentation"],
                    "managedEntrypoint": AGENTSCOPE_ENTRYPOINT,
                    "hostTemplateDigest": host_template_digest(self.builder.project_root, self.binding),
                    "managedTemplateDigest": self.binding["managedTemplate"]["digest"],
                    "sharedHostSources": self.source_inventory,
                    "transport": {"agUi": "AG_UI_SSE_V1", "tokenStreaming": False, "historyOwner": "HOST", "clientTools": False}}
            if self.options.get("model_selection") is not None:
                lock["modelSelection"] = self.options["model_selection"]
            if self.options.get("method_snapshot") is not None:
                lock["method"] = json.loads((self.candidate / "method-selection.json").read_bytes())
            if self.options.get("interface_config") is not None:
                lock["interface"] = {"configuration": self.options["interface_config"],
                    "sha256": digest(self.candidate / "ui-config.json"),
                    "resources": {k: v for k, v in lock["files"].items() if k.startswith("ui/")}}
            self.write_lock(lock)
            if verify:
                from .managed_launcher import launch_command
                command = launch_command(self.candidate, lock)
                command[-2] = "io.github.bitlhk.finance.builder.local.AgentScopeAdmission"
                self.stage("component_installation", command, self.candidate)
                plan = self.candidate / "component-installation-plan.json"
                lock["files"][plan.name] = digest(plan)
                lock["componentAdmission"] = {"plan": "component-plan.json", "observedPlan": plan.name,
                                              "status": "PREPARED_NOT_RUNTIME_REGISTERED"}
                self.write_lock(lock)
            self.report.update(status="BUILT" if verify else "NOT_RUN", lockSha256=digest(self.output / "solution-lock.json"))
        except Exception:
            self.report["status"] = "FAILED"
            raise
        finally:
            self.report["elapsedMillis"] = round((time.monotonic() - started) * 1000)
            write_json(self.report_path, self.report)
        return BuildResult(self.output, self.output / "solution-lock.json", self.report_path,
                           self.candidate, self.report["status"])

    def write_lock(self, lock):
        write_json(self.candidate / "service-lock.json", lock)
        write_json(self.output / "solution-lock.json", lock)
