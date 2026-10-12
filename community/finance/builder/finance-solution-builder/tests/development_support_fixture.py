"""Harness 597a834 metadata snapshot, rebound only to synthetic test pack bytes."""
import json
from pathlib import Path
import shutil

from finance_solution_builder.assets import tree_digest
from finance_solution_builder.method_assets import canonical, sha

FIXTURE = Path(__file__).parent / "fixtures/development-support"


def write_declarations(root, declarations):
    refs = []
    for index, declaration in enumerate(declarations):
        source = f"repo://metadata/development-support/fixture-{index}/0.1.0.json"
        path = root / source.removeprefix("repo://")
        path.parent.mkdir(parents=True, exist_ok=True)
        raw = canonical(declaration)
        path.write_bytes(raw)
        refs.append({"source": source, "digest": sha(raw)})
    path = root / "metadata/development-support/index.v0.1.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical({"schemaVersion": "finance-development-support-index.v0.1", "declarations": refs}))
    return refs


def install_support(root):
    shutil.copytree(FIXTURE / "contracts", root / "contracts", dirs_exist_ok=True)
    index = json.loads((FIXTURE / "metadata/development-support/index.v0.1.json").read_text())
    declarations = []
    for ref in index["declarations"]:
        declaration = json.loads((FIXTURE / ref["source"].removeprefix("repo://")).read_text())
        subject = declaration["subject"]
        subject["digest"] = tree_digest(root / subject["source"].removeprefix("repo://"))
        for case_ref in declaration["caseRefs"]:
            relative = case_ref["source"].removeprefix("repo://")
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(FIXTURE / relative, target)
        declarations.append(declaration)
    write_declarations(root, declarations)
    return declarations
