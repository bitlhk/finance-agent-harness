"""Finite persistent-service recipe. Keeps the original controlled recipe and Base API unchanged."""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import signal
import sys
import time
from copy import deepcopy
from pathlib import Path

from finance_builder_base.runtime import copy_directory_tree, require_path_within, spawn_process
from .build_process import build_process_options
from .readonly import copy_asset, revision

CASHFLOW = {"finance.transaction-summary.query", "finance.spending-analysis.query"}
CAPABILITIES = {"wealth.facts.describe", "account.balance.query"} | CASHFLOW
PROFILE = "local-read-only"
MAIN = "io.github.bitlhk.finance.builder.local.LocalAgentHost"


def maven_runtime_java(maven: str) -> str:
    """Use the JVM Maven selected to compile the candidate, when discoverable."""
    try:
        probe = subprocess.run([maven, "--version"], capture_output=True, text=True, timeout=15, check=False)
        output = probe.stdout + probe.stderr
        match = re.search(r"^Java home:\s*(.+)$", output, re.MULTILINE)
        if match is None:
            # Homebrew's Maven wrapper labels the same value as `runtime`.
            match = re.search(r"^Java version:.*?, runtime:\s*(.+)$", output, re.MULTILINE)
        if match:
            executable = Path(match.group(1).strip()) / "bin" / "java"
            if executable.is_file() and os.access(executable, os.X_OK):
                return str(executable)
    except (OSError, subprocess.SubprocessError):
        pass
    return "java"


def resolved_openjiuwen_runtime(solution):
    """Freeze the Maven artifacts behind a source-backed OpenJiuwen runtime."""
    value = deepcopy(solution)
    runtime = value.get("runtime", {})
    template = runtime.get("templateRef")
    integration = runtime.get("integrationRef")
    if (runtime.get("family") == "openjiuwen" and runtime.get("language") == "java"
            and isinstance(template, str) and template.startswith("maven://")
            and integration == "repo://integrations/openjiuwen-java"):
        runtime["resolvedArtifacts"] = {
            "templateRef": template,
            "integrationRef": "maven://io.github.bitlhk.finance:finance-harness-openjiuwen-java:0.1.0-SNAPSHOT",
        }
    return value


def valid_method_scope(values):
    return (isinstance(values, list) and bool(values) and all(isinstance(c, str) for c in values)
            and len(values) == len(set(values)) and set(values) <= CAPABILITIES)


def valid_capability_set(values):
    return (valid_method_scope(values)
            and (not set(values) & CASHFLOW or CASHFLOW <= set(values)))


def stable_failure_code(error):
    """Expose only a bounded machine-readable build gate, never exception details."""
    structured = getattr(error, "code", None)
    if isinstance(structured, str) and re.fullmatch(r"[a-z0-9_.-]{3,64}", structured):
        return re.sub(r"[.-]", "_", structured).upper()
    match = re.match(r"^([A-Z][A-Z0-9_]{2,63})(?::|$)", str(error))
    return match.group(1) if match else type(error).__name__.upper()


def installation_for_method(values):
    if not valid_method_scope(values):
        raise ValueError("SERVICE_METHOD_SCOPE_INVALID")
    return sorted(set(values) | (CASHFLOW if set(values) & CASHFLOW else set()))


