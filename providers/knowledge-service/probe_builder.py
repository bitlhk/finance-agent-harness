"""Read-only admission probe against a separately pinned Builder checkout.

Never patch a Host declaration to manufacture a successful build.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(builder, harness):
    base = builder / "community/finance/builder"
    project = base / "finance-solution-builder"
    sys.path[:0] = [str(project / "src"), str(base / "base/src")]
    from finance_solution_builder.component_assembly import ComponentAssembly, AssemblyRejected

    selection = [{"pack": {"id": "knowledge-readonly", "version": "0.1.0"},
                  "provider": {"id": "knowledge-service", "version": "0.1.0-probe.1"}}]
    results = []
    for path in sorted((project / "templates").glob("**/*host-support.v0.1.json")):
        host = json.loads(path.read_bytes())
        if host.get("profile") != "local-read-only":
            continue
        for kind in host["connectionKinds"]:
            try:
                plan = ComponentAssembly(harness).select(selection, host=host, connection_kind=kind)
                result = {"status": "PLAN_ONLY_NOT_INSTALLED", "plan": plan}
            except AssemblyRejected as error:
                result = {"status": "BLOCKED", "code": str(error)}
            results.append({"host": str(path.relative_to(builder)), "hostSha256": sha(path),
                            "runtime": host["runtime"], "connectionKind": kind, **result})
    if not results:
        raise RuntimeError("NO_COMPATIBLE_PROFILE_DECLARATIONS_FOUND")
    return {"schemaVersion": "knowledge-builder-probe.v0.1",
            "builderCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=builder, text=True).strip(),
            "harnessBase": "e911c71bdd3404f450bf775f32ebe028f90fb475",
            "selection": selection, "hosts": results, "installedAgent": None, "installationLock": None,
            "agentUiToolCall": "NOT_RUN_ADMISSION_BLOCKED", "modelCalls": 0,
            "productionModified": False}


if __name__ == "__main__":
    cli = argparse.ArgumentParser()
    cli.add_argument("--builder", required=True, type=Path)
    cli.add_argument("--output", required=True, type=Path)
    args = cli.parse_args()
    report = run(args.builder.resolve(), Path(__file__).resolve().parents[2])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"hosts": len(report["hosts"]), "results": [x["status"] for x in report["hosts"]]}))
