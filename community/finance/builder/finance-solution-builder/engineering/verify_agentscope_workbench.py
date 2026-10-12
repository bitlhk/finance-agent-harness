"""Opt-in actual Studio lifecycle: native Runtime, managed model, synthetic bank."""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
from urllib.parse import quote, parse_qs, unquote, urlsplit

from playwright.sync_api import sync_playwright
from browser_agui_capture import CAPTURE_AGUI, read_agui_capture
from verify_agentscope_host_live import SyntheticBank, SUBJECT


RUNTIME = "agentscope-java-2.0.3"
SCENARIOS = [
    ("balance", {"account.balance.query"},
     "请查询我的账户余额，并说明币种和数据来源。"),
    ("cashflow", {"finance.transaction-summary.query", "finance.spending-analysis.query"},
     "请汇总2026年8月的收入和支出，再看主要花在哪些类别。"),
]
FOLLOW_UP = {
    "balance": "请重新查询这个账户的当前余额，并说明本次数据来源。",
    "cashflow": "改成2026年7月，也请汇总收入和支出，再看主要花在哪些类别。",
}
BUSINESS_TIME = {"FH_BUSINESS_DATE": "2026-09-20", "FH_BUSINESS_TIME_ZONE": "Asia/Shanghai",
                 "FH_BUSINESS_DATE_SOURCE": "ISOLATED_ACCEPTANCE"}
TOOLS = {"account.balance.query": "account_balance_query",
         "finance.transaction-summary.query": "finance_transaction_summary",
         "finance.spending-analysis.query": "finance_spending_analysis"}


def require(value, code):
    if not value:
        raise AssertionError(code)


def agui_result(raw, request, started):
    events = []
    for block in raw.replace("\r\n", "\n").split("\n\n"):
        data = [line[5:].lstrip() for line in block.splitlines() if line.startswith("data:")]
        if data:
            events.append(json.loads("\n".join(data)))
    require(events and not any(e["type"] == "RUN_ERROR" for e in events), "AGUI_FAILED_OR_EMPTY")
    for kind in ("RUN_STARTED", "RUN_FINISHED"):
        matches = [e for e in events if e["type"] == kind]
        require(len(matches) == 1 and matches[0].get("runId") == request["runId"]
                and matches[0].get("threadId") == started["sessionId"], "AGUI_RUN_SESSION_MISMATCH")
    require(events[0]["type"] == "RUN_STARTED" and events[-1]["type"] == "RUN_FINISHED", "AGUI_ORDER")
    displays = [e["value"] for e in events if e["type"] == "CUSTOM" and e.get("name") == "finance.display"]
    require(len(displays) == 1, "AGUI_DISPLAY_MISSING")
    result = displays[0]
    require(result.get("status") == "COMPLETED" and result.get("runId") == request["runId"]
            and result.get("sessionId") == started["sessionId"]
            and result["display"]["runId"] == request["runId"], "AGUI_RESULT_IDENTITY")
    messages = [e for e in events if e["type"].startswith("TEXT_MESSAGE_")]
    require(len(messages) >= 3 and messages[0]["type"] == "TEXT_MESSAGE_START"
            and messages[-1]["type"] == "TEXT_MESSAGE_END"
            and all(e["type"] == "TEXT_MESSAGE_CONTENT" for e in messages[1:-1])
            and all(e.get("messageId") == result["display"]["messageId"] for e in messages), "AGUI_MESSAGE_IDENTITY")
    require("".join(e["delta"] for e in messages[1:-1]) == result.get("text"), "AGUI_TEXT_MISMATCH")
    require(bool(result.get("executionObservation", {}).get("requestId")), "AGUI_REQUEST_ID_MISSING")
    return result, events


def score_body(text, dom_text, rendered_text):
    normalize = lambda value: " ".join(value.split())
    require(isinstance(text, str) and bool(text.strip()), "ANSWER_EMPTY")
    require(not re.search(r"(?:file://|/(?:home|tmp|opt)/|[A-Za-z]:\\\\).*|\S+\.response\.json", text), "ANSWER_IS_LOCAL_PATH")
    require(normalize(dom_text) == normalize(rendered_text) and bool(normalize(dom_text)), "ANSWER_DOM_BODY_MISMATCH")
    require(normalize(dom_text) not in {"金融助手", "结果与来源"}, "ANSWER_HEADER_ONLY")


