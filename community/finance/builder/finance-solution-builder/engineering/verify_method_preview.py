"""Explicit configured-model preview against synthetic bank data (may consume model quota)."""
import argparse
import os
from pathlib import Path
import shlex

from finance_solution_builder.local_model_binding import bind_configured_model
from finance_solution_builder.method_assets import write_private
from finance_solution_builder.method_trials import run_preview
from finance_solution_builder.workbench import Jobs


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--model-ref", required=True)
    parser.add_argument("--environment-file", type=Path, required=True)
    args = parser.parse_args()
    env = dict(os.environ)
    for line in args.environment_file.read_text().splitlines():
        tokens = shlex.split(line, comments=True)
        if not tokens: continue
        if tokens[0] == "export": tokens = tokens[1:]
        if len(tokens) != 1 or "=" not in tokens[0]: raise ValueError("ENVIRONMENT_FILE_FORMAT_UNSUPPORTED")
        key, value = tokens[0].split("=", 1)
        if key.startswith("FH_"): env[key] = value
    args.data.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.environ.update(bind_configured_model(env, args.data.resolve(), args.model_ref))
    project = Path(__file__).resolve().parents[1]
    jobs = Jobs(project, args.harness.resolve(), data_dir=args.data.resolve())
    try:
        draft = jobs.method_assets.create("preview-operator", {
            "name": "财富缺口核验", "content": "# 财富信息核验\n用户要求财富概览时先调用财富事实工具。按已知事实、缺失信息、需核实问题三部分输出。不得把缺数补为零，不做正式推荐。引用 references/coverage.md。",
            "resources": {"references/coverage.md": "资产统计范围不清晰时明确说明；测评体系和有效期应分别列出，缺失时不得推断。"},
            "requiredResources": ["references/coverage.md"], "capabilityIds": ["wealth.facts.describe"],
            "cases": [{"name": "财富概览", "query": "请查询我的财富概览，并说明还缺少哪些信息。", "expectedTerms": []}]})
        report = run_preview(jobs, "preview-operator", draft, args.model_ref, draft["data"]["cases"][0]["query"])
        write_private(args.data / "configured-model-preview.json", report)
        print("Configured-model preview completed; synthetic source; professional quality requires human review.")
        print("Report: " + str(args.data / "configured-model-preview.json"))
    finally:
        jobs.close()
