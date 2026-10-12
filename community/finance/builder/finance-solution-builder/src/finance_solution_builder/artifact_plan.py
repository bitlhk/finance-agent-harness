"""Fail-closed local reactor closure for trusted generated Java candidates.

This is deliberately not a general Maven resolver. Unsupported Maven constructs
on financial dependencies require explicit support, never an inferred fallback.
"""
from hashlib import sha256
from pathlib import Path
import re
import xml.etree.ElementTree as ET

GROUP = "io.github.bitlhk.finance"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ET.register_namespace("", NS["m"])


def _text(node, path, default=""):
    return node.findtext(path, default, NS).strip()


def _read(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError("ARTIFACT_POM_UNAVAILABLE")
    return ET.fromstring(path.read_bytes())


def _inside(root, relative):
    path = root / relative
    if Path(relative).is_absolute() or ".." in Path(relative).parts:
        raise ValueError("ARTIFACT_PATH_INVALID")
    if any(p.is_symlink() for p in [path, *path.parents] if p != root.parent):
        raise ValueError("ARTIFACT_SYMLINK_REJECTED")
    path.resolve().relative_to(root.resolve())
    return path


def _resolve(value, group, version):
    for key, replacement in {"project.groupId": group, "project.version": version,
                             "pom.groupId": group, "pom.version": version}.items():
        value = value.replace("${" + key + "}", replacement)
    if "${" in value or not value:
        raise ValueError("ARTIFACT_COORDINATE_UNRESOLVED")
    return value


def _deps(node, group, version):
    # Profiles, inherited custom dependency management and classified financial
    # artifacts are not silently approximated by this bounded implementation.
    for dependency in node.findall(".//m:profiles//m:dependency", NS):
        if GROUP in _text(dependency, "m:groupId") or "${" in _text(dependency, "m:groupId"):
            raise ValueError("ARTIFACT_PROFILE_UNSUPPORTED")
    result = []
    for dep in node.findall("m:dependencies/m:dependency", NS):
        raw_group = _text(dep, "m:groupId")
        dep_group = _resolve(raw_group, group, version)
        if dep_group != GROUP:
            continue
        scope = _text(dep, "m:scope", "compile")
        if scope == "test":
            continue
        if (scope not in {"compile", "runtime"} or _text(dep, "m:type", "jar") != "jar"
                or _text(dep, "m:classifier") or _text(dep, "m:optional", "false") != "false"
                or dep.find("m:exclusions", NS) is not None):
            raise ValueError("ARTIFACT_DEPENDENCY_UNSUPPORTED")
        result.append((dep_group, _resolve(_text(dep, "m:artifactId"), group, version),
                       _resolve(_text(dep, "m:version"), group, version)))
    return result


def add_provider_dependencies(pom, provider_refs):
    node = _read(pom)
    dependencies = node.find("m:dependencies", NS)
    if dependencies is None:
        raise ValueError("ARTIFACT_DEPENDENCIES_MISSING")
    existing = {}
    for dep in dependencies.findall("m:dependency", NS):
        existing[(_text(dep, "m:groupId"), _text(dep, "m:artifactId"))] = _text(dep, "m:version")
    for ref in provider_refs:
        match = re.fullmatch(r"maven://(io\.github\.bitlhk\.finance):([a-zA-Z0-9_.-]+):([a-zA-Z0-9_.-]+)", ref["source"])
        if not match:
            raise ValueError("ARTIFACT_PROVIDER_REFERENCE_INVALID")
        group, artifact, version = match.groups()
        if (group, artifact) in existing:
            if existing[group, artifact] != version:
                raise ValueError("ARTIFACT_VERSION_CONFLICT")
            continue
        dep = ET.SubElement(dependencies, "{" + NS["m"] + "}dependency")
        for key, value in (("groupId", group), ("artifactId", artifact), ("version", version)):
            ET.SubElement(dep, "{" + NS["m"] + "}" + key).text = value
        existing[group, artifact] = version
    ET.ElementTree(node).write(pom, encoding="utf-8", xml_declaration=True)


def financial_artifact_plan(harness, candidate):
    harness, candidate = Path(harness), Path(candidate)
    root = _read(harness / "pom.xml")
    group, version = _text(root, "m:groupId"), _text(root, "m:version")
    if group != GROUP or not version:
        raise ValueError("ARTIFACT_REACTOR_INVALID")
    if _deps(root, group, version):
        raise ValueError("ARTIFACT_INHERITED_DEPENDENCIES_UNSUPPORTED")
    modules = {}
    for entry in root.findall("m:modules/m:module", NS):
        relative = (entry.text or "").strip()
        path = _inside(harness, relative)
        node = _read(path / "pom.xml")
        own_group = _text(node, "m:groupId", _text(node, "m:parent/m:groupId"))
        own_version = _text(node, "m:version", _text(node, "m:parent/m:version"))
        artifact = _text(node, "m:artifactId")
        coordinate = (own_group, artifact, own_version)
        if coordinate in modules:
            raise ValueError("ARTIFACT_DUPLICATE_COORDINATE")
        modules[coordinate] = (relative, node)
    candidate_node = _read(candidate / "pom.xml")
    pending = _deps(candidate_node, _text(candidate_node, "m:groupId"), _text(candidate_node, "m:version"))
    selected, visiting, versions = {}, set(), {}

    def visit(coordinate):
        key = coordinate[:2]
        if key in versions and versions[key] != coordinate[2]:
            raise ValueError("ARTIFACT_VERSION_CONFLICT")
        versions[key] = coordinate[2]
        if coordinate in visiting:
            raise ValueError("ARTIFACT_DEPENDENCY_CYCLE")
        if coordinate in selected:
            return
        if coordinate not in modules:
            raise ValueError("ARTIFACT_NOT_IN_TRUSTED_REACTOR")
        relative, node = modules[coordinate]
        if (_text(node, "m:packaging", "jar") != "jar"
                or _text(node, "m:parent/m:artifactId") != _text(root, "m:artifactId")
                or _text(node, "m:parent/m:groupId") != group
                or _text(node, "m:parent/m:version") != version):
            raise ValueError("ARTIFACT_PARENT_UNSUPPORTED")
        visiting.add(coordinate)
        for dependency in _deps(node, coordinate[0], coordinate[2]):
            visit(dependency)
        visiting.remove(coordinate)
        selected[coordinate] = {"coordinate": ":".join(coordinate), "module": relative,
                                "pomSha256": sha256((harness / relative / "pom.xml").read_bytes()).hexdigest(),
                                "jar": coordinate[1] + "-" + coordinate[2] + ".jar"}

    for coordinate in pending:
        visit(coordinate)
    if not selected:
        raise ValueError("ARTIFACT_PLAN_EMPTY")
    entries = sorted(selected.values(), key=lambda x: x["coordinate"])
    if len({entry["jar"] for entry in entries}) != len(entries):
        raise ValueError("ARTIFACT_FILENAME_COLLISION")
    return {"schemaVersion": "finance-build-artifacts.v0.1",
            "reactorPomSha256": sha256((harness / "pom.xml").read_bytes()).hexdigest(),
            "candidatePomSha256": sha256((candidate / "pom.xml").read_bytes()).hexdigest(),
            "artifacts": entries}


def verify_financial_artifacts(harness, candidate):
    plan = financial_artifact_plan(harness, candidate)
    expected = {}
    for entry in plan["artifacts"]:
        target = _inside(Path(harness), entry["module"]) / "target"
        jars = list(target.glob("*.jar"))
        if len(jars) != 1 or jars[0].name != entry["jar"] or jars[0].is_symlink():
            raise ValueError("HARNESS_JAR_UNAVAILABLE")
        expected[entry["jar"]] = sha256(jars[0].read_bytes()).hexdigest()
    files = list((Path(candidate) / "target/dependency").glob("finance-*.jar"))
    if any(file.is_symlink() for file in files):
        raise ValueError("HARNESS_JARS_MISMATCH")
    actual = {file.name: sha256(file.read_bytes()).hexdigest() for file in files}
    if actual != expected:
        raise ValueError("HARNESS_JARS_MISMATCH")
    return dict(plan, jarDigests=expected)