def stop_owned_process(child, *, group=False, timeout=12):
    if child is None or child.poll() is not None:
        return
    try:
        if group and os.name == "posix":
            os.killpg(child.pid, signal.SIGTERM)
        else:
            child.terminate()
    except ProcessLookupError:
        child.wait()
        return
    try:
        child.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        if group and os.name == "posix":
            os.killpg(child.pid, signal.SIGKILL)
        else:
            child.kill()
        child.wait(timeout=5)


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _verify_harness_jars(harness_repo: Path, candidate: Path) -> None:
    if (candidate / "pom.xml").is_file():
        from .artifact_plan import verify_financial_artifacts
        evidence = verify_financial_artifacts(harness_repo, candidate)
        write_json(candidate / "build-artifacts.json", evidence)
        return
    expected = {}
    modules = ["packages/java/core", "packages/java/wealth", "integrations/openjiuwen-java", "providers/non-ai-banking"]
    for module in ("packages/java/cashflow", "packages/java/assembly", "packages/java/execution"):
        if (harness_repo / module / "pom.xml").is_file():
            modules.append(module)
    for module in modules:
        jars = list((harness_repo / module / "target").glob("*.jar"))
        if len(jars) != 1:
            raise ValueError("HARNESS_JAR_UNAVAILABLE")
        expected[jars[0].name] = digest(jars[0])
    actual = {p.name: digest(p) for p in (candidate / "target/dependency").glob("finance-*.jar")}
    if actual != expected:
        raise ValueError("HARNESS_JARS_MISMATCH")


def write_json(path: Path, value: dict) -> None:
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def solution_for(capabilities: list[str], build_id: str, method_ref=None, method_capabilities=None) -> dict:
    if not valid_capability_set(capabilities):
        raise ValueError("READONLY_SELECTION_INVALID")
    from .readonly import RECIPE
    value = json.loads(RECIPE.read_text(encoding="utf-8"))
    value.update(solutionId=build_id, displayName="Local Financial Agent", modelRef="config://local-model")
    value["profileRef"] = {"id": PROFILE, "version": "0.1.0", "source": "repo://profiles/local-read-only.md"}
    value["domainPackRefs"] = []
    value["capabilityPackRefs"] = []
    value["providerRefs"] = []
    value["capabilityBindings"] = {}
    if "wealth.facts.describe" in capabilities:
        value["domainPackRefs"] = [{"id": "wealth-shared-facts", "version": "0.1.1", "source": "repo://domain-packs/wealth/shared-facts"}]
        value["capabilityPackRefs"].append({"id": "wealth-facts-description", "version": "0.1.0", "source": "repo://capability-packs/wealth-facts-description"})
        value["providerRefs"].append({"id": "wealth-facts-adapter", "version": "0.1.0", "source": "maven://io.github.bitlhk.finance:finance-harness-wealth:0.1.0-SNAPSHOT"})
        value["capabilityBindings"]["wealth.facts.describe"] = "wealth-facts-adapter"
    if "account.balance.query" in capabilities:
        value["capabilityPackRefs"].append({"id": "account-balance-readonly", "version": "0.1.0", "source": "repo://capability-packs/account-balance-readonly"})
        value["providerRefs"].append({"id": "non-ai-banking-reference", "version": "0.1.0-SNAPSHOT", "source": "maven://io.github.bitlhk.finance:finance-provider-non-ai-banking:0.1.0-SNAPSHOT"})
        value["capabilityBindings"]["account.balance.query"] = "non-ai-banking-reference"
    if CASHFLOW <= set(capabilities):
        value["domainPackRefs"].append({"id": "cashflow-customer-self-service", "version": "0.1.3", "source": "repo://domain-packs/cashflow/customer-self-service"})
        value["capabilityPackRefs"].append({"id": "cashflow-readonly", "version": "0.1.0", "source": "repo://capability-packs/cashflow-readonly"})
        if not any(ref["id"] == "non-ai-banking-reference" for ref in value["providerRefs"]):
            value["providerRefs"].append({"id": "non-ai-banking-reference", "version": "0.1.0-SNAPSHOT", "source": "maven://io.github.bitlhk.finance:finance-provider-non-ai-banking:0.1.0-SNAPSHOT"})
        value["capabilityBindings"].update({c: "non-ai-banking-reference" for c in sorted(CASHFLOW)})
    if method_ref is not None:
        if (not isinstance(method_ref, dict) or set(method_ref) != {"id", "version", "source", "digest"}
                or not re.fullmatch(r"method-[a-f0-9]{16}", str(method_ref["id"]))
                or not re.fullmatch(r"[0-9]{1,4}\.[0-9]{1,4}\.[0-9]{1,4}", str(method_ref["version"]))
                or method_ref["source"] != "repo://private-methods/" + method_ref["id"] + "/" + method_ref["version"]
                or not re.fullmatch(r"sha256:[a-f0-9]{64}", str(method_ref["digest"]))):
            raise ValueError("SERVICE_METHOD_REFERENCE_INVALID")
        scope = capabilities if method_capabilities is None else method_capabilities
        if not valid_method_scope(scope) or not set(scope) <= set(capabilities):
            raise ValueError("SERVICE_METHOD_SCOPE_INVALID")
        replaced = set()
        if "wealth.facts.describe" in scope:
            replaced.add("wealth-shared-facts")
        # Replace a domain pack as a unit; a method may use only a subset of its installed tools.
        if CASHFLOW & set(scope):
            replaced.add("cashflow-customer-self-service")
        value["domainPackRefs"] = [ref for ref in value["domainPackRefs"] if ref["id"] not in replaced]
        value["domainPackRefs"].append(dict(method_ref))
    return value


