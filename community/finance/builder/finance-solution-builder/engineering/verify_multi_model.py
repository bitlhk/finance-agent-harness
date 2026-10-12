"""Real generated hosts and isolated HTTP model endpoints; no paid inference."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

from finance_solution_builder.service import digest, stop_owned_process
from verify_local_service import Endpoints, expiry


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--browser", action="store_true")
    parser.add_argument("--browser-only", action="store_true")
    parser.add_argument("--directory", action="store_true", help="Exercise the versioned template/component entry path")
    parser.add_argument("--template-modules", action="store_true", help="Exercise template/module/runtime selection v0.5")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    bank, a, b, c = [Endpoints() for _ in range(4)]
    child = None
    evidence = {"mode": "FOUR_ISOLATED_HTTP_FIXTURES", "realModel": "NOT_RUN", "realBank": "NOT_RUN", "checks": [], "builds": []}
    def check(name, value):
        if not value: raise AssertionError(name)
        evidence["checks"].append(name)
    try:
        with tempfile.TemporaryDirectory(prefix="finance-model-binding-") as temp:
            root = Path(temp)
            policy = root / "access.json"
            def grant(enabled=True):
                policy.write_text(json.dumps({"schemaVersion": "local-simulator-access.v0.1", "bankUrl": bank.base,
                    "subjects": {bank.customer: {"enabled": enabled, "expiresAt": expiry(),
                    "scopes": ["wealth:facts:read", "wealth:customer:read", "account:read"]}}})); policy.chmod(0o600)
            grant()
            catalog = {"schemaVersion": "finance-model-catalog.v1", "source": {"kind": "CONTROLLED_TEST"}, "models": [
                {"modelRef": "model-" + x, "displayName": "Controlled " + x.upper(), "provider": "OpenAI",
                 "modelName": "controlled-" + x, "eaCatalogId": "test-" + x, "eaConfigured": False} for x in "abc"]}
            catalog_path = root / "catalog.json"; catalog_path.write_text(json.dumps(catalog))
            registry = {"schemaVersion": "finance-model-bindings.v1", "bindings": [
                {"modelRef": "model-" + x, "revision": "v1", "enabled": True, "protocol": "OPENAI_CHAT_COMPLETIONS",
                 "provider": "OpenAI", "modelName": "controlled-" + x, "urlEnvironment": "FH_CONN_" + x.upper() + "_URL",
                 "keyEnvironment": "FH_CONN_" + x.upper() + "_KEY"} for x in "abc"]}
            reg = root / "registry.json"
            def save_registry():
                tmp = reg.with_suffix(".tmp"); tmp.write_text(json.dumps(registry)); tmp.chmod(0o600); tmp.replace(reg)
            save_registry()
            env = {k: v for k, v in os.environ.items() if not k.startswith("FH_")}
            env.update(FH_MODEL_CATALOG=str(catalog_path), FH_MODEL_REGISTRY=str(reg), FH_MODEL_MODE="CONTROLLED_TEST",
                       FH_BANK_URL=bank.base, FH_BANK_LOGIN="demo", FH_BANK_SECRET="demo123", FH_ACCESS_POLICY=str(policy))
            for name, fixture in (("A", a), ("B", b), ("C", c)):
                env["FH_CONN_" + name + "_URL"] = fixture.base + "/v1"
                env["FH_CONN_" + name + "_KEY"] = "controlled-private-canary-" + name
            with socket.socket() as sock: sock.bind(("127.0.0.1", 0)); port = sock.getsockname()[1]
            with (output / "workbench.log").open("wb") as log:
                child = subprocess.Popen([sys.executable, "-m", "finance_solution_builder.workbench", "--harness-repo",
                    str(args.harness.resolve()), "--port", str(port)], cwd=project, env=env, stdout=log, stderr=subprocess.STDOUT)
                base = "http://127.0.0.1:" + str(port)
                opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
                csrf = ""
                def request(path, body=None):
                    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                        headers={"Content-Type": "application/json", "Origin": base, "X-CSRF-Token": csrf})
                    try: response = opener.open(req, timeout=150)
                    except urllib.error.HTTPError as error: response = error
                    with response: return response.code, json.load(response)
                deadline = time.monotonic() + 20
                while True:
                    if child.poll() is not None or time.monotonic() > deadline: raise AssertionError("START_FAILED")
                    try: _, boot = request("/api/bootstrap"); csrf = boot["csrf"]; break
                    except urllib.error.URLError: time.sleep(.1)
                _, cat = request("/api/v1/catalog")
                check("catalog_no_credentials_or_endpoints", "private-canary" not in json.dumps(cat) and a.base not in json.dumps(cat))
                combinations = (("balance", ["account.balance.query"]), ("wealth", ["wealth.facts.describe"]),
                                ("both", ["account.balance.query", "wealth.facts.describe"]))
                for label, caps in (() if args.browser_only else combinations):
                    grant()
                    body = {"extension_type": "finance-workbench.selection", "schema_version": "0.3",
                        "runtimeId": "openjiuwen-java-0.1.15", "applicationTemplateId": "banking-readonly-web-v1",
                        "presentation": "DESKTOP_WEB", "bankConnectionId": "local-simulator", "capabilityIds": caps,
                        "selectedModelRefs": ["model-a", "model-b"], "defaultModelRef": "model-a", "modelCatalogRevision": cat["modelCatalogRevision"]}
                    if args.template_modules:
                        body.update(schema_version="0.5", scenarioTemplateId="mobile-banking",
                                    moduleIds=["agent.account" if cap == "account.balance.query" else "agent.wealth_aggregate" for cap in caps],
                                    directoryRevision=cat["templateModuleDirectory"]["revision"])
                    elif args.directory:
                        template = {"balance": "customer-account-readonly", "both": "customer-wealth-readonly"}.get(label)
                        body.update(schema_version="0.4", audienceId="CUSTOMER", componentIds=caps,
                                    entryMode="TEMPLATE" if template else "COMPONENTS", scenarioTemplateId=template,
                                    directoryRevision=cat["directory"]["revision"])
                    code, build = request("/api/v1/builds", body); check(label + ":queued", code == 202)
                    route = "/api/v1/builds/" + build["buildId"]
                    deadline = time.monotonic() + 1200
                    while build["buildStatus"] in {"QUEUED", "RUNNING"}:
                        if time.monotonic() > deadline: raise AssertionError("BUILD_TIMEOUT")
                        time.sleep(.5); _, build = request(route)
                    check(label + ":compiled", build["buildStatus"] == "SUCCEEDED")
                    if args.directory or args.template_modules:
                        check(label + ":directory_frozen", build["selection"] == body
                              and build["directorySelection"]["revision"] == cat["templateModuleDirectory" if args.template_modules else "directory"]["revision"]
                              and {t["capabilityId"] for c in build["directorySelection"]["components"] for t in c["tools"]} == set(caps))
                    candidate = project / "build/workbench" / build["buildId"] / "candidate"
                    lock = json.loads((candidate / "service-lock.json").read_text())
                    check(label + ":frozen_models", lock["modelSelection"]["selectedModelRefs"] == ["model-a", "model-b"]
                          and lock["modelSelection"]["defaultModelRef"] == "model-a"
                          and all(digest(candidate / f) == h for f, h in lock["files"].items()))
                    check(label + ":no_secret_in_artifact", all(b"private-canary" not in (candidate / f).read_bytes() for f in lock["files"]))
                    code, started = request(route + "/start", {"artifactId": build["artifact"]["artifactId"]})
                    check(label + ":started", code == 200); iid = started["instance"]["instanceId"]
                    code, opened = request(route + "/sessions", {"instanceId": iid})
                    check(label + ":default_a", code == 200 and opened["result"]["modelRef"] == "model-a")
                    sid = opened["sessionId"]
                    def chat(query): return request(route + "/chat", {"instanceId": iid, "sessionId": sid, "query": query})
                    before_b = len(b.models); before_a = len(a.models)
                    marker = "private-prior-marker-" + label
                    code, turn = chat(marker)
                    check(label + ":a_endpoint_only", code == 200 and len(a.models) > before_a and len(b.models) == before_b
                          and all(x["model"] == "controlled-a" for x in a.models[before_a:]) and turn["result"]["toolResults"])
                    if args.template_modules:
                        runtime_lock = turn["result"]["runtimeLock"]
                        actual = [cap for installation in runtime_lock["installations"] for cap in installation["capabilities"]]
                        expected_tools = {"account_balance_query" if cap == "account.balance.query" else "wealth_facts_describe" for cap in caps}
                        check(label + ":actual_installation_exact", {cap["capabilityId"] for cap in actual} == set(caps)
                              and {cap["operation"] for cap in actual} == {"QUERY"}
                              and {tool["function"]["name"] for req in a.models[before_a:] for tool in req.get("tools", [])} == expected_tools)
                        check(label + ":actual_runtime_matches_selection", runtime_lock["runtime"]["family"] == "openjiuwen"
                              and runtime_lock["runtime"]["templateRef"].endswith(":0.1.15"))
                        write = output / (label + "-runtime-lock.json")
                        write.write_text(json.dumps(runtime_lock, indent=2))
                    old_sid = sid
                    code, switched = request(route + "/switch-model", {"instanceId": iid, "sessionId": sid, "modelRef": "model-b"})
                    check(label + ":fresh_b_session", code == 200 and switched["sessionId"] != sid); sid = switched["sessionId"]
                    before_a = len(a.models)
                    code, turn = chat("new-model-task")
                    check(label + ":b_endpoint_only", code == 200 and len(b.models) > before_b and len(a.models) == before_a
                          and all(x["model"] == "controlled-b" for x in b.models[before_b:]))
                    check(label + ":no_history_transfer", marker not in json.dumps(b.models[before_b:]))
                    counts = (len(a.models), len(b.models), len(c.models), len(bank.reads))
                    code, _ = request(route + "/chat", {"instanceId": iid, "sessionId": old_sid, "query": "stale"})
                    check(label + ":old_session_blocked", code == 409 and counts == (len(a.models), len(b.models), len(c.models), len(bank.reads)))
                    code, _ = request(route + "/switch-model", {"instanceId": iid, "sessionId": sid, "modelRef": "model-c"})
                    check(label + ":unselected_c_not_called", code == 403 and not c.models)
                    ready, release = threading.Event(), threading.Event()
                    bank.pause_source = (ready, release)
                    try:
                        with ThreadPoolExecutor(max_workers=1) as pool:
                            pending = pool.submit(chat, "delayed-data-revocation")
                            try:
                                check(label + ":bank_request_arrived", ready.wait(20))
                                registry["bindings"][1]["enabled"] = False; save_registry()
                                count_b = len(b.models); release.set()
                                code, denied = pending.result(timeout=60)
                                check(label + ":disable_before_next_model_dispatch", code == 403 and len(b.models) == count_b
                                      and denied["clearConversation"])
                            finally: release.set()
                    finally: bank.pause_source = None
                    registry["bindings"][1]["enabled"] = True; save_registry()
                    code, opened = request(route + "/sessions", {"instanceId": iid, "modelRef": "model-a"}); sid = opened.get("sessionId")
                    check(label + ":new_session_after_reenable", code == 200)
                    count_reads = len(bank.reads); counts = (len(a.models), len(b.models))
                    grant(False); code, denied = chat("revoked-user")
                    check(label + ":bank_grant_still_required", code == 403 and len(bank.reads) == count_reads
                          and counts == (len(a.models), len(b.models)))
                    check(label + ":no_business_writes", not bank.writes)
                    code, _ = request(route + "/stop", {"instanceId": iid}); check(label + ":stopped", code == 200)
                    evidence["builds"].append({"composition": label, "buildId": build["buildId"], "artifactId": build["artifact"]["artifactId"],
                                               "candidate": str(candidate.relative_to(project)), "modelSelection": lock["modelSelection"]})
                if args.browser or args.browser_only:
                    grant()
                    before_a, before_b = len(a.models), len(b.models)
                    subprocess.run(["node", str(project / "engineering/verify_studio_ui.cjs"), base,
                                    str(output / "browser"), "controlled"], check=True, timeout=1800)
                    check("browser_actual_both_model_endpoints", len(a.models) > before_a and len(b.models) > before_b)
                    check("browser_switch_does_not_forward_history", "browser-private-first-marker" not in json.dumps(a.models[before_a:]))
                    check("browser_unselected_c_zero", not c.models)
                evidence["modelRequestCounts"] = {"a": len(a.models), "b": len(b.models), "unselectedC": len(c.models)}
    finally:
        stop_owned_process(child)
        for fixture in (bank, a, b, c): fixture.close()
        (output / "report.json").write_text(json.dumps(evidence, indent=2))
    print(json.dumps({"checks": len(evidence["checks"]), "modelRequestCounts": evidence["modelRequestCounts"], "realModel": "NOT_RUN"}))


if __name__ == "__main__": main()
