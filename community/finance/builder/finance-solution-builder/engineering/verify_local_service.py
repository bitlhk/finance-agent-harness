"""Explicit test-model native-host checks. Never used as the workbench's live model fallback."""
from __future__ import annotations

import argparse
from decimal import Decimal
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import hashlib
import os
import re
from pathlib import Path
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid


class Endpoints:
    def __init__(self):
        self.models = []; self.reads = []; self.sessions = {}; self.writes = []
        self.customer = "customer-test-a"; self.missing_risk = False; self.zero = False
        self.pause_final = None
        self.pause_source = None
        self.model_responder = None
        self.final_text = "CONTROLLED_TEST_COMPLETION"
        self.cashflow = None
        self.metadata_reads = []
        parent = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def reply(self, code, body):
                data = json.dumps(body).encode(); self.send_response(code); self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
                if self.path == "/api/v1/sessions":
                    sid = str(uuid.uuid4()); parent.sessions[sid] = parent.customer
                    return self.reply(200, {"status":"COMPLETED", "data":{"sessionId":sid,"customerRef":parent.customer,"expiresAt":expiry()}})
                if self.path == "/v1/chat/completions":
                    parent.models.append(body)
                    if parent.model_responder is not None:
                        code, result = parent.model_responder(body)
                        return self.reply(code, result)
                    if body["messages"][-1]["role"] == "tool":
                        if parent.pause_final:
                            ready, release=parent.pause_final; ready.set(); release.wait(timeout=15)
                        message = {"role":"assistant", "content":parent.final_text}
                        reason = "stop"
                    else:
                        tools = [t["function"]["name"] for t in body["tools"]]
                        name = tools[0]
                        last_query = next((m.get("content", "") for m in reversed(body["messages"]) if m["role"] == "user"), "")
                        if "disabled-tool-probe" in str(last_query): name = "account_balance_query"
                        if "balance-probe" in str(last_query): name = "account_balance_query"
                        if "请查询我的账户余额" in str(last_query): name = "account_balance_query"
                        if "wealth-probe" in str(last_query) or "请查询我的资产概览" in str(last_query): name = "wealth_facts_describe"
                        arguments = {"customerRef":"other-customer"} if "selector-probe" in str(last_query) else {}
                        if parent.cashflow is not None:
                            if "summary-probe" in str(last_query) or "收支汇总" in str(last_query): name = "finance_transaction_summary"
                            if "analysis-probe" in str(last_query) or "收支分类" in str(last_query): name = "finance_spending_analysis"
                            if name in {"finance_transaction_summary", "finance_spending_analysis"}:
                                period = re.search(r"\b[0-9]{4}-[0-9]{2}\b", str(last_query))
                                if period: arguments["period"] = period.group()
                        message = {"role":"assistant","content":None,"tool_calls":[{"id":"call-"+uuid.uuid4().hex[:8],"type":"function","function":{"name":name,"arguments":json.dumps(arguments)}}]}
                        reason = "tool_calls"
                    return self.reply(200, {"id":uuid.uuid4().hex,"object":"chat.completion","created":int(time.time()),"model":"explicit-boundary-test",
                            "choices":[{"index":0,"message":message,"finish_reason":reason}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}})
                parent.writes.append(self.path); self.reply(405, {"error":"NOT_READ_ONLY"})
            def do_DELETE(self):
                if self.path == "/api/v1/sessions/current":
                    parent.sessions.pop(self.headers.get("X-Session-Id"), None)
                    return self.reply(200, {"status":"COMPLETED", "data":None})
                parent.writes.append(self.path); self.reply(405, {})
            def do_GET(self):
                if self.path == "/health": return self.reply(200,{"status":"UP","service":"non-ai-banking","providerMode":"simulator"})
                sid = self.headers.get("X-Session-Id")
                customer = parent.sessions.get(sid)
                if not customer: return self.reply(401, {"status":"FAILED","error":{"failureClass":"NOT_AUTHENTICATED","sourceCode":"SESSION_EXPIRED"}})
                if self.path == "/api/v1/sessions/current":
                    return self.reply(200, {"status":"COMPLETED","data":{"sessionId":sid,"customerRef":customer,"expiresAt":expiry()}})
                route = self.path.split("?")[0]
                cashflow_names = {"/api/v1/finance/transaction-summary": "summary", "/api/v1/finance/spending-analysis": "analysis"}
                if parent.cashflow is not None and route.endswith("/readiness") and route.removesuffix("/readiness") in cashflow_names:
                    parent.metadata_reads.append(self.path)
                    return self.reply(200, parent.cashflow[cashflow_names[route.removesuffix("/readiness")] + "-policy"])
                parent.reads.append((self.path, customer))
                if parent.cashflow is not None and route in cashflow_names:
                    data = parent.cashflow[cashflow_names[route]]
                    if self.path != route + "?period=" + data["period"]: return self.reply(400, {})
                elif self.path.startswith("/api/v1/accounts"):
                    data = {"accounts":[{"accountId":"test-"+customer,"availableBalance":0 if parent.zero else "12345.67","currency":"CNY"}]}
                elif self.path == "/api/v1/wealth/aggregate":
                    data = {"overview":{"totalAssets":0 if parent.zero else (2000000.34 if customer.endswith("-b") else 1000000.12),"currency":"CNY"},"holdings":[],"dataQuality":{"status":"READY"}}
                elif self.path == "/api/v1/finance/risk-assessment":
                    if parent.missing_risk: return self.reply(503, {"status":"FAILED","error":{"failureClass":"TEMPORARY_FAILURE","sourceCode":"RISK_UNAVAILABLE"}})
                    data = {"riskLevel":"C3","assessmentVersion":"test-v1","status":"VALID","assessedOn":"2026-01-01","expiresOn":"2099-01-01"}
                else: return self.reply(404, {})
                if parent.pause_source:
                    ready, release = parent.pause_source
                    ready.set()
                    if not release.wait(timeout=15): return self.reply(504, {})
                self.reply(200, {"requestId":"test-request","traceId":"test-trace","status":"COMPLETED","data":data,"sourceSystem":"controlled-bank","asOf":"2026-09-06T00:00:00Z"})
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True); self.thread.start()
        self.base = "http://127.0.0.1:" + str(self.server.server_port)
    def close(self): self.server.shutdown(); self.server.server_close(); self.thread.join()