def validate_service(solution: dict) -> list[str]:
    ids = [r.get("id") for r in solution.get("capabilityPackRefs", [])]
    mapping = {"wealth-facts-description": ["wealth.facts.describe"], "account-balance-readonly": ["account.balance.query"],
               "cashflow-readonly": sorted(CASHFLOW)}
    if any(i not in mapping for i in ids):
        raise ValueError("SERVICE_CAPABILITY_UNSUPPORTED")
    capabilities = [cap for i in ids for cap in mapping[i]]
    domain_refs = solution.get("domainPackRefs", [])
    methods = [ref for ref in domain_refs if str(ref.get("id", "")).startswith("method-")]
    method_ref = methods[0] if len(methods) == 1 else None
    retained = set()
    if any(ref.get("id") == "wealth-shared-facts" for ref in domain_refs):
        retained.add("wealth.facts.describe")
    if any(ref.get("id") == "cashflow-customer-self-service" for ref in domain_refs):
        retained.update(CASHFLOW)
    scope = [cap for cap in capabilities if cap not in retained] if method_ref else None
    expected = solution_for(capabilities, solution.get("solutionId", ""), method_ref, scope)
    if solution != expected:
        raise ValueError("SERVICE_COMPOSITION_UNSUPPORTED")
    return capabilities


def template_digest(project):
    from .assets import tree_digest
    return tree_digest(project / "templates/local-readonly-service")


def reuse_binaries(builder, source, candidate):
    """Reuse a verified compatible host, never mutate or hard-link the source artifact."""
    resource = Path("src/main/resources/capability-presentations.json")
    before, after = source / resource, candidate / resource
    if before.exists() != after.exists() or (before.exists() and before.read_bytes() != after.read_bytes()):
        raise ValueError("REUSABLE_PRESENTATION_MANIFEST_CHANGED")
    manifest_file = source / "service-lock.json"
    report = json.loads((source.parent / "build-report.json").read_text())
    manifest = json.loads(manifest_file.read_text())
    if (report.get("status") != "BUILT" or digest(manifest_file) != report.get("lockSha256")
            or manifest.get("verification") != "COMPILED"
            or manifest.get("hostTemplateDigest") != template_digest(builder.project_root)
            or manifest.get("sources", {}).get("harness") != revision(builder.harness_repo)):
        raise ValueError("REUSABLE_HOST_INCOMPATIBLE")
    jars = {}
    for name, expected in manifest["files"].items():
        file = require_path_within(source / name, source)
        if file.is_symlink() or not file.is_file() or digest(file) != expected:
            raise ValueError("REUSABLE_HOST_ARTIFACT_CHANGED")
        if name.startswith("target/") and name.endswith(".jar"):
            target = candidate / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, target)
            jars[name] = expected
    if len(list((candidate / "target").glob("*.jar"))) != 1 or not jars:
        raise ValueError("REUSABLE_HOST_JAR_MISSING")
    return {"sourceBuildId": manifest["buildId"], "sourceLockSha256": digest(manifest_file), "jars": jars}


