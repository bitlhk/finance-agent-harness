"""Offline generation validation, frozen model binding, and child isolation."""
import copy
import json
import os
import shutil
import subprocess
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor

import pytest

from finance_solution_builder.model_generation import (
    GenerationError, freeze_generation, generation_digest, generation_json, validate_generation,
)
from finance_solution_builder.model_registry import ModelRegistry
from finance_solution_builder.service import digest, write_json
from finance_solution_builder.workbench import ENV_KEYS, Jobs
from test_model_registry import configured


GENERATION = {"schemaVersion": "finance.eval-generation.v1", "temperature": .25, "top_p": .8, "max_tokens": 1234}


@pytest.mark.parametrize("key,value", [
    ("temperature", True), ("temperature", "0"), ("temperature", -.1), ("temperature", 2.1),
    ("temperature", float("nan")), ("temperature", float("inf")),
    ("top_p", False), ("top_p", "1"), ("top_p", 0), ("top_p", 1.01), ("top_p", float("nan")),
    ("max_tokens", True), ("max_tokens", 1.0), ("max_tokens", "1"), ("max_tokens", 0), ("max_tokens", 32769),
    ("schemaVersion", "wrong"), ("url", "https://untrusted"), ("key", "untrusted"),
])
def test_generation_rejects_invalid_values(key, value):
    with pytest.raises(GenerationError, match="^EVAL_GENERATION_INVALID$"):
        validate_generation({**GENERATION, key: value})


@pytest.mark.parametrize("missing", list(GENERATION))
def test_generation_requires_all_fields(missing):
    with pytest.raises(GenerationError):
        validate_generation({k: v for k, v in GENERATION.items() if k != missing})


@pytest.mark.parametrize("temperature,top_p,maximum", [(0, .001, 1), (2, 1, 32768)])
def test_generation_boundaries(temperature, top_p, maximum):
    value = {**GENERATION, "temperature": temperature, "top_p": top_p, "max_tokens": maximum}
    assert validate_generation(value) == value


def test_generation_connection_binding_is_copy_and_cannot_be_rebound(configured):
    registry = ModelRegistry(configured[0])
    snapshot = registry.snapshot(["model-a"], "model-a", registry.catalog_revision)
    parameters = copy.deepcopy(GENERATION)
    frozen = freeze_generation(snapshot, {"modelRef": "model-a", "parameters": parameters})
    parameters["max_tokens"] = 1
    assert "modelGeneration" not in snapshot
    assert frozen["modelGeneration"]["parameters"] == GENERATION
    assert frozen["connections"] == snapshot["connections"]
    with pytest.raises(GenerationError):
        freeze_generation(frozen, {"modelRef": "model-a", "parameters": parameters})
    with pytest.raises(GenerationError):
        freeze_generation(snapshot, {"modelRef": "model-b", "parameters": GENERATION})


def test_registry_generation_is_child_local_not_global(configured):
    environment = configured[0]
    environment["FH_MODEL_GENERATION_JSON"] = "untrusted ambient value"
    before = copy.deepcopy(environment)
    registry = ModelRegistry(environment)
    snapshot = registry.snapshot(["model-a"], "model-a", registry.catalog_revision)
    first = freeze_generation(snapshot, {"modelRef": "model-a", "parameters": GENERATION})
    second = freeze_generation(snapshot, {"modelRef": "model-a", "parameters": {**GENERATION, "max_tokens": 20}})
    with ThreadPoolExecutor(2) as pool:
        a, b = list(pool.map(registry.child_environment, (first, second)))
    assert a["FH_MODEL_GENERATION_JSON"] != b["FH_MODEL_GENERATION_JSON"]
    assert "FH_MODEL_GENERATION_JSON" not in registry.child_environment(snapshot)
    assert environment == before


