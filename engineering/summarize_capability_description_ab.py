"""Summarize paired A/B tool-selection evidence without re-running a model or Provider."""

import argparse
import json
from collections import Counter, defaultdict
from pathlib import Path
from statistics import median


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    report = json.loads(args.report.read_text())
    rows = report["results"]
    paired = defaultdict(dict)
    by_variant = defaultdict(list)
    for row in rows:
        assert row["bankTransportOperations"] == 0, f"Bank transport observed: {row['id']}"
        pair_key = f'{row["id"]}#{row.get("repetition", 1)}'
        paired[pair_key][row["variant"]] = row
        by_variant[row["variant"]].append(row)
    assert all(set(value) == {"A", "B"} for value in paired.values()), "A/B pairing is incomplete"

    summary = {"schemaVersion": "finance-capability-description-ab-summary.v0.2",
               "sourceReport": str(args.report), "modelName": report["modelName"],
               "runtime": report["runtime"], "variants": {}, "paired": {}}
    for variant, selected in sorted(by_variant.items()):
        categories = {}
        for category in sorted({row["category"] for row in selected}):
            group = [row for row in selected if row["category"] == category]
            categories[category] = {"total": len(group), "passed": sum(row["passed"] for row in group),
                                    "failed": sum(not row["passed"] for row in group)}
        summary["variants"][variant] = {
            "total": len(selected), "passed": sum(row["passed"] for row in selected),
            "failed": sum(not row["passed"] for row in selected),
            "unexpectedToolCalls": sum(row["expected"] in {"NO_TOOL_CALL", "CLARIFY"} and
                                        bool(row.get("evaluatedToolCalls", row["toolCalls"])) for row in selected),
            "unexpectedTransferCalls": sum(row["expected"] in {"NO_TOOL_CALL", "CLARIFY"} and
                                            "transfer_internal_create" in tool_names(
                                                row.get("evaluatedToolCalls", row["toolCalls"])) for row in selected),
            "unexpectedTaskSelections": sum(bool(row.get("unexpectedTaskSelection")) for row in selected),
            "runFailures": sum(row["outcome"] == "RUN_FAILED" for row in selected),
            "controlledProviderOperations": sum(row["controlledProviderOperations"] for row in selected),
            "meanElapsedMillis": round(sum(row["elapsedMillis"] for row in selected) / len(selected), 1),
            "medianElapsedMillis": round(median(row["elapsedMillis"] for row in selected), 1),
            "categories": categories,
        }
    changes = Counter()
    for value in paired.values():
        before, after = value["A"]["passed"], value["B"]["passed"]
        changes["improved" if after and not before else "regressed" if before and not after else
                "both_passed" if before else "both_failed"] += 1
    summary["paired"] = {key: changes[key] for key in
                         ("both_passed", "improved", "regressed", "both_failed")}
    case_count = len(next(iter(by_variant.values())))
    summary["measurementLimits"] = [
        f"The {case_count} live executions per variant are a technical pre-experiment, not a statistically powered bank acceptance set.",
        "TASK selection stops at the Runtime boundary; deterministic QUERY results may use the in-memory Provider.",
        "No bank transport is configured or executed.",
        "Clarification scoring checks declared response evidence terms; it is not a full language-quality review.",
        "Elapsed time is per-case wall time and includes fresh Agent installation; it is not model-only latency.",
        "Token usage and monetary cost were not exposed by this Runtime path and are NOT_CAPTURED.",
        "Declared response evidence and arguments are checked, but full natural-language quality and trusted task confirmation are outside this experiment."
    ]
    rendered = json.dumps(summary, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered)
    print(rendered, end="")


def tool_names(selections):
    return [selection["toolName"] if isinstance(selection, dict) else selection for selection in selections]


if __name__ == "__main__":
    main()
