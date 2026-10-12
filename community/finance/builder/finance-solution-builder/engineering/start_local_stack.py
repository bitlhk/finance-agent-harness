"""Own a local simulator + Builder UI; stop only children created by this launcher."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import signal
import socket
import subprocess
import sys
import time
import urllib.request

from finance_solution_builder.local_model_binding import bind_configured_model
from finance_solution_builder.server_access import ServerAccess

PROJECT = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0)); return sock.getsockname()[1]


def stop(child):
    if child is not None and child.poll() is None:
        child.terminate()
        try: child.wait(timeout=15)
        except subprocess.TimeoutExpired: child.kill(); child.wait()


def main():
    def interrupted(_number, _frame): raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness-repo", type=Path, required=True)
    parser.add_argument("--simulator-jar", type=Path, required=True)
    parser.add_argument("--simulator-sha256", required=True)
    parser.add_argument("--bank-port", type=int, default=18119)
    parser.add_argument("--ui-port", type=int, default=18118)
    parser.add_argument("--provision-demo-policy", action="store_true")
    parser.add_argument("--provision-cashflow-demo-policy", action="store_true")
    parser.add_argument("--task-policy", type=Path, help="Explicit operator-owned synthetic TASK grant policy; never derived from selected assets")
    parser.add_argument("--persistent-simulator", action="store_true", help="Keep synthetic bank tasks and ledger across restarts in workspace")
    parser.add_argument("--workspace", type=Path, default=PROJECT / "build/local-environment")
    parser.add_argument("--bind-configured-model", help="Explicit catalog ref matching FH_MODEL_NAME; no automatic fallback")
    parser.add_argument("--data-dir", type=Path)
    parser.add_argument("--public-origin")
    parser.add_argument("--proxy-token-file", type=Path)
    parser.add_argument("--max-instances", type=int, default=2)
    args = parser.parse_args()
    if bool(args.public_origin) != bool(args.proxy_token_file):
        parser.error("public-origin and proxy-token-file must be supplied together")
    if args.public_origin:
        if args.data_dir is None or args.max_instances < 1:
            parser.error("server mode requires data-dir and a positive max-instances")
        ServerAccess.load(args.public_origin, args.proxy_token_file)
        os.umask(0o077)
    jar = args.simulator_jar.resolve()
    if hashlib.sha256(jar.read_bytes()).hexdigest() != args.simulator_sha256:
        raise SystemExit("SIMULATOR_ARTIFACT_MISMATCH")
    port = args.bank_port or free_port()
    try:
        with socket.socket() as check:
            check.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            check.bind(("127.0.0.1", port)); check.listen()
    except OSError: raise SystemExit("BANK_PORT_IN_USE") from None
    bank_url = "http://127.0.0.1:" + str(port)
    workspace = args.workspace.resolve()
    workspace.mkdir(parents=True, exist_ok=True)
    if args.task_policy and not args.persistent_simulator:
        parser.error("task-policy requires persistent-simulator to retain bank outcomes")
    task_policy = args.task_policy.resolve() if args.task_policy else None
    if task_policy:
        if not task_policy.is_file() or args.task_policy.is_symlink():
            raise SystemExit("TASK_POLICY_REQUIRED")
        configured = json.loads(task_policy.read_text())
        if configured.get("schemaVersion") != "local-simulator-access.v0.1" or configured.get("bankUrl") != bank_url:
            raise SystemExit("TASK_POLICY_BANK_MISMATCH")
    model_env = (bind_configured_model(os.environ, workspace, args.bind_configured_model)
                 if args.bind_configured_model else dict(os.environ))
    policy = workspace / "access-policy.json"
    if args.provision_demo_policy:
        if policy.exists(): raise SystemExit("POLICY_EXISTS: existing authorization is never silently reset")
        with os.fdopen(os.open(policy, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            json.dump({"schemaVersion":"local-simulator-access.v0.1","bankUrl":bank_url,"subjects":{"customer-demo-001":{
                "enabled":True,"expiresAt":(datetime.now(timezone.utc)+timedelta(days=1)).isoformat(),
                "scopes":["wealth:facts:read","wealth:customer:read","account:read"]}}},stream,indent=2)
    if not policy.exists(): raise SystemExit("ACCESS_POLICY_REQUIRED: explicitly provision or configure the synthetic policy")
    cashflow_policy = workspace / "cashflow-access-policy.json"
    if args.provision_cashflow_demo_policy:
        if cashflow_policy.exists(): raise SystemExit("CASHFLOW_POLICY_EXISTS: existing authorization is never silently reset")
        with os.fdopen(os.open(cashflow_policy, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as stream:
            json.dump({"schemaVersion":"local-simulator-access.v0.1","bankUrl":bank_url,"subjects":{"customer-demo-001":{
                "enabled":True,"expiresAt":(datetime.now(timezone.utc)+timedelta(days=1)).isoformat(),
                "scopes":["wealth:facts:read","wealth:customer:read","account:read","cashflow:read"]}}},stream,indent=2)
    nonce = secrets.token_hex(20)
    database = ("jdbc:h2:file:" + str(workspace / "bank-db") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                if args.persistent_simulator else "jdbc:h2:mem:workbench_"+nonce+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
    env = dict(os.environ, FH_SIMULATOR_INSTANCE=nonce, BANKING_DB_URL=database,
               BANKING_PROVIDER_MODE="simulator", BANKING_FRAUD_RISK_ENABLED="false", BANKING_FAQ_SEARCH_ENABLED="false")
    bank = ui = None
    with (workspace / "simulator.log").open("wb") as bank_log:
        try:
            bank = subprocess.Popen(["java","-Xmx512m","-jar",str(jar),"--server.address=127.0.0.1","--server.port="+str(port)],
                                    env=env,cwd=workspace,stdout=bank_log,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+60
            while True:
                if bank.poll() is not None or time.monotonic()>deadline: raise RuntimeError("SIMULATOR_START_FAILED")
                try:
                    with urllib.request.urlopen(bank_url+"/api/v1/sessions/runtime",timeout=2) as response: identity=json.load(response)
                    if identity.get("instanceId")==nonce: break
                except Exception: pass
                time.sleep(.2)
            inventory={"simulatorJarSha256":args.simulator_sha256,"simulatorInstance":nonce,"bankUrl":bank_url,
                       "database":"ISOLATED_SYNTHETIC_FILE" if args.persistent_simulator else "ISOLATED_SYNTHETIC_MEMORY", "modelMode":"LIVE" if all(os.environ.get(k) for k in ("FH_MODEL_URL","FH_MODEL_NAME","FH_MODEL_KEY")) else "NOT_CONFIGURED"}
            (workspace/"running-components.json").write_text(json.dumps(inventory,indent=2))
            host_env=dict(model_env,FH_BANK_URL=bank_url,FH_BANK_LOGIN="demo",FH_BANK_SECRET="demo123",FH_ACCESS_POLICY=str(policy))
            # This launcher opts in explicitly; inherited flags cannot silently enable task execution.
            host_env.pop("FH_TASK_ACCESS_POLICY", None)
            host_env.pop("FH_TASK_SIMULATOR_ENABLED", None)
            if task_policy:
                host_env.update(FH_TASK_ACCESS_POLICY=str(task_policy), FH_TASK_SIMULATOR_ENABLED="true")
            if cashflow_policy.is_file(): host_env["FH_CASHFLOW_ACCESS_POLICY"] = str(cashflow_policy)
            command=[sys.executable,"-m","finance_solution_builder.workbench","--harness-repo",str(args.harness_repo.resolve()),"--port",str(args.ui_port)]
            if args.data_dir: command.extend(["--data-dir", str(args.data_dir)])
            if args.public_origin:
                command.extend(["--public-origin", args.public_origin, "--proxy-token-file", str(args.proxy_token_file),
                                "--max-instances", str(args.max_instances)])
            ui=subprocess.Popen(command,
                                env=host_env,cwd=PROJECT)
            while ui.poll() is None and bank.poll() is None: time.sleep(.5)
            raise RuntimeError("LOCAL_COMPONENT_EXITED")
        except KeyboardInterrupt: pass
        finally: stop(ui); stop(bank)


if __name__=="__main__": main()
