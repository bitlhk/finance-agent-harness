"""Generate a browser-only test manifest; never contacts or mutates a service."""
import argparse
from itertools import combinations
import json
from pathlib import Path

SUPPORT = {
    "openjiuwen-java-0.1.15": ("account.balance.query", "wealth.facts.describe", "wealth-customer-context", "finance.cashflow.readonly", "internal-transfer", "channel-preferences"),
    "openjiuwen-python-0.1.18": ("account.balance.query", "wealth.facts.describe", "finance.cashflow.readonly"),
    "agentscope-java-2.0.3": ("account.balance.query", "finance.cashflow.readonly", "internal-transfer"),
}
CAPABILITIES = {
    "account.balance.query": ["account.balance.query"],
    "wealth.facts.describe": ["wealth.facts.describe"],
    "wealth-customer-context": ["wealth.customer-context.query"],
    "finance.cashflow.readonly": ["finance.transaction-summary.query", "finance.spending-analysis.query"],
    "internal-transfer": ["transfer.internal.create"],
    "channel-preferences": ["channel.preferences.update"],
}

def manifest(mode):
    cases = []
    for runtime, cards in SUPPORT.items():
        if mode == "full":
            groups = [group for size in range(1, len(cards) + 1) for group in combinations(cards, size)]
        else:
            groups = [(card,) for card in cards] + [cards]
            if runtime.startswith("openjiuwen-java"):
                groups.append(cards[:4])
        for group in groups:
            cases.append({"caseId": f"BROWSER-{len(cases)+1:03d}", "runtimeId": runtime,
                          "componentIds": list(group),
                          "expectedCapabilityIds": sorted(cap for card in group for cap in CAPABILITIES[card]),
                          "steps": ["preflight", "assemble", "build", "launch", "query_each_selected_card",
                                    "task_preview_cancel_only", "inspect_record_twice_without_execution",
                                    "stop", "delete_created_agent", "reload_and_verify_absence"],
                          "status": "NOT_RUN"})
    return {"schemaVersion": "finance-dot-browser-regression.v1", "mode": mode,
            "origin": "https://finance-harness.demo.linggan.top/", "cases": cases,
            "maxConcurrentAgents": 1, "automaticRetries": 0, "allowBusinessCommit": False,
            "deleteOnlyAgentsCreatedByThisRun": True,
            "stopBatchOnCleanupFailure": True,
            "operatorInputs": ["releaseCommit", "modelRef", "approvedSyntheticIdentity", "businessMonth",
                               "approvedSyntheticTransferPreviewArguments", "approvedPreferencePreviewValue"],
            "resultFields": ["caseId", "status", "buildId", "selectedCapabilities", "installedTools",
                             "requestIds", "recordIds", "observedToolCalls", "businessOutcome",
                             "screenshots", "replayAddedCalls", "stopStatus", "deleteStatus",
                             "cleanupRemainingIds", "failureStage", "failureReason"],
            "limitations": ["Manifest is not executed evidence.", "Full means all declared card subsets, not all model inputs.",
                            "TASK commit is excluded unless separately approved.",
                            "Unavailable browser observability is NOT_OBSERVED, never zero."]}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("smoke", "full"), default="smoke")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with args.output.open("x") as output:
        json.dump(manifest(args.mode), output, indent=2)
        output.write("\n")
    print(f"Generated {len(manifest(args.mode)['cases'])} NOT_RUN cases: {args.output}")

if __name__ == "__main__":
    main()
