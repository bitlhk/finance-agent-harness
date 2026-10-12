"""Build real query-pack extensions without editing Runtime or public Host dispatch.

Uses only local artifacts. Does not start a model or connect to a bank.
"""
import argparse
import json
from pathlib import Path
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.service import build_service, solution_for


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    builder = SolutionBuilder(project, args.harness.resolve(), maven=args.maven, service_output_root=args.output.resolve())
    solution = solution_for(["account.balance.query"], "account-history-extension")
    for pack, capability in [("account-statement-readonly", "account.statement.query"),
                             ("account-transactions-readonly", "account.transaction.query")]:
        solution["capabilityPackRefs"].append({"id": pack, "version": "0.1.0", "source": "repo://capability-packs/" + pack})
        solution["capabilityBindings"][capability] = "non-ai-banking-reference"
    result = build_service(builder, solution, args.output.resolve() / "account-history", args.verify, managed=True)
    print(json.dumps({"status": result.status, "output": str(result.output_dir)}, default=str))


if __name__ == "__main__":
    main()