class ReadOnlyRecorder:
    """Forward reads to the actual simulator; record every attempt without bodies or credentials."""
    def __init__(self, upstream):
        self.calls=[]; parent=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*_): pass
            def forward(self):
                identity=self.path.startswith("/api/v1/sessions") or self.path=="/health"
                permitted=self.command=="GET" or (identity and self.command in {"POST","DELETE"})
                parent.calls.append({"method":self.command,"route":self.path.split("?")[0],"identity":identity,"permitted":permitted})
                if not permitted:
                    self.send_response(405); self.end_headers(); self.wfile.write(b'{}'); return
                data=self.rfile.read(int(self.headers.get("Content-Length","0"))) or None
                req=urllib.request.Request(upstream+self.path,data=data,method=self.command,
                    headers={k:v for k,v in self.headers.items() if k.lower() not in {"host","content-length","connection"}})
                try: response=urllib.request.urlopen(req,timeout=15)
                except urllib.error.HTTPError as error: response=error
                with response:
                    payload=response.read(); self.send_response(response.code)
                    self.send_header("Content-Type","application/json"); self.send_header("Content-Length",str(len(payload)))
                    self.end_headers(); self.wfile.write(payload)
            do_GET=do_POST=do_DELETE=do_PUT=do_PATCH=forward
        self.server=ThreadingHTTPServer(("127.0.0.1",0),Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True); self.thread.start()
        self.base="http://127.0.0.1:"+str(self.server.server_port)
    def close(self): self.server.shutdown(); self.server.server_close(); self.thread.join()


