"""Verify the declared capability matrix and optionally build representative artifacts.

Resolution exhaustively covers every non-empty component subset. Artifact builds
cover every singleton plus the maximal set for each Runtime; this proves every
pack independently and detects aggregate classpath/tool/contract conflicts without
re-running the Harness reactor for every powerset member.
"""
from __future__ import annotations

import argparse
from itertools import combinations
import json
import os
from pathlib import Path
import time

from finance_solution_builder.managed_runtime_support import declarations
from finance_solution_builder.unified_catalog import unified_catalog
from finance_solution_builder.workbench import Jobs
from finance_solution_builder.workbench_contracts import parse_selection


PROJECT = Path(__file__).resolve().parents[1]


def groups(components):
    for size in range(1, len(components) + 1):
        yield from combinations(components, size)


def managed_host(group, preferred=None):
    choices = [{option["hostId"]: option for option in component["runtimeHostOptions"]} for component in group]
    common = set.intersection(*(set(choice) for choice in choices))
    if not common:
        raise AssertionError("DECLARED_COMPONENTS_HAVE_NO_COMMON_HOST")
    if preferred is not None and preferred not in common:
        raise AssertionError("PREFERRED_HOST_DOES_NOT_SUPPORT_COMPONENTS")
    host_id = preferred or sorted(common)[0]
    return choices[0][host_id]


def selection(directory, runtime_id, group, preferred_host=None):
    capabilities = sorted({cap for component in group for cap in component["capabilityIds"]})
    value = {
        "extension_type": "finance-workbench.selection", "schema_version": "0.9",
        "runtimeId": runtime_id, "applicationTemplateId": "banking-readonly-web-v1",
        "bankConnectionId": "local-simulator", "capabilityIds": capabilities,
        "selectedModelRefs": ["model-matrix"], "defaultModelRef": "model-matrix",
        "modelCatalogRevision": "a" * 64, "scenarioTemplateId": "customer-custom",
        "directoryRevision": directory["revision"],
        "moduleIds": sorted({module for component in group for module in component["moduleIds"]}),
        "packBindings": [binding for component in group for binding in component["packBindings"]],
    }
    if runtime_id.startswith("agentscope-"):
        try:
            host = managed_host(group, preferred_host)
            host_id, profile = host["hostId"], host["profileRef"]
        except AssertionError:
            # Negative combinations have no advertised Host option. Supply a
            # real declared Host so resolution can prove that it rejects the
            # unsupported asset rather than failing request parsing first.
            record = next(item for item in declarations(PROJECT) if item["id"] == runtime_id)
            declared = record["hosts"][0]
            host_id, profile = declared["id"], declared["profileRef"]
        value.update(schema_version="0.10", hostId=host_id, profileRef=profile)
    return value


def wait(jobs, job_id, timeout=1200):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status = jobs.jobs[job_id]["status"]
        if status not in {"QUEUED", "BUILDING"}:
            return status
        time.sleep(0.2)
    raise TimeoutError(job_id)


def verify(harness: Path, output: Path | None, maven: str, runtime_filter=(), preferred_host=None):
    catalog = unified_catalog(PROJECT, harness)
    directory = catalog["templateModuleDirectory"]
    result = {"schemaVersion": "finance-runtime-capability-matrix.v0.1", "builder": None,
              "harness": None, "runtimes": [], "status": "RUNNING"}
    for label, root in (("builder", PROJECT), ("harness", harness)):
        import subprocess
        override = os.environ.get("FH_MATRIX_" + label.upper() + "_REVISION")
        result[label] = override or subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()

    jobs = Jobs(PROJECT, harness, maven=maven, data_dir=output) if output else None
    try:
        for runtime in directory["runtimes"]:
            runtime_id = runtime["id"]
            if runtime_filter and runtime_id not in runtime_filter:
                continue
            supported = [component for component in directory["components"] if runtime_id in component["runtimeIds"]]
            runtime_result = {"runtimeId": runtime_id, "components": [c["id"] for c in supported],
                              "resolved": [], "rejected": [], "builds": []}
            for group in groups(supported):
                request = selection(directory, runtime_id, group, preferred_host)
                parsed = parse_selection(request)
                solution, _, _ = parsed.resolve_details(PROJECT, harness, "matrix-resolution")
                if sorted(solution["capabilityBindings"]) != request["capabilityIds"]:
                    raise AssertionError("RESOLVED_CAPABILITY_MISMATCH")
                runtime_result["resolved"].append([component["id"] for component in group])

            unsupported = [component for component in directory["components"] if runtime_id not in component["runtimeIds"]]
            for component in unsupported:
                request = selection(directory, runtime_id, [component], preferred_host)
                try:
                    parse_selection(request).resolve_details(PROJECT, harness, "matrix-negative")
                except Exception as error:
                    runtime_result["rejected"].append({"component": component["id"], "reason": str(error)})
                else:
                    raise AssertionError("UNDECLARED_RUNTIME_COMBINATION_ACCEPTED")

            if jobs:
                representatives = [(component,) for component in supported]
                maximal = tuple(supported)
                if maximal not in representatives:
                    representatives.append(maximal)
                for group in representatives:
                    request = selection(directory, runtime_id, group, preferred_host)
                    created = jobs.create(parse_selection(request).legacy(), "matrix-owner", selection=request)
                    status = wait(jobs, created["id"])
                    report = {"jobId": created["id"], "components": [component["id"] for component in group],
                              "status": status}
                    runtime_result["builds"].append(report)
                    if status != "BUILT":
                        raise AssertionError("REPRESENTATIVE_BUILD_FAILED:" + created["id"])
            result["runtimes"].append(runtime_result)
        result["status"] = "PASS"
        return result
    finally:
        if jobs:
            jobs.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, help="Enable representative artifact builds in this new directory")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--runtime", action="append", default=[], help="Limit artifact and resolution checks to this Runtime ID")
    parser.add_argument("--host", help="Require this declared Host for the selected Runtime components")
    args = parser.parse_args()
    if args.output and args.output.exists():
        parser.error("output already exists")
    report = verify(args.harness.resolve(), args.output.resolve() if args.output else None,
                    args.maven, args.runtime, args.host)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "runtimes": [
        {"runtimeId": item["runtimeId"], "resolved": len(item["resolved"]),
         "rejected": len(item["rejected"]), "builds": len(item["builds"])} for item in report["runtimes"]]}))


if __name__ == "__main__":
    main()
