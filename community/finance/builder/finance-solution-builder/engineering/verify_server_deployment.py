"""Explicit HTTPS/proxy + generated-host smoke. Only synthetic HTTP model/bank data."""
import argparse
import base64
from contextlib import ExitStack
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import threading
import urllib.error
import urllib.request

from finance_solution_builder.service import digest, stop_owned_process
from finance_solution_builder.model_registry import ModelRegistry
from finance_solution_builder.workbench_contracts import default_selection
from prepare_server_deployment import prepare
from verify_local_service import Endpoints, expiry, has_multiturn_context
from ag_ui.core import Event
from pydantic import TypeAdapter


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0)); return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--browser", action="store_true")
    args = parser.parse_args()
    for command in ("nginx", "openssl"):
        if shutil.which(command) is None: raise SystemExit(command + " is required; no skipped PASS")
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    project = Path(__file__).resolve().parents[1]
    report = {"mode": "REAL_TLS_PROXY_AND_GENERATED_HOST_WITH_CONTROLLED_MODEL_AND_BANK",
              "realModel": "NOT_RUN", "realBank": "NOT_RUN", "publicDeployment": "NOT_RUN", "checks": []}
    host = nginx = None
    fixture = Endpoints()
    def check(name, condition):
        if not condition: raise AssertionError(name)
        report["checks"].append(name)
    try:
        with tempfile.TemporaryDirectory(prefix="finance-server-test-") as temporary, ExitStack() as cleanup:
            cleanup.callback(lambda: stop_owned_process(host))
            cleanup.callback(lambda: stop_owned_process(nginx))
            root = Path(temporary)
            prepared = root / "deploy"; prepare("127.0.0.1", prepared)
            data = root / "state"; data.mkdir(mode=0o700)
            check("storage_outside_source_tree", not data.is_relative_to(project))
            host_port, tls_port, http_port = port(), port(), port()
            base = "https://127.0.0.1:" + str(tls_port)
            key, cert = root / "tls.key", root / "tls.pem"
            subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                            "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1",
                            "-keyout", str(key), "-out", str(cert)], check=True, capture_output=True)
            password = secrets.token_urlsafe(24)
            hashed = subprocess.run(["openssl", "passwd", "-6", "-stdin"], input=password + "\n",
                                    text=True, capture_output=True, check=True).stdout.strip()
            users = root / "users.htpasswd"
            users.write_text("alice:" + hashed + "\nbob:" + hashed + "\n"); users.chmod(0o600)
            config = (prepared / "nginx.conf").read_text()
            replacements = {
                "listen 80;": "listen 127.0.0.1:" + str(http_port) + ";",
                "listen 443 ssl;": "listen 127.0.0.1:" + str(tls_port) + " ssl;",
                'if ($http_host != "127.0.0.1")': 'if ($http_host != "127.0.0.1:' + str(tls_port) + '")',
                "proxy_set_header Host 127.0.0.1;": "proxy_set_header Host 127.0.0.1:" + str(tls_port) + ";",
                "http://127.0.0.1:18148": "http://127.0.0.1:" + str(host_port),
                "/etc/finance-studio/tls/fullchain.pem": str(cert),
                "/etc/finance-studio/tls/privkey.pem": str(key),
                "/etc/finance-studio/users.htpasswd": str(users),
                "/etc/finance-studio/proxy-header.conf": str(prepared / "proxy-header.conf"),
                "/var/log/nginx/finance-studio-error.log": str(root / "nginx-error.log"),
            }
            for before, after in replacements.items():
                assert before in config; config = config.replace(before, after)
            conf = root / "nginx.conf"
            conf.write_text("worker_processes 1;\npid " + str(root / "nginx.pid") + ";\nerror_log " + str(root / "main-error.log")
                            + ";\nevents { worker_connections 128; }\nhttp {\naccess_log off;\nclient_body_temp_path "
                            + str(root / "body") + ";\nproxy_temp_path " + str(root / "proxy") + ";\n" + config + "\n}\n")
            validation = subprocess.run(["nginx", "-t", "-p", str(root), "-c", str(conf)], capture_output=True, text=True)
            if validation.returncode:
                report["nginxValidationError"] = validation.stderr.replace(str(root), "<temporary>").replace(
                    (prepared / "proxy-token").read_text().strip(), "[REDACTED]")
                raise RuntimeError(report["nginxValidationError"])
            check("nginx_configuration_valid", True)
            policy = root / "policy.json"
            def grant(enabled):
                policy.write_text(json.dumps({"schemaVersion": "local-simulator-access.v0.1", "bankUrl": fixture.base,
                    "subjects": {fixture.customer: {"enabled": enabled, "expiresAt": expiry(),
                        "scopes": ["account:read", "wealth:facts:read", "wealth:customer:read"]}}})); policy.chmod(0o600)
            grant(True)
            env = {k: v for k, v in os.environ.items() if not k.startswith("FH_")}
            env.update(FH_MODEL_URL=fixture.base + "/v1", FH_MODEL_NAME="explicit-boundary-test", FH_MODEL_KEY="synthetic-canary",
                       FH_MODEL_MODE="CONTROLLED_TEST", FH_BANK_URL=fixture.base, FH_BANK_LOGIN="demo",
                       FH_BANK_SECRET="demo123", FH_ACCESS_POLICY=str(policy))
            catalog_path, registry_path = root / "catalog.json", root / "registry.json"
            catalog_path.write_text(json.dumps({"schemaVersion": "finance-model-catalog.v1", "source": {"kind": "CONTROLLED_TEST"},
                "models": [{"modelRef": "model-server-test", "displayName": "Controlled Test Model", "provider": "OpenAI",
                            "modelName": "explicit-boundary-test", "eaCatalogId": "test", "eaConfigured": False}]}))
            registry_path.write_text(json.dumps({"schemaVersion": "finance-model-bindings.v1", "bindings": [
                {"modelRef": "model-server-test", "revision": "v1", "enabled": True, "protocol": "OPENAI_CHAT_COMPLETIONS",
                 "provider": "OpenAI", "modelName": "explicit-boundary-test", "urlEnvironment": "FH_CONN_TEST_URL",
                 "keyEnvironment": "FH_CONN_TEST_KEY"}]})); registry_path.chmod(0o600)
            env.update(FH_MODEL_CATALOG=str(catalog_path), FH_MODEL_REGISTRY=str(registry_path),
                       FH_CONN_TEST_URL=fixture.base + "/v1", FH_CONN_TEST_KEY="synthetic-canary")
            ModelRegistry(env)
            command = [sys.executable, "-m", "finance_solution_builder.workbench", "--harness-repo", str(args.harness.resolve()),
                       "--port", str(host_port), "--data-dir", str(data), "--public-origin", base,
                       "--proxy-token-file", str(prepared / "proxy-token"), "--max-instances", "2"]
            log = (output / "workbench.log").open("wb")
            def start_host():
                child = subprocess.Popen(command, cwd=project, env=env, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    if child.poll() is not None: raise AssertionError("HOST_EXITED")
                    try:
                        with socket.create_connection(("127.0.0.1", host_port), timeout=.2): return child
                    except OSError: time.sleep(.1)
                stop_owned_process(child); raise AssertionError("HOST_TIMEOUT")
            host = start_host()
            nginx = subprocess.Popen(["nginx", "-p", str(root), "-c", str(conf), "-g", "daemon off;"],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            context = ssl.create_default_context(cafile=str(cert))
            clients = {u: urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()),
                                                     urllib.request.HTTPSHandler(context=context)) for u in ("alice", "bob", "anonymous")}
            tokens = {}
            def request(path, body=None, user="alice", overrides=None, endpoint=base):
                headers = {"Origin": base, "Content-Type": "application/json", "X-CSRF-Token": tokens.get(user, "")}
                if user != "anonymous": headers["Authorization"] = "Basic " + base64.b64encode((user + ":" + password).encode()).decode()
                headers.update(overrides or {})
                req = urllib.request.Request(endpoint + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
                try: response = clients[user].open(req, timeout=150)
                except urllib.error.HTTPError as error: response = error
                with response:
                    payload = response.read()
                    return response.code, json.loads(payload) if "json" in response.headers.get("Content-Type", "") else payload, response.headers
            deadline = time.monotonic() + 15
            while True:
                try: code, _, _ = request("/api/bootstrap", user="anonymous"); break
                except urllib.error.URLError:
                    if nginx.poll() is not None or time.monotonic() > deadline: raise
                    time.sleep(.1)
            check("anonymous_nginx_401", code == 401)
            check("incorrect_password_401", request("/api/bootstrap", overrides={"Authorization": "Basic " +
                  base64.b64encode(b"alice:wrong").decode()})[0] == 401)
            for user in ("alice", "bob"):
                code, boot, headers = request("/api/bootstrap", user=user)
                check(user + "_authenticated_secure_cookie", code == 200 and "; Secure" in headers["Set-Cookie"])
                tokens[user] = boot["csrf"]
            check("direct_backend_rejects_forged_username", request("/api/bootstrap", endpoint="http://127.0.0.1:" + str(host_port),
                  overrides={"Host": "127.0.0.1:" + str(tls_port), "X-Finance-User": "alice"})[0] == 403)
            check("foreign_origin_denied", request("/api/v1/builds", overrides={"Origin": "https://evil.internal"})[0] == 403)
            check("foreign_host_denied", request("/api/bootstrap", overrides={"Host": "evil.internal"})[0] == 421)
            check("csrf_required", request("/api/v1/preflight", default_selection(["account.balance.query"]), overrides={"X-CSRF-Token": ""})[0] == 403)
            code, catalog, _ = request("/api/v1/catalog")
            check("configured_model_catalog_available", code == 200 and "modelCatalogRevision" in catalog)
            selection = {"extension_type": "finance-workbench.selection", "schema_version": "0.3",
                "runtimeId": "openjiuwen-java-0.1.15", "applicationTemplateId": "banking-readonly-web-v1",
                "presentation": "DESKTOP_WEB", "bankConnectionId": "local-simulator",
                "capabilityIds": ["account.balance.query", "wealth.facts.describe"],
                "selectedModelRefs": ["model-server-test"], "defaultModelRef": "model-server-test",
                "modelCatalogRevision": catalog["modelCatalogRevision"]}
            code, build, _ = request("/api/v1/builds", selection)
            check("https_build_queued", code == 202)
            route = "/api/v1/builds/" + build["buildId"]
            deadline = time.monotonic() + 1200
            while build["buildStatus"] in {"QUEUED", "RUNNING"}:
                if time.monotonic() > deadline: raise AssertionError("BUILD_TIMEOUT")
                time.sleep(1); _, build, _ = request(route)
            candidate = data / "workbench" / build["buildId"] / "candidate"
            evidence = output / "build-evidence"; evidence.mkdir()
            if (candidate.parent / "build-report.json").exists():
                shutil.copy2(candidate.parent / "build-report.json", evidence / "build-report.json")
            if (candidate.parent / "logs").exists(): shutil.copytree(candidate.parent / "logs", evidence / "logs")
            check("external_directory_real_build", build["buildStatus"] == "SUCCEEDED")
            lock = json.loads((candidate / "service-lock.json").read_text())
            shutil.copy2(candidate / "service-lock.json", evidence / "service-lock.json")
            check("all_locked_artifacts_match", all(digest(candidate / f) == h for f, h in lock["files"].items()))
            check("selected_model_bound_in_artifact", lock["modelSelection"]["defaultModelRef"] == "model-server-test")
            check("no_transfer_tool_selected", set(lock["capabilities"]) == {"account.balance.query", "wealth.facts.describe"})
            check("bob_cannot_read_alice", request(route, user="bob")[0] == 403)
            check("proxy_overwrites_spoofed_user", request(route, user="bob", overrides={"X-Finance-User": "alice", "X-Finance-Proxy-Token": "fake"})[0] == 403)
            check("bob_cannot_download", request("/api/jobs/" + build["buildId"] + "/artifact", user="bob")[0] == 403)
            code, started, _ = request(route + "/start", {"artifactId": build["artifact"]["artifactId"]})
            check("https_instance_started", code == 200)
            iid = started["instance"]["instanceId"]
            _, opened, _ = request(route + "/sessions", {"instanceId": iid})
            sid = opened["sessionId"]
            def chat(query): return request(route + "/chat", {"instanceId": iid, "sessionId": sid, "query": query})
            def agui(query):
                code, payload, _ = request(route + "/ag-ui", {"threadId": sid, "runId": "run-" + secrets.token_hex(8),
                    "messages": [{"id": "m-" + secrets.token_hex(8), "role": "user", "content": query}],
                    "tools": [], "context": [], "state": {}, "forwardedProps": {"instanceId": iid}})
                if code != 200: return code, payload
                events = [json.loads(line[6:]) for line in payload.decode().splitlines() if line.startswith("data: ")]
                for event in events: TypeAdapter(Event).validate_python(event)
                return code, events
            code, first_events = agui("balance-probe server-first-marker")
            check("agui_official_protocol_success", code == 200 and first_events[0]["type"] == "RUN_STARTED"
                  and first_events[-1]["type"] == "RUN_FINISHED")
            first = next(e["value"] for e in first_events if e.get("name") == "finance.evidence")
            check("https_tool_read", code == 200 and first["result"]["toolResults"] and fixture.reads)
            boundary = len(fixture.models)
            code, second = agui("second-turn")
            check("two_turn_context_preserved", code == 200 and has_multiturn_context(fixture.models[:boundary], fixture.models[boundary:], "server-first-marker"))
            ready, release = threading.Event(), threading.Event()
            fixture.pause_source = (ready, release)
            racing = []
            worker = threading.Thread(target=lambda: racing.append(agui("balance-probe delayed")))
            worker.start()
            try:
                check("delayed_source_actually_received", ready.wait(15))
                model_count = len(fixture.models); grant(False); release.set(); worker.join(40)
                check("revocation_blocks_next_model_http", not worker.is_alive() and len(fixture.models) == model_count)
                raw = json.dumps(racing)
                check("agui_revocation_has_no_financial_output", racing[0][1][-1]["type"] == "RUN_ERROR"
                      and "12345.67" not in raw and "finance.evidence" not in raw
                      and "TEXT_MESSAGE_CONTENT" not in raw)
            finally:
                release.set(); worker.join(45); fixture.pause_source = None
            grant(True)
            _, opened, _ = request(route + "/sessions", {"instanceId": iid})
            sid = opened["sessionId"]
            before = (len(fixture.models), len(fixture.reads)); grant(False)
            code, denied, _ = chat("revoked")
            check("bank_revocation_not_overridden_by_login", code == 403 and denied["clearConversation"] and before == (len(fixture.models), len(fixture.reads)))
            check("no_bank_business_writes", not fixture.writes)
            old_csrf = tokens["alice"]
            stop_owned_process(host); host = start_host()
            code, restored, _ = request(route)
            check("restart_preserves_owner_and_stops_child", code == 200 and restored["instance"]["status"] == "STOPPED")
            _, boot, _ = request("/api/bootstrap")
            check("restart_stable_authenticated_identity", boot["csrf"] == old_csrf)
            from verify_native_agui import verify as verify_native
            report["nativeAgUiChecks"] = verify_native(candidate, env, fixture, grant)
            if args.browser:
                browser_env = dict(os.environ, FH_TEST_BASIC_PASSWORD=password)
                subprocess.run(["node", str(project / "engineering/verify_server_ui.cjs"), base, str(output)],
                               env=browser_env, check=True, timeout=90)
                check("authenticated_desktop_mobile_browser", True)
            users.write_text("bob:" + hashed + "\n")
            check("removed_login_cannot_reuse_cookie", request(route)[0] == 401)
            users.write_text("alice:" + hashed + "\nbob:" + hashed + "\n")
            report.update(buildId=build["buildId"], artifactId=build["artifact"]["artifactId"], sources=lock["sources"],
                          dependencyJarCount=sum(f.endswith(".jar") for f in lock["files"]),
                          artifactBytes=sum(p.stat().st_size for p in candidate.rglob("*") if p.is_file()))
            code, _, _ = request(route + "/delete", {"buildId": build["buildId"]})
            check("delete_removes_generated_artifact_only", code == 200 and not candidate.exists()
                  and (data / "deletion-receipts" / (build["buildId"] + ".json")).is_file())
            shutil.copy2(data / "deletion-receipts" / (build["buildId"] + ".json"), output / "deletion-receipt.json")
            report["temporaryArtifactRetention"] = "DELETED_WITH_RECEIPT"
            log.close()
    finally:
        stop_owned_process(nginx); stop_owned_process(host); fixture.close()
        (output / "report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps({"checks": len(report["checks"]), "realModel": report["realModel"], "artifactBytes": report["artifactBytes"]}))


if __name__ == "__main__": main()
