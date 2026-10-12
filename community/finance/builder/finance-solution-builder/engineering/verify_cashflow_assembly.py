"""Build seven finite recipes and run their actual generated Java hosts against controlled HTTP.

This is not a real model, real bank, or independent human acceptance entry.
"""
import argparse
import json
from pathlib import Path

from finance_solution_builder.method_trials import reusable_seed, fixture_module, probe, run_trial
from finance_solution_builder.service import CASHFLOW, solution_for, write_json
from finance_solution_builder.workbench import Jobs


def verify(project, harness, output):
    jobs = Jobs(project, harness, data_dir=output)
    results = {"source": "CONTROLLED_HTTP", "model": "CONTROLLED_TEST", "recipes": [], "methodTrial": "NOT_RUN"}
    try:
        seed = reusable_seed(jobs, "verification")
        helper = fixture_module(project, harness)
        units = [["account.balance.query"], ["wealth.facts.describe"], sorted(CASHFLOW)]
        for bits in range(1, 8):
            caps = [c for i, unit in enumerate(units) if bits & (1 << i) for c in unit]
            name = "combination-" + str(bits)
            request = output / (name + ".json")
            write_json(request, solution_for(caps, "agent-" + name))
            built = jobs.builder.build(request, output / name, reuse_candidate=seed)
            cases = []
            if bits & 1: cases.append({"name": "balance", "query": "balance-probe", "expectedTerms": []})
            if bits & 2: cases.append({"name": "wealth", "query": "wealth-probe", "expectedTerms": []})
            if bits & 4:
                cases += [{"name": "summary", "query": "summary-probe 2026-08", "expectedTerms": []},
                          {"name": "analysis", "query": "analysis-probe 2026-08", "expectedTerms": []}]
            result = probe(built.candidate_dir, cases, helper)
            results["recipes"].append({"capabilities": caps, "evidence": result})
            write_json(output / "assembly-verification.json", results)
        draft = jobs.method_assets.create("verification", {"name": "Monthly summary only",
            "content": "# Summary method\nFor an explicitly requested month, use only finance_transaction_summary. "
                       "Preserve scope, currency, source time and missing facts. SUMMARY_METHOD_SUBSET_V1",
            "capabilityIds": ["finance.transaction-summary.query"],
            "cases": [{"name": "summary", "query": "summary-probe 2026-08", "expectedTerms": []}]})
        trial = jobs.method_assets.trial_input("verification", draft["assetId"], draft["revision"])
        results["methodTrial"] = run_trial(jobs, "verification", trial)
        assert results["methodTrial"]["runtimeVerified"]
        write_json(output / "assembly-verification.json", results)
        print(json.dumps({"combinations": len(results["recipes"]), "methodRuntimeVerified": True,
                          "model": "CONTROLLED_TEST", "source": "CONTROLLED_HTTP"}))
    finally:
        jobs.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    verify(Path(__file__).resolve().parents[1], args.harness.resolve(), args.output.resolve())
