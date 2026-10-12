from copy import deepcopy
import pytest

from finance_solution_builder.component_assembly import AssemblyRejected
from finance_solution_builder.managed_selection import (
    build_managed,
    evaluation_pack_ref,
    solution_from_selection,
)
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.service import solution_for
from tests.test_component_assembly import COMBINATIONS, PROJECT, root


def selection_for(caps):
    old = solution_for(caps, "managed-selection")
    bindings = []
    for pack in old["capabilityPackRefs"]:
        provider_id = "wealth-facts-adapter" if pack["id"] == "wealth-facts-description" else "non-ai-banking-reference"
        provider = next(ref for ref in old["providerRefs"] if ref["id"] == provider_id)
        bindings.append({"pack": {key: pack[key] for key in ("id", "version")},
                         "provider": {key: provider[key] for key in ("id", "version")}})
    return {"schemaVersion": "finance-managed-selection.v0.1", "solutionId": "managed-selection", "packBindings": bindings}


@pytest.mark.parametrize("caps", COMBINATIONS)
def test_identity_selection_preserves_legacy_business_bindings_without_calling_recipe(root, caps, monkeypatch):
    selection = selection_for(caps)
    expected = solution_for(caps, "managed-selection")
    def forbidden(*args, **kwargs):
        raise AssertionError("Managed entry must not call a business-specific recipe")
    monkeypatch.setattr("finance_solution_builder.service.solution_for", forbidden)
    actual = solution_from_selection(PROJECT, root, selection)
    for key in ("capabilityBindings", "capabilityPackRefs", "providerRefs", "domainPackRefs", "profileRef", "runtime"):
        assert actual[key] == expected[key]


@pytest.mark.parametrize("field,value", [("className", "unsafe.Factory"), ("grantedScopes", ["all"]),
                                        ("runtime", {"language": "python"}), ("hostSupport", {"operations": ["TASK"]})])
def test_configuration_cannot_choose_code_grants_or_host_support(root, field, value):
    selection = selection_for(["account.balance.query"])
    selection[field] = value
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_SELECTION_INVALID"):
        solution_from_selection(PROJECT, root, selection)


def test_sources_are_managed_not_client_paths(root):
    selection = selection_for(["account.balance.query"])
    selection["packBindings"][0]["provider"]["source"] = "file:///private"
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_SELECTION_INVALID"):
        solution_from_selection(PROJECT, root, selection)


def test_domain_pack_owns_its_evaluation_reference(tmp_path):
    domain = tmp_path / "domain-packs" / "audit"
    domain.mkdir(parents=True)
    (domain / "pack.yaml").write_text(
        "evaluationPackRef:\n"
        "  id: smart-audit-enterprise-credit\n"
        "  version: 1.0.0\n"
        "  source: git+https://example.invalid/eval.git#enterprise-credit\n",
        encoding="utf-8",
    )

    ref = evaluation_pack_ref(
        tmp_path,
        [{"source": "repo://domain-packs/audit"}],
        {"id": "legacy", "version": "1.0", "source": "repo://legacy"},
    )

    assert ref == {
        "id": "smart-audit-enterprise-credit",
        "version": "1.0.0",
        "source": "git+https://example.invalid/eval.git#enterprise-credit",
    }


@pytest.mark.parametrize("invalid", [[], [{"pack": {"id": "unknown", "version": "1"},
                                         "provider": {"id": "non-ai-banking-reference", "version": "0.1.0-SNAPSHOT"}}]])
def test_unqualified_selection_cannot_create_build_output(root, tmp_path, invalid):
    selection = selection_for(["account.balance.query"])
    selection["packBindings"] = invalid
    output = tmp_path / "generated"
    builder = SolutionBuilder(PROJECT, root, service_output_root=output)
    with pytest.raises(AssemblyRejected):
        build_managed(builder, selection, output / "candidate", verify=False)
    assert not output.exists()


def test_duplicate_pack_binding_is_rejected(root):
    selection = selection_for(["account.balance.query"])
    selection["packBindings"].append(deepcopy(selection["packBindings"][0]))
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_DUPLICATE_ASSET"):
        solution_from_selection(PROJECT, root, selection)


@pytest.mark.parametrize("identifier", ["../escape", "bad<xml>", "", "x" * 65])
def test_managed_build_identifier_is_bounded(root, identifier):
    selection = selection_for(["account.balance.query"])
    selection["solutionId"] = identifier
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_SELECTION_INVALID"):
        solution_from_selection(PROJECT, root, selection)
