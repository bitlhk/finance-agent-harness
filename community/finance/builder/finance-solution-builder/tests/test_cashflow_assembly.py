import itertools
import pytest
from finance_solution_builder.service import (CASHFLOW, CAPABILITIES, installation_for_method,
                                               solution_for, validate_service)


def test_exactly_seven_installations_not_fifteen_arbitrary_tool_sets():
    accepted = []
    for n in range(1, 5):
        for selected in itertools.combinations(sorted(CAPABILITIES), n):
            caps = list(selected)
            if set(caps) & CASHFLOW and not CASHFLOW <= set(caps):
                with pytest.raises(ValueError, match="READONLY_SELECTION_INVALID"):
                    solution_for(caps, "probe")
            else:
                solution = solution_for(caps, "probe")
                assert set(validate_service(solution)) == set(caps)
                assert set(solution["capabilityBindings"]) == set(caps)
                assert len({r["id"] for r in solution["providerRefs"]}) == len(solution["providerRefs"])
                assert all("transfer" not in r["id"] for r in solution["capabilityPackRefs"])
                accepted.append(caps)
    assert len(accepted) == 7


@pytest.mark.parametrize("method", [["finance.transaction-summary.query"], ["finance.spending-analysis.query"],
                                   ["finance.transaction-summary.query", "wealth.facts.describe"]])
def test_trial_installation_expands_without_mutating_method_manifest(method):
    original = list(method)
    assert set(installation_for_method(method)) == set(method) | CASHFLOW
    assert method == original


def test_invalid_method_does_not_gain_tools_by_expansion():
    for scope in ([], ["transfer.submit"], ["finance.transaction-summary.query"] * 2):
        with pytest.raises(ValueError, match="SERVICE_METHOD_SCOPE_INVALID"):
            installation_for_method(scope)
