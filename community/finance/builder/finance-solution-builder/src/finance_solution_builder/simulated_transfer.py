"""Explicit isolated Java pilot, deliberately not admitted by the read-only Studio recipe."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import shutil
import time
import uuid

import yaml
from finance_builder_base.runtime import copy_directory_tree
from .build_process import build_process_options
from .readonly import PROJECT, copy_asset, revision
from .service import digest, write_json, stop_owned_process


def build(harness: Path, output: Path, maven: str, allow_dirty: bool = False) -> dict:
    harness, output = harness.resolve(), output.absolute()
    if output.exists():
        raise ValueError("OUTPUT_EXISTS")
    sources = {"harness": revision(harness), "builder": revision(PROJECT)}
    if not allow_dirty and any(v["worktreeDirty"] for v in sources.values()):
        raise ValueError("CLEAN_SOURCES_REQUIRED")
    output.mkdir(parents=True)
    candidate = output / "candidate"
    report = {"schemaVersion": "simulated-transfer-build.v0.1", "buildId": "transfer-" + uuid.uuid4().hex,
              "sources": sources, "status": "BUILDING", "stages": [], "browser": "NOT_RUN",
              "bankLedger": "NOT_RUN", "realModel": "NOT_RUN", "humanOnboarding": "NOT_RUN"}
    def stage(name, command, cwd, *, maven=False):
        log = output / (name + ".log")
        start = time.monotonic()
        with log.open("wb") as stream:
            child = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT,
                                     **build_process_options(maven=maven))
            try:
                code = child.wait(timeout=900)
            finally:
                stop_owned_process(child)
        report["stages"].append({"name": name, "exitCode": code, "elapsedMs": round(1000*(time.monotonic()-start)),
                                  "log": log.name, "sha256": digest(log)})
        write_json(output / "build-report.json", report)
        if code:
            raise RuntimeError("BUILD_STAGE_FAILED:" + name)
    try:
        copy_directory_tree(PROJECT, "templates/simulated-transfer-service", output, "candidate")
        shutil.copy2(PROJECT / "examples/agent-experience/icons.svg", candidate / "ui/icons.svg")
        if (candidate / "target").exists():
            shutil.rmtree(candidate / "target")
        solution = yaml.safe_load((harness / "examples/solutions/openjiuwen-retail-banking/solution.yaml").read_text())
        solution["solutionId"] = report["buildId"]
        solution["displayName"] = "Isolated simulated transfer"
        solution["providerRefs"][0]["source"] = "maven://io.github.bitlhk.finance:finance-provider-non-ai-banking:0.1.0-SNAPSHOT"
        for ref in [solution["profileRef"], *solution["capabilityPackRefs"], solution["evaluationPackRef"]]:
            relative = Path(ref["source"].removeprefix("repo://"))
            if not (candidate / relative).exists():
                copy_asset(harness, relative, candidate)
        write_json(candidate / "solution.yaml", solution)
        stage("browser-renderer", [str(PROJECT / "node_modules/.bin/esbuild"),
              str(PROJECT / "browser/markdown.js"), "--bundle", "--minify", "--format=iife",
              "--global-name=FinanceMarkdown", "--outfile=" + str(candidate / "ui/markdown.js")], PROJECT)
        stage("harness-install", [maven, "-B", "-ntp", "install"], harness, maven=True)
        stage("candidate-package", [maven, "-B", "-ntp", "package"], candidate, maven=True)
        if sources != {"harness": revision(harness), "builder": revision(PROJECT)}:
            raise ValueError("SOURCE_CHANGED_DURING_BUILD")
        actual = {p.name: digest(p) for p in (candidate / "target/dependency").glob("finance-*.jar")}
        expected = {}
        for module in ("packages/java/core", "packages/java/cashflow",
                       "integrations/openjiuwen-java", "providers/non-ai-banking"):
            jars = list((harness / module / "target").glob("*.jar"))
            if len(jars) != 1 or actual.get(jars[0].name) != digest(jars[0]):
                raise ValueError("HARNESS_ARTIFACT_MISMATCH")
            expected[jars[0].name] = digest(jars[0])
        if actual != expected:
            raise ValueError("UNEXPECTED_HARNESS_ARTIFACT")
        files = {p.relative_to(candidate).as_posix(): digest(p) for p in sorted(candidate.rglob("*"))
                 if p.is_file() and ("target" not in p.relative_to(candidate).parts or p.suffix == ".jar")}
        lock = {"schemaVersion": "simulated-transfer-candidate.v0.1", "buildId": report["buildId"],
                "sources": sources, "runtime": solution["runtime"], "files": files,
                "capabilities": ["account.balance.query", "transfer.internal.create"],
                "simulationOnly": True, "candidateStatus": "COMPILED_NOT_BUSINESS_ACCEPTED"}
        write_json(candidate / "service-lock.json", lock)
        report.update(status="BUILT", lockSha256=digest(candidate / "service-lock.json"))
    except BaseException:
        report["status"] = "FAILED"
        raise
    finally:
        write_json(output / "build-report.json", report)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--allow-dirty-development", action="store_true")
    args = parser.parse_args()
    print(json.dumps(build(args.harness, args.output, args.maven, args.allow_dirty_development), ensure_ascii=False))
