"""Generic scope projection; actual packaged admission is a separate opt-in probe."""
from copy import deepcopy
import json
from pathlib import Path
import shutil

import pytest

from finance_solution_builder.agentscope_service import project_observation_refs
from finance_solution_builder.component_assembly import AssemblyRejected, ComponentAssembly
from finance_solution_builder.service import digest, write_json
from tests.test_component_assembly import root


SCHEMA = "contracts/json-schema/observation/observation-requirements.v0.1.schema.json"


@pytest.fixture
def assembly(root):
    destination = root / SCHEMA
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__).parent / "fixtures/observation-requirements.v0.1.schema.json", destination)
    return ComponentAssembly(root)


def declaration(assembly, identity, scopes):
    document = {"schemaVersion": "finance-observation-requirements.v0.1", "id": identity, "version": "0.1.0",
                "requirements": [{"semanticId": "capability.result", "semanticVersion": "0.1",
                    "obligation": "REQUIRED", "boundary": "PROVIDER", "content": "METADATA",
                    "fields": ["capabilityId", "businessStatus"], "capabilityIds": scope} for scope in scopes]}
    path = assembly.root / (identity + ".json")
    write_json(path, document)
    return {"id": identity, "version": "0.1.0", "source": "repo://" + path.name,
            "digest": "sha256:" + digest(path)}


def test_whole_scoped_files_and_common_requirements_preserved(assembly):
    refs = [declaration(assembly, "one", [["example.a"]]),
            declaration(assembly, "two", [["example.b", "example.c"]]),
            declaration(assembly, "common", [[]])]
    before = deepcopy(refs)
    selected, records = project_observation_refs(assembly, refs, ["example.a"])
    assert selected == [refs[0], refs[2]] and refs == before
    assert [item["status"] for item in records] == ["SELECTED", "NOT_APPLICABLE", "SELECTED"]
    assert records[1]["reference"] == refs[1]
    assert records[2]["containsCommonRequirements"] is True
    for ref in selected:
        path = assembly.root / ref["source"].removeprefix("repo://")
        assert "sha256:" + digest(path) == ref["digest"]
        assert all(item["obligation"] == "REQUIRED" for item in json.loads(path.read_bytes())["requirements"])
    assert project_observation_refs(assembly, refs, ["example.a", "example.b", "example.c"])[0] == refs


@pytest.mark.parametrize("scopes", [[["example.a", "example.b"]], [["example.a"], ["example.b"]],
                                  [[], ["example.b"]]])
def test_mixed_or_partially_selected_file_is_rejected_not_clipped(assembly, scopes):
    ref = declaration(assembly, "mixed", scopes)
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_OBSERVATION_SCOPE_INCOMPLETE"):
        project_observation_refs(assembly, [ref], ["example.a"])


@pytest.mark.parametrize("mutation", ["digest", "identity", "version", "schema", "traversal"])
def test_unselected_files_still_require_valid_fixed_identity(assembly, mutation):
    ref = declaration(assembly, "unselected", [["example.b"]])
    if mutation == "digest":
        ref["digest"] = "sha256:" + "0" * 64
    elif mutation in {"identity", "version"}:
        ref["id" if mutation == "identity" else "version"] = "wrong"
    elif mutation == "traversal":
        ref["source"] = "repo://../outside.json"
    else:
        path = assembly.root / "unselected.json"
        document = json.loads(path.read_bytes())
        document["requirements"][0]["obligation"] = "IGNORE"
        write_json(path, document)
        ref["digest"] = "sha256:" + digest(path)
    with pytest.raises(AssemblyRejected):
        project_observation_refs(assembly, [ref], ["example.a"])


def test_empty_reference_list_has_no_invented_requirements(assembly):
    assert project_observation_refs(assembly, [], ["example.a"]) == ([], [])
