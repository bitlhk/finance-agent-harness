"""Live-model acceptance for one generated Python read-only cashflow Candidate.

The bank side is synthetic and local. Model credentials are read from the
environment and are never written to the report.
"""
from __future__ import annotations

import argparse
import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import time

from verify_local_service import Endpoints, request


def expiry():
    return (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat()


def fixture_for(harness: Path, period: str):
    source = harness / "conformance/fixtures/cashflow/source"
    values = {name: json.loads((source / (name + ".json")).read_text())
              for name in ("summary", "analysis", "summary-policy", "analysis-policy")}
    values["summary"]["period"] = period
    values["analysis"]["period"] = period
    if period == "2026-07":
        values["summary"].update(totalIncome="7100.00", totalExpense="4300.00", netCashflow="2800.00")
        values["analysis"].update(totalIncome="7100.00", totalExpense="4300.00", netCashflow="2800.00")
        values["analysis"]["categories"][0]["category"] = "交通"
    else:
        values["summary"].update(totalIncome="8300.00", totalExpense="5100.00", netCashflow="3200.00")
        values["analysis"].update(totalIncome="8300.00", totalExpense="5100.00", netCashflow="3200.00")
        values["analysis"]["categories"][0]["category"] = "餐饮"
    values["analysis"]["categories"][0].update(income=values["analysis"]["totalIncome"],
        expense=values["analysis"]["totalExpense"])
    year, month = map(int, period.split("-"))
    trend = []
    for offset in range(5, -1, -1):
        absolute = year * 12 + month - 1 - offset
        item_period = f"{absolute // 12:04d}-{absolute % 12 + 1:02d}"
        trend.append({"period": item_period, "income": values["analysis"]["totalIncome"],
            "expense": values["analysis"]["totalExpense"], "netCashflow": values["analysis"]["netCashflow"]})
    values["analysis"]["trend"] = trend
    return values


def verify(candidate: Path, harness: Path, output: Path):
    candidate, harness, output = candidate.resolve(), harness.resolve(), output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    output.chmod(0o700)
    fixture = Endpoints()
    fixture.cashflow = fixture_for(harness, "2026-07")
    policy = output / "policy.json"
    policy.write_text(json.dumps({"schemaVersion": "local-simulator-access.v0.1", "bankUrl": fixture.base,
        "subjects": {fixture.customer: {"enabled": True, "expiresAt": expiry(), "scopes": ["cashflow:read"]}}}))
    policy.chmod(0o600)
    required = ("FH_MODEL_URL", "FH_MODEL_NAME", "FH_MODEL_KEY")
    if any(not os.environ.get(name) for name in required):
        raise ValueError("LIVE_MODEL_CONFIGURATION_REQUIRED")
    token = secrets.token_urlsafe(48)
    lock = (candidate / "service-lock.json").read_bytes()
    env = dict(os.environ, FH_HOST_TOKEN=token, FH_PORT="0", FH_BANK_URL=fixture.base,
        FH_BANK_LOGIN="synthetic", FH_BANK_SECRET="synthetic", FH_ACCESS_POLICY=str(policy),
        FH_HISTORY_DIR=str(output / "history"), FH_HISTORY_OWNER="readonly-task-collaboration",
        FH_EXPECTED_LOCK=hashlib.sha256(lock).hexdigest(), FH_BUSINESS_DATE="2026-09-15",
        FH_BUSINESS_TIME_ZONE="Asia/Shanghai", FH_BUSINESS_DATE_SOURCE="controlled-evaluation-host")
    (candidate / "instance.json").unlink(missing_ok=True)
    log = (output / "host.log").open("wb")
    child = subprocess.Popen([str(candidate / ".venv/bin/python"), "-I", str(candidate / "start_python.py")],
        cwd=candidate, env=env, stdout=log, stderr=subprocess.STDOUT)
    checks, turns = [], []
    def check(name, condition):
        checks.append({"name": name, "passed": bool(condition)})
        if not condition:
            raise AssertionError(name)
    def chat(base, sid, query):
        before = len(fixture.reads)
        code, result = request(base, token, f"/sessions/{sid}/chat", {"query": query})
        calls = copy.deepcopy(fixture.reads[before:])
        turns.append({"query": query, "status": code, "answer": result.get("answer"),
                      "toolResults": result.get("toolResults", []), "businessReads": calls})
        return code, result, calls
    try:
        deadline = time.monotonic() + 45
        while not (candidate / "instance.json").exists():
            if child.poll() is not None or time.monotonic() > deadline:
                raise RuntimeError("HOST_START_FAILED")
            time.sleep(.1)
        info = json.loads((candidate / "instance.json").read_text())
        base = "http://127.0.0.1:" + str(info["port"])
        code, health = request(base, token, "/health")
        check("python_runtime", code == 200 and health["runtime"] == "openjiuwen-python-0.1.18")
        check("two_readonly_capabilities", set(health["selectedCapabilities"]) == {
            "finance.transaction-summary.query", "finance.spending-analysis.query"})
        code, opened = request(base, token, "/sessions", {})
        check("session_open", code == 200)
        sid = opened["sessionId"]

        code, result, calls = chat(base, sid, "查询2026年7月的收入、支出和净收支。")
        check("single_goal_summary", code == 200 and len(result.get("toolResults", [])) == 1
              and "transaction-summary" in calls[0][0] and "period=2026-07" in calls[0][0])

        code, result, calls = chat(base, sid, "再汇总2026年7月收支，并告诉我主要花在哪。")
        routes = [item[0] for item in calls]
        answer = result.get("answer", "")
        check("two_goals_use_prior_summary_and_new_analysis", code == 200
              and not any("transaction-summary" in route for route in routes)
              and any("spending-analysis" in route for route in routes)
              and "7,100" in answer and "交通" in answer)

        fixture.cashflow = fixture_for(harness, "2026-08")
        code, result, calls = chat(base, sid, "改成上个月，仍然汇总收支并分析主要支出。")
        routes = [item[0] for item in calls]
        answer = result.get("answer", "")
        check("trusted_relative_month_changes_both_goals", code == 200 and len(routes) >= 2
              and all("period=2026-08" in route for route in routes) and "餐饮" in answer)

        code, unsupported = request(base, token, "/sessions", {})
        check("unsupported_session_open", code == 200)
        before = len(fixture.reads)
        code, result, calls = chat(base, unsupported["sessionId"], "查一下我的i豆余额。")
        check("known_term_missing_capability_no_substitute", code == 200 and not calls
              and len(fixture.reads) == before and any(word in result.get("answer", "") for word in ("不支持", "未安装", "无法")))

        code, clarify = request(base, token, "/sessions", {})
        check("clarification_session_open", code == 200)
        before = len(fixture.reads)
        code, result, calls = chat(base, clarify["sessionId"], "查八月收支。")
        check("month_without_year_clarifies", code == 200 and not calls and len(fixture.reads) == before
              and any(word in result.get("answer", "") for word in ("年份", "哪一年", "具体年份")))
        check("zero_bank_writes", not fixture.writes)
        plan = json.loads((candidate / "component-installation-plan.json").read_text())
        descriptions = plan.get("capabilityDescriptions", {})
        check("description_catalog_digest_present", str(descriptions.get("digest", "")).startswith("sha256:"))
        check("terminology_digest_present", str(descriptions.get("terminologyPromptDigest", "")).startswith("sha256:"))
        check("collaboration_digest_present", str(plan.get("taskCollaboration", {}).get("instructionDigest", "")).startswith("sha256:"))
        report = {"status": "PASS", "runtime": health["runtime"], "model": os.environ["FH_MODEL_NAME"],
            "bank": "CONTROLLED_CONTRACT_HTTP", "checks": checks, "turns": turns,
            "lockSha256": hashlib.sha256(lock).hexdigest(), "buildId": health["buildId"],
            "capabilityDescriptions": descriptions, "taskCollaboration": plan.get("taskCollaboration"),
            "independentHuman": "NOT_RUN"}
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps({"status": "PASS", "checks": len(checks), "report": str(output / "report.json")}))
    except Exception as failure:
        (output / "report.json").write_text(json.dumps({"status": "FAIL", "checks": checks,
            "turns": turns, "failureType": type(failure).__name__, "failure": str(failure)}, ensure_ascii=False, indent=2))
        raise
    finally:
        child.terminate()
        try:
            child.wait(15)
        except subprocess.TimeoutExpired:
            child.kill(); child.wait(5)
        log.close(); fixture.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    verify(args.candidate, args.harness, args.output)
