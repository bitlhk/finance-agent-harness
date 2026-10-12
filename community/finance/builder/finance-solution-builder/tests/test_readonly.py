import copy
import json
import shutil
import xml.etree.ElementTree as ET

import pytest

from finance_solution_builder import readonly
from finance_solution_builder.builder import SolutionBuilder


@pytest.fixture
def recipe():
    return json.loads(readonly.RECIPE.read_text(encoding="utf-8"))


@pytest.mark.parametrize("change", ["live_model", "task", "runtime", "provider", "source", "scope", "missing_pack"])
def test_unsupported_composition_does_not_silently_fallback(recipe, change):
    if change == "live_model":
        recipe["modelRef"] = "model://live"
    elif change == "task":
        recipe["capabilityBindings"] = {"transfer.internal.create": "wealth-facts-adapter"}
    elif change == "runtime":
        recipe["runtime"]["templateRef"] = "maven://com.openjiuwen:agent-core-java:999.0"
    elif change == "provider":
        recipe["providerRefs"][0]["digest"] = "sha256:" + "0" * 64
    elif change == "source":
        recipe["domainPackRefs"][0]["source"] = "repo://../../private"
    elif change == "scope":
        recipe["grantedScopes"] = ["*"]
    else:
        del recipe["capabilityPackRefs"]
    with pytest.raises(ValueError, match="READONLY_RECIPE_UNSUPPORTED"):
        readonly.validate_readonly(recipe)


def test_supported_pinning_preserves_original_input(recipe):
    for ref in (recipe["profileRef"], recipe["domainPackRefs"][0], recipe["capabilityPackRefs"][0]):
        ref["digest"] = "sha256:" + "a" * 64
    original = copy.deepcopy(recipe)
    readonly.validate_readonly(recipe)
    assert recipe == original  # Assembler must receive the actual declared digests.


def test_standalone_template_pins_serialization_and_does_not_relocate_runtime():
    root = ET.parse(readonly.PROJECT / "templates/clean-readonly-agent/pom.xml").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    managed = root.find("m:dependencyManagement/m:dependencies/m:dependency", ns)
    assert managed.findtext("m:artifactId", namespaces=ns) == "jackson-bom"
    assert managed.findtext("m:version", namespaces=ns) == "2.20.1"
    assert not root.findall("m:parent", ns)
    assert "maven-shade-plugin" not in ET.tostring(root, encoding="unicode")


@pytest.fixture
def setup(tmp_path, monkeypatch, recipe):
    project, harness = tmp_path / "builder", tmp_path / "harness"
    template = project / "templates/clean-readonly-agent"
    shutil.copytree(readonly.PROJECT / "templates/clean-readonly-agent", template)
    inputs = project / "solution.json"
    inputs.write_text(json.dumps(recipe), encoding="utf-8")
    files = {
        "contracts/json-schema/solution/solution-build-input.v1.schema.json": {"type": "object"},
        "contracts/json-schema/wealth/wealth-facts.v0.1.schema.json": {},
        "examples/solutions/wealth-facts-kit/fixtures/mobile.json": {},
        "examples/solutions/wealth-facts-kit/fixtures/ea.json": {},
        "examples/solutions/wealth-facts-kit/customer-readonly-cases.json": [{"mode": "CONTROLLED"}],
        "domain-packs/wealth/shared-facts/pack.yaml": {},
        "capability-packs/wealth-facts-description/pack.yaml": {},
        "conformance/fixtures/wealth/v0.1/fixture.json": {},
    }
    for name, value in files.items():
        target = harness / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(value), encoding="utf-8")
    for name in ["profiles/shared-read-only.md", "engineering/verify_wealth_kit.py",
                 "engineering/verify_contracts.py", "engineering/requirements-conformance.txt"]:
        target = harness / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("# controlled test placeholder", encoding="utf-8")
    monkeypatch.setattr(readonly, "revision", lambda _: {"commit": "a" * 40, "worktreeDirty": False})
    return SolutionBuilder(project, harness), inputs, project / "build/run"


def test_generation_has_no_mobile_or_harness_source_and_no_execution_claim(setup):
    builder, inputs, output = setup
    result = builder.build(inputs, output, verify=False)
    report = json.loads(result.report_path.read_text())
    assert report["status"] == "not_run"
    assert report["verification"]["generatedCandidateExecuted"] is False
    assert report["verification"]["controlledMatrix"] == "NOT_RUN"
    assert len(list(result.candidate_dir.rglob("*.java"))) == 1
    assert not (result.candidate_dir / "agents").exists()
    assert not (result.candidate_dir / "framework").exists()
    solution = json.loads((result.candidate_dir / "assets" / readonly.KIT / "solution.yaml").read_text())
    assert solution == json.loads(inputs.read_text())
    assert "@@" not in (result.candidate_dir / "pom.xml").read_text()


def test_existing_output_and_source_directories_are_never_deleted(setup):
    builder, inputs, output = setup
    output.mkdir(parents=True)
    sentinel = output / "original.txt"
    sentinel.write_text("keep")
    for path in [output, builder.project_root / "templates", output.parent]:
        with pytest.raises(ValueError):
            builder.build(inputs, path, verify=False)
        assert sentinel.read_text() == "keep"


def test_failure_keeps_failed_report_and_no_false_verification(setup, monkeypatch):
    builder, inputs, output = setup
    monkeypatch.setattr(readonly, "run_process", lambda *a, **kw: type("Result", (), {"returncode": 7})())
    with pytest.raises(RuntimeError, match="CONTROLLED_BUILD_FAILED"):
        builder.build(inputs, output)
    report = json.loads((output / "build-report.json").read_text())
    assert report["status"] == "failed"
    assert report["stages"][0]["exitCode"] == 7
    assert report["verification"]["controlledMatrix"] == "NOT_RUN"
    assert str(builder.project_root) not in json.dumps(report)


def test_live_switch_is_rejected_without_making_candidate(setup, monkeypatch):
    builder, inputs, output = setup
    monkeypatch.setenv("EA_LIVE", "true")
    with pytest.raises(ValueError, match="LIVE_MODE_DISABLED"):
        builder.build(inputs, output)
    assert not output.exists()


def test_dependency_inventory_requires_all_real_matching_jars(tmp_path):
    harness, candidate = tmp_path / "harness", tmp_path / "candidate"
    with pytest.raises(ValueError, match="INCOMPLETE"):
        readonly.verify_artifacts(harness, candidate)
    for index, module in enumerate(readonly.MODULES):
        jar = harness / module / "target" / f"finance-{index}.jar"
        jar.parent.mkdir(parents=True)
        jar.write_bytes(str(index).encode())
        target = candidate / "target/dependency" / jar.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(jar, target)
    assert len(readonly.verify_artifacts(harness, candidate)) == len(readonly.MODULES)
    target.write_bytes(b"stale snapshot")
    with pytest.raises(ValueError, match="MISMATCH"):
        readonly.verify_artifacts(harness, candidate)


def test_only_explicit_maven_stages_get_child_umask(setup, monkeypatch):
    from finance_solution_builder.build_process import build_process_options
    builder, inputs, output = setup
    calls = []

    def run(command, **options):
        calls.append((command, options))
        return type("Result", (), {"returncode": 17 if command[-1] == "package" else 0})()

    monkeypatch.setattr(readonly, "run_process", run)
    with pytest.raises(RuntimeError, match="CONTROLLED_BUILD_FAILED"):
        builder.build(inputs, output)
    assert len(calls) == 4
    for command, options in calls:
        expected = build_process_options(maven=command[0] == builder.maven)
        assert {key: value for key, value in options.items() if key == "umask"} == expected
