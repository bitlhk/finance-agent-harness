"""Full synthetic-bank qualification. Never point at a real bank or existing shared simulator."""
from __future__ import annotations

import argparse
from decimal import Decimal
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse
import requests


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str) + "\n")
    path.chmod(0o600)


def qualify(candidate, output, bank_url, java, port, proxy_port, instance):
    assert urlparse(bank_url).hostname in {"127.0.0.1", "localhost"}
    assert not output.exists(), "KEEP_OLD_EVIDENCE"
    output.mkdir(parents=True, mode=0o700)
    checks, calls = [], []
    flags = {"badSubmit": False, "holdSubmit": False, "failQuery": False}
    reached, release = threading.Event(), threading.Event()
    def check(name, condition):
        checks.append({"name": name, "passed": bool(condition)})
        save(output / "checks.json", {"checks": checks, "calls": calls})
        assert condition, name
    class Proxy(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_GET(self): self.forward()
        def do_POST(self): self.forward()
        def forward(self):
            path = urlparse(self.path).path
            route = path
            if path.startswith("/api/v1/tasks/"):
                route = "/api/v1/tasks/{taskId}" + ("/"+path.rsplit("/",1)[1] if path.count("/")>4 else "")
            event = {"method":self.command,"route":route,"at":time.time()};calls.append(event)
            body = self.rfile.read(int(self.headers.get("Content-Length","0")))
            if flags["failQuery"] and self.command == "GET" and path.startswith("/api/v1/tasks/"):
                flags["failQuery"] = False; status, raw = 503, b'{"status":"FAILED"}'
            else:
                upstream=requests.request(self.command,bank_url+self.path,data=body,
                    headers={k:v for k,v in self.headers.items() if k.lower() not in {"host","content-length","connection"}},timeout=15)
                status,raw=upstream.status_code,upstream.content
                if path.endswith("/submit") and flags["holdSubmit"]:
                    flags["holdSubmit"]=False;reached.set();release.wait(30)
                if path.endswith("/submit") and flags["badSubmit"]:
                    flags["badSubmit"]=False;raw=b'{"status":"SUCCESS","data":[]}'
            event["status"]=status
            try:
                self.send_response(status);self.send_header("Content-Type","application/json");self.end_headers();self.wfile.write(raw)
            except (BrokenPipeError,ConnectionResetError): pass
    proxy=ThreadingHTTPServer(("127.0.0.1",proxy_port),Proxy)
    threading.Thread(target=proxy.serve_forever,daemon=True).start()
    host_url=f"http://127.0.0.1:{port}"
    policy=output/"policy.json"
    policy_value={"schemaVersion":"simulated-transfer-access.v0.1","bankUrl":f"http://127.0.0.1:{proxy_port}",
        "simulatorInstance":instance,"maxAmountCny":"1.00","subjects":{"customer-demo-001":{
        "enabled":True,"scopes":["account:read","transfer:create"],"expiresAt":(datetime.now(timezone.utc)+timedelta(hours=2)).isoformat()}}}
    save(policy,policy_value)
    state=output/"state"
    env=dict(os.environ,FH_TRANSFER_SIMULATOR_ONLY="true",FH_TRANSFER_PORT=str(port),FH_TRANSFER_STATE=str(state),
             FH_TRANSFER_POLICY=str(policy),FH_BANK_URL=policy_value["bankUrl"],FH_BANK_INSTANCE=instance)
    command=[java,"-cp",str(next((candidate/"target").glob("*.jar")))+os.pathsep+str(candidate/"target/dependency/*"),
             "io.github.bitlhk.finance.builder.transfer.TransferHost",str(candidate)]
    child=None; logs=[]
    def stop():
        nonlocal child
        if child and child.poll() is None:
            child.terminate()
            try:child.wait(15)
            except subprocess.TimeoutExpired:child.kill();child.wait(10)
        child=None
    def start():
        nonlocal child
        env["FH_TRANSFER_START_ID"] = str(uuid.uuid4())
        log=(output/f"host-{len(logs)}.log").open("wb");logs.append(log)
        child=subprocess.Popen(command,cwd=candidate,env=env,stdout=log,stderr=subprocess.STDOUT)
        for _ in range(100):
            if child.poll() is not None: raise AssertionError("HOST_START_FAILED")
            try:
                response=requests.get(host_url+"/health",timeout=1)
                if response.status_code==200 and response.json().get("instanceId")==env["FH_TRANSFER_START_ID"]:return
            except requests.RequestException:pass
            time.sleep(.1)
        raise AssertionError("HOST_START_TIMEOUT")
    def request(client,path,body=None,expected=200,**headers):
        response=client.post(host_url+"/api/"+path,json=body or {},timeout=45,
            headers={"Origin":host_url,"Content-Type":"application/json",**headers})
        assert response.status_code==expected,(path,response.status_code,response.text[:300])
        return response.json()
    def login():
        time.sleep(1.05)
        client=requests.Session()
        data=request(client,"login",{"loginName":"demo","loginSecret":"demo123"})
        client.headers["X-CSRF-Token"]=data["csrf"]
        request(client,"step-up",{"verificationCode":"000000"})
        return client
    def bank_read(client,path):
        # Test oracle uses the actual persisted simulator session, never returns it in the report.
        sid=client.cookies.get("fh_transfer")
        session=json.loads((state/"sessions"/(sid+".json")).read_text())
        response=requests.get(bank_url+path,headers={"X-Session-Id":session["bankSession"],"X-Channel":"M"},timeout=10)
        response.raise_for_status();return response.json(parse_float=Decimal)["data"]
    def ledger(client,account):
        accounts=bank_read(client,"/api/v1/accounts")
        rows=accounts if isinstance(accounts,list) else accounts["accounts"]
        balance=next(Decimal(str(r["availableBalance"])) for r in rows if r["accountId"]==account)
        transactions=bank_read(client,"/api/v1/accounts/"+account+"/transactions")
        receipts=bank_read(client,"/api/v1/transfer-receipts")
        return {"balance":balance,"transactions":transactions,"receipts":receipts}
    def counts():
        return {k:sum(c["method"]=="POST" and (c["route"]=="/api/v1/tasks" if k=="create" else c["route"].endswith("/"+k)) for c in calls) for k in ("create","confirm","submit")}
    def prepare(client):
        choices=request(client,"choices");accounts=choices["accounts"]
        if isinstance(accounts,dict):accounts=accounts["accounts"]
        account=next(x["accountId"] for x in accounts if x["currency"]=="CNY" and x["status"]=="NORMAL")
        recipient=next(x["beneficiaryId"] for x in choices["beneficiaries"] if x["internal"] and x["status"]=="ACTIVE")
        result=request(client,"prepare",{"fromAccountId":account,"beneficiaryId":recipient,"amount":"0.01","purpose":"Harness isolated acceptance"})
        save(output/("prepare-"+str(len(checks))+".json"),result)
        handle=result.get("financeTaskHandle")
        check("prepare-does-not-submit-"+str(len(checks)),bool(handle) and result["status"]=="NEED_USER")
        return account,handle,request(client,"challenge",{"handle":handle})
    try:
        start();client=login();before=counts()
        account,handle,challenge=prepare(client)
        check("no-confirm-or-submit-before-host-approval",counts()["submit"]==before["submit"] and counts()["confirm"]==before["confirm"])
        current=request(client,"state")
        check("owned-task-visible-in-page-record",any(t["handle"]==handle and t["awaitingConfirmation"] for t in current["tasks"]))
        mutations=counts()
        changed=dict(challenge["parameters"],amount="0.02")
        request(client,"prepare",changed)
        check("pending-task-prevents-second-bank-create",counts()==mutations)
        request(client,"confirm",{"handle":handle,"nonce":challenge["nonce"],"amount":"0.02"},400)
        check("confirmation-parameter-override-rejected",counts()==mutations)
        request(client,"confirm",{"handle":handle,"nonce":str(uuid.uuid4())},403)
        check("unknown-confirmation-no-write",counts()==mutations)
        nonce_path=state/"confirmations"/(challenge["nonce"]+".json")
        expired=json.loads(nonce_path.read_text())
        expired["expiresAt"]=(datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat()
        save(nonce_path,expired)
        request(client,"confirm",{"handle":handle,"nonce":challenge["nonce"]},403)
        check("expired-confirmation-no-write",counts()==mutations)
        challenge=request(client,"challenge",{"handle":handle})
        policy_value["subjects"]["customer-demo-001"]["scopes"]=["account:read"]
        save(policy,policy_value)
        request(client,"confirm",{"handle":handle,"nonce":challenge["nonce"]},403)
        check("revoked-transfer-scope-no-write",counts()==mutations)
        policy_value["subjects"]["customer-demo-001"]["scopes"]=["account:read","transfer:create"]
        save(policy,policy_value)
        snapshot=ledger(client,account)
        result=request(client,"confirm",{"handle":handle,"nonce":challenge["nonce"]})
        save(output/"confirmed.json",result)
        check("bank-completed",result["status"]=="COMPLETED")
        after=ledger(client,account)
        check("balance-debited-once",snapshot["balance"]-after["balance"]==Decimal("0.01"))
        check("receipt-added-once",len(after["receipts"])-len(snapshot["receipts"])==1)
        check("ledger-entry-added",len(after["transactions"])-len(snapshot["transactions"])==1)
        mutations=counts();request(client,"confirm",{"handle":handle,"nonce":challenge["nonce"]})
        check("duplicate-confirm-no-write",counts()==mutations)
        stop();start();request(client,"resume",{"handle":handle})
        check("restart-terminal-no-write",counts()==mutations and ledger(client,account)["balance"]==after["balance"])
        other=login();request(other,"confirm",{"handle":handle,"nonce":challenge["nonce"]},403)
        check("cross-session-confirm-no-write",counts()==mutations)

        # Lost submit response, observation failure, and process restart retain the original task.
        client=login();account,handle,challenge=prepare(client);snapshot=ledger(client,account)
        flags["badSubmit"]=True
        result=request(client,"confirm",{"handle":handle,"nonce":challenge["nonce"]})
        check("bad-submit-preserves-handle",result.get("financeTaskHandle")==handle)
        mutations=counts();flags["failQuery"]=True
        check("query-failure-preserves-handle",request(client,"resume",{"handle":handle}).get("financeTaskHandle")==handle)
        stop();start();result=request(client,"resume",{"handle":handle})
        check("unknown-restart-completed",result["status"]=="COMPLETED" and counts()==mutations)
        after=ledger(client,account)
        check("unknown-ledger-once",snapshot["balance"]-after["balance"]==Decimal("0.01") and len(after["receipts"])-len(snapshot["receipts"])==1)

        # Stop the actual owned Host after the bank committed but before its response reaches the Host.
        client=login();account,handle,challenge=prepare(client);snapshot=ledger(client,account)
        flags["holdSubmit"]=True;errors=[]
        def pending():
            try:request(client,"confirm",{"handle":handle,"nonce":challenge["nonce"]})
            except Exception as failure:errors.append(type(failure).__name__)
        thread=threading.Thread(target=pending);thread.start()
        check("bank-commit-response-held",reached.wait(15))
        child.kill();child.wait(10);child=None;release.set();thread.join(15)
        check("owned-interrupted-request-ended",not thread.is_alive())
        mutations=counts();start();result=request(client,"resume",{"handle":handle})
        check("inflight-host-crash-recovered-original",result["status"]=="COMPLETED" and counts()==mutations)
        after=ledger(client,account)
        check("inflight-ledger-once",snapshot["balance"]-after["balance"]==Decimal("0.01") and len(after["receipts"])-len(snapshot["receipts"])==1)

        client=login();account,handle,challenge=prepare(client);mutations=counts()
        request(client,"decline",{"handle":handle})
        check("declined-result-retained",request(client,"confirm",{"handle":handle,"nonce":challenge["nonce"]})["status"]=="REJECTED")
        check("decline-no-submit",counts()==mutations)
        policy_value["subjects"]["customer-demo-001"]["enabled"]=False;save(policy,policy_value)
        reads=len([c for c in calls if c["route"] in {"/api/v1/accounts","/api/v1/beneficiaries"}])
        request(client,"choices",expected=403)
        check("revoked-no-business-read",reads==len([c for c in calls if c["route"] in {"/api/v1/accounts","/api/v1/beneficiaries"}]))
        save(output/"report.json",{"status":"CONTROLLED_FULL_SIMULATOR_PASSED","checks":checks,"calls":calls,
             "candidateLockSha256":hashlib.sha256((candidate/"service-lock.json").read_bytes()).hexdigest(),
             "faultScope":"Owned Host killed only after isolated bank commit, before response; not all crash cut points",
             "realBank":False,"realModel":"NOT_RUN","humanOnboarding":"NOT_RUN"})
    finally:
        release.set();stop();proxy.shutdown();proxy.server_close()
        for log in logs:log.close()
        save(output/"checks.json",{"checks":checks,"calls":calls})
    print(json.dumps({"checks":len(checks),"passed":sum(c["passed"] for c in checks)}))


if __name__=="__main__":
    parser=argparse.ArgumentParser();parser.add_argument("--candidate",type=Path,required=True);parser.add_argument("--output",type=Path,required=True)
    parser.add_argument("--bank",required=True);parser.add_argument("--instance",required=True);parser.add_argument("--java",required=True)
    parser.add_argument("--port",type=int,default=18662);parser.add_argument("--proxy-port",type=int,default=18663)
    a=parser.parse_args();qualify(a.candidate.resolve(),a.output.resolve(),a.bank,a.java,a.port,a.proxy_port,a.instance)