def business_reads(rows):
    return [r for r in rows if r["business"] and not r["readiness"]]


def bank_route(row):
    path = urlsplit(row["path"]).path
    if re.fullmatch(r"/api/v1/accounts/[^/]+", path):
        return "/api/v1/accounts/{accountId}"
    return path


def score_bank_turn(result, expected, period, rows):
    reads, cards = business_reads(rows), result["display"]["cards"]
    routes = {"/api/v1/accounts": "account.balance.query",
              "/api/v1/accounts/{accountId}": "account.balance.query",
              "/api/v1/finance/transaction-summary": "finance.transaction-summary.query",
              "/api/v1/finance/spending-analysis": "finance.spending-analysis.query"}
    actual = [routes.get(bank_route(r)) for r in reads]
    require(set(actual) == expected and len(reads) == len(expected), "BANK_TARGETS_OR_REPEAT")
    require(len(cards) == len(expected), "DISPLAY_TARGET_COUNT")
    require(len({r["requestId"] for r in reads}) == len(reads) and all(r["requestId"] for r in reads), "BANK_REQUEST_IDS")
    for cap, row in zip(actual, reads):
        require(row["method"] == "GET" and row["status"] == 200 and row["response"]["status"] == "SUCCESS", "BANK_NOT_SUCCESSFUL_READ")
        params = parse_qs(urlsplit(row["path"]).query, keep_blank_values=True)
        require(params == ({} if period is None else {"period": [period]}), "BANK_ACTUAL_HTTP_PARAMETERS")
        source, data = row["response"], row["response"]["data"]
        if cap == "account.balance.query":
            if bank_route(row) == "/api/v1/accounts/{accountId}":
                require(data.get("accountId") == unquote(urlsplit(row["path"]).path.rsplit("/", 1)[1]),
                        "BANK_RESPONSE_ACCOUNT_MISMATCH")
                accounts = [data]
            else:
                accounts = data["accounts"]
            found = [c for c in cards if c["type"] == "balance"]
            require(len(found) == 1, "BALANCE_CARD_MISSING")
            card = found[0]
            require(card["source"] == {k: source[k] for k in ("sourceSystem", "asOf")}, "BALANCE_SOURCE")
            require([(r["currency"], Decimal(r["amount"])) for r in card["rows"]]
                    == [(a["currency"], Decimal(a["availableBalance"])) for a in accounts], "BALANCE_AMOUNT")
        else:
            summary = cap == "finance.transaction-summary.query"
            found = [c for c in cards if c["type"] == "cashflow" and c["section"] == ("SUMMARY" if summary else "ANALYSIS")]
            require(len(found) == 1, "CASHFLOW_CARD_MISSING")
            card = found[0]
            require(card["period"] == data["period"] == period and card["currency"] == "CNY", "CASHFLOW_PERIOD_CURRENCY")
            require(card["totals"] == {"income": data["totalIncome"], "expense": data["totalExpense"], "netCashflow": data["netCashflow"]}, "CASHFLOW_TOTALS")
            require(card["source"]["system"] == source["sourceSystem"] and card["source"]["asOf"] == source["asOf"]
                    and card["source"]["recordVersion"] == data["summaryVersion" if summary else "analysisVersion"], "CASHFLOW_SOURCE")
            require(bool(card["gaps"]) and "PERIOD_COVERAGE_NOT_VERIFIED" in card["gaps"]
                    and "CREDIT_CARD_BILLS_NOT_ADDED_TO_CASHFLOW" in card["gaps"], "CASHFLOW_GAPS")
            if not summary:
                require([(c["name"], c["classificationVersion"], c["totals"]["expense"], c["transactionCount"]) for c in card["categories"]]
                        == [(c["category"], c["classificationVersion"], c["expense"], c["transactionCount"]) for c in data["categories"]], "CASHFLOW_CATEGORIES")
                require(card["trend"] == [{"period": p["period"], "totals": {k: p[k] for k in ("income", "expense", "netCashflow")}} for p in data["trend"]], "CASHFLOW_TREND")
    # HTTP order is evidence, not a newly invented mandatory SOP protocol.
    return {"httpCapabilityOrder": actual, "businessReads": len(reads), "extraReads": len(reads) - len(expected),
            "period": period, "answerProfessionalReview": "PENDING"}


