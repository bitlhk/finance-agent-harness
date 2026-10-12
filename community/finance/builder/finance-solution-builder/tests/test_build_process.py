import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from types import SimpleNamespace

import pytest

from finance_builder_base.runtime import UnsafeWorkspacePath
from finance_solution_builder import build_process, service
from finance_solution_builder.builder import SolutionBuilder


def test_non_maven_options_preserve_inheritance():
    assert build_process.build_process_options() == {}
    assert build_process.build_process_options(maven=False) == {}


def test_stable_failure_code_uses_structured_code_without_path():
    error = UnsafeWorkspacePath("workspace.path_outside_root", "private/location", "path leaves workspace")
    assert service.stable_failure_code(error) == "WORKSPACE_PATH_OUTSIDE_ROOT"


def test_non_posix_does_not_pass_unsupported_umask(monkeypatch):
    monkeypatch.setattr(build_process.os, "name", "nt")
    assert build_process.build_process_options(maven=True) == {}


RUNNER = r'''
import json
from pathlib import Path
import sys
from types import SimpleNamespace
from finance_builder_base.runtime import spawn_process
from finance_solution_builder.build_process import build_process_options
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.agentscope_service import AgentScopeServiceBuild

root = Path(sys.argv[1])
root.mkdir(mode=0o700)
kind = sys.argv[2]
child = "from pathlib import Path; import sys; Path(sys.argv[1]).write_bytes(b'controlled output')"
if kind == "legacy":
    builder = SolutionBuilder(root, root)
elif kind == "native":
    builder = AgentScopeServiceBuild.__new__(AgentScopeServiceBuild)
    builder.output = root
    builder.report = {"stages": []}
    builder.report_path = root / "build-report.json"
    builder.builder = SimpleNamespace(service_cancel=None, service_process=None)

def run(name, maven=False):
    command = [sys.executable, "-c", child, str(root / name)]
    if kind == "helper":
        process = spawn_process(command, **build_process_options(maven=maven))
        assert process.wait(timeout=20) == 0
    elif kind == "legacy":
        builder._run_stage([], name, command, root, maven=maven)
    else:
        builder.stage(name, command, root, maven=maven)

run("inherited-before")
run("maven", maven=True)
run("runtime-after")
if len(sys.argv) > 3:
    source = root / "UmaskProbe.java"
    source.write_text("class UmaskProbe { public static void main(String[] args) throws Exception { "
                      "java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), \"runtime\"); } }")
    command = [sys.argv[3], str(source), str(root / "java-runtime-after")]
    builder.stage("component_installation", command, root)
(root / "private-record.json").write_text("{}")
print(json.dumps({p.relative_to(root).as_posix(): oct(p.stat().st_mode & 0o777)
                  for p in root.rglob("*") if p.is_file()}))
'''


@pytest.mark.skipif(os.name != "posix", reason="POSIX child umask contract")
@pytest.mark.parametrize("kind", ["helper", "legacy", "native"])
def test_real_children_leave_parent_runtime_and_records_private(tmp_path, kind):
    env = dict(os.environ, PYTHONPATH=os.pathsep.join(str(Path(p).resolve()) for p in sys.path if p))
    result = subprocess.run(
        [sys.executable, "-c", RUNNER, str(tmp_path / kind), kind],
        env=env, capture_output=True, text=True, timeout=60, umask=0o077, check=True,
    )
    modes = json.loads(result.stdout)
    assert modes["maven"] == "0o644"
    assert all(mode == "0o600" for name, mode in modes.items() if name != "maven")
    assert (tmp_path / kind).stat().st_mode & 0o777 == 0o700


@pytest.mark.skipif(os.name != "posix" or not shutil.which("java"), reason="POSIX and Java required")
def test_real_java_non_maven_stage_stays_077_after_maven(tmp_path):
    env = dict(os.environ, PYTHONPATH=os.pathsep.join(str(Path(p).resolve()) for p in sys.path if p))
    result = subprocess.run(
        [sys.executable, "-c", RUNNER, str(tmp_path / "java"), "native", shutil.which("java")],
        env=env, capture_output=True, text=True, timeout=60, umask=0o077, check=True,
    )
    modes = json.loads(result.stdout)
    assert modes["maven"] == "0o644"
    assert modes["java-runtime-after"] == "0o600"
    assert modes["private-record.json"] == "0o600"
    assert modes["logs/component_installation.log"] == "0o600"


def test_service_marks_only_maven_calls(harness, tmp_path, monkeypatch):
    from finance_solution_builder import artifact_plan
    # This fixture tests process options, not reactor resolution or JAR admission.
    # Artifact integrity is exercised independently in test_artifact_plan.py.
    monkeypatch.setattr(artifact_plan, "financial_artifact_plan", lambda *_: {})
    project = Path(__file__).resolve().parents[1]
    builder = SolutionBuilder(project, harness, service_output_root=tmp_path / "builds")
    monkeypatch.setattr(service, "revision", lambda _: {"commit": "a" * 40, "worktreeDirty": False})
    calls = []

    def spawn(command, **options):
        calls.append((command, options))
        return SimpleNamespace(wait=lambda **_: 17 if command[-1] == "package" else 0)

    monkeypatch.setattr(service, "spawn_process", spawn)
    with pytest.raises(RuntimeError, match="LOCAL_SERVICE_BUILD_FAILED"):
        service.build_service(builder, service.solution_for(["account.balance.query"], "agent-umask"),
                              tmp_path / "builds/agent-umask", True)
    report = json.loads((tmp_path / "builds/agent-umask/build-report.json").read_text())
    assert report["failureCode"] == "BUILD_STAGE_FAILED"
    assert len(calls) == 3
    assert "umask" not in calls[0][1]
    assert [command[-1] for command, _ in calls[1:]] == ["install", "package"]
    expected = build_process.build_process_options(maven=True)
    for _, options in calls[1:]:
        assert {key: options[key] for key in expected} == expected
    assert builder.service_process is None
