from pathlib import Path

import pytest

from finance_solution_builder.service import _verify_harness_jars


MODULES = {
    "packages/java/core": "finance-harness-core-0.1.0-SNAPSHOT.jar",
    "packages/java/wealth": "finance-harness-wealth-0.1.0-SNAPSHOT.jar",
    "integrations/openjiuwen-java": "finance-harness-openjiuwen-java-0.1.0-SNAPSHOT.jar",
    "providers/non-ai-banking": "finance-provider-non-ai-banking-0.1.0-SNAPSHOT.jar",
}
ASSEMBLY_JAR = "finance-harness-assembly-0.1.0-SNAPSHOT.jar"
EXECUTION_JAR = "finance-harness-execution-0.1.0-SNAPSHOT.jar"


def jar_fixture(tmp_path: Path, *, assembly=True, cashflow=True, execution=False):
    harness, candidate = tmp_path / "harness", tmp_path / "candidate"
    dependencies = candidate / "target/dependency"
    dependencies.mkdir(parents=True)
    modules = dict(MODULES)
    if cashflow:
        modules["packages/java/cashflow"] = "finance-harness-cashflow-0.1.0-SNAPSHOT.jar"
    if assembly:
        modules["packages/java/assembly"] = ASSEMBLY_JAR
    if execution:
        modules["packages/java/execution"] = EXECUTION_JAR
    for module, name in modules.items():
        source = harness / module
        (source / "target").mkdir(parents=True)
        (source / "pom.xml").write_text("<project/>")
        content = ("same-source-fixture:" + module).encode()
        (source / "target" / name).write_bytes(content)
        (dependencies / name).write_bytes(content)
    return harness, candidate


@pytest.mark.parametrize("assembly,cashflow", [(False, False), (False, True), (True, False), (True, True)])
def test_exact_closure_supports_legacy_and_optional_modules(tmp_path, assembly, cashflow):
    harness, candidate = jar_fixture(tmp_path, assembly=assembly, cashflow=cashflow)
    _verify_harness_jars(harness, candidate)


@pytest.mark.parametrize("mutation", ["missing", "wrong-bytes", "wrong-name"])
def test_candidate_assembly_must_match_source_name_and_digest(tmp_path, mutation):
    harness, candidate = jar_fixture(tmp_path)
    jar = candidate / "target/dependency" / ASSEMBLY_JAR
    if mutation == "missing":
        jar.unlink()
    elif mutation == "wrong-name":
        jar.rename(jar.with_name("finance-harness-assembly-0.2.0-SNAPSHOT.jar"))
    else:
        jar.write_bytes(b"different-assembly-build")
    with pytest.raises(ValueError, match="^HARNESS_JARS_MISMATCH$"):
        _verify_harness_jars(harness, candidate)


@pytest.mark.parametrize("jar_count", [0, 2])
def test_declared_assembly_requires_exactly_one_source_jar(tmp_path, jar_count):
    harness, candidate = jar_fixture(tmp_path)
    target = harness / "packages/java/assembly/target"
    if jar_count == 0:
        (target / ASSEMBLY_JAR).unlink()
    else:
        (target / "finance-harness-assembly-other.jar").write_bytes(b"ambiguous-build")
    with pytest.raises(ValueError, match="^HARNESS_JAR_UNAVAILABLE$"):
        _verify_harness_jars(harness, candidate)


@pytest.mark.parametrize("assembly", [False, True])
def test_extra_finance_jar_is_rejected_for_old_and_new_closures(tmp_path, assembly):
    harness, candidate = jar_fixture(tmp_path, assembly=assembly)
    (candidate / "target/dependency/finance-unexpected.jar").write_bytes(b"unexpected")
    with pytest.raises(ValueError, match="^HARNESS_JARS_MISMATCH$"):
        _verify_harness_jars(harness, candidate)


def test_assembly_without_pom_is_not_admitted_by_stale_target(tmp_path):
    harness, candidate = jar_fixture(tmp_path)
    (harness / "packages/java/assembly/pom.xml").unlink()
    with pytest.raises(ValueError, match="^HARNESS_JARS_MISMATCH$"):
        _verify_harness_jars(harness, candidate)


def test_existing_finance_jars_still_require_exact_digest(tmp_path):
    harness, candidate = jar_fixture(tmp_path)
    (candidate / "target/dependency" / MODULES["packages/java/core"]).write_bytes(b"wrong-core")
    with pytest.raises(ValueError, match="^HARNESS_JARS_MISMATCH$"):
        _verify_harness_jars(harness, candidate)


def test_non_finance_dependencies_remain_outside_this_check(tmp_path):
    harness, candidate = jar_fixture(tmp_path)
    (candidate / "target/dependency/other-library.jar").write_bytes(b"other-library")
    _verify_harness_jars(harness, candidate)


def test_shared_execution_jar_is_part_of_exact_closure(tmp_path):
    harness, candidate = jar_fixture(tmp_path, execution=True)
    _verify_harness_jars(harness, candidate)


@pytest.mark.parametrize("mutation", ["missing", "wrong-bytes", "undeclared"])
def test_shared_execution_jar_cannot_bypass_source_verification(tmp_path, mutation):
    harness, candidate = jar_fixture(tmp_path, execution=True)
    jar = candidate / "target/dependency" / EXECUTION_JAR
    if mutation == "missing":
        jar.unlink()
    elif mutation == "wrong-bytes":
        jar.write_bytes(b"different-execution-build")
    else:
        (harness / "packages/java/execution/pom.xml").unlink()
    with pytest.raises(ValueError, match="^HARNESS_JARS_MISMATCH$"):
        _verify_harness_jars(harness, candidate)
