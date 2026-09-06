"""Re-run the existing controlled gates; never certify a human or live authorization."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


def controlled_environment(source):
    active = [key for key, value in source.items() if key.endswith("_LIVE") and value.lower() == "true"]
    if active:
        raise ValueError("Disable live smoke switches before onboarding: " + ", ".join(sorted(active)))
    # Prevent inherited JVM/Maven switches from enabling smoke or skipping tests.
    removed = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_OPTS", "MAVEN_ARGS", "PYTHONOPTIMIZE"}
    return {key: value for key, value in source.items() if key not in removed}


def steps(python):
    return [
        ("java-version", ["java", "-version"]),
        ("maven-version", ["mvn", "-version"]),
        ("contracts", [python, "engineering/verify_contracts.py"]),
        ("java-verify", ["mvn", "-B", "-ntp", "verify"]),
        ("python-regressions", [python, "-m", "unittest", "discover", "-s", "engineering", "-p", "test_*.py", "-v"]),
        ("controlled-cli", ["bash", "examples/solutions/wealth-facts-kit/run-controlled.sh"]),
        ("report-verification", [python, "engineering/verify_wealth_kit.py"]),
    ]


def git(root, *arguments):
    return subprocess.check_output(["git", *arguments], cwd=root, text=True).strip()


def preflight(root, environment):
    if sys.version_info < (3, 10):
        raise ValueError("Python 3.10+ is required")
    if git(root, "status", "--porcelain", "--untracked-files=normal"):
        raise ValueError("Use a clean Git checkout; commit changes before measuring a reproducible run")
    env = controlled_environment(environment)
    for binary in ("git", "java", "mvn", "bash"):
        if shutil.which(binary, path=env.get("PATH")) is None:
            raise ValueError(binary + " is required")
    return env


def execute(command, root, env, log):
    return subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, check=False).returncode


def run_steps(root, output, commands, env, execute_command=execute):
    report = {
        "evidenceKind": "CONTROLLED_DEVELOPER_REPRODUCTION",
        "commit": git(root, "rev-parse", "HEAD"),
        "tree": git(root, "rev-parse", "HEAD^{tree}"),
        "pythonVersion": sys.version.split()[0],
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "automatedStatus": "RUNNING",
        "liveAuthorization": "NOT_RUN",
        "realProviderSubstitution": "NOT_RUN",
        "independentHumanOnboarding": "PENDING_REVIEW",
        "humanElapsedSeconds": None,
        "helpRequests": None,
        "artifactCollection": "NOT_RUN",
        "steps": [],
    }
    if not commands:
        raise ValueError("No onboarding gates provided")
    path = output / "report.json"

    def save():
        path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    save()
    started = time.monotonic()
    try:
        for index, (name, command) in enumerate(commands, 1):
            log_path = output / (str(index) + "-" + name + ".log")
            step = {"name": name, "command": command, "status": "RUNNING", "log": log_path.name}
            report["steps"].append(step)
            save()
            before = time.monotonic()
            print("Running " + name + "; log: " + str(log_path), flush=True)
            with log_path.open("w", encoding="utf-8") as log:
                code = execute_command(command, root, env, log)
            step.update(status="PASS" if code == 0 else "FAIL", exitCode=code,
                        elapsedSeconds=round(time.monotonic() - before, 3),
                        logSha256=hashlib.sha256(log_path.read_bytes()).hexdigest())
            save()
            if code != 0:
                report["automatedStatus"] = "FAIL"
                return 1
        report["automatedStatus"] = "PASS"
        return 0
    except (OSError, KeyboardInterrupt) as failure:
        report["automatedStatus"] = "INTERRUPTED" if isinstance(failure, KeyboardInterrupt) else "FAIL"
        report["failureType"] = type(failure).__name__
        if report["steps"] and report["steps"][-1]["status"] == "RUNNING":
            report["steps"][-1]["status"] = report["automatedStatus"]
        return 1
    finally:
        report["automatedElapsedSeconds"] = round(time.monotonic() - started, 3)
        report["finishedAt"] = datetime.now(timezone.utc).isoformat()
        save()


def collect_controlled(root, output):
    path = output / "report.json"
    report = json.loads(path.read_text(encoding="utf-8"))
    if report["automatedStatus"] != "PASS":
        return 1
    try:
        shutil.copytree(root / "examples/solutions/wealth-facts-kit/target/runs", output / "controlled-runs")
        files = sorted(file for file in (output / "controlled-runs").rglob("*") if file.is_file())
        if not files:
            raise ValueError("Controlled artifacts missing")
        report["controlledArtifacts"] = {str(file.relative_to(output)): hashlib.sha256(file.read_bytes()).hexdigest()
                                         for file in files}
        report["artifactCollection"] = "PASS"
        code = 0
    except (OSError, ValueError) as failure:
        report.update(automatedStatus="FAIL", artifactCollection="FAIL", failureType=type(failure).__name__)
        code = 1
    path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return code


def main():
    if len(sys.argv) != 1:
        raise SystemExit("No arguments supported. Run from a clean checkout with dependencies installed.")
    try:
        env = preflight(ROOT, os.environ)
    except (ValueError, subprocess.CalledProcessError) as failure:
        print("Preflight rejected: " + str(failure), file=sys.stderr)
        return 2
    output = ROOT / "target" / "onboarding" / uuid4().hex
    output.mkdir(parents=True, mode=0o700)
    code = run_steps(ROOT, output, steps(sys.executable), env)
    # Only the controlled fixture outputs are collected. Do not copy live-smoke output.
    if code == 0:
        code = collect_controlled(ROOT, output)
    print("Report: " + str(output / "report.json"), flush=True)
    print("Automated checks only; live authorization and independent human acceptance are NOT certified.")
    return code


if __name__ == "__main__":
    sys.exit(main())
