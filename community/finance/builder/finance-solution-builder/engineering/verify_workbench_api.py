"""Real HTTP builds and generated hosts, using only explicit local synthetic endpoints."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

from finance_solution_builder.service import digest, stop_owned_process
from finance_solution_builder.workbench_contracts import default_selection
from verify_local_service import Endpoints, expiry, has_multiturn_context


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    evidence = {"mode": "EXPLICIT_CONTROLLED_HTTP_MODEL_AND_BANK", "realModel": "NOT_RUN",
                "fullSimulator": "NOT_RUN", "humanOnboarding": "NOT_RUN", "builds": [], "checks": []}
    fixture = Endpoints()
    child = None
    def check(name, passed):
        if not passed: raise AssertionError(name)
        evidence["checks"].append(name)
    try:
        with tempfile.TemporaryDirectory(prefix="finance-api-host-") as directory:
            policy = Path(directory) / "policy.json"
            def grant(enabled=True):
                policy.write_text(json.dumps({"schemaVersion": "local-simulator-access.v0.1", "bankUrl": fixture.base,
                    "subjects": {fixture.customer: {"enabled": enabled, "expiresAt": expiry(),
                    "scopes": ["wealth:facts:read", "wealth:customer:read", "account:read"]}}}))
                policy.chmod(0o600)
            grant()
            with socket.socket() as available:
                available.bind(("127.0.0.1", 0)); port = available.getsockname()[1]
            env = {key: value for key, value in os.environ.items() if not key.startswith("FH_")}
            env.update(PYTHONPATH=str(project / "src"), FH_MODEL_URL=fixture.base + "/v1",
                       FH_MODEL_NAME="explicit-api-regression", FH_MODEL_KEY="explicit-test-not-a-key",
                       FH_MODEL_MODE="CONTROLLED_TEST", FH_BANK_URL=fixture.base, FH_BANK_LOGIN="demo",
                       FH_BANK_SECRET="demo123", FH_ACCESS_POLICY=str(policy))
            with (output / "workbench.log").open("wb") as log:
                child = subprocess.Popen([sys.executable, "-m", "finance_solution_builder.workbench", "--harness-repo",
                                          str(args.harness.resolve()), "--port", str(port)], cwd=project, env=env, stdout=log, stderr=subprocess.STDOUT)
                base = "http://127.0.0.1:" + str(port)
                opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
                csrf = ""
                def request(path, body=None):
                    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                        headers={"Content-Type": "application/json", "X-CSRF-Token": csrf, "Origin": base})
                    try: response = opener.open(req, timeout=140)
                    except urllib.error.HTTPError as error: response = error
                    with response: return response.code, json.load(response)
                deadline = time.monotonic() + 15
                while True:
                    if child.poll() is not None or time.monotonic() > deadline: raise AssertionError("API_START_FAILED")
                    try:
                        code, boot = request("/api/bootstrap"); csrf = boot["csrf"]; break
                    except urllib.error.URLError: time.sleep(.1)
                code, catalog = request("/api/v1/catalog")
                check("finite_catalog", code == 200 and len(catalog["allowedCapabilitySets"]) == 3)
                (output / "catalog.json").write_text(json.dumps(catalog, indent=2))
                for label, capabilities in (("balance", ["account.balance.query"]), ("wealth", ["wealth.facts.describe"]),
                                            ("both", ["wealth.facts.describe", "account.balance.query"])):
                    grant()
                    body = default_selection(capabilities)
                    code, preflight = request("/api/v1/preflight", body)
                    check(label + ":preflight_without_network", code == 200 and preflight["canBuild"] and preflight["networkRequests"] == 0)
                    code, build = request("/api/v1/builds", body)
                    check(label + ":actual_build_queued", code == 202)
                    key = build["buildId"]; route = "/api/v1/builds/" + key
                    deadline = time.monotonic() + 1200
                    while build["buildStatus"] in {"QUEUED", "RUNNING"}:
                        if time.monotonic() > deadline: raise AssertionError("BUILD_TIMEOUT")
                        time.sleep(.5); code, build = request(route)
                    check(label + ":artifact_not_instance_ready", build["buildStatus"] == "SUCCEEDED"
                          and build["instance"]["status"] == "NOT_STARTED"
                          and all(stage["status"] == "SUCCEEDED" for stage in build["stages"]))
                    artifact = build["artifact"]["artifactId"]
                    candidate = project / "build/workbench" / key / "candidate"
                    lock = json.loads((candidate / "service-lock.json").read_text())
                    check(label + ":lock_and_all_file_hashes", artifact == "sha256:" + digest(candidate / "service-lock.json")
                          and all(digest(candidate / path) == sha for path, sha in lock["files"].items()))
                    code, started = request(route + "/start", {"artifactId": artifact})
                    check(label + ":instance_ready", code == 200 and started["instance"]["status"] == "READY")
                    iid = started["instance"]["instanceId"]
                    code, opened = request(route + "/sessions", {"instanceId": iid})
                    check(label + ":authorized_session", code == 200 and bool(opened.get("sessionId")))
                    sid = opened["sessionId"]
                    installed = {cap["capabilityId"] for pack in opened["result"]["installations"] for cap in pack["capabilities"]}
                    check(label + ":actual_installations", installed == set(capabilities))
                    histories = []; run_ids = []
                    for turn in range(2):
                        before = len(fixture.models)
                        code, answer = request(route + "/chat", {"instanceId": iid, "sessionId": sid,
                            "query": "Describe my banking facts api-marker-" + key if turn == 0 else "Which facts were missing?"})
                        check(label + ":controlled_turn_" + str(turn), code == 200 and bool(answer.get("result", {}).get("toolResults"))
                              and answer["result"]["evidenceKind"] == "EXPLICIT_TEST_MODEL")
                        histories.append(fixture.models[before:]); run_ids.append(answer["runId"])
                    check(label + ":multiturn_context", has_multiturn_context(*histories, "api-marker-" + key))
                    check(label + ":distinct_run_ids", run_ids[0] != run_ids[1])
                    if label == "both":
                        code, balance = request(route + "/chat", {"instanceId": iid, "sessionId": sid, "query": "balance-probe"})
                        check(label + ":second_selected_tool_executes", code == 200 and bool(balance["result"]["toolResults"]))
                    reads = len(fixture.reads); models = len(fixture.models)
                    grant(False)
                    code, rejected = request(route + "/chat", {"instanceId": iid, "sessionId": sid, "query": "Repeat my prior balance"})
                    check(label + ":revocation_blocks_source_and_model", code == 403 and rejected["clearConversation"]
                          and "result" not in rejected and len(fixture.reads) == reads and len(fixture.models) == models)
                    code, stopped = request(route + "/stop", {"instanceId": iid})
                    check(label + ":stop_preserves_artifact", code == 200 and stopped["instance"]["status"] == "STOPPED"
                          and stopped["artifact"]["artifactId"] == artifact and stopped["buildStatus"] == "SUCCEEDED")
                    grant()
                    code, restarted = request(route + "/start", {"artifactId": artifact})
                    new_iid = restarted["instance"]["instanceId"]
                    check(label + ":restart_new_instance_no_session", code == 200 and new_iid != iid and restarted["instance"]["sessionId"] is None)
                    code, stale = request(route + "/stop", {"instanceId": iid})
                    check(label + ":old_tab_cannot_stop_new_instance", code == 409 and stale["error"]["code"] == "INSTANCE_CHANGED")
                    code, final = request(route + "/stop", {"instanceId": new_iid})
                    check(label + ":final_stop", code == 200 and final["instance"]["status"] == "STOPPED")
                    evidence["builds"].append({"composition": label, "buildId": key, "artifactId": artifact,
                        "candidate": "build/workbench/" + key + "/candidate", "sources": lock["sources"],
                        "runIds": run_ids, "instanceIds": [iid, new_iid]})
                    (output / (label + "-build.json")).write_text(json.dumps(build, indent=2))
                    print(label + ": PASS " + key, flush=True)
                check("no_business_writes", fixture.writes == [])
                evidence["sourceReadCount"] = len(fixture.reads)
                evidence["controlledModelRequestCount"] = len(fixture.models)
                evidence["status"] = "PASS"
    except Exception as error:
        evidence["status"] = "FAIL"; evidence["failureClass"] = type(error).__name__
        raise
    finally:
        stop_owned_process(child)
        fixture.close()
        (output / "report.json").write_text(json.dumps(evidence, indent=2))


if __name__ == "__main__": main()
