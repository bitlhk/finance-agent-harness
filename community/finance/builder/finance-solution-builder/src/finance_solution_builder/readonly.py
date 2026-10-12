"""Explicit controlled reference recipe, not a production host or a second Harness."""
from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

from finance_builder_base.runtime import copy_directory_tree, require_path_within, run_process

from .assets import tree_digest
from .build_process import build_process_options

PROJECT = Path(__file__).resolve().parents[2]
RECIPE = PROJECT / "examples/wealth-facts-readonly/solution-build-input.v1.json"
KIT = Path("examples/solutions/wealth-facts-kit")
JAVA_MAIN = "io.github.bitlhk.finance.builder.candidate.ReadOnlyCandidate"
MODULES = ["packages/java/core", "packages/java/wealth", "integrations/openjiuwen-java",
           "providers/non-ai-banking", "providers/employee-agent", str(KIT)]


def validate_readonly(solution: dict) -> None:
    expected = json.loads(RECIPE.read_text(encoding="utf-8"))
    actual = json.loads(json.dumps(solution))
    for field in ("profileRef", "evaluationPackRef"):
        actual.get(field, {}).pop("digest", None)
    for field in ("domainPackRefs", "capabilityPackRefs"):
        for ref in actual.get(field, []):
            ref.pop("digest", None)
    # An external Provider source digest cannot certify a loaded Java artifact.
    # Runtime and Provider JARs are checked after actual installation instead.
    for field in ("solutionId", "displayName"):
        actual[field] = expected[field]
    if actual != expected:
        raise ValueError("READONLY_RECIPE_UNSUPPORTED: use the versioned controlled wealth recipe; live/task combinations are not enabled")


