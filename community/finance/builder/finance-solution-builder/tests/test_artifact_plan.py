from pathlib import Path
import xml.etree.ElementTree as ET

import pytest

from finance_solution_builder.artifact_plan import (
    GROUP, NS, add_provider_dependencies, financial_artifact_plan, verify_financial_artifacts,
)


def write(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body)


def dep(name, version="1"):
    return f"<dependency><groupId>{GROUP}</groupId><artifactId>{name}</artifactId><version>{version}</version></dependency>"


def fixture(tmp):
    harness, candidate = tmp / "harness", tmp / "candidate"
    write(harness / "pom.xml", f'<project xmlns="{NS["m"]}"><groupId>{GROUP}</groupId><artifactId>parent</artifactId><version>1</version><modules><module>core</module><module>provider</module><module>new-provider</module></modules></project>')
    for module, name, deps in [("core", "finance-core", ""), ("provider", "finance-provider", dep("finance-core")),
                                ("new-provider", "finance-new-provider", dep("finance-core"))]:
        write(harness / module / "pom.xml", f'<project xmlns="{NS["m"]}"><parent><groupId>{GROUP}</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>{name}</artifactId><dependencies>{deps}</dependencies></project>')
        write(harness / module / "target" / (name + "-1.jar"), module)
    write(candidate / "pom.xml", f'<project xmlns="{NS["m"]}"><groupId>host</groupId><artifactId>host</artifactId><version>1</version><dependencies>{dep("finance-provider")}</dependencies></project>')
    for module, name in [("core", "finance-core"), ("provider", "finance-provider")]:
        write(candidate / "target/dependency" / (name + "-1.jar"), module)
    return harness, candidate


def test_new_provider_closure_without_changing_verifier(tmp_path):
    harness, candidate = fixture(tmp_path)
    assert len(verify_financial_artifacts(harness, candidate)["artifacts"]) == 2
    refs = [{"source": f"maven://{GROUP}:finance-new-provider:1"}]
    add_provider_dependencies(candidate / "pom.xml", refs)
    add_provider_dependencies(candidate / "pom.xml", refs)
    assert len(ET.parse(candidate / "pom.xml").findall("m:dependencies/m:dependency", NS)) == 2
    with pytest.raises(ValueError, match="HARNESS_JARS_MISMATCH"):
        verify_financial_artifacts(harness, candidate)
    write(candidate / "target/dependency/finance-new-provider-1.jar", "new-provider")
    evidence = verify_financial_artifacts(harness, candidate)
    assert len(evidence["artifacts"]) == 3
    assert len(evidence["jarDigests"]) == 3


@pytest.mark.parametrize("mutation", ["missing", "tampered", "extra", "symlink"])
def test_artifact_integrity(tmp_path, mutation):
    harness, candidate = fixture(tmp_path)
    jar = candidate / "target/dependency/finance-provider-1.jar"
    if mutation == "missing":
        jar.unlink()
    elif mutation == "tampered":
        jar.write_text("wrong")
    elif mutation == "extra":
        write(jar.with_name("finance-extra-1.jar"), "extra")
    else:
        jar.unlink()
        jar.symlink_to(harness / "provider/target/finance-provider-1.jar")
    with pytest.raises(ValueError, match="HARNESS_JARS_MISMATCH"):
        verify_financial_artifacts(harness, candidate)


@pytest.mark.parametrize("source", ["maven://external:other:1", f"maven://{GROUP}:finance-provider:2", "repo://provider"])
def test_invalid_provider_reference(tmp_path, source):
    _, candidate = fixture(tmp_path)
    with pytest.raises(ValueError, match="ARTIFACT_"):
        add_provider_dependencies(candidate / "pom.xml", [{"source": source}])


@pytest.mark.parametrize("mutation", ["unresolved", "unknown", "cycle", "version", "classifier", "profile", "escape"])
def test_unsupported_closure_rejected(tmp_path, mutation):
    harness, candidate = fixture(tmp_path)
    path = harness / "provider/pom.xml"
    text = path.read_text()
    if mutation == "unresolved":
        text = text.replace("<version>1</version></dependency>", "<version>${unknown}</version></dependency>")
    elif mutation == "unknown":
        text = text.replace("finance-core", "finance-unknown")
    elif mutation == "cycle":
        text = text.replace("finance-core", "finance-provider")
    elif mutation == "version":
        text = text.replace("<version>1</version></dependency>", "<version>2</version></dependency>")
    elif mutation == "classifier":
        text = text.replace("</dependency>", "<classifier>other</classifier></dependency>")
    elif mutation == "profile":
        text = text.replace("</project>", f"<profiles><profile><dependencies>{dep('finance-core')}</dependencies></profile></profiles></project>")
    else:
        root = harness / "pom.xml"
        root.write_text(root.read_text().replace("<module>core</module>", "<module>../core</module>"))
    path.write_text(text)
    with pytest.raises(ValueError, match="ARTIFACT_"):
        financial_artifact_plan(harness, candidate)


@pytest.mark.parametrize("profile", [False, True])
def test_parent_financial_dependencies_not_silently_ignored(tmp_path, profile):
    harness, candidate = fixture(tmp_path)
    root = harness / "pom.xml"
    addition = f"<dependencies>{dep('finance-core')}</dependencies>"
    if profile:
        addition = "<profiles><profile>" + addition + "</profile></profiles>"
    root.write_text(root.read_text().replace("</project>", addition + "</project>"))
    with pytest.raises(ValueError, match="ARTIFACT_"):
        financial_artifact_plan(harness, candidate)


def test_multiple_versions_not_approximated_as_maven_mediation(tmp_path):
    harness, candidate = fixture(tmp_path)
    pom = candidate / "pom.xml"
    pom.write_text(pom.read_text().replace("</dependencies>", dep("finance-core", "2") + "</dependencies>"))
    with pytest.raises(ValueError, match="ARTIFACT_VERSION_CONFLICT"):
        financial_artifact_plan(harness, candidate)
