"""Generate a fresh isolated pilot with the normal service recipe and locked artifacts."""
import argparse
from pathlib import Path
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.service import CASHFLOW, solution_for, write_json


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--unit", choices=["cashflow", "balance", "wealth", "balance-wealth", "balance-cashflow", "wealth-cashflow", "all"], default="cashflow")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    capabilities = {"cashflow": sorted(CASHFLOW), "balance": ["account.balance.query"],
                    "wealth": ["wealth.facts.describe"],
                    "balance-wealth": ["account.balance.query", "wealth.facts.describe"],
                    "balance-cashflow": sorted(CASHFLOW | {"account.balance.query"}),
                    "wealth-cashflow": sorted(CASHFLOW | {"wealth.facts.describe"}),
                    "all": sorted(CASHFLOW | {"account.balance.query", "wealth.facts.describe"})}[args.unit]
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    spec = output.parent / (output.name + ".input.json")
    write_json(spec, solution_for(capabilities, "history-" + args.unit))
    builder = SolutionBuilder(project, args.harness.resolve(), service_output_root=output.parent)
    builder.build(spec, output)


if __name__ == "__main__":
    main()
