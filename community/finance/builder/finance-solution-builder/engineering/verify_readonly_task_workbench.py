"""Browser-to-generated-Agent acceptance for read-only task collaboration.

Uses the public creation controls, a real configured model, and a synthetic
local bank. It owns and stops only the isolated processes created here.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

from playwright.sync_api import sync_playwright

from unified_browser_selection import assert_same_packages, blank_selection, select_pack
from verify_local_service import Endpoints
from verify_readonly_task_collaboration import fixture_for


def write_json(path: Path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2))
    path.chmod(0o600)


def verify(harness: Path, output: Path, maven: str, chromium: str):
    harness, output = harness.resolve(), output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    output.chmod(0o700)
    project = Path(__file__).resolve().parents[1]
    endpoint = Endpoints()
    endpoint.cashflow = fixture_for(harness, "2026-07")
    checks, turns = [], []
    child = None

    def check(name, condition):
        checks.append({"name": name, "passed": bool(condition)})
        write_json(output / "checks.json", checks)
        if not condition:
            raise AssertionError(name)

    required = ("FH_MODEL_URL", "FH_MODEL_NAME", "FH_MODEL_KEY")
    if any(not os.environ.get(name) for name in required):
        raise ValueError("LIVE_MODEL_CONFIGURATION_REQUIRED")
    model_ref = "model-live-acceptance"
    catalog = output / "model-catalog.json"
    registry = output / "model-bindings.json"
    policy = output / "access-policy.json"
    write_json(catalog, {"schemaVersion": "finance-model-catalog.v1", "source": {"kind": "CONTROLLED_ACCEPTANCE"},
        "models": [{"modelRef": model_ref, "displayName": os.environ["FH_MODEL_NAME"], "provider": "OpenAI",
            "modelName": os.environ["FH_MODEL_NAME"], "eaCatalogId": "controlled-live-acceptance", "eaConfigured": True}]})
    write_json(registry, {"schemaVersion": "finance-model-bindings.v1", "bindings": [{"modelRef": model_ref,
        "revision": "acceptance-v1", "enabled": True, "protocol": "OPENAI_CHAT_COMPLETIONS", "provider": "OpenAI",
        "modelName": os.environ["FH_MODEL_NAME"], "urlEnvironment": "FH_CONN_ACCEPTANCE_URL",
        "keyEnvironment": "FH_CONN_ACCEPTANCE_KEY"}]})
    write_json(policy, {"schemaVersion": "local-simulator-access.v0.1", "bankUrl": endpoint.base,
        "subjects": {endpoint.customer: {"enabled": True,
            "expiresAt": (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat(), "scopes": ["cashflow:read"]}}})
    env = dict(os.environ, FH_MODEL_CATALOG=str(catalog), FH_MODEL_REGISTRY=str(registry),
        FH_CONN_ACCEPTANCE_URL=os.environ["FH_MODEL_URL"], FH_CONN_ACCEPTANCE_KEY=os.environ["FH_MODEL_KEY"],
        FH_MODEL_MODE="LIVE", FH_BANK_URL=endpoint.base, FH_BANK_LOGIN="synthetic", FH_BANK_SECRET="synthetic",
        FH_ACCESS_POLICY=str(policy), FH_CASHFLOW_ACCESS_POLICY=str(policy), FH_BUSINESS_DATE="2026-09-15",
        FH_BUSINESS_TIME_ZONE="Asia/Shanghai", FH_BUSINESS_DATE_SOURCE="controlled-browser-host",
        PYTHONPATH=str(project / "src") + os.pathsep + str(project.parent / "base/src"))
    log_path = output / "workbench.log"
    try:
        with log_path.open("wb") as log:
            child = subprocess.Popen([sys.executable, "-m", "finance_solution_builder.workbench",
                "--harness-repo", str(harness), "--port", "0", "--data-dir", str(output / "jobs"),
                "--maven", maven], cwd=project, env=env, stdout=log, stderr=log)
        deadline = time.monotonic() + 45
        base = None
        while time.monotonic() < deadline:
            match = re.search(r"http://127\.0\.0\.1:\d+", log_path.read_text())
            if match:
                base = match.group()
                break
            if child.poll() is not None:
                raise AssertionError("WORKBENCH_START_FAILED")
            time.sleep(.1)
        check("workbench_started", bool(base))
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(executable_path=chromium, headless=True)
            try:
                page = browser.new_page(viewport={"width": 1440, "height": 1000})
                errors = []
                page.on("pageerror", lambda error: errors.append(str(error)))
                page.goto(base)
                page.wait_for_timeout(1200)
                directory = page.request.get(base + "/api/v1/catalog").json()
                assert_same_packages(directory)
                blank_selection(page)
                select_pack(page, directory, "cashflow-readonly")
                page.locator('[data-runtime="openjiuwen-python-0.1.18"]').check()
                page.locator('[data-template="DESKTOP_WEB"]').click()
                check("page_selection_exact", page.locator('[data-action="build"]').is_enabled())
                page.screenshot(path=str(output / "creation-desktop.png"), full_page=True)
                with page.expect_response(lambda response: response.url.endswith("/api/v1/builds")
                                          and response.request.method == "POST", timeout=30000) as response:
                    page.locator('[data-action="build"]').click()
                accepted = response.value
                built = accepted.json()
                check("page_build_accepted", accepted.status == 202)
                selection = accepted.request.post_data_json
                check("cashflow_pack_selected", [item["pack"]["id"] for item in selection["packBindings"]]
                      == ["cashflow-readonly"])
                check("python_selected", selection["runtimeId"] == "openjiuwen-python-0.1.18")
                build_id = built["buildId"]
                candidate = output / "jobs/workbench" / build_id / "candidate"
                report_path = candidate.parent / "build-report.json"
                deadline = time.monotonic() + 900
                while time.monotonic() < deadline:
                    if report_path.exists() and json.loads(report_path.read_text()).get("status") in {"BUILT", "FAILED"}:
                        break
                    if child.poll() is not None:
                        raise AssertionError("WORKBENCH_EXITED_DURING_BUILD")
                    time.sleep(1)
                report = json.loads(report_path.read_text())
                check("page_build_completed", report["status"] == "BUILT")
                lock = json.loads((candidate / "service-lock.json").read_text())
                plan = json.loads((candidate / "component-installation-plan.json").read_text())
                descriptions = plan.get("capabilityDescriptions", {})
                check("installed_capabilities_exact", set(lock["capabilities"])
                      == {"finance.transaction-summary.query", "finance.spending-analysis.query"})
                check("description_and_terminology_frozen", str(descriptions.get("digest", "")).startswith("sha256:")
                      and str(descriptions.get("terminologyPromptDigest", "")).startswith("sha256:"))
                check("collaboration_instruction_frozen",
                      str(plan.get("taskCollaboration", {}).get("instructionDigest", "")).startswith("sha256:"))

                def post(action, body):
                    return page.evaluate("""async ({path,body})=>{const boot=await(await fetch('/api/bootstrap')).json();
                      const response=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json',
                      'X-CSRF-Token':boot.csrf},body:JSON.stringify(body)});
                      return {status:response.status,body:await response.json()};}""",
                      {"path": f"/api/v1/builds/{build_id}/{action}", "body": body})

                job = page.request.get(base + "/api/v1/builds/" + build_id).json()
                started = post("start", {"artifactId": job["artifact"]["artifactId"]})
                check("generated_agent_started", started["status"] == 200)
                instance_id = started["body"]["instance"]["instanceId"]
                try:
                    ticket = post("experience", {"instanceId": instance_id})
                    check("experience_ticket_issued", ticket["status"] == 200)
                    chat = browser.new_page(viewport={"width": 1440, "height": 1000})
                    chat.on("pageerror", lambda error: errors.append(str(error)))
                    chat.goto(base + ticket["body"]["url"])
                    chat.locator('[data-action="start"]').click()
                    chat.locator("#query").wait_for(timeout=30000)

                    def send(query):
                        before = chat.locator(".assistant-message").count()
                        read_before = len(endpoint.reads)
                        chat.locator("#query").fill(query)
                        chat.locator("#chat-form").evaluate("form => form.requestSubmit()")
                        chat.locator(".assistant-message").nth(before).wait_for(timeout=120000)
                        chat.locator(".run-progress").wait_for(state="detached", timeout=120000)
                        answer = chat.locator(".assistant-message").nth(before).inner_text()
                        reads = endpoint.reads[read_before:]
                        turns.append({"query": query, "answer": answer,
                            "businessReads": [route for route, _ in reads]})
                        write_json(output / "turns.json", turns)
                        return answer, reads

                    def fresh_session():
                        chat.locator('[data-action="end"]').click()
                        chat.locator('[data-action="start"]').wait_for(timeout=30000)
                        chat.locator('[data-action="start"]').click()
                        chat.locator("#query").wait_for(timeout=30000)

                    answer, reads = send("查询2026年7月的收入、支出和净收支。")
                    check("browser_single_goal", any("transaction-summary" in route for route, _ in reads)
                          and "7,100" in answer)
                    answer, reads = send("再汇总2026年7月收支，并告诉我主要花在哪。")
                    check("browser_two_goals_reuse_valid_evidence", not any("transaction-summary" in route for route, _ in reads)
                          and any("spending-analysis" in route for route, _ in reads) and "交通" in answer)
                    endpoint.cashflow = fixture_for(harness, "2026-08")
                    answer, reads = send("改成上个月，仍然汇总收支并分析主要支出。")
                    check("browser_changed_month_refreshes_both", len(reads) >= 2
                          and all("period=2026-08" in route for route, _ in reads) and "餐饮" in answer)
                    fresh_session()
                    read_before = len(endpoint.reads)
                    answer, reads = send("查一下我的i豆余额。")
                    check("browser_missing_capability_no_substitute", not reads and len(endpoint.reads) == read_before
                          and any(word in answer for word in ("不支持", "未安装", "无法")))
                    fresh_session()
                    read_before = len(endpoint.reads)
                    answer, reads = send("查八月收支。")
                    check("browser_month_only_clarifies", not reads and len(endpoint.reads) == read_before
                          and any(word in answer for word in ("年份", "哪一年", "具体年份")))
                    check("zero_bank_writes", not endpoint.writes)
                    chat.screenshot(path=str(output / "experience-desktop.png"), full_page=True)
                    chat.set_viewport_size({"width": 390, "height": 844})
                    chat.screenshot(path=str(output / "experience-mobile.png"), full_page=True)
                    check("mobile_no_horizontal_overflow",
                          chat.evaluate("document.documentElement.scrollWidth <= innerWidth"))
                finally:
                    stopped = post("stop", {"instanceId": instance_id})
                    check("owned_agent_stopped", stopped["status"] == 200)
                check("browser_has_no_script_errors", not errors)
                result = {"status": "PASS", "checks": checks, "buildId": build_id,
                    "candidate": str(candidate), "harnessCommit": subprocess.check_output(
                        ["git", "rev-parse", "HEAD"], cwd=harness, text=True).strip(),
                    "builderCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=project, text=True).strip(),
                    "serviceLockSha256": hashlib.sha256((candidate / "service-lock.json").read_bytes()).hexdigest(),
                    "descriptionCatalogDigest": descriptions.get("digest"),
                    "terminologyDigest": descriptions.get("terminologyPromptDigest"),
                    "taskCollaborationDigest": plan.get("taskCollaboration", {}).get("instructionDigest"),
                    "model": os.environ["FH_MODEL_NAME"], "bank": "SYNTHETIC_LOCAL_HTTP",
                    "humanIndependentAcceptance": "NOT_RUN"}
                write_json(output / "result.json", result)
                print(json.dumps({"status": "PASS", "checks": len(checks), "output": str(output)}))
            finally:
                browser.close()
    except Exception as failure:
        write_json(output / "result.json", {"status": "FAIL", "checks": checks,
            "failureType": type(failure).__name__, "failure": str(failure)})
        raise
    finally:
        if child is not None and child.poll() is None:
            child.send_signal(signal.SIGINT)
            try:
                child.wait(timeout=30)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
        endpoint.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--chromium", required=True)
    arguments = parser.parse_args()
    verify(arguments.harness, arguments.output, arguments.maven, arguments.chromium)
