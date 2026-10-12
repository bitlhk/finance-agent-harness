"""Explicit paid-model smoke against a deployed, synthetic read-only demonstration."""
import argparse
import base64
import http.cookiejar
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid
from ag_ui.core import Event
from pydantic import TypeAdapter


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--access-file", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--build-id")
    parser.add_argument("--dialogue-regression", action="store_true")
    args = parser.parse_args()
    access = json.loads(args.access_file.read_text())
    base = access["url"]
    if not base.startswith("https://"): raise ValueError("HTTPS_REQUIRED")
    args.output.mkdir(parents=True, exist_ok=False)
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = ""
    report = {"mode": "EXPLICIT_CONFIGURED_REAL_MODEL_WITH_SYNTHETIC_BANK", "checks": [], "turns": [],
              "realBankIam": "NOT_RUN", "humanOnboarding": "NOT_RUN"}
    def check(name, value):
        if not value: raise AssertionError(name)
        report["checks"].append(name)
    def request(path, body=None):
        headers = {"Content-Type": "application/json", "Origin": base, "X-CSRF-Token": csrf,
                   "Authorization": "Basic " + base64.b64encode((access["username"]+":"+access["password"]).encode()).decode()}
        req = urllib.request.Request(base+path, data=None if body is None else json.dumps(body).encode(), headers=headers)
        try: response = opener.open(req, timeout=150)
        except urllib.error.HTTPError as error: response = error
        with response:
            payload = response.read()
            if "text/event-stream" in response.headers.get("Content-Type", ""):
                events = [json.loads(line[6:]) for line in payload.decode().splitlines() if line.startswith("data: ")]
                for event in events: TypeAdapter(Event).validate_python(event)
                return response.code, events
            return response.code, json.loads(payload)
    try:
        code, boot = request("/api/bootstrap"); check("authenticated_server", code == 200 and boot["deploymentMode"] == "AUTHENTICATED_SERVER")
        csrf = boot["csrf"]
        _, catalog = request("/api/v1/catalog")
        check("not_scripted_model_mode", catalog["executionMode"] == "CONFIGURED_MODEL")
        if args.build_id:
            code, build = request("/api/v1/builds/"+args.build_id)
        else:
            selection = {"extension_type": "finance-workbench.selection", "schema_version": "0.3",
                "runtimeId": "openjiuwen-java-0.1.15", "applicationTemplateId": "banking-readonly-web-v1",
                "presentation": "MOBILE_WEB", "bankConnectionId": "local-simulator",
                "capabilityIds": ["account.balance.query", "wealth.facts.describe"],
                "selectedModelRefs": ["model-ea-002"], "defaultModelRef": "model-ea-002", "modelCatalogRevision": catalog["modelCatalogRevision"]}
            code, build = request("/api/v1/builds", selection)
            check("real_build_queued", code == 202)
        report["buildId"] = build["buildId"]
        print("BUILD " + build["buildId"], flush=True)
        route = "/api/v1/builds/" + build["buildId"]
        deadline = time.monotonic() + 1200
        while build["buildStatus"] in {"QUEUED", "RUNNING"}:
            if time.monotonic() > deadline: raise AssertionError("BUILD_TIMEOUT")
            time.sleep(2); _, build = request(route)
        (args.output / "build.json").write_text(json.dumps(build, indent=2))
        check("server_compiled_new_agent", build["buildStatus"] == "SUCCEEDED")
        report["artifactId"] = build["artifact"]["artifactId"]
        report["sources"] = build["sources"]
        code, started = request(route + "/start", {"artifactId": build["artifact"]["artifactId"]})
        check("instance_started", code == 200)
        iid = started["instance"]["instanceId"]
        code, session = request(route + "/sessions", {"instanceId": iid})
        check("session_created", code == 200)
        sid = session["sessionId"]
        report["instanceId"] = iid
        (args.output / "session.json").write_text(json.dumps(session, indent=2))
        questions = ["请查询我的账户可用余额和币种，并描述我的已知资产范围、风险测评和信息缺口。只使用查询结果，不做产品推荐。",
                     "结合上一轮结果，哪些信息仍不能确认？请区分已查询事实和缺失信息，不用猜测。"]
        if args.dialogue_regression:
            questions = ["查下我的余额", "你好", "看看我的资产和风险测评", "谢谢"]
        for index, query in enumerate(questions):
            began = time.monotonic()
            code, events = request(route + "/ag-ui", dict(threadId=sid, runId=str(uuid.uuid4()), state={}, tools=[], context=[],
                forwardedProps={"instanceId": iid}, messages=[dict(id=str(uuid.uuid4()), role="user", content=query)]))
            check("agui_turn_"+str(index), code == 200 and events[-1]["type"] == "RUN_FINISHED")
            result = next(e["value"]["result"] for e in events if e.get("name") == "finance.evidence")
            check("native_model_"+str(index), result["modelName"] == "deepseek-v4-flash" and result["events"])
            if index == 0: check("actual_financial_tools", bool(result["toolResults"]) and bool(result["sourceCalls"]))
            if args.dialogue_regression:
                actual_tools = {event["tool"] for event in result["events"] if event.get("hook") == "AFTER_TOOL_CALL"}
                expected_tools = [{"account_balance_query"}, set(), {"wealth_facts_describe"}, set()][index]
                check("current_task_tools_"+str(index), actual_tools == expected_tools)
                if not expected_tools:
                    check("no_greeting_or_thanks_business_read_"+str(index), not result["sourceCalls"])
            report["turns"].append({"elapsedSeconds": round(time.monotonic()-began, 2),
                                    "model": result["modelName"], "eventTypes": [e["type"] for e in events],
                                    "query": query,
                                    "tools": sorted({e["tool"] for e in result["events"] if e.get("hook") == "AFTER_TOOL_CALL"}),
                                    "evidenceSha256": result["evidenceSha256"]})
            (args.output / ("turn-"+str(index)+".json")).write_text(json.dumps(events, ensure_ascii=False, indent=2))
            print("TURN " + str(index) + " PASSED", flush=True)
        code, revoked = request(route + "/revoke", {"instanceId": iid, "sessionId": sid})
        check("session_revoked", code == 200 and revoked["clearConversation"])
        code, denied = request(route + "/chat", {"instanceId": iid, "sessionId": sid, "query": "再次查询余额"})
        check("revoked_session_rejected", code in {403,409} and denied["clearConversation"])
        code, stopped = request(route + "/stop", {"instanceId": iid})
        check("stopped_after_live_test", code == 200 and stopped["instance"]["status"] == "STOPPED")
        report["status"] = "PASSED"
    finally:
        (args.output / "report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps({"checks": len(report["checks"]), "buildId": report["buildId"], "turns": report["turns"]}))


if __name__ == "__main__": main()