def sha(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def revision(repo: Path) -> dict:
    head = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    dirty = subprocess.check_output(["git", "-C", str(repo), "status", "--porcelain"], text=True)
    return {"commit": head, "worktreeDirty": bool(dirty)}


def copy_asset(root: Path, relative: Path, destination: Path) -> None:
    source = require_path_within(root / relative, root)
    if source.is_symlink() or not source.exists():
        raise ValueError("READONLY_ASSET_UNAVAILABLE")
    target = destination / relative
    if source.is_dir():
        copy_directory_tree(root, relative, destination, relative)
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def assemble(builder, solution: dict, candidate: Path) -> None:
    copy_directory_tree(builder.project_root, "templates/clean-readonly-agent", candidate.parent, candidate.name)
    pom = candidate / "pom.xml"
    pom.write_text(pom.read_text(encoding="utf-8").replace("@@SOLUTION_ID@@", solution["solutionId"]), encoding="utf-8")
    assets = candidate / "assets"
    assets.mkdir()
    refs = [solution["profileRef"], *solution["domainPackRefs"], *solution["capabilityPackRefs"], solution["evaluationPackRef"]]
    for ref in refs:
        copy_asset(builder.harness_repo, Path(ref["source"].removeprefix("repo://")), assets)
    for path in [KIT / "fixtures", Path("contracts/json-schema/wealth/wealth-facts.v0.1.schema.json")]:
        copy_asset(builder.harness_repo, path, assets)
    solution_bytes = json.dumps(solution, ensure_ascii=False, indent=2) + "\n"
    (candidate / "solution.normalized.json").write_text(solution_bytes, encoding="utf-8")
    (assets / KIT / "solution.yaml").write_text(solution_bytes, encoding="utf-8")
    shutil.copy2(builder.harness_repo / KIT / "customer-readonly-cases.json", assets / "cases.json")
    verification = candidate / "verification"
    verification.mkdir()
    for name in ("verify_wealth_kit.py", "verify_contracts.py", "requirements-conformance.txt"):
        shutil.copy2(builder.harness_repo / "engineering" / name, verification / name)


def verify_artifacts(harness: Path, candidate: Path) -> dict:
    expected = {}
    for module in MODULES:
        jars = [p for p in (harness / module / "target").glob("*.jar")
                if not p.name.endswith(("-tests.jar", "-sources.jar", "-javadoc.jar"))]
        if len(jars) != 1:
            raise ValueError("HARNESS_ARTIFACT_INVENTORY_INCOMPLETE")
        expected[jars[0].name] = sha(jars[0])
    actual = {p.name: sha(p) for p in (candidate / "target/dependency").glob("finance-*.jar")}
    if actual != expected:
        raise ValueError("CANDIDATE_HARNESS_ARTIFACT_MISMATCH")
    return actual


def build_readonly(builder, solution: dict, output: Path, verify: bool):
    from .builder import BuildResult

    validate_readonly(solution)
    # New controlled outputs cannot erase old builds, templates, or source inputs.
    allowed = builder.project_root / "build"
    if allowed.is_symlink():
        raise ValueError("READONLY_OUTPUT_ROOT_INVALID")
    allowed.mkdir(exist_ok=True)
    output = require_path_within(output, allowed)
    if output == allowed or output.exists():
        raise ValueError("READONLY_OUTPUT_EXISTS: use a new build directory")
    if any(key.endswith("_LIVE") and value.lower() == "true" for key, value in os.environ.items()):
        raise ValueError("READONLY_LIVE_MODE_DISABLED")
    sources = {"harness": revision(builder.harness_repo), "builder": revision(builder.project_root)}
    output.mkdir(parents=True)
    candidate = output / "candidate"
    logdir = output / "logs"
    logdir.mkdir()
    started = time.monotonic()
    stages = []
    report = {
        "schemaVersion": "finance-clean-runtime-build.v0.1", "solutionId": solution["solutionId"],
        "status": "NOT_RUN", "sources": sources, "stages": stages,
        "evidenceKind": "CONTROLLED_HTTP_AND_SCRIPTED_MODEL",
        "verification": {"generatedCandidateExecuted": False, "controlledMatrix": "NOT_RUN",
                         "liveAuthorization": "NOT_RUN", "fullBankSimulator": "NOT_RUN",
                         "realProviderSubstitution": "PENDING", "independentHumanOnboarding": "PENDING",
                         "builderWebUI": "NOT_RUN", "transfers": "NOT_RUN"},
    }
    lock = {"schemaVersion": "finance-clean-runtime-build-lock.v0.1", "sources": sources,
            "declaredRuntime": solution["runtime"], "runtimeVerification": "NOT_RUN",
            "builderTemplate": {"source": "builder://templates/clean-readonly-agent",
                                "digest": tree_digest(builder.project_root / "templates/clean-readonly-agent")},
            "controlledHost": "maven://io.github.bitlhk.finance:finance-example-wealth-facts-kit:0.1.0-SNAPSHOT"}

    def execute(name, command, cwd, timeout=900, *, maven=False):
        began = time.monotonic()
        log = logdir / (name + ".log")
        with log.open("wb") as stream:
            try:
                result = run_process(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT,
                                     timeout=timeout, check=False, **build_process_options(maven=maven))
                code = result.returncode
            except subprocess.TimeoutExpired:
                code = 124
            except OSError:
                stream.write(b"PROCESS_START_FAILED\n")
                code = 127
        stages.append({"name": name, "status": "PASS" if code == 0 else "FAIL", "exitCode": code,
                       "elapsedMillis": round((time.monotonic() - began) * 1000),
                       "log": "logs/" + log.name, "logSha256": sha(log)})
        if code:
            raise RuntimeError("CONTROLLED_BUILD_STAGE_FAILED: " + name)

    try:
        assemble(builder, solution, candidate)
        lock["inputSha256"] = sha(candidate / "solution.normalized.json")
        lock["materializedAssetsDigest"] = tree_digest(candidate / "assets")
        lock["verificationDigest"] = tree_digest(candidate / "verification")
        if verify:
            execute("harness_contracts", [sys.executable, "engineering/verify_contracts.py"], builder.harness_repo)
            execute("harness_tests_install", [builder.maven, "-B", "-ntp", "install"], builder.harness_repo, maven=True)
            execute("harness_python_tests", [sys.executable, "-m", "unittest", "discover", "-s", "engineering",
                                             "-p", "test_*.py"], builder.harness_repo)
            execute("candidate_package", [builder.maven, "-B", "-ntp", "package"], candidate, maven=True)
            lock["harnessJars"] = verify_artifacts(builder.harness_repo, candidate)
            lock["runtimeClasspath"] = {p.name: sha(p) for p in sorted((candidate / "target/dependency").glob("*.jar"))}
            execute("candidate_controlled_runtime", ["java", "-cp", "target/classes" + os.pathsep + "target/dependency/*",
                                                     JAVA_MAIN, "assets", "target/runs"], candidate, timeout=180)
            report["verification"]["generatedCandidateExecuted"] = True
            execute("candidate_evidence", [sys.executable, "verification/verify_wealth_kit.py", "--root", "assets",
                                            "--runs", "target/runs", "--matrix", "customer-readonly"], candidate)
            records = []
            for path in sorted((candidate / "target/runs").glob("*/report.json")):
                value = json.loads(path.read_text(encoding="utf-8"))
                runtime_lock = path.parent / "solution-lock.json"
                records.append({"case": path.parent.name, "report": path.relative_to(output).as_posix(),
                                "digest": sha(path), "lockDigest": sha(runtime_lock),
                                "toolStatus": value["toolResults"][0]["status"], "sourceReadCount": value["sourceReadCount"]})
            report["cases"] = records
            report["verification"]["controlledMatrix"] = "PASS"
            lock["runtimeVerification"] = "OBSERVED_IN_CANDIDATE_LOCKS"
        if sources != {"harness": revision(builder.harness_repo), "builder": revision(builder.project_root)}:
            raise RuntimeError("SOURCE_REVISION_CHANGED_DURING_BUILD")
        if lock["materializedAssetsDigest"] != tree_digest(candidate / "assets"):
            raise RuntimeError("CANDIDATE_ASSETS_CHANGED_DURING_BUILD")
        if lock["verificationDigest"] != tree_digest(candidate / "verification"):
            raise RuntimeError("CANDIDATE_VERIFIER_CHANGED_DURING_BUILD")
        report["status"] = "passed_with_risk" if verify else "not_run"
    except Exception:
        report["status"] = "failed"
        # Public errors omit server paths, raw process output and source credentials.
        raise RuntimeError("CONTROLLED_BUILD_FAILED: inspect the local stage logs") from None
    finally:
        report["elapsedMillis"] = round((time.monotonic() - started) * 1000)
        lock_path = output / "solution-lock.json"
        lock_path.write_text(json.dumps(lock, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        report["lockSha256"] = sha(lock_path)
        (output / "build-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return BuildResult(output, output / "solution-lock.json", output / "build-report.json", candidate,
                       report["status"], ["Controlled reference only; real authorization, full banking application and human onboarding remain unverified"])