def expiry(): return (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat()


def request(base, token, path, body=None):
    req = urllib.request.Request(base+path, data=None if body is None else json.dumps(body).encode(),
            headers={"Content-Type":"application/json", "Authorization":"Bearer "+token})
    try:
        with urllib.request.urlopen(req, timeout=60) as response: return response.status, json.load(response)
    except urllib.error.HTTPError as error: return error.code, json.load(error)


def business_calls(fixture, recorder=None):
    if recorder is not None:
        return [dict(call) for call in recorder.calls if not call["identity"]]
    return ([{"method": "GET", "route": route.split("?")[0], "subject": subject} for route, subject in fixture.reads]
            + [{"method": "REJECTED_WRITE_ATTEMPT", "route": route} for route in fixture.writes])


def prior_tool_messages(first):
    messages = []
    for call in first:
        for message in call["messages"]:
            if message["role"] == "tool" and message.get("content") and message not in messages:
                messages.append(message)
    return messages


def has_multiturn_context(first, second, marker):
    if not first or not second: return False
    initial = second[0]["messages"]
    previous = prior_tool_messages(first)
    return (any(m["role"] == "user" and marker in str(m.get("content", "")) for m in initial)
            and bool(previous) and all(m in initial for m in previous))


def isolated_context(first, other, marker):
    payload = json.dumps(other)
    return (bool(other) and marker not in payload
            and all(message not in call["messages"] for message in prior_tool_messages(first) for call in other))


def verify(candidate: Path, bank_url=None):
    candidate = candidate.resolve(); fixture = Endpoints()
    recorder=ReadOnlyRecorder(bank_url) if bank_url else None
    bank = recorder.base if recorder else fixture.base
    subject = "customer-demo-001" if bank_url else fixture.customer
    policy = candidate / "test-access-policy.json"
    def policy_value(enabled=True, scopes=None):
        return {"schemaVersion":"local-simulator-access.v0.1","bankUrl":bank,"subjects":{subject:{"enabled":enabled,"expiresAt":expiry(),
                "scopes":scopes if scopes is not None else ["wealth:facts:read","wealth:customer:read","account:read"]}}}
    def save_policy(value): policy.write_text(json.dumps(value)); policy.chmod(0o600)
    save_policy(policy_value())
    token = secrets.token_urlsafe(40)
    env = dict(os.environ, FH_BANK_URL=bank,FH_BANK_LOGIN="demo",FH_BANK_SECRET="demo123",FH_ACCESS_POLICY=str(policy),
               FH_HOST_TOKEN=token,FH_MODEL_URL=fixture.base+"/v1",FH_MODEL_KEY="explicit-test-not-a-key",
               FH_MODEL_NAME="explicit-boundary-test",FH_MODEL_MODE="CONTROLLED_TEST",FH_PORT="0")
    info = candidate / "instance.json"; info.unlink(missing_ok=True)
    jar = next((candidate / "target").glob("*.jar"))
    log = (candidate / "test-host.log").open("wb")
    child = subprocess.Popen(["java","-Xmx512m","-cp",str(jar)+os.pathsep+str(candidate/"target/dependency/*"),
            "io.github.bitlhk.finance.builder.local.LocalAgentHost",str(candidate)],env=env,cwd=candidate,stdout=log,stderr=subprocess.STDOUT)
    evidence = {"model":"CONTROLLED_TEST", "source":"FULL_SIMULATOR" if bank_url else "CONTROLLED_HTTP", "checks":[]}
    def check(name, condition):
        if not condition: raise AssertionError(name)
        evidence["checks"].append(name)
    try:
        deadline=time.monotonic()+30
        instance = None
        while instance is None:
            if child.poll() is not None or time.monotonic()>deadline: raise AssertionError("HOST_START_FAILED")
            if info.exists():
                try: instance=json.loads(info.read_text())
                except ValueError: pass
            time.sleep(.1)
        base="http://127.0.0.1:"+str(instance["port"])
        code, health=request(base,token,"/health")
        check("instance_pid_and_build",code==200 and instance["pid"]==child.pid and health["buildId"]==instance["buildId"])
        lock_bytes=(candidate/"service-lock.json").read_bytes()
        check("running_instance_matches_frozen_lock",health["artifactLock"]==json.loads(lock_bytes)
              and health["instanceId"]==instance["instanceId"])
        evidence["lockSha256"]=hashlib.sha256(lock_bytes).hexdigest()
        check("explicit_test_mode_not_live",health["mode"]=="EXPLICIT_TEST_MODEL")
        code,_=request(base,"wrong-token","/health"); check("host_token_required",code==401)
        code,_=request(base,token,"/sessions",{"customerRef":"other"}); check("front_identity_override_rejected",code==400)
        code, opened=request(base,token,"/sessions",{})
        if code != 200: raise AssertionError("session_authorized: " + str(code) + " " + str(opened))
        check("session_authorized", True)
        sid=opened["sessionId"]
        capabilities = {c["capabilityId"] for p in opened["installations"] for c in p["capabilities"]}
        check("only_selected_query_tools",capabilities==set(health["selectedCapabilities"]) and all(c["operation"]=="QUERY" for p in opened["installations"] for c in p["capabilities"]))
        turns=[]; turn_requests=[]; marker="turn-one-" + uuid.uuid4().hex
        for question in ("Describe my available banking facts", "And what information is missing from that result?"):
            start=len(fixture.models)
            code,value=request(base,token,f"/sessions/{sid}/chat",{"query":question + (" " + marker if not turns else "")})
            check("native_turn_"+str(len(turns)+1),code==200 and value.get("toolResults") and value.get("evidenceKind")=="EXPLICIT_TEST_MODEL")
            sent = fixture.models[start:]
            current_question = question + (" " + marker if not turns else "")
            user_messages = [m for m in sent[0]["messages"] if m["role"] == "user"]
            check("latest_user_message_"+str(len(turns)+1), bool(user_messages)
                  and user_messages[-1].get("content") == current_question)
            system = "\n".join(str(m.get("content", "")) for m in sent[0]["messages"] if m["role"] == "system")
            check("single_current_turn_policy_"+str(len(turns)+1), system.count("HOST_DIALOGUE_SCOPE_V1") == 1)
            if "account.balance.query" in capabilities:
                definition = next(t["function"] for t in sent[0]["tools"] if t["function"]["name"] == "account_balance_query")
                check("balance_selector_optional_"+str(len(turns)+1), "accountId" not in definition["parameters"].get("required", []))
            turns.append(value)
            turn_requests.append(fixture.models[start:])
        evidence["turns"]=turns
        check("native_multiturn_context",has_multiturn_context(*turn_requests, marker))
        evidence["contextProof"]={"marker":marker,"firstTurnToolMessages":len(prior_tool_messages(turn_requests[0])),
                                  "secondTurnFirstRequestChecked":True}
        if len(capabilities)==2:
            code,balance=request(base,token,f"/sessions/{sid}/chat",{"query":"balance-probe"})
            check("combined_balance_reaches_bank",code==200 and any("accounts" in str(call) for call in balance.get("sourceCalls",[])))
            evidence["balanceTurn"]=balance
        if not bank_url:
            other = "customer-test-b"; fixture.customer=other
            policy_b=policy_value(); policy_b["subjects"][other]=policy_b["subjects"][subject].copy(); save_policy(policy_b)
            code, second=request(base,token,"/sessions",{}); check("second_customer_session",code==200)
            before_model=len(fixture.models)
            code, second_result=request(base,token,f"/sessions/{second['sessionId']}/chat",{"query":"Describe my banking facts"})
            check("customers_do_not_share_model_context",code==200 and subject not in json.dumps(fixture.models[before_model:])
                  and isolated_context(turn_requests[0],fixture.models[before_model:],marker))
            check("second_customer_source_binding",fixture.reads[-1][1]==other)
            request(base,token,f"/sessions/{second['sessionId']}/revoke",{})
            fixture.customer=subject; save_policy(policy_value())
        save_policy(policy_value(False)); before=(len(fixture.models),business_calls(fixture,recorder))
        code,value=request(base,token,f"/sessions/{sid}/chat",{"query":"Repeat the previous private amount"})
        check("revocation_blocks_model_and_reads",code==403 and value.get("clearConversation") and before==(len(fixture.models),business_calls(fixture,recorder)))
        save_policy(policy_value()); code,_=request(base,token,f"/sessions/{sid}/chat",{"query":"Repeat"})
        check("revoked_session_not_revived",code==403)
        save_policy(policy_value(scopes=[])); before=business_calls(fixture,recorder)
        code,_=request(base,token,"/sessions",{}); check("no_scope_no_business_read",code==403 and business_calls(fixture,recorder)==before)
        save_policy(policy_value()); code,opened=request(base,token,"/sessions",{}); check("fresh_session",code==200)
        sid=opened["sessionId"]
        if not bank_url:
            before=len(fixture.reads)
            request(base,token,f"/sessions/{sid}/chat",{"query":"selector-probe"})
            check("model_subject_selector_no_read",len(fixture.reads)==before)
            code,opened=request(base,token,"/sessions",{}); check("fresh_session_after_probe",code==200)
            sid=opened["sessionId"]
            if "wealth.facts.describe" in capabilities:
                fixture.missing_risk=True
                code, missing=request(base,token,f"/sessions/{sid}/chat",{"query":"Describe missing risk information"})
                risk=missing.get("toolResults",[{}])[0].get("data",{}).get("facts",{}).get("risk",{})
                check("missing_risk_remains_missing",code==200 and risk.get("availability") != "AVAILABLE" and "level" not in risk)
                fixture.missing_risk=False; fixture.zero=True
                code,zero=request(base,token,f"/sessions/{sid}/chat",{"query":"Describe zero assets"})
                assets=zero.get("toolResults",[{}])[0].get("data",{}).get("facts",{}).get("assets",{})
                check("zero_is_not_missing",code==200 and assets.get("availability")=="AVAILABLE"
                      and Decimal(assets["amounts"][0]["money"]["value"])==0 and assets["amounts"][0]["money"]["currency"]=="CNY")
                fixture.zero=False
        if capabilities=={"wealth.facts.describe"}:
            before=len(business_calls(fixture,recorder))
            request(base,token,f"/sessions/{sid}/chat",{"query":"disabled-tool-probe"})
            check("disabled_balance_cannot_execute",not any("accounts" in r["route"] for r in business_calls(fixture,recorder)[before:]))
        code,_=request(base,token,"/tools/transfer_internal_create",{}); check("no_direct_tool_route",code==404)
        check("no_business_writes",all(call["method"]=="GET" for call in business_calls(fixture,recorder)))
        if not bank_url:
            save_policy(policy_value())
            code, fresh=request(base,token,"/sessions",{}); check("session_for_source_revocation",code==200)
            fixture.sessions.clear(); before=(len(fixture.models),len(fixture.reads))
            code,value=request(base,token,f"/sessions/{fresh['sessionId']}/chat",{"query":"Repeat private facts"})
            check("source_revocation_blocks_model_and_reads",code==403 and value.get("clearConversation")
                  and before==(len(fixture.models),len(fixture.reads)))
            code,fresh=request(base,token,"/sessions",{}); check("session_for_inflight_revocation",code==200)
            ready,release=threading.Event(),threading.Event(); fixture.pause_final=(ready,release)
            result=[]
            worker=threading.Thread(target=lambda: result.append(request(base,token,f"/sessions/{fresh['sessionId']}/chat",{"query":"Describe my available facts"})))
            worker.start()
            try:
                check("inflight_native_tool_result_observed",ready.wait(timeout=30))
                before=len(fixture.reads); save_policy(policy_value(False)); release.set(); worker.join(timeout=30)
                check("revocation_discards_inflight_answer",not worker.is_alive() and len(result)==1
                      and result[0][0]==403 and result[0][1].get("clearConversation") and len(fixture.reads)==before)
            finally: release.set(); worker.join(timeout=30); fixture.pause_final=None
        if recorder:
            business=[call for call in recorder.calls if not call["identity"]]
            check("full_simulator_received_reads",len(business)>0 and all(call["method"]=="GET" for call in business))
            check("full_simulator_zero_business_write_attempts",not any(not call["permitted"] for call in recorder.calls))
            evidence["simulatorTransport"]=recorder.calls
            evidence["recordingProxy"]="READ_ONLY_FORWARDING_TO_FULL_SIMULATOR"
        evidence["modelRequests"]=len(fixture.models); evidence["businessReads"]=business_calls(fixture,recorder)
        evidence["buildId"]=instance["buildId"]; evidence["liveModelValidation"]="NOT_RUN"
        evidence["humanOnboarding"]="PENDING"
        output=candidate/("full-simulator-service-report.json" if bank_url else "controlled-service-report.json")
        output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)); print(json.dumps({"report":str(output),"checks":len(evidence["checks"]),"model":"CONTROLLED_TEST"}))
    finally:
        child.terminate()
        try: child.wait(timeout=15)
        except subprocess.TimeoutExpired: child.kill(); child.wait()
        log.close(); fixture.close(); policy.unlink(missing_ok=True)
        if recorder: recorder.close()


if __name__=="__main__":
    parser=argparse.ArgumentParser(); parser.add_argument("candidate",type=Path); parser.add_argument("--bank-url")
    args=parser.parse_args(); verify(args.candidate,args.bank_url)