def test_jobs_start_owner_isolation_restart_and_lock_binding(configured, tmp_path, monkeypatch):
    env = configured[0]
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    for key in ENV_KEYS[3:]:
        monkeypatch.setenv(key, "test-only")
    monkeypatch.setenv("FH_MODEL_GENERATION_JSON", "ambient-must-not-be-used")
    registry = ModelRegistry()
    snapshot = registry.snapshot(["model-a"], "model-a", registry.catalog_revision)
    jobs = Jobs(tmp_path / "project", tmp_path / "harness", data_dir=tmp_path / "jobs")
    captured, manifests = [], {}
    class Child:
        def __init__(self, pid):
            self.pid, self.code = pid, None
        def poll(self): return self.code
        def terminate(self): self.code = 0
        def wait(self, **kwargs): self.code = 0; return 0
    def spawn(command, *, cwd, env, **kwargs):
        key = cwd.parent.name
        child = Child(10000 + len(captured))
        captured.append((key, dict(env)))
        write_json(cwd / "instance.json", {"pid": child.pid, "buildId": key, "port": 1, "instanceId": str(child.pid)})
        return child
    monkeypatch.setattr("finance_solution_builder.workbench.spawn_process", spawn)
    monkeypatch.setattr("finance_solution_builder.managed_launcher.launch_command", lambda *args: ["never-executed"])
    monkeypatch.setattr(jobs, "_request", lambda key, *args, **kwargs: (200, {
        "artifactLock": manifests[key], "instanceId": jobs.jobs[key]["instanceId"]}))
    try:
        for key, owner, maximum in [("agent-a", "owner-a", 100), ("agent-b", "owner-b", 200)]:
            frozen = freeze_generation(snapshot, {"modelRef": "model-a", "parameters": {**GENERATION, "max_tokens": maximum}})
            jobs.jobs[key] = {"id": key, "owner": owner, "status": "BUILT", "modelSelection": frozen}
            candidate = jobs.path(key) / "candidate"
            candidate.mkdir(parents=True)
            manifests[key] = {"runtime": {"language": "java"}, "files": {}, "modelSelection": copy.deepcopy(frozen)}
            write_json(candidate / "service-lock.json", manifests[key])
            write_json(candidate.parent / "build-report.json", {"lockSha256": digest(candidate / "service-lock.json")})
        before = dict(os.environ)
        with ThreadPoolExecutor(2) as pool:
            list(pool.map(lambda pair: jobs.start(*pair), [("agent-a", "owner-a"), ("agent-b", "owner-b")]))
        by_key = dict(captured)
        for key, maximum in (("agent-a", 100), ("agent-b", 200)):
            assert json.loads(by_key[key]["FH_MODEL_GENERATION_JSON"])["parameters"]["max_tokens"] == maximum
        jobs.stop("agent-a", "owner-a", expected_instance=jobs.jobs["agent-a"]["instanceId"])
        jobs.start("agent-a", "owner-a")
        assert captured[-1][1]["FH_MODEL_GENERATION_JSON"] == by_key["agent-a"]["FH_MODEL_GENERATION_JSON"]
        assert dict(os.environ) == before
        with pytest.raises(PermissionError):
            jobs.start("agent-a", "owner-b")
        jobs.stop("agent-a", "owner-a")
        jobs.jobs["agent-a"]["modelSelection"]["modelGeneration"]["parameters"]["max_tokens"] = 999
        count = len(captured)
        with pytest.raises(ValueError, match="MODEL_GENERATION_LOCK_MISMATCH"):
            jobs.start("agent-a", "owner-a")
        assert len(captured) == count
    finally:
        jobs.close()


def test_private_api_build_freezes_generation_in_real_jobs_create(configured, tmp_path, monkeypatch):
    from finance_solution_builder.workbench_api import WorkbenchApi
    from test_model_registry import selection
    registry = ModelRegistry(configured[0])
    snapshot = registry.snapshot(["model-a", "model-b"], "model-a", registry.catalog_revision)
    jobs = Jobs(tmp_path / "project", tmp_path / "harness", data_dir=tmp_path / "jobs")
    monkeypatch.setattr("finance_solution_builder.workbench_api.preflight", lambda *args, **kwargs: {
        "canBuild": True, "modelSelection": snapshot})
    monkeypatch.setattr("finance_solution_builder.component_plan.qualify_solution", lambda *args, **kwargs: None)
    monkeypatch.setattr(jobs.queue, "submit", lambda *args: None)
    api = WorkbenchApi(jobs)
    binding = {"modelRef": "model-a", "parameters": copy.deepcopy(GENERATION)}
    try:
        code, state = api.post("/api/v1/builds", selection(registry), "owner-a", generation_binding=binding)
        assert code == 202
        key = state["buildId"]
        binding["parameters"]["max_tokens"] = 1
        frozen = jobs.owned(key, "owner-a")["modelSelection"]
        assert frozen["modelGeneration"]["parameters"] == GENERATION
        assert "modelGeneration" not in snapshot
        assert json.loads((jobs.records / (key + ".json")).read_text())["modelSelection"] == frozen
        with pytest.raises(PermissionError):
            api.inspect(key, "owner-b")
    finally:
        jobs.close()