def score_binding(detail, result, expected, build_id, started, lock_raw, service_raw, rows):
    lock = json.loads(lock_raw)
    require(detail["requestId"] == result["executionObservation"]["requestId"] and detail["buildId"] == build_id
            and detail["instanceId"] == started["instanceId"] and bool(detail["processBootId"])
            and detail["sessionRef"] == hashlib.sha256(started["sessionId"].encode()).hexdigest()
            and detail["subjectRef"] == hashlib.sha256(SUBJECT.encode()).hexdigest()
            and detail["modelContext"]["clientRunRef"] == hashlib.sha256(result["runId"].encode()).hexdigest(), "HISTORY_CURRENT_IDENTITY")
    require(detail["complete"] is True and detail["status"] == "COMPLETED", "HISTORY_INCOMPLETE")
    require({r["capabilityId"] for r in detail["businessResults"]} == expected
            and all(r["businessStatus"] == "COMPLETED" for r in detail["businessResults"]), "HISTORY_TARGETS")
    for kind, raw in (("runtime", lock_raw), ("service", service_raw)):
        require(detail["versions"][kind]["verified"] is True
                and detail["versions"][kind]["digest"] == hashlib.sha256(raw).hexdigest(), "HISTORY_CURRENT_LOCK")
    require(lock["solutionId"] == build_id and lock["runtime"]["family"] == "agentscope"
            and lock["runtime"]["language"] == "java"
            and lock["runtime"]["templateRef"] == "maven://io.agentscope:agentscope-core:2.0.3", "LOCK_RUNTIME")
    installed = [c for p in lock["installations"] for c in p["capabilities"]]
    require(len(installed) == len(expected) and {c["capabilityId"] for c in installed} == expected
            and all(c["operation"] == "QUERY" and c["toolName"] == TOOLS[c["capabilityId"]]
                    and c["providerId"] == "non-ai-banking-reference" for c in installed)
            and all(p["integrationId"] == "agentscope-java" for p in lock["installations"]), "LOCK_INSTALLED_TOOLS")
    attempts = [s for s in detail["spans"] if s["component"] == "provider-http"
                and not s["links"].get("route", "").endswith("/readiness")]
    require(len(attempts) == len(business_reads(rows)), "PROVIDER_HTTP_COUNT")
    for row in business_reads(rows):
        require(sum(s["dispatch"] == "SENT" and s["links"].get("harnessRequestId") == row["requestId"]
                    and s["links"].get("route") == bank_route(row) for s in attempts) == 1, "PROVIDER_HTTP_REQUEST_LINK")