def build_service(builder, solution: dict, output: Path, verify: bool, *, model_selection=None, interface_config=None,
                  method_snapshot=None, reuse_candidate=None, observation_requirements=None, managed=False):
    from .builder import BuildResult
    from .component_plan import qualify_solution
    if managed and not re.fullmatch(r"[a-z][a-z0-9-]{2,63}", str(solution.get("solutionId", ""))):
        raise ValueError("ASSEMBLY_SELECTION_INVALID")
    component_plan = qualify_solution(builder.project_root, builder.harness_repo, solution, method_snapshot) if managed else None
    capabilities = sorted(component_plan["capabilityBindings"]) if managed else validate_service(solution)
    method_refs = [r for r in solution["domainPackRefs"] if r["id"].startswith("method-")]
    if method_refs:
        from .method_assets import runtime_reference, materialize_snapshot
        if (method_snapshot is None or runtime_reference(method_snapshot) != method_refs[0]
                or not set(method_snapshot["data"]["capabilityIds"]) <= set(capabilities)
                or (not managed and solution != solution_for(capabilities, solution["solutionId"], method_refs[0], method_snapshot["data"]["capabilityIds"]))):
            raise ValueError("SERVICE_METHOD_SNAPSHOT_REQUIRED")
    elif method_snapshot is not None:
        raise ValueError("SERVICE_METHOD_SNAPSHOT_UNEXPECTED")
    component_plan = qualify_solution(builder.project_root, builder.harness_repo, solution, method_snapshot)
    resolved_runtime = resolved_openjiuwen_runtime(solution)["runtime"].get("resolvedArtifacts")
    if resolved_runtime is not None:
        component_plan["resolvedRuntime"] = resolved_runtime
    allowed = getattr(builder, "service_output_root", None) or builder.project_root / "build"
    if allowed.is_symlink():
        raise ValueError("OUTPUT_ROOT_INVALID")
    allowed.mkdir(parents=True, exist_ok=True)
    output = require_path_within(output, allowed)
    if output == allowed or output.exists():
        raise ValueError("OUTPUT_EXISTS")
    output.mkdir(parents=True)
    candidate = output / "candidate"
    sources = {"harness": revision(builder.harness_repo), "builder": revision(builder.project_root)}
    runtime_java = maven_runtime_java(builder.maven)
    report = {"schemaVersion": "local-agent-build.v0.1", "buildId": solution["solutionId"], "status": "BUILDING",
              "sources": sources, "stages": [], "realModel": "NOT_RUN", "fullSimulator": "NOT_RUN", "humanOnboarding": "PENDING"}
    report_path = output / "build-report.json"
    began = time.monotonic()

    def stage(name: str, command: list[str], cwd: Path, *, maven: bool = False) -> None:
        log = output / "logs" / (name + ".log")
        log.parent.mkdir(exist_ok=True)
        item = {"name": name, "status": "RUNNING", "log": "logs/" + log.name}
        report["stages"].append(item)
        write_json(report_path, report)
        started = time.monotonic()
        with log.open("wb") as stream:
            child = None
            try:
                cancel = getattr(builder, "service_cancel", None)
                if cancel is not None and cancel.is_set():
                    raise RuntimeError("BUILD_CANCELLED")
                child = spawn_process(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT,
                                      start_new_session=os.name == "posix", **build_process_options(maven=maven))
                builder.service_process = child
                if cancel is not None and cancel.is_set():
                    stop_owned_process(child, group=True)
                code = child.wait(timeout=900)
            except subprocess.TimeoutExpired:
                stop_owned_process(child, group=True)
                code = 124
            except OSError:
                code = 127
            except RuntimeError:
                code = 130
            finally:
                builder.service_process = None
        item.update(status="PASS" if code == 0 else "FAIL", exitCode=code,
                    elapsedMillis=round((time.monotonic() - started) * 1000), logSha256=digest(log))
        write_json(report_path, report)
        if code:
            raise RuntimeError("BUILD_STAGE_FAILED: " + name)

    try:
        copy_directory_tree(builder.project_root, "templates/local-readonly-service", output, "candidate")
        from .component_plan import support_file_for
        shutil.copyfile(support_file_for(builder.project_root, solution["profileRef"]["id"]),
                        candidate / "host-support.v0.1.json")
        write_json(candidate / "component-plan.json", component_plan)
        from .presentation_assets import presentation_manifest
        write_json(candidate / "src/main/resources/capability-presentations.json",
                   presentation_manifest(builder.harness_repo, component_plan))
        routing = component_plan.get("routingAssets", {})
        routing_refs = [*routing.get("descriptionRefs", []), *routing.get("terminologyRefs", [])]
        for ref in routing_refs:
            copy_asset(builder.harness_repo, Path(ref.removeprefix("repo://")), candidate / "assets")
        write_json(candidate / "routing-selection.json", {
            "schemaVersion": "finance-routing-selection.v0.1",
            "descriptionRefs": routing.get("descriptionRefs", []),
            "terminologyRefs": routing.get("terminologyRefs", [])})
        pom = candidate / "pom.xml"
        pom.write_text(pom.read_text(encoding="utf-8").replace("@@SOLUTION_ID@@", solution["solutionId"]), encoding="utf-8")
        from .artifact_plan import add_provider_dependencies
        add_provider_dependencies(pom, component_plan["providerRefs"])
        from .artifact_plan import financial_artifact_plan
        artifact_plan = financial_artifact_plan(builder.harness_repo, candidate) if verify else None
        refs = [solution["profileRef"], *solution["domainPackRefs"], *solution["capabilityPackRefs"]]
        # External evaluation packs are retained as immutable solution inputs;
        # they are not runtime assets to copy from the local Harness archive.
        if solution["evaluationPackRef"]["source"].startswith("repo://"):
            refs.append(solution["evaluationPackRef"])
        for ref in refs:
            if ref in method_refs:
                continue
            copy_asset(builder.harness_repo, Path(ref["source"].removeprefix("repo://")), candidate / "assets")
        if method_refs:
            materialize_snapshot(method_snapshot, candidate / "assets")
            write_json(candidate / "method-selection.json", {
                "assetId": method_snapshot["assetId"], "version": method_snapshot["version"],
                "releaseDigest": method_snapshot["releaseDigest"], "capabilityIds": method_snapshot["data"]["capabilityIds"],
                "installationDigest": method_snapshot["installationDigest"]})
        # The source-backed integration reference is provenance, while the JVM
        # assembler needs the immutable Maven artifact it actually loads.
        write_json(candidate / "solution.json", resolved_openjiuwen_runtime(solution))
        if observation_requirements is not None:
            # Requirements stay in Harness; Builder copies a selected asset, not financial rules.
            selected = require_path_within(Path(observation_requirements), builder.harness_repo)
            from .observation_assets import materialize
            write_json(candidate / "observation-reference.json", materialize(
                builder.harness_repo, selected, candidate, capabilities))
        if interface_config is not None:
            write_json(candidate / "ui-config.json", interface_config)
            copy_directory_tree(builder.project_root, "examples/agent-experience", candidate, "ui")
            portable = candidate / "portable/finance_solution_builder"
            portable.mkdir(parents=True)
            (portable / "__init__.py").write_text("")
            for name in ("experience.py", "experience_contracts.py", "speech.py", "portable_ui.py", "task_routes.py"):
                shutil.copy2(builder.project_root / "src/finance_solution_builder" / name, portable / name)
        if model_selection is not None:
            write_json(candidate / "model-selection.json", model_selection)
        reuse = None
        if verify and reuse_candidate is not None:
            reused_at = time.monotonic()
            reuse = reuse_binaries(builder, reuse_candidate, candidate)
            report["stages"].append({"name": "asset_reassembly", "status": "PASS", "exitCode": 0,
                                      "jarsVerified": len(reuse["jars"]), "elapsedMillis": round((time.monotonic() - reused_at) * 1000)})
        elif verify:
            stage("harness_contracts", [sys.executable, "engineering/verify_contracts.py"], builder.harness_repo)
            stage("harness_tests_install", [builder.maven, "-B", "-ntp", "install"], builder.harness_repo, maven=True)
            stage("candidate_package", [builder.maven, "-B", "-ntp", "package"], candidate, maven=True)
        if verify:
            if financial_artifact_plan(builder.harness_repo, candidate) != artifact_plan:
                raise ValueError("ARTIFACT_PLAN_CHANGED_DURING_BUILD")
            _verify_harness_jars(builder.harness_repo, candidate)
        files = {}
        for path in sorted(candidate.rglob("*")):
            if path.is_file() and ("target" not in path.parts or path.suffix == ".jar"):
                files[path.relative_to(candidate).as_posix()] = digest(path)
        if sources != {"harness": revision(builder.harness_repo), "builder": revision(builder.project_root)}:
            raise ValueError("SOURCE_CHANGED_DURING_BUILD")
        lock = {"schemaVersion": "local-agent-service-lock.v0.1", "buildId": solution["solutionId"],
                "sources": sources, "runtime": solution["runtime"], "capabilities": capabilities, "files": files,
                "verification": "COMPILED" if verify else "NOT_RUN", "modelConnection": "EXTERNAL_HOST_CONFIGURATION",
                "javaExecutable": runtime_java}
        lock["hostTemplateDigest"] = template_digest(builder.project_root)
        if reuse is not None:
            lock["binaryReuse"] = reuse
        if method_refs:
            lock["method"] = json.loads((candidate / "method-selection.json").read_text())
        lock["transport"] = {"agUi": "AG_UI_SSE_V1", "tokenStreaming": False,
                             "historyOwner": "HOST", "clientTools": False}
        if model_selection is not None:
            lock["modelSelection"] = model_selection
        if interface_config is not None:
            lock["interface"] = {"configuration": interface_config, "sha256": digest(candidate / "ui-config.json"),
                                 "resources": {k: v for k, v in files.items() if k.startswith("ui/")}}
        write_json(candidate / "service-lock.json", lock)
        write_json(output / "solution-lock.json", lock)
        if verify:
            jars = list((candidate / "target").glob("*.jar"))
            if len(jars) != 1: raise ValueError("CANDIDATE_JAR_MISSING")
            stage("component_installation", [runtime_java, "-cp", str(jars[0]) + os.pathsep + "target/dependency/*",
                  "io.github.bitlhk.finance.builder.local.ComponentAdmission", str(candidate)], candidate)
            lock["files"]["component-installation-plan.json"] = digest(candidate / "component-installation-plan.json")
            lock["componentAdmission"] = {"plan": "component-plan.json",
                "observedPlan": "component-installation-plan.json", "status": "PREPARED_NOT_RUNTIME_REGISTERED"}
            write_json(candidate / "service-lock.json", lock)
            write_json(output / "solution-lock.json", lock)
        if verify and observation_requirements is not None:
            jars = list((candidate / "target").glob("*.jar"))
            if len(jars) != 1:
                raise ValueError("CANDIDATE_JAR_MISSING")
            stage("observation_admission", [runtime_java, "-cp", str(jars[0]) + os.pathsep + "target/dependency/*",
                  "io.github.bitlhk.finance.builder.local.ObservationAdmission", str(candidate)], candidate)
        report.update(status="BUILT" if verify else "NOT_RUN", lockSha256=digest(output / "solution-lock.json"))
    except Exception as error:
        report.update(status="FAILED", failureCode=stable_failure_code(error))
        raise RuntimeError("LOCAL_SERVICE_BUILD_FAILED: inspect stage logs") from None
    finally:
        report["elapsedMillis"] = round((time.monotonic() - began) * 1000)
        write_json(report_path, report)
    return BuildResult(output, output / "solution-lock.json", report_path, candidate, report["status"])