@pytest.fixture(scope="module")
def host_generation_jvm(tmp_path_factory):
    if not shutil.which("javac") or not shutil.which("java"):
        pytest.skip("local JDK required; never download dependencies")
    repository = Path.home() / ".m2/repository"
    jars = [repository / "io/github/bitlhk/finance/finance-harness-core/0.1.0-SNAPSHOT/finance-harness-core-0.1.0-SNAPSHOT.jar"]
    for group, artifact in (("core", "jackson-core"), ("core", "jackson-databind"), ("core", "jackson-annotations"),
                            ("datatype", "jackson-datatype-jsr310")):
        found = sorted((repository / "com/fasterxml/jackson" / group / artifact).glob("*/*.jar"))
        if not found:
            pytest.skip("cached Jackson JARs required; never download dependencies")
        jars.append(found[-1])
    if not jars[0].is_file():
        pytest.skip("cached Harness core JAR required; never install dependencies")
    root = tmp_path_factory.mktemp("generation-jvm")
    template = Path(__file__).resolve().parents[1] / "templates/local-readonly-service/src/main/java/io/github/bitlhk/finance/builder/local"
    shutil.copyfile(template / "ModelConnections.java", root / "ModelConnections.java")
    (root / "BankAccess.java").write_text('''package io.github.bitlhk.finance.builder.local;
final class BankAccess { static String env(String key) { return System.getenv(key); } }
''')
    (root / "GenerationProbe.java").write_text('''package io.github.bitlhk.finance.builder.local;
import java.nio.file.*;
import java.util.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
public class GenerationProbe {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        var manifest = HarnessJson.mapper().readTree(root.resolve("service-lock.json").toFile());
        var models = new ModelConnections(root, manifest);
        var output = new LinkedHashMap<String, Object>();
        output.put("legacy", models.generation(args[1]) == null);
        models.generationEvidence(args[1], output);
        System.out.print(HarnessJson.mapper().writeValueAsString(output));
    }
}
''')
    classpath = os.pathsep.join(map(str, jars))
    subprocess.run(["javac", "-cp", classpath, "-d", str(root), *map(str, root.glob("*.java"))],
                   check=True, capture_output=True, text=True, timeout=30)
    return os.pathsep.join((str(root), classpath))


@pytest.mark.parametrize("mode", ["explicit", "legacy", "missing-env", "changed-env", "other-model", "invalid-locked"])
@pytest.mark.parametrize("temperature,top_p", [(.25, .8), (-0.0, 1), (1e-7, 1e-5), (2, 5e-324)])
def test_real_host_generation_configuration(host_generation_jvm, configured, tmp_path, mode, temperature, top_p):
    registry = ModelRegistry(configured[0])
    snapshot = registry.snapshot(["model-a", "model-b"], "model-a", registry.catalog_revision)
    if mode != "legacy":
        snapshot = freeze_generation(snapshot, {"modelRef": "model-a", "parameters": {
            **GENERATION, "temperature": temperature, "top_p": top_p}})
    env = dict(os.environ)
    env.pop("FH_MODEL_GENERATION_JSON", None)
    env.update(registry.child_environment(snapshot))
    if mode == "missing-env":
        env.pop("FH_MODEL_GENERATION_JSON")
    if mode == "changed-env":
        env["FH_MODEL_GENERATION_JSON"] = env["FH_MODEL_GENERATION_JSON"].replace("1234", "1235")
    if mode == "invalid-locked":
        snapshot["modelGeneration"]["parameters"]["temperature"] = True
        env["FH_MODEL_GENERATION_JSON"] = generation_json(snapshot["modelGeneration"])
    write_json(tmp_path / "model-selection.json", snapshot)
    write_json(tmp_path / "service-lock.json", {"modelSelection": snapshot, "files": {"model-selection.json": "test-fixture"}})
    result = subprocess.run(["java", "-cp", host_generation_jvm, "io.github.bitlhk.finance.builder.local.GenerationProbe",
                             str(tmp_path), "model-b" if mode == "other-model" else "model-a"],
                            env=env, capture_output=True, text=True, timeout=15)
    if mode in {"explicit", "legacy"}:
        assert result.returncode == 0, result.stderr
        observed = json.loads(result.stdout)
        assert observed["legacy"] == (mode == "legacy")
        if mode == "explicit":
            assert observed["modelGeneration"] == snapshot["modelGeneration"]
            assert observed["modelGenerationSha256"] == generation_digest(snapshot["modelGeneration"])
        else:
            assert "modelGeneration" not in observed
    else:
        assert result.returncode != 0 and "MODEL_GENERATION_" in result.stderr


def test_generation_classes_are_qualification_anchors():
    template = Path(__file__).resolve().parents[1] / "templates/local-readonly-service/src/main/java/io/github/bitlhk/finance/builder/local"
    source = (template / "CollectorQualification.java").read_text()
    assert "ModelConnections.Generation.class" in source
    assert "ModelConnections.GenerationBinding.class" in source