def transport_counts(root):
    """Count Host append-only transport observations, not remote server receipts."""
    files, sent = {}, set()
    for path in sorted(root.glob("*.jsonl")):
        raw = path.read_bytes()
        require(not raw or raw.endswith(b"\n"), "HISTORY_COUNTER_PARTIAL_LINE")
        events = [json.loads(line) for line in raw.splitlines()]
        files[path.name] = {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
        for event in events:
            if event.get("component") == "model-http" and event.get("dispatch") == "SENT":
                sent.add((event["requestId"], event["processBootId"], event["spanId"]))
    return {"boundary": "HOST_TRANSPORT_OBSERVED", "remoteServerCount": "NOT_OBSERVED",
            "sentAttempts": len(sent), "files": files}


def score_pure_view(before, after):
    require(before["model"]["sentAttempts"] > 0, "MODEL_TRANSPORT_EVIDENCE_MISSING")
    require(before["bankBusiness"] == after["bankBusiness"], "HISTORY_TRIGGERED_BANK")
    require(before["model"] == after["model"], "HISTORY_TRIGGERED_MODEL_OR_ARCHIVE_CHANGED")


def verify(args):
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    output.chmod(0o700)
    project = Path(__file__).resolve().parents[1]
    harness = args.harness.resolve()
    checks, runs, errors = [], [], []
    bank, child, page = SyntheticBank(harness), None, None

    def save(name, value):
        path = output / name
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        path.chmod(0o600)

    def check(name, value):
        checks.append({"name": name, "passed": bool(value)})
        save("checks.json", checks)
        if not value:
            raise AssertionError(name)

    sources = {}
    for label, root in (("builder", project), ("harness", harness)):
        sources[label] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
        check(label + "-fixed-clean-source", not subprocess.check_output(["git", "status", "--porcelain"], cwd=root).strip())
    required = ("FH_MODEL_URL", "FH_MODEL_KEY", "FH_MODEL_NAME", "FH_AGENTSCOPE_ADMISSION_CANDIDATE",
                "FH_AGENTSCOPE_ADMISSION_LOCK_SHA256", "FH_AGENTSCOPE_QUALIFICATION_FILE",
                "FH_AGENTSCOPE_QUALIFICATION_SHA256", "FH_OBSERVATION_QUALIFICATION_FILE",
                "FH_OBSERVATION_QUALIFICATION_SHA256", "FH_OBSERVATION_REQUIREMENTS")
    check("operator-provided-live-and-qualified-config", all(os.environ.get(key) for key in required))
    save("model-catalog.json", {"schemaVersion": "finance-model-catalog.v1", "source": {"kind": "ACCEPTANCE"},
        "models": [{"modelRef": "model-native-live", "displayName": os.environ["FH_MODEL_NAME"], "provider": "OpenAI",
        "modelName": os.environ["FH_MODEL_NAME"], "eaCatalogId": "acceptance", "eaConfigured": True}]})
    save("model-registry.json", {"schemaVersion": "finance-model-bindings.v1", "bindings": [{
        "modelRef": "model-native-live", "revision": "native-query-v01", "enabled": True,
        "protocol": "OPENAI_CHAT_COMPLETIONS", "provider": "OpenAI", "modelName": os.environ["FH_MODEL_NAME"],
        "urlEnvironment": "FH_CONN_NATIVE_URL", "keyEnvironment": "FH_CONN_NATIVE_KEY"}]})
    save("policy.json", {"schemaVersion": "local-simulator-access.v0.1", "bankUrl": bank.url,
        "subjects": {SUBJECT: {"enabled": True, "expiresAt": (datetime.now(timezone.utc) + timedelta(hours=2)).isoformat(),
        "scopes": ["account:read", "cashflow:read"]}}})
    env = dict(os.environ, PYTHONPATH=str(project / "src") + os.pathsep + str(project.parent / "base/src"),
        FH_CANDIDATE_RUNTIME_IDS=RUNTIME, FH_MODEL_CATALOG=str(output / "model-catalog.json"),
        FH_MODEL_REGISTRY=str(output / "model-registry.json"), FH_CONN_NATIVE_URL=os.environ["FH_MODEL_URL"],
        FH_CONN_NATIVE_KEY=os.environ["FH_MODEL_KEY"], FH_MODEL_MODE="LIVE", FH_BANK_URL=bank.url,
        FH_BANK_LOGIN="synthetic", FH_BANK_SECRET="synthetic", FH_ACCESS_POLICY=str(output / "policy.json"),
        FH_CASHFLOW_ACCESS_POLICY=str(output / "policy.json"), **BUSINESS_TIME,
        FH_HISTORY_ENABLED="true", FH_HISTORY_OWNER="local-operator", FH_SNAPSHOTS_ENABLED="true",
        FH_SNAPSHOT_CAPTURE_OWNERS="local-operator")
    env.pop("FH_BUSINESS_TIME_SOURCE", None)
    renderer = subprocess.check_output(["node", "-e", "process.stdout.write(require('esbuild').buildSync({"
        "entryPoints:['browser/markdown.js'],bundle:true,write:false,format:'iife',"
        "globalName:'AcceptanceMarkdown'}).outputFiles[0].text)"], cwd=project, text=True)

    def stop_server():
        nonlocal child
        if child and child.poll() is None:
            child.send_signal(signal.SIGINT)
            try:
                child.wait(timeout=30)
            except subprocess.TimeoutExpired:
                child.terminate()
                child.wait(timeout=30)

    def start_server(index):
        nonlocal child
        log = output / ("workbench-" + str(index) + ".log")
        with log.open("wb") as stream:
            child = subprocess.Popen([sys.executable, "-m", "finance_solution_builder.workbench", "--harness-repo",
                str(harness), "--port", "0", "--data-dir", str(output / "jobs")], cwd=project, env=env,
                stdout=stream, stderr=stream)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            match = re.search(r"http://127\.0\.0\.1:\d+", log.read_text())
            if match:
                return match.group()
            if child.poll() is not None:
                raise RuntimeError("ISOLATED_WORKBENCH_START_FAILED")
            time.sleep(.1)
        raise TimeoutError("ISOLATED_WORKBENCH_START_TIMEOUT")

    try:
        base = start_server(1)
        with sync_playwright() as pw:
            browser = pw.chromium.launch(executable_path=args.chromium, headless=True)
            context = browser.new_context(viewport={"width": 1366, "height": 900})
            context.add_init_script(CAPTURE_AGUI)
            try:
                # Keep the product CSP intact; render expected text in a separate test document.
                rendering_page = context.new_page()
                rendering_page.add_script_tag(content=renderer)
                page = context.new_page()
                page.on("pageerror", lambda error: errors.append(str(error)))
                page.goto(base)
                page.request.get(base + "/api/bootstrap")
                cookie = next(c for c in context.cookies() if c["name"] == "fh_local")
                owner = cookie["value"].split(".", 1)[0]
                # The real bootstrap issues this identity. Restart only this empty test service
                # so its immutable process environment can permit this one synthetic owner's bodies.
                stop_server()
                env["FH_SNAPSHOT_CAPTURE_OWNERS"] = "local-operator," + hashlib.sha256(owner.encode()).hexdigest()
                env["FH_SNAPSHOT_VIEW_OWNERS"] = hashlib.sha256(owner.encode()).hexdigest()
                base = start_server(2)
                with page.expect_response(lambda r: r.url.endswith("/api/v1/catalog") and r.status == 200):
                    page.goto(base)
                catalog = page.request.get(base + "/api/v1/catalog").json()["unifiedDirectory"]
                selected_runtime = next(r for r in catalog["runtimes"] if r["id"] == RUNTIME)
                check("candidate-is-not-formal-release", selected_runtime.get("candidateEnabled") is True
                      and selected_runtime.get("declaredStatus") == "CANDIDATE")
                for name, expected, query in SCENARIOS:
                    presentation = "DESKTOP_WEB" if name == "balance" else "MOBILE_WEB"
                    page.set_viewport_size({"width": 1366, "height": 900})
                    page.goto(base)
                    page.locator('[data-scenario="mobile-banking"]').click()
                    page.locator('[data-scenario="customer-custom"]').click()
                    page.locator('[data-nav="assets"]').click()
                    component = next(c for c in catalog["components"] if set(c["capabilityIds"]) == expected)
                    page.locator('[data-method-action="business-detail"][data-component="' + component["id"] + '"]').click()
                    page.get_by_role("tab", name="检查与生成").click()
                    page.get_by_role("tabpanel", name="检查与生成").locator('[data-method-action="use-business"]').click()
                    page.locator('[data-runtime="' + RUNTIME + '"]').check()
                    page.locator('[data-template="' + presentation + '"]').click()
                    page.locator('[data-runtime="' + RUNTIME + '"]').evaluate(
                        "el => el.closest('label').scrollIntoView({block:'nearest'})")
                    page.screenshot(path=str(output / (name + "-selection.png")), full_page=True)
                    with page.expect_response(lambda r: r.url.endswith("/api/v1/builds") and r.request.method == "POST", timeout=120000) as pending:
                        page.locator('[data-action="build"]').click()
                    response = pending.value
                    save(name + "-accepted.json", {"status": response.status, "body": response.json(),
                         "selection": response.request.post_data_json})
                    check(name + "-build-accepted", response.status == 202)
                    build_id = response.json()["buildId"]
                    selection = response.request.post_data_json
                    check(name + "-explicit-runtime-binding", selection["runtimeId"] == RUNTIME and bool(selection.get("hostId"))
                          and bool(selection.get("profileRef")))
                    check(name + "-explicit-presentation", selection["presentation"] == presentation)

                    def job():
                        return page.request.get(base + "/api/v1/builds/" + build_id).json()

                    deadline = time.monotonic() + 1800
                    value = job()
                    while value["buildStatus"] in {"QUEUED", "RUNNING"} and time.monotonic() < deadline:
                        page.wait_for_timeout(1000)
                        value = job()
                    save(name + "-build.json", value)
                    check(name + "-actual-build", value["buildStatus"] == "SUCCEEDED")
                    check(name + "-real-runtime-presentation", value["runtimePresentation"]["runtimeId"] == RUNTIME)
                    page.screenshot(path=str(output / (name + "-built.png")), full_page=True)
                    with page.expect_response(lambda r: r.url.endswith("/api/v1/builds/" + build_id + "/start")
                                              and r.request.method == "POST", timeout=120000) as launched:
                        page.locator('[data-action="launch"]').click()
                    launch_response = launched.value
                    launch_body = launch_response.json()
                    save(name + "-launch.json", {"status": launch_response.status,
                        "error": launch_body.get("error"), "instance": launch_body.get("instance")})
                    check(name + "-launch-post-succeeded", launch_response.status == 200
                          and launch_body.get("instance", {}).get("status") == "READY")
                    page.locator('[data-action="start"]').wait_for(timeout=90000)
                    page.set_viewport_size({"width": 1366 if name == "balance" else 390, "height": 900})
                    with page.expect_response(lambda r: r.url.endswith("/api/start") and r.request.method == "POST", timeout=90000) as start_response:
                        page.locator('[data-action="start"]').click()
                    check(name + "-session-started", start_response.value.status == 200)
                    started = start_response.value.json()
                    page.locator("#query").wait_for(timeout=90000)
                    check(name + "-actual-terminal", page.locator('body.' + presentation.lower()).count() == 1)
                    candidate = output / "jobs/workbench" / build_id / "candidate"
                    instance = json.loads((candidate / "instance.json").read_bytes())
                    check(name + "-actual-process-instance", instance["instanceId"] == started["instanceId"]
                          and instance["buildId"] == build_id)
                    # Read only these non-secret process fields; never serialize the full environment.
                    inherited = dict(item.split(b"=", 1) for item in Path(f"/proc/{int(instance['pid'])}/environ").read_bytes().split(b"\0") if b"=" in item)
                    actual_time = {key: inherited.get(key.encode(), b"").decode() for key in BUSINESS_TIME}
                    del inherited
                    save(name + "-trusted-business-time.json", {"expected": BUSINESS_TIME, "actualProcess": actual_time,
                        "modelPromptSource": "host-managed-business-clock", "boundary": "CURRENT_HOST_PROCESS_ENVIRONMENT"})
                    check(name + "-actual-date-source", actual_time == BUSINESS_TIME)
                    turns = []
                    for turn_index, question in enumerate((query, FOLLOW_UP[name])):
                        if name == "cashflow":
                            page.set_viewport_size({"width": 390 if turn_index == 0 else 320, "height": 900})
                        row_start = len(bank.rows)
                        page.locator("#query").fill(question)
                        with page.expect_response(lambda r: r.url.endswith("/api/ag-ui") and r.request.method == "POST", timeout=240000) as agui:
                            page.locator("#chat-form").evaluate("form => form.requestSubmit()")
                        response = agui.value
                        check(name + f"-turn-{turn_index}-agui-http", response.status == 200)
                        request = response.request.post_data_json
                        check(name + f"-turn-{turn_index}-actual-query", request["query"] == question)
                        raw, capture = read_agui_capture(page, request["runId"], response.status, response.url)
                        result, events = agui_result(raw, request, started)
                        page.locator(".assistant-message").nth(turn_index).wait_for(timeout=240000)
                        page.locator(".run-progress").wait_for(state="detached", timeout=240000)
                        dom = page.locator(".assistant-message").nth(turn_index).locator(".markdown").text_content()
                        rendered = rendering_page.evaluate("text => {const d=document.createElement('div');d.innerHTML=AcceptanceMarkdown.renderMarkdown(text);return d.textContent}", result["text"])
                        rows = list(bank.rows[row_start:])
                        period = ("2026-08", "2026-07")[turn_index] if name == "cashflow" else None
                        turn = {"request": request, "result": result, "events": events, "domBodyText": dom,
                                "renderedActualText": rendered, "bankRows": rows, "period": period,
                                "capture": capture}
                        save(name + f"-turn-{turn_index}.json", turn)
                        score_body(result["text"], dom, rendered)
                        turn["facts"] = score_bank_turn(result, expected, period, rows)
                        save(name + f"-turn-{turn_index}.json", turn)
                        check(name + f"-turn-{turn_index}-actual-text-month-source", True)
                        check(name + f"-turn-{turn_index}-conversation-no-overflow",
                              page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"))
                        turns.append(turn)
                        page.screenshot(path=str(output / (name + f"-conversation-{turn_index}.png")), full_page=True)
                    check(name + "-two-distinct-turn-identities", len({t["result"]["executionObservation"]["requestId"] for t in turns}) == 2
                          and len({t["result"]["runId"] for t in turns}) == 2
                          and len({t["result"]["display"]["messageId"] for t in turns}) == 2)
                    value = job()
                    save(name + "-running.json", value)
                    check(name + "-still-current-instance", value["instance"]["instanceId"] == started["instanceId"])
                    lock_raw = (candidate / "runtime-evidence" / started["sessionId"] / "runtime-lock.json").read_bytes()
                    service_raw = (candidate / "service-lock.json").read_bytes()
                    archive = output / "jobs/execution-history" / hashlib.sha256(owner.encode()).hexdigest()
                    def view_counts():
                        return {"bankBusiness": len(business_reads(bank.rows)), "model": transport_counts(archive)}
                    # This baseline precedes even the first history list GET, not just detail/body reads.
                    view_before = view_counts()
                    save(name + "-view-before.json", view_before)
                    history = base + "/api/v1/execution-history?buildId=" + build_id
                    deadline = time.monotonic() + 20
                    records = []
                    while time.monotonic() < deadline:
                        records = [r for r in page.request.get(history).json()["records"]
                                   if str(r.get("requestType", "")).startswith("CONVERSATION_TURN")]
                        if len(records) == 2 and all(r["status"] != "UNFINISHED" for r in records):
                            break
                        page.wait_for_timeout(100)
                    check(name + "-two-conversation-histories", {r["requestId"] for r in records}
                          == {t["result"]["executionObservation"]["requestId"] for t in turns} and len(records) == 2)
                    snapshots = []
                    details = []
                    for turn_index, turn in enumerate(turns):
                        detail_url = base + "/api/v1/execution-history/" + turn["result"]["executionObservation"]["requestId"]
                        detail = page.request.get(detail_url).json()
                        save(name + f"-history-{turn_index}.json", detail)
                        score_binding(detail, turn["result"], expected, build_id, started, lock_raw, service_raw, turn["bankRows"])
                        details.append(detail)
                        refs = detail.get("snapshots", [])
                        calls = {ref["callId"] for ref in refs}
                        check(name + f"-turn-{turn_index}-paired-bodies", bool(calls) and all(
                            {ref["part"] for ref in refs if ref["callId"] == call} == {"request", "response"} for call in calls))
                        for ref in refs:
                            url = detail_url + "/snapshots/" + quote(ref["callId"], safe="") + "/" + quote(ref["part"], safe="")
                            body = page.request.get(url)
                            value_body = body.json()
                            snapshots.append({"url": url, "callId": ref["callId"], "part": ref["part"], "httpStatus": body.status, "content": value_body})
                            check(name + "-body-readable-" + str(len(snapshots)), body.status == 200
                                  and value_body.get("state") in {"CAPTURED", "FILTERED"} and bool(value_body.get("body"))
                                  and value_body.get("requestId") == detail["requestId"] and value_body.get("callId") == ref["callId"])
                            if ref["part"] == "request":
                                model_request = value_body["body"]
                                if isinstance(model_request, str): model_request = json.loads(model_request)
                                check(name + "-snapshot-installed-tools-" + str(len(snapshots)),
                                      {t["function"]["name"] for t in model_request["tools"]} == {TOOLS[c] for c in expected})
                                prompt = json.dumps(model_request["messages"], ensure_ascii=False)
                                check(name + "-actual-model-trusted-date-" + str(len(snapshots)),
                                      all(token in prompt for token in ("2026-09-20", "Asia/Shanghai", "host-managed-business-clock")))
                    check(name + "-same-process-both-turns", details[0]["processBootId"] == details[1]["processBootId"])
                    save(name + "-snapshots.json", snapshots)
                    other = browser.new_context()
                    try:
                        foreign = other.new_page()
                        foreign.goto(base)
                        foreign.request.get(base + "/api/bootstrap")
                        check(name + "-other-owner-denied", foreign.request.get(detail_url).status == 403)
                        check(name + "-other-owner-body-denied", foreign.request.get(snapshots[0]["url"]).status == 403)
                    finally:
                        other.close()
                    page.goto(base)
                    navigation = page.locator('[data-sidebar-open]')
                    navigation.wait_for(state="attached")
                    if navigation.is_visible():
                        navigation.click()
                    page.locator('[data-nav="history"]').click()
                    page.locator('[data-history-id="' + detail["requestId"] + '"]').click()
                    page.locator('[data-history-tab="model"]').wait_for()
                    for width in (1366, 390, 320):
                        page.set_viewport_size({"width": width, "height": 900})
                        check(name + "-history-no-overflow-" + str(width), page.evaluate("document.documentElement.scrollWidth <= innerWidth + 1"))
                        page.screenshot(path=str(output / (name + "-history-" + str(width) + ".png")), full_page=True)
                    page.set_viewport_size({"width": 1366, "height": 900})
                    page.locator('[data-history-tab="model"]').click()
                    with page.expect_response(lambda r: "/snapshots/" in r.url and r.request.method == "GET", timeout=30000) as ui_body:
                        page.locator('[data-snapshot-read]').click()
                    page.locator('.snapshot-content').wait_for(timeout=30000)
                    visible_body = ui_body.value.json()
                    check(name + "-ui-body-is-actual-snapshot", ui_body.value.status == 200
                          and visible_body.get("state") in {"CAPTURED", "FILTERED"}
                          and json.loads(page.locator('[data-snapshot-body]').text_content())
                          == (json.loads(visible_body["body"]) if isinstance(visible_body["body"], str) else visible_body["body"]))
                    page.screenshot(path=str(output / (name + "-model-interaction.png")), full_page=True)
                    view_after = view_counts()
                    save(name + "-view-after.json", view_after)
                    score_pure_view(view_before, view_after)
                    check(name + "-history-zero-bank-and-host-model-transport", True)
                    stopped = page.evaluate("""async ({id, instance}) => {
                        const boot=await(await fetch('/api/bootstrap')).json();
                        const r=await fetch('/api/v1/builds/'+id+'/stop',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':boot.csrf},body:JSON.stringify({instanceId:instance})});
                        return {status:r.status,body:await r.json()};
                    }""", {"id": build_id, "instance": value["instance"]["instanceId"]})
                    check(name + "-owned-instance-stopped", stopped["status"] == 200)
                    runs.append({"buildId": build_id, "component": component["id"], "runtime": RUNTIME,
                                 "requestIds": [t["result"]["executionObservation"]["requestId"] for t in turns],
                                 "answers": [t["result"]["text"] for t in turns], "turnFacts": [t["facts"] for t in turns],
                                 "businessReads": sum(t["facts"]["businessReads"] for t in turns)})
                check("zero-bank-business-writes", not any(r["business"] and r["method"] != "GET" for r in bank.rows))
                check("no-browser-errors", not errors)
                save("report.json", {"status": "PASS", "checks": checks, "runs": runs, "sources": sources,
                    "model": "CONFIGURED_REAL_MODEL", "bank": "LOOPBACK_SYNTHETIC_HTTP", "humanAcceptance": "NOT_RUN",
                    "professionalQuality": "REQUIRES_HUMAN_REVIEW", "deployment": "NOT_PERFORMED",
                    "modelCountBoundary": "HOST_TRANSPORT_OBSERVED", "remoteModelServerCount": "NOT_OBSERVED",
                    "viewWindow": "BEFORE_FIRST_HISTORY_LIST_THROUGH_DETAIL_SNAPSHOT_FOREIGN_OWNER_AND_UI_READ",
                    "sopOrder": "REPORTED_HTTP_ORDER_NOT_ENFORCED_AS_PROTOCOL"})
            except Exception:
                if page and not page.is_closed():
                    page.screenshot(path=str(output / "failure.png"), full_page=True)
                    save("failure.json", {"checks": checks, "browserErrors": errors, "page": page.locator("body").inner_text()})
                raise
            finally:
                browser.close()
    finally:
        stop_server()
        save("bank-http.json", bank.rows)
        bank.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--chromium", required=True)
    verify(parser.parse_args())
