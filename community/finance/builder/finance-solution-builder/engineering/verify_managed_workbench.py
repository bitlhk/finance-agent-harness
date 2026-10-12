"""Isolated browser -> real managed build. Controlled model/bank, never live credentials."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

from playwright.sync_api import sync_playwright
from verify_local_service import Endpoints, expiry
from unified_browser_selection import blank_selection, select_pack, assert_same_packages


def verify(harness, output, maven, chromium):
    output.mkdir(parents=True, exist_ok=False)
    endpoint = Endpoints()
    project = Path(__file__).resolve().parents[1]
    def save(name, value):
        path = output / name
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2))
        return str(path)
    catalog = save("model-catalog.json", {"schemaVersion": "finance-model-catalog.v1",
        "source": {"kind": "CONTROLLED_TEST"}, "models": [{"modelRef": "model-probe", "displayName": "Controlled probe",
            "provider": "OpenAI", "modelName": "explicit-boundary-test", "eaCatalogId": "probe", "eaConfigured": False}]})
    registry = save("model-bindings.json", {"schemaVersion": "finance-model-bindings.v1", "bindings": [{
        "modelRef": "model-probe", "revision": "v1", "enabled": True, "protocol": "OPENAI_CHAT_COMPLETIONS",
        "provider": "OpenAI", "modelName": "explicit-boundary-test", "urlEnvironment": "FH_CONN_PROBE_URL", "keyEnvironment": "FH_CONN_PROBE_KEY"}]})
    policy = save("access.json", {"schemaVersion": "local-simulator-access.v0.1", "bankUrl": endpoint.base,
        "subjects": {endpoint.customer: {"enabled": True, "expiresAt": expiry(),
            "scopes": ["account:read", "wealth:customer:read", "wealth:facts:read"]}}})
    env = dict(os.environ, FH_MODEL_CATALOG=catalog, FH_MODEL_REGISTRY=registry,
        FH_CONN_PROBE_URL=endpoint.base + "/v1", FH_CONN_PROBE_KEY="controlled-not-a-key", FH_MODEL_MODE="CONTROLLED_TEST",
        FH_BANK_URL=endpoint.base, FH_BANK_LOGIN="demo", FH_BANK_SECRET="demo123", FH_ACCESS_POLICY=policy,
        PYTHONPATH=str(project / "src") + os.pathsep + str(project.parent / "base/src"))
    log_path = output / "workbench.log"
    child = None
    try:
        with log_path.open("wb") as log:
            child = subprocess.Popen([sys.executable, "-m", "finance_solution_builder.workbench", "--harness-repo", str(harness),
                "--port", "0", "--data-dir", str(output / "jobs"), "--maven", maven], cwd=project, env=env, stdout=log, stderr=log)
        deadline = time.monotonic() + 30
        base = None
        while time.monotonic() < deadline:
            match = re.search(r"http://127\.0\.0\.1:\d+", log_path.read_text())
            if match: base = match.group(); break
            if child.poll() is not None: raise AssertionError("workbench failed to start")
            time.sleep(.1)
        assert base, "workbench startup timed out"
        with sync_playwright() as p:
            browser = p.chromium.launch(executable_path=chromium, headless=True)
            try:
                page = browser.new_page(viewport={"width": 1440, "height": 1000})
                errors = []
                page.on("pageerror", lambda error: errors.append(str(error)))
                page.goto(base)
                page.wait_for_timeout(2000)
                page.screenshot(path=str(output / "initial.png"), full_page=True)
                save("initial.json", {"text": page.locator("body").inner_text(), "errors": errors})
                catalog_value = page.request.get(base + "/api/v1/catalog").json()
                assert_same_packages(catalog_value)
                blank_selection(page)
                select_pack(page, catalog_value, 'wealth-customer-context')
                for terminal in ("ATM_WEB", "DESKTOP_WEB", "MOBILE_WEB"):
                    page.locator(f'[data-template="{terminal}"]').click()
                    assert page.locator('[data-action="build"]').is_enabled()
                page.locator('[data-runtime="openjiuwen-python-0.1.18"]').check()
                assert page.locator('[data-action="build"]').is_disabled()
                assert page.locator('#selected-method').count() == 1
                page.locator('[data-runtime="openjiuwen-java-0.1.15"]').check()
                page.screenshot(path=str(output / "managed-desktop.png"), full_page=True)
                page.set_viewport_size({"width": 390, "height": 844})
                page.screenshot(path=str(output / "managed-mobile.png"), full_page=True)
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth"), "horizontal overflow"
                page.set_viewport_size({"width": 1440, "height": 1000})
                with page.expect_response(lambda r: r.url.endswith("/api/v1/builds") and r.request.method == "POST", timeout=30000) as response:
                    page.locator('[data-action="build"]').click()
                result = response.value.json()
                assert response.value.status == 202, result
                assert response.value.request.post_data_json["schema_version"] == "0.9"
                assert [b['pack']['id'] for b in response.value.request.post_data_json['packBindings']] == ['wealth-customer-context']
                build_id = result["buildId"]
                deadline = time.monotonic() + 900
                while time.monotonic() < deadline:
                    job = page.request.get(base + "/api/v1/builds/" + build_id).json()
                    record = output / "jobs/workbench-records" / (build_id + ".json")
                    if record.exists() and json.loads(record.read_text())["status"] in ("BUILT", "FAILED"):
                        break
                    if job.get("status") in ("BUILT", "FAILED"): break
                    # The public projection exposes buildState rather than the internal status.
                    report = output / "jobs/workbench" / build_id / "build-report.json"
                    if report.exists() and json.loads(report.read_text()).get("status") in ("BUILT", "FAILED"):
                        break
                    time.sleep(1)
                save("browser-build.json", job)
                assert not errors, errors
                candidate = output / "jobs/workbench" / build_id / "candidate"
                assert list((candidate / "target").glob("*.jar")), "candidate not compiled"
                def post(action, body):
                    return page.evaluate("""async ({path,body})=>{
                      const boot=await (await fetch('/api/bootstrap')).json();
                      const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':boot.csrf},body:JSON.stringify(body)});
                      return {status:r.status,body:await r.json()};
                    }""", {"path": "/api/v1/builds/" + build_id + "/" + action, "body": body})
                started = post("start", {"artifactId": job["artifact"]["artifactId"]})
                save("start.json", started)
                assert started["status"] == 200, started
                instance = started["body"]["instance"]["instanceId"]
                try:
                    opened = post("sessions", {"instanceId": instance})
                    save("session.json", opened)
                    assert opened["status"] == 200, opened
                    session = opened["body"]["sessionId"]
                    turns = []
                    for question in ("Describe my banking facts", "Explain those facts again"):
                        turn = post("chat", {"instanceId": instance, "sessionId": session, "query": question})
                        turns.append(turn)
                        assert turn["status"] == 200, turn
                    save("turns.json", turns)
                    assert len(endpoint.models) >= 4
                    assert any("1000000.12" in json.dumps(m) for m in endpoint.models)
                    assert any("wealth_customer_context_query" in json.dumps(m.get("tools", [])) for m in endpoint.models)
                    assert not any("account_balance_query" in json.dumps(m.get("tools", [])) for m in endpoint.models)
                    assert endpoint.reads and not endpoint.writes
                    before = (len(endpoint.reads), len(endpoint.models))
                    revoked = post("revoke", {"instanceId": instance, "sessionId": session})
                    assert revoked["status"] == 200, revoked
                    rejected = post("chat", {"instanceId": instance, "sessionId": session, "query": "Repeat private amount"})
                    assert (rejected["status"] in (403, 409) and rejected["body"]["clearConversation"]
                            and rejected["body"]["error"]["code"] in ("AUTHORIZATION_REJECTED", "INSTANCE_CHANGED")
                            and (len(endpoint.reads), len(endpoint.models)) == before), rejected
                    save("source-evidence.json", {"reads": endpoint.reads, "writes": endpoint.writes,
                         "modelRequests": endpoint.models, "revoked": rejected})
                finally:
                    stopped = post("stop", {"instanceId": instance})
                    save("stop.json", stopped)
                    assert stopped["status"] == 200, stopped
                save("result.json", {"browserBuild": "PASS", "candidate": str(candidate), "uiErrors": errors,
                    "model": "CONTROLLED_TEST", "realModel": "NOT_RUN", "humanOnboarding": "NOT_RUN"})
                print(json.dumps({"status": "PASS", "output": str(output)}), flush=True)
            finally:
                browser.close()
    finally:
        if child is not None and child.poll() is None:
            child.send_signal(2)
            try: child.wait(timeout=30)
            except subprocess.TimeoutExpired: child.kill(); child.wait()
        endpoint.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--chromium", required=True)
    args = parser.parse_args()
    verify(args.harness.resolve(), args.output.resolve(), args.maven, args.chromium)
