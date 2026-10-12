"""Qualify the actual managed selection/generator output with isolated Host checks."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

PROJECT = Path(__file__).resolve().parents[1]
TEMPLATES = PROJECT / "templates"


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def run(command, out: Path, env=None, cwd=None):
    with out.open("w") as log:
        result = subprocess.run([str(x) for x in command], cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"COMMAND_FAILED:{out.name}:exit={result.returncode}")


def stage(output: Path, harness: Path, native: Path, repo: Path, maven: str, *, tasks: bool = False):
    sys.path[:0] = [str(PROJECT / "src"), str(PROJECT.parent / "base/src")]
    from finance_solution_builder.managed_runtime_support import declarations
    from finance_solution_builder.agentscope_service import AgentScopeServiceBuild, host_source_inventory, host_template_digest
    from finance_solution_builder.builder import SolutionBuilder
    from finance_solution_builder.unified_catalog import unified_catalog
    from finance_solution_builder.workbench_contracts import parse_selection

    candidate = output / "candidate"
    template = TEMPLATES / "agentscope-query-service"
    record = next(item for item in declarations(PROJECT) if item["id"] == "agentscope-java-2.0.3")
    host_id = "agentscope-task-service" if tasks else "agentscope-query-service"
    host = next(item for item in record["hosts"] if item["id"] == host_id)
    environment = {"FH_CANDIDATE_RUNTIME_IDS": record["id"]}
    catalog = unified_catalog(PROJECT, harness, environment=environment)
    directory = catalog["templateModuleDirectory"]
    packs = ["account-balance-readonly", "cashflow-readonly"]
    if tasks:
        packs.append("internal-transfer")
    entries = [next(e for e in catalog["entries"] if e["pack"]["id"] == name) for name in packs]
    components = {entry["componentId"] for entry in entries}
    selection = {
        "extension_type": "finance-workbench.selection", "schema_version": "0.10",
        "runtimeId": record["id"], "hostId": host["id"], "profileRef": host["profileRef"],
        "applicationTemplateId": "banking-readonly-web-v1", "bankConnectionId": "local-simulator",
        "scenarioTemplateId": "customer-custom", "directoryRevision": directory["revision"],
        "selectedModelRefs": ["model-controlled"], "defaultModelRef": "model-controlled",
        "modelCatalogRevision": "0" * 64,
        "moduleIds": [module["id"] for module in directory["modules"] if components.intersection(module["componentIds"])],
        "packBindings": [{"pack": entry["pack"], "provider": entry["providers"][0]} for entry in entries],
        "capabilityIds": sorted(cap["capabilityId"] for entry in entries for cap in entry["capabilities"]),
    }
    write(output / "selection-input.json", selection)
    solution, resolved, selected_plan = parse_selection(selection).resolve_details(
        PROJECT, harness, "agentscope-host-controlled", environment=environment)
    binding = resolved["runtimeBinding"]
    identity = binding["managedTemplate"]
    support_source = template / "agentscope-support.v0.1.json"
    if binding["supportProfile"] != {
        "source": "builder-template://agentscope-query-service/agentscope-support.v0.1.json",
        "digest": "sha256:" + digest(support_source),
    }:
        raise AssertionError("MANAGED_SUPPORT_SOURCE_NOT_SELECTED")
    builder = SolutionBuilder(PROJECT, harness, maven=maven, service_output_root=output)
    generated = AgentScopeServiceBuild(builder, solution, output / "generator", binding, None)
    generated.assemble()
    generated.build(verify=False)
    write(output / "selection-plan.json", selected_plan)
    comparison = {"selectionPlanSha256": digest(output / "selection-plan.json"),
                  "generatorPlanSha256": digest(generated.candidate / "component-plan.json"),
                  "equal": selected_plan == generated.plan,
                  "differentTopLevelKeys": sorted(key for key in set(selected_plan) | set(generated.plan)
                                                  if selected_plan.get(key) != generated.plan.get(key))}
    write(output / "selection-generator-comparison.json", comparison)
    if not comparison["equal"]:
        raise AssertionError("SELECTION_GENERATOR_PLAN_DIFFERENCE")
    shutil.copytree(generated.candidate, candidate)
    sources = host_source_inventory(PROJECT, binding)
    if not sources or sources != read(generated.output / "template-sources.json"):
        raise AssertionError("GENERATOR_SOURCE_CLOSURE_MISMATCH")
    template_digest = host_template_digest(PROJECT, binding)
    if identity["digest"] == template_digest:
        raise AssertionError("COMPOSED_AND_INVENTORY_IDENTITIES_CONFLATED")
    write(output / "template-sources.json", sources)
    write(output / "template-identity.json", {"managedTemplate": identity, "hostTemplateDigest": template_digest,
          "identityFunction": "finance_solution_builder.managed_runtime_support.managed_template_identity",
          "inventoryFunction": "finance_solution_builder.agentscope_service.host_template_digest"})
    assets = candidate / "assets"
    write(candidate / "ui-config.json", {"sessionPolicy": {"idleSeconds": 30, "warningSeconds": 2, "absoluteSeconds": 300}})
    # OPTIONAL bootstraps evidence, not production readiness. REQUIRED is tested against the resulting proof.
    requirement = {"schemaVersion": "finance-observation-requirements.v0.1", "id": "agentscope-host-observation", "version": "0.1.0",
                   "requirements": [
                       {"semanticId": "execution.history", "semanticVersion": "0.1", "obligation": "OPTIONAL", "boundary": "HOST",
                        "content": "METADATA", "fields": ["requestId", "outcome"], "capabilityIds": []},
                       {"semanticId": "model.client.snapshot", "semanticVersion": "0.1", "obligation": "OPTIONAL", "boundary": "MODEL_CLIENT",
                        "content": "DIAGNOSTIC_BODY", "fields": ["messages", "tools", "parameters", "response"], "capabilityIds": []},
                   ]}
    write(assets / "host-observation.json", requirement)
    write(candidate / "observation-reference.json", {"id": requirement["id"], "version": requirement["version"],
          "source": "repo://host-observation.json", "digest": "sha256:" + digest(assets / "host-observation.json")})
    run([maven, "-B", f"-Dmaven.repo.local={repo}", "package"], output / "host-maven.log", cwd=candidate)
    jars = list((candidate / "target").glob("*.jar"))
    if len(jars) != 1:
        raise AssertionError("PACKAGED_JAR_COUNT")
    dependencies = list((candidate / "target/dependency").glob("*.jar"))
    if any("openjiuwen" in jar.name.lower() or "agent-core-java" in jar.name for jar in dependencies):
        raise AssertionError("OJ_RUNTIME_DEPENDENCY")
    lock = read(candidate / "service-lock.json")
    for path in sorted(candidate.rglob("*")):
        relative = path.relative_to(candidate).as_posix()
        if path.is_file() and relative != "service-lock.json" and (relative.startswith("assets/") or path.suffix == ".json" and not relative.startswith("target/")
                              or path in dependencies or path in jars):
            lock["files"][relative] = digest(path)
    write(candidate / "service-lock.json", lock)
    preserved = ["component-plan.json", "solution-input.json", "solution.json", "agentscope-support-source.json",
                 "agentscope-support.json", "runtime-asset-projection.json"]
    raw = {name: {"generator": digest(generated.candidate / name), "qualifiedInput": digest(candidate / name)}
           for name in preserved}
    write(output / "generator-byte-preservation.json", {"files": raw,
          "bootstrapBuild": "NOT_RUN", "hostCompilation": "PASS",
          "backendRegistryJobs": "NOT_CLAIMED", "modelCatalogAdmission": "NOT_CLAIMED"})
    if any(item["generator"] != item["qualifiedInput"] for item in raw.values()):
        raise AssertionError("GENERATOR_BYTES_CHANGED")
    return candidate


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--native-bundle", type=Path, required=True)
    parser.add_argument("--maven-repo", type=Path, default=PROJECT / "target/m2-agentscope")
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--stage-only", action="store_true")
    parser.add_argument("--tasks", action="store_true", help="Stage the managed QUERY/TASK Host; requires TASK qualification")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    try:
        if args.tasks and not args.stage_only:
            raise ValueError("TASK stage requires --stage-only; run shared TASK acceptance separately")
        candidate = stage(output, args.harness.resolve(), args.native_bundle.resolve(), args.maven_repo.resolve(), args.maven, tasks=args.tasks)
        write(output / "stage-result.json", {"status": "PASS", "scope": "PACKAGED_TEMPLATE_AND_SHARED_JAVA_TESTS",
              "candidate": str(candidate), "nativeQualification": (args.native_bundle / "qualification/qualification.sha256").read_text().strip()})
        print(f"Packaged template: {candidate}")
        if not args.stage_only:
            from agentscope_host_checks import verify
            verify(candidate, args.harness.resolve(), args.native_bundle.resolve(), output / "host-checks")
    except Exception as failure:
        write(output / "GENERATION-FAILED.json", {"status": "FAIL", "type": type(failure).__name__, "message": str(failure)})
        raise


if __name__ == "__main__":
    main()
