"""Compare new declaration admission with seven legacy recipes, without business calls."""
from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path
import sys
import time

PROJECT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT / "src"))
sys.path.insert(0, str(PROJECT.parent / "base/src"))

from finance_solution_builder.component_assembly import ComponentAssembly
from finance_solution_builder.service import solution_for


def verify(harness):
    support = json.loads((PROJECT / "templates/local-readonly-service/host-support.v0.1.json").read_text())
    # Legacy regression inputs, not an assembly resolver capability allowlist.
    units = [["wealth.facts.describe"], ["account.balance.query"],
             ["finance.transaction-summary.query", "finance.spending-analysis.query"]]
    results = []
    for n in range(1, 4):
        for selected in itertools.combinations(units, n):
            solution = solution_for(sum(selected, []), "declaration-regression")
            start = time.monotonic_ns()
            result = ComponentAssembly(harness).resolve(solution["capabilityPackRefs"], solution["providerRefs"],
                solution["capabilityBindings"], host=support, runtime=solution["runtime"],
                connection_kind="synthetic-bank-http.v1")
            for key in ("capabilityPackRefs", "providerRefs", "domainPackRefs", "capabilityBindings"):
                assert solution[key] == result[key], key
            results.append({"capabilities": sorted(solution["capabilityBindings"]),
                            "legacyReferencesUnchanged": True,
                            "elapsedMillis": (time.monotonic_ns() - start) / 1_000_000,
                            "evidence": result["evidence"]})
    return {"schemaVersion": "component-assembly-verification.v0.1", "cases": results,
            "bankCalls": 0, "modelCalls": 0, "scope": "OFFLINE_ADMISSION_ONLY",
            "hostInstallation": "NOT_RUN", "sharedTask": "NOT_IMPLEMENTED",
            "humanOnboarding": "NOT_RUN", "deployment": "NOT_RUN"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(args.harness), indent=2))
