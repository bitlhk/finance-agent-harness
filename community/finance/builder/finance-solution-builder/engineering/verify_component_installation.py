"""Exercise the generated Java admission entry, without creating a bank session or Agent.

Cases deliberately rehash modified metadata: hashes alone cannot establish that a
declaration matches the loaded implementation. Test roots share only compiled JARs
with the supplied candidate; they are not portable generated distributions.
"""
import argparse
from copy import deepcopy
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import subprocess


def run(candidate: Path, output: Path):
    candidate = candidate.resolve()
    language = json.loads((candidate / "service-lock.json").read_text())["runtime"]["language"]
    entry = {"java": "ComponentAdmission", "python": "ReadOnlyPlanExporter"}.get(language)
    if entry is None:
        raise ValueError("UNSUPPORTED_CANDIDATE_RUNTIME")
    output.mkdir(parents=True, exist_ok=False)
    jars = list((candidate / "target").glob("*.jar"))
    assert len(jars) == 1, "CANDIDATE_JAR_REQUIRED"
    classpath = str(jars[0]) + os.pathsep + str(candidate / "target/dependency/*")
    cases = [("valid", None), ("missing-plan", "COMPONENT_PLAN_NOT_LOCKED"),
             ("capability-scopes", "COMPONENT_CAPABILITY_PLAN_MISMATCH"),
             ("provider-binding", ("COMPONENT_PROVIDER_PLAN_MISMATCH", "PROVIDER_FACTORY_DEPENDENCY_UNAVAILABLE")),
             ("provider-version", "COMPONENT_IMPLEMENTATION_VERSION_MISMATCH"),
             ("forged-runtime-pair", "COMPONENT_RUNTIME_PLAN_MISMATCH")]
    results = []
    for name, expected in cases:
        root = output / name
        shutil.copytree(candidate, root, ignore=shutil.ignore_patterns(
            "target", ".venv", "__pycache__", "*.log", "service-state"))
        lock = json.loads((root / "service-lock.json").read_text())
        plan = json.loads((root / "component-plan.json").read_text())
        support = json.loads((root / "host-support.v0.1.json").read_text())
        if name == "capability-scopes":
            plan["capabilities"][0]["requiredScopes"] = ["fictional:read"]
        elif name == "provider-binding":
            key = next(iter(plan["capabilityBindings"]))
            plan["capabilityBindings"][key] = "not-the-installed-provider"
        elif name == "provider-version":
            for provider in plan["providerRefs"]:
                provider["version"] = "999.999"
        elif name == "forged-runtime-pair":
            plan["runtime"]["templateRef"] = "maven://com.openjiuwen:agent-core-java:999.999"
            support["runtime"] = deepcopy(plan["runtime"])
        support_bytes = json.dumps(support).encode()
        (root / "host-support.v0.1.json").write_bytes(support_bytes)
        plan["managedTemplate"]["supportDigest"] = "sha256:" + sha256(support_bytes).hexdigest()
        (root / "component-plan.json").write_text(json.dumps(plan))
        for file in ("component-plan.json", "host-support.v0.1.json"):
            lock["files"][file] = sha256((root / file).read_bytes()).hexdigest()
        (root / "service-lock.json").write_text(json.dumps(lock))
        if name == "missing-plan":
            (root / "component-plan.json").unlink()
        process = subprocess.run(["java", "-cp", classpath,
            "io.github.bitlhk.finance.builder.local." + entry, str(root)],
            text=True, capture_output=True, timeout=60)
        text = process.stdout + process.stderr
        (root / "probe.log").write_text(text)
        errors = expected if isinstance(expected, tuple) else (expected,)
        passed = process.returncode == 0 if expected is None else process.returncode != 0 and any(error in text for error in errors)
        results.append(dict(case=name, expected=expected or "PREPARED", exitCode=process.returncode,
                            passed=passed))
        (output / "results.json").write_text(json.dumps(results, indent=2))
    assert all(case["passed"] for case in results), results
    return results


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.candidate, args.output)))
