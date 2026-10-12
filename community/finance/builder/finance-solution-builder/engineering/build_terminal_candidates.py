"""Build locked terminal candidates. Controlled verification is a separate command."""
import argparse
import json
from pathlib import Path
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.service import solution_for
from finance_solution_builder.terminal_ui import presentation_config


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--names", nargs="+", default=["mobile","desktop","atm","teal","wealth-only"])
    parser.add_argument("--reuse-binaries-from", type=Path, help="Explicit previously verified Candidate; existing hash and template checks still apply")
    args=parser.parse_args(); output=args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    project=Path(__file__).resolve().parents[1]
    builder=SolutionBuilder(project,args.harness,service_output_root=output)
    for name in args.names:
        terminal={"mobile":"MOBILE_WEB","desktop":"DESKTOP_WEB","atm":"ATM_WEB","teal":"DESKTOP_WEB","wealth-only":"MOBILE_WEB"}[name]
        config={"voiceInput":name=="mobile"}
        if name=="teal": config.update(theme="EXAMPLE_TEAL",balanceCard="balance-ledger-v1",assistantName="示例银行助手")
        caps=["wealth.facts.describe"] if name=="wealth-only" else ["account.balance.query","wealth.facts.describe"]
        recipe=output/(name+"-input.json"); recipe.write_text(json.dumps(solution_for(caps,"terminal-"+name)))
        result=builder.build(recipe,output/name,interface_config=presentation_config({"presentation":terminal,"interfaceConfig":config}),
                             reuse_candidate=args.reuse_binaries_from)
        print(name,result.status,flush=True)


if __name__=="__main__": main()
