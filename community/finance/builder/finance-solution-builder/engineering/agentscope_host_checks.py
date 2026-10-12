"""Real packaged AgentScope Host checks using only loopback controlled HTTP fixtures."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import hashlib
import os
from pathlib import Path
import queue
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
import zipfile
import xml.etree.ElementTree as ET

from verify_agentscope_host import digest, read, write

PACKAGE = "io.github.bitlhk.finance.builder.local."
TOKEN = "controlled-host-token-" + "x" * 48
SUBJECT = "fixture-customer-private"
MODEL_KEY = "fixture-model-private-key"
HOST_HTTP = []


def expect(condition, name):
    if not condition:
        raise AssertionError(name)


def wait_for(predicate, label, timeout=15):
    limit = time.monotonic() + timeout
    while time.monotonic() < limit:
        value = predicate()
        if value:
            return value
        time.sleep(0.02)
    raise AssertionError("WAIT_TIMEOUT:" + label)


def http(url, body=None, token=TOKEN, timeout=30):
    request = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                     headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        response = urllib.request.urlopen(request, timeout=timeout)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        raw = response.read().decode()
        result = json.loads(raw) if raw.startswith("{") else raw
        HOST_HTTP.append({"url": url, "request": body, "status": response.status, "response": result})
        return response.status, result


def completion(text=None, tool=None, arguments=None):
    message = {"role": "assistant", "content": text or "", "reasoning_content": "PRIVATE_REASONING_MUST_NOT_ARCHIVE"}
    if tool:
        message["tool_calls"] = [{"id": "call-" + uuid.uuid4().hex, "type": "function",
                                  "function": {"name": tool, "arguments": json.dumps(arguments or {})}}]
    return {"id": "controlled-" + uuid.uuid4().hex, "object": "chat.completion", "created": 1,
            "model": "controlled-host", "choices": [{"index": 0, "message": message,
            "finish_reason": "tool_calls" if tool else "stop"}], "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}


class Fixtures:
    def __init__(self, harness):
        self.harness = harness
        self.model_requests, self.bank_requests = [], []
        self.answers = queue.Queue()
        self.sessions = {}
        self.account_status = 200
        self.bank_barrier = None
        self.revoked = threading.Event()
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.handle_request()

            def do_POST(self):
                self.handle_request()

            def do_DELETE(self):
                self.handle_request()

            def handle_request(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                route = self.path.split("?", 1)[0]
                status = 200
                if self.server.kind == "model":
                    fixture.model_requests.append({"path": self.path, "body": json.loads(body), "at": time.time_ns()})
                    try:
                        status, answer, entered, release = fixture.answers.get(timeout=15)
                        if entered:
                            entered.set()
                            if not release.wait(20):
                                raise TimeoutError("MODEL_BARRIER_TIMEOUT")
                    except Exception:
                        status, answer = 500, {"error": "CONTROLLED_MODEL_QUEUE_EMPTY"}
                else:
                    fixture.bank_requests.append({"method": self.command, "path": self.path,
                                                  "requestId": self.headers.get("X-Request-Id", ""), "at": time.time_ns()})
                    if route == "/health":
                        answer = {"service": "non-ai-banking", "providerMode": "simulator"}
                    elif route == "/api/v1/sessions" and self.command == "POST":
                        token = "fixture-bank-" + uuid.uuid4().hex
                        state = {"sessionId": token, "customerRef": SUBJECT,
                                 "expiresAt": (datetime.now(timezone.utc) + timedelta(minutes=15)).isoformat()}
                        fixture.sessions[token] = state
                        answer = {"status": "SUCCESS", "data": state}
                    elif route == "/api/v1/sessions/current":
                        token = self.headers.get("X-Session-Id")
                        state = fixture.sessions.get(token)
                        if self.command == "DELETE":
                            fixture.sessions.pop(token, None)
                            fixture.revoked.set()
                            answer = {"status": "SUCCESS", "data": {}}
                        elif state is None:
                            status, answer = 401, {"status": "FAILED"}
                        else:
                            answer = {"status": "SUCCESS", "data": state}
                    elif route.startswith("/api/v1/accounts"):
                        status = fixture.account_status
                        answer = {"status": "SUCCESS", "sourceSystem": "controlled-bank", "asOf": "2026-09-20T00:00:00Z",
                                  "data": {"accounts": [{"accountId": "fixture-account", "currency": "CNY", "availableBalance": "12345.67"}]}}
                        if status != 200:
                            answer = {"error": {"code": "BANK_SESSION_DENIED", "message": "Source session rejected"}}
                        if fixture.bank_barrier:
                            entered, release = fixture.bank_barrier
                            entered.set()
                            release.wait(20)
                    elif route.startswith("/api/v1/finance/"):
                        name = "analysis" if "spending-analysis" in route else "summary"
                        policy = route.endswith("/readiness")
                        data = read(harness / f"conformance/fixtures/cashflow/source/{name}{'-policy' if policy else ''}.json")
                        answer = data if policy else {"status": "SUCCESS", "data": data, "sourceSystem": "controlled-cashflow", "asOf": "2026-09-01T01:00:00Z"}
                    else:
                        status, answer = 404, {"error": "CONTROLLED_ROUTE_UNKNOWN"}
                data = json.dumps(answer).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                try:
                    self.wfile.write(data)
                except (BrokenPipeError, ConnectionResetError):
                    pass

        self.servers = []
        for kind in ("bank", "model"):
            server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
            server.kind = kind
            server.daemon_threads = True
            threading.Thread(target=server.serve_forever, daemon=True).start()
            self.servers.append(server)
            setattr(self, kind + "_url", f"http://127.0.0.1:{server.server_port}")

    def enqueue(self, answer, *, status=200, block=False):
        entered, release = (threading.Event(), threading.Event()) if block else (None, None)
        self.answers.put((status, answer, entered, release))
        return entered, release

    def close(self):
        for server in self.servers:
            server.shutdown()
            server.server_close()


def classpath(candidate):
    jars = list((candidate / "target").glob("*.jar"))
    expect(len(jars) == 1, "HOST_JAR_COUNT")
    return str(jars[0]) + os.pathsep + str(candidate / "target/dependency/*")


def java(candidate, name, env, log):
    result = subprocess.run(["java", "-cp", classpath(candidate), PACKAGE + name, str(candidate)],
                            env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    write(log, {"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
    return result


def relock(candidate, files):
    lock = read(candidate / "service-lock.json")
    for name in files:
        lock["files"][name] = digest(candidate / name)
    write(candidate / "service-lock.json", lock)


@contextmanager
def host(candidate, env, output):
    instance = candidate / "instance.json"
    if instance.exists():
        instance.unlink()
    with (output / "host.log").open("w") as log:
        child = subprocess.Popen(["java", "-Xmx512m", "-cp", classpath(candidate), PACKAGE + "LocalAgentHost", str(candidate)],
                                 env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            def ready():
                if child.poll() is not None:
                    raise AssertionError("HOST_START_FAILED:" + str(child.returncode))
                return read(instance) if instance.exists() else None
            state = wait_for(ready, "host-ready")
            yield f"http://127.0.0.1:{state['port']}", state
        finally:
            child.terminate()
            try:
                child.wait(timeout=15)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=5)


def verify(candidate, harness, native, output):
    output.mkdir(parents=True, exist_ok=False)
    HOST_HTTP.clear()
    fixture = Fixtures(harness)
    evidence = {}
    env = os.environ.copy()
    for key in ("FH_OBSERVATION_QUALIFICATION_FILE", "FH_OBSERVATION_QUALIFICATION_SHA256", "FH_EXPECTED_LOCK"):
        env.pop(key, None)
    env.update(FH_HOST_TOKEN=TOKEN, FH_BANK_URL=fixture.bank_url, FH_BANK_LOGIN="fixture", FH_BANK_SECRET="fixture-bank-secret",
               FH_ACCESS_POLICY=str(output / "access-policy.json"), FH_MODEL_MODE="CONTROLLED_TEST", FH_PORT="0",
               FH_MODEL_URL=fixture.model_url, FH_MODEL_NAME="controlled-host", FH_MODEL_KEY=MODEL_KEY,
               FH_HISTORY_DIR=str(output / "history"), FH_HISTORY_OWNER="controlled-owner",
               FH_SNAPSHOTS_ENABLED="true", FH_SNAPSHOT_CAPTURE_OWNERS="controlled-owner", FH_BUSINESS_DATE="2026-09-20",
               FH_AGENTSCOPE_QUALIFICATION_FILE=str(native / "qualification/agentscope-observation-qualification.json"),
               FH_AGENTSCOPE_QUALIFICATION_SHA256=(native / "qualification/qualification.sha256").read_text().strip())
    policy = {"schemaVersion": "local-simulator-access.v0.1", "bankUrl": fixture.bank_url, "subjects": {
        SUBJECT: {"enabled": True, "expiresAt": (datetime.now(timezone.utc) + timedelta(minutes=15)).isoformat(),
                  "scopes": ["account:read", "cashflow:read"]}}}
    write(Path(env["FH_ACCESS_POLICY"]), policy)
    binding = {"modelRef": "model-controlled", "revision": "v1", "enabled": True, "protocol": "OPENAI_CHAT_COMPLETIONS",
               "provider": "OpenAI", "modelName": "controlled-host", "urlEnvironment": "FH_CONN_CONTROLLED_URL", "keyEnvironment": "FH_CONN_CONTROLLED_KEY"}
    model_digest = hashlib.sha256()
    for value in [binding[key] for key in ("modelRef", "revision", "protocol", "provider", "modelName", "urlEnvironment", "keyEnvironment")] + [fixture.model_url]:
        data = value.encode()
        model_digest.update(str(len(data)).encode() + b":" + data)
    selection = {"schemaVersion": "finance-model-selection.v1", "selectedModelRefs": [binding["modelRef"]],
                 "defaultModelRef": binding["modelRef"], "connections": [{"modelRef": binding["modelRef"], "provider": "OpenAI",
                 "modelName": binding["modelName"], "configVersion": "sha256:" + model_digest.hexdigest()}]}
    registry = {"schemaVersion": "finance-model-bindings.v1", "bindings": [binding]}
    write(output / "model-registry.json", registry)
    env.update(FH_MODEL_REGISTRY=str(output / "model-registry.json"), FH_CONN_CONTROLLED_URL=fixture.model_url, FH_CONN_CONTROLLED_KEY=MODEL_KEY)
    write(candidate / "model-selection.json", selection)
    lock = read(candidate / "service-lock.json")
    lock["modelSelection"] = selection
    write(candidate / "service-lock.json", lock)
    relock(candidate, ["model-selection.json"])

    def mark(name, value):
        path = output / f"{name}.json"
        write(path, {"check": name, "passed": True, **value})
        evidence[name] = path

    def open_session(url, **body):
        status, result = http(url + "/sessions", body)
        expect(status == 200, "SESSION_OPEN:" + str(result))
        return result["sessionId"]

    try:
        admission = java(candidate, "AgentScopeAdmission", env, output / "admission.json")
        expect(admission.returncode == 0, "ADMISSION_FAILED")
        expect(len(fixture.model_requests) == len(fixture.bank_requests) == 0, "ADMISSION_NETWORK_SIDE_EFFECT")
        value = json.loads(admission.stdout)
        expect(value["installation"] == value["modelInvocation"] == "NOT_OBSERVED", "PREVIEW_CLAIM")
        repeat = java(candidate, "AgentScopeAdmission", env, output / "admission-second-jvm.json")
        expect(repeat.returncode == 0 and json.loads(repeat.stdout) == value, "ADMISSION_REPEAT_CHANGED_PLAN")
        expect(len(fixture.model_requests) == len(fixture.bank_requests) == 0, "REPEATED_ADMISSION_NETWORK_SIDE_EFFECT")
        mark("artifact-admission", {"admission": value, "secondJvmAdmission": json.loads(repeat.stdout)})
        for jar in (candidate / "target/dependency").glob("*.jar"):
            with zipfile.ZipFile(jar) as archive:
                expect(not any(name.startswith("com/openjiuwen/") for name in archive.namelist()), "OJ_ENGINE_CLASSES")
        mark("no-oj-runtime", {"jars": {p.name: digest(p) for p in (candidate / "target/dependency").glob("*.jar")}})
        with host(candidate, env, output) as (url, instance):
            status, _ = http(url + "/health", token="wrong-owner-token")
            expect(status == 401, "HOST_TOKEN_REQUIRED")
            status, _ = http(url + "/sessions", {"customerRef": "forged"})
            expect(status == 400, "HOST_OWNS_IDENTITY")
            first = open_session(url)
            second = open_session(url)
            fixture.enqueue(completion(tool="account_balance_query"))
            fixture.enqueue(completion("Balance 12345.67 CNY, controlled-bank."))
            status, reply = http(url + f"/sessions/{first}/chat", {"query": "Read balance. private marker " + MODEL_KEY, "runId": "balance"})
            expect(status == 200 and "12345.67" in reply["response"], "NATIVE_BALANCE")
            expect(len(reply["executions"]) == 1 and reply["executions"][0]["result"]["status"] == "COMPLETED", "ORIGINAL_RESULT")
            expect(reply["display"]["cards"][0]["type"] == "balance", "SHARED_DISPLAY")
            expect(reply["diagnosticFailures"] == 0, "SNAPSHOT_CALLBACK_FAILURE")
            expect(len(fixture.model_requests) == 2, "NATIVE_TWO_MODEL_HTTP")
            expect(all(not row["body"].get("stream", False) for row in fixture.model_requests), "NON_STREAM")
            mark("native-http", {"reply": reply, "modelHttp": fixture.model_requests[:], "bankHttp": fixture.bank_requests[:]})
            _, snapshot = http(url + f"/sessions/{first}/snapshot", {})
            _, other = http(url + f"/sessions/{second}/snapshot", {})
            expect(len(snapshot["messages"]) == 2 and other["messages"] == [], "SESSION_DISPLAY_ISOLATION")
            fixture.enqueue(completion("Second isolated session."))
            status, _ = http(url + f"/sessions/{second}/chat", {"query": "Hello isolated session", "runId": "isolated"})
            expect(status == 200 and MODEL_KEY not in json.dumps(fixture.model_requests[-1]["body"]), "NATIVE_MEMORY_ISOLATION")
            mark("history-isolation", {"first": snapshot, "secondBefore": other, "secondModel": fixture.model_requests[-1]})
            mark("owner-auth", {"badTokenStatus": 401, "identityOverrideStatus": 400})
            status, _ = http(url + f"/sessions/{first}/tasks", {})
            expect(status == 400, "TASK_ROUTE_DENIED")
            mark("task-denial", {"status": status, "installedTools": [x["toolName"] for group in reply["runtimeLock"]["installations"] for x in group["capabilities"]]})

            cancelled = open_session(url)
            entered, release = fixture.enqueue(completion("CANCELLED_SECRET_MUST_NOT_RETURN"), block=True)
            with ThreadPoolExecutor(max_workers=2) as pool:
                pending = pool.submit(http, url + f"/sessions/{cancelled}/chat", {"query": "cancelled-context-marker", "runId": "cancelled"})
                expect(entered.wait(10), "CANCEL_MODEL_ENTERED")
                status, cancellation = http(url + f"/sessions/{cancelled}/cancel", {"runId": "cancelled"})
                expect(status == 200 and cancellation["status"] == "CANCEL_REQUESTED", "CANCEL_SIGNAL")
                release.set()
                terminal = pending.result(timeout=25)
            expect(terminal[0] == 409 and "CANCELLED_SECRET" not in json.dumps(terminal), "CANCEL_FINAL_DELIVERY")
            time.sleep(0.15)
            _, empty = http(url + f"/sessions/{cancelled}/snapshot", {})
            expect(empty["messages"] == [], "CANCEL_HISTORY_CLEARED")
            fixture.enqueue(completion("Fresh generation."))
            status, fresh = http(url + f"/sessions/{cancelled}/chat", {"query": "fresh-context-marker", "runId": "fresh"})
            expect(status == 200 and fresh["generation"] == 2, "CANCEL_NEW_GENERATION")
            expect("cancelled-context-marker" not in json.dumps(fixture.model_requests[-1]["body"]), "CANCEL_MEMORY_CLEARED")
            mark("cancel-generation", {"cancellation": cancellation, "terminal": terminal, "snapshot": empty, "fresh": fresh,
                                        "modelHttp": fixture.model_requests[-2:]})

            agui = open_session(url)
            entered, release = fixture.enqueue(completion("Buffered authorized AGUI answer."), block=True)
            body = {"threadId": agui, "runId": "agui", "messages": [{"id": "user-agui", "role": "user", "content": "Hello"}],
                    "tools": [], "context": [], "state": {}, "forwardedProps": {"instanceId": instance["instanceId"]}}
            request = urllib.request.Request(url + f"/sessions/{agui}/ag-ui", data=json.dumps(body).encode(),
                                             headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"})
            response = urllib.request.urlopen(request, timeout=30)
            prefix = response.readline().decode() + response.readline().decode()
            prefix += response.readline().decode() + response.readline().decode()
            expect("RUN_STARTED" in prefix and "TEXT_MESSAGE" not in prefix, "AGUI_PREMATURE_BODY")
            expect(entered.wait(10), "AGUI_MODEL_ENTERED")
            release.set()
            suffix = response.read().decode()
            response.close()
            expect("TEXT_MESSAGE_CONTENT" in suffix and "RUN_FINISHED" in suffix, "AGUI_COMPLETE")
            mark("ag-ui-buffering", {"beforeRelease": prefix, "afterRelease": suffix})

            revoked = open_session(url)
            entered, release = fixture.enqueue(completion("REVOKED_SECRET_MUST_NOT_RETURN"), block=True)
            before = len(fixture.model_requests)
            fixture.revoked.clear()
            with ThreadPoolExecutor(max_workers=2) as pool:
                pending = pool.submit(http, url + f"/sessions/{revoked}/chat", {"query": "revoke me", "runId": "revoked"})
                expect(entered.wait(10), "REVOKE_MODEL_ENTERED")
                revoke_call = pool.submit(http, url + f"/sessions/{revoked}/revoke", {})
                expect(fixture.revoked.wait(10), "REVOKE_BANK_SESSION")
                release.set()
                terminal = pending.result(timeout=25)
                revoke_result = revoke_call.result(timeout=25)
            expect(terminal[0] != 200 and "REVOKED_SECRET" not in json.dumps(terminal), "REVOKE_DELIVERY")
            expect(len(fixture.model_requests) == before + 1, "NO_MODEL_AFTER_REVOKE")
            status, gone = http(url + f"/sessions/{revoked}/snapshot", {})
            expect(status == 403, "REVOKED_HISTORY_NOT_READABLE")
            mark("revoke-before-delivery", {"terminal": terminal, "revoke": revoke_result, "snapshot": gone, "modelDelta": 1})

            # Source 401 poisons the real native loop, not just the outer response.
            bad_bank = open_session(url)
            fixture.account_status = 401
            fixture.enqueue(completion(tool="account_balance_query"))
            before = len(fixture.model_requests)
            status, bank_failure = http(url + f"/sessions/{bad_bank}/chat", {"query": "Read balance", "runId": "bank401"})
            fixture.account_status = 200
            expect(status != 200 and len(fixture.model_requests) == before + 1, "BANK401_NO_FOLLOWUP_MODEL")
            mark("bank401-host", {"status": status, "result": bank_failure, "modelDelta": 1})

            changed = open_session(url)
            binding["revision"] = "v2"
            write(output / "model-registry.json", registry)
            before = len(fixture.model_requests)
            status, denied = http(url + f"/sessions/{changed}/chat", {"query": "Should not dispatch", "runId": "changed"})
            expect(status == 403 and len(fixture.model_requests) == before, "MODEL_CONFIGURATION_GATE")
            binding["revision"] = "v1"
            write(output / "model-registry.json", registry)
            mark("model-revision", {"status": status, "result": denied, "modelDelta": 0})

        # Inspect durable body/metadata only after the Host has drained its real queue.
        histories = [read_jsonl(path) for path in (output / "history").glob("*.jsonl")]
        events = [item for rows in histories for item in rows if "spanId" in item and "phase" in item]
        starts = {item["spanId"] for item in events if item["phase"] == "START"}
        expect(all(not item.get("parentSpanId") or item["parentSpanId"] in starts for item in events), "ORPHAN_PARENT")
        expect(all(any(row.get("recordType") == "terminal" for row in rows) for rows in histories), "MISSING_HOST_TERMINAL")
        mark("archive-parent-terminal", {"requestCount": len(histories), "eventCount": len(events), "histories": histories})
        bodies = [read(path) for path in (output / "history/model-snapshots").glob("*.json")]
        expect(len(bodies) >= 4, "ACTUAL_SNAPSHOT_BODIES")
        model_spans = {item["spanId"] for item in events if item["component"] == "model"}
        expect(all(item["spanId"] in model_spans for item in bodies), "SNAPSHOT_REAL_MODEL_SPAN")
        expect(all(item["dispatch"] == "NOT_OBSERVED" for item in bodies), "SNAPSHOT_NOT_HTTP_CLAIM")
        text = json.dumps(bodies)
        expect(MODEL_KEY not in text and SUBJECT not in text and "PRIVATE_REASONING_MUST_NOT_ARCHIVE" not in text, "SNAPSHOT_REDACTION")
        mark("snapshot-correlation", {"bodies": bodies, "modelSpanIds": sorted(model_spans)})
        mark("snapshot-redaction", {"bodyCount": len(bodies), "privateMarkersAbsent": True})
        for rows in histories:
            for index, row in enumerate(rows):
                if row.get("recordType") == "model-snapshot-storage":
                    expect(any(previous.get("recordType") == "model-snapshot" and previous.get("callId") == row["callId"]
                               and previous.get("part") == row["part"] and previous.get("digest") == row["digest"]
                               for previous in rows[:index]), "BODY_BEFORE_DURABLE_REFERENCE")
        mark("snapshot-reference-before-body", {"requestsChecked": len(histories)})

        no_capture = output / "permission"
        no_capture.mkdir()
        denied_env = dict(env, FH_HISTORY_DIR=str(no_capture / "history"), FH_SNAPSHOT_CAPTURE_OWNERS="different-owner")
        with host(candidate, denied_env, no_capture) as (url, _):
            selected = open_session(url)
            fixture.enqueue(completion("No diagnostic body permission."))
            status, result = http(url + f"/sessions/{selected}/chat", {"query": "Hello", "runId": "no-capture"})
            expect(status == 200, "OPTIONAL_CAPTURE_MUST_NOT_BLOCK_FINANCE")
        expect(not list((no_capture / "history/model-snapshots").glob("*.json")), "BODY_WITHOUT_OWNER_PERMISSION")
        mark("snapshot-permission", {"status": status, "bodyFiles": 0})

        broken = output / "storage-failure"
        (broken / "history").mkdir(parents=True)
        (broken / "history/model-snapshots").write_text("blocked-directory")
        broken_env = dict(env, FH_HISTORY_DIR=str(broken / "history"))
        with host(candidate, broken_env, broken) as (url, _):
            selected = open_session(url)
            fixture.enqueue(completion("Runtime completed while optional body storage failed."))
            status, result = http(url + f"/sessions/{selected}/chat", {"query": "Hello", "runId": "storage-failure"})
            expect(status == 200, "OPTIONAL_STORAGE_FAILURE_CHANGED_RESULT")
        rows = [row for path in (broken / "history").glob("*.jsonl") for row in read_jsonl(path)]
        expect(any(row.get("state") == "WRITE_FAILED" for row in rows), "STORAGE_FAILURE_UNREPORTED")
        mark("snapshot-storage-failure", {"status": status, "history": rows})

        reports = output / "java-tests"
        shutil.copytree(candidate / "target/surefire-reports", reports)
        suite = ET.parse(reports / ("TEST-" + PACKAGE + "ModelSnapshotsTest.xml")).getroot()
        expect(all(suite.get(key) == "0" for key in ("failures", "errors", "skipped")), "SNAPSHOT_UNIT_FAILURE")
        expect(any(case.get("name") == "freezesRedactsTruncatesAndMarksQueueFailure" for case in suite.findall("testcase")), "QUEUE_FAILURE_CHECK_MISSING")
        mark("snapshot-queue-failure", {"test": "ModelSnapshotsTest.freezesRedactsTruncatesAndMarksQueueFailure",
                                        "junit": "java-tests/" + "TEST-" + PACKAGE + "ModelSnapshotsTest.xml"})

        qualified = output / "qualified-candidate"
        shutil.copytree(candidate, qualified, ignore=shutil.ignore_patterns("runtime-evidence", "instance.json", "component-installation-plan.json"))
        requirements = read(qualified / "assets/host-observation.json")
        for item in requirements["requirements"]:
            item["obligation"] = "REQUIRED"
        write(qualified / "assets/host-observation.json", requirements)
        ref = read(qualified / "observation-reference.json")
        ref["digest"] = "sha256:" + digest(qualified / "assets/host-observation.json")
        write(qualified / "observation-reference.json", ref)
        relock(qualified, ["assets/host-observation.json", "observation-reference.json"])
        rejected = java(qualified, "AgentScopeAdmission", env, output / "unqualified-admission.json")
        expect(rejected.returncode != 0, "REQUIRED_ACCEPTED_WITHOUT_HOST_QUALIFICATION")
        identity_result = java(candidate, "CollectorQualification", env, output / "collector-identities.json")
        expect(identity_result.returncode == 0, "COLLECTOR_IDENTITY_FAILED")
        identities = json.loads(identity_result.stdout)
        fake = {"schemaVersion": "finance-agentscope-host-proof.v0.1", "status": "PASS", "tests": 999,
                "collectorIdentities": identities, "hostTemplateDigest": lock["hostTemplateDigest"],
                "nativeQualification": env["FH_AGENTSCOPE_QUALIFICATION_SHA256"], "checks": []}
        write(output / "count-only-proof.json", fake)
        def envelope(proof):
            return {"schemaVersion": "finance-observation-qualification.v0.1", "collectors": [
                {"collectorId": collector, "identity": identity, "semantics": ["execution.history", "capability.result"]
                 if collector == "local-host-archive" else ["model.client.snapshot"], "passed": True,
                 "evidence": [{"file": proof.name, "sha256": digest(proof)}]} for collector, identity in identities.items()]}
        write(output / "count-only-qualification.json", envelope(output / "count-only-proof.json"))
        fake_env = dict(env, FH_OBSERVATION_QUALIFICATION_FILE=str(output / "count-only-qualification.json"),
                        FH_OBSERVATION_QUALIFICATION_SHA256=digest(output / "count-only-qualification.json"))
        rejected_count = java(qualified, "AgentScopeAdmission", fake_env, output / "count-only-admission.json")
        expect(rejected_count.returncode != 0, "COUNT_ONLY_PROOF_ACCEPTED")
        mark("qualification-denial", {"missingProofExit": rejected.returncode, "countOnlyExit": rejected_count.returncode})

        checks = []
        for name, path in sorted(evidence.items()):
            refs = [{"file": path.relative_to(output).as_posix(), "sha256": digest(path)}]
            if name == "snapshot-queue-failure":
                report = reports / ("TEST-" + PACKAGE + "ModelSnapshotsTest.xml")
                refs.append({"file": report.relative_to(output).as_posix(), "sha256": digest(report)})
            checks.append({"id": name, "passed": True, "evidence": refs})
        source_files = {str(Path(__file__).name): digest(Path(__file__)), "verify_agentscope_host.py": digest(Path(__file__).with_name("verify_agentscope_host.py"))}
        proof = {"schemaVersion": "finance-agentscope-host-proof.v0.1", "status": "PASS", "collectorIdentities": identities,
                 "hostTemplateDigest": lock["hostTemplateDigest"], "nativeQualification": env["FH_AGENTSCOPE_QUALIFICATION_SHA256"],
                 "checks": checks, "verificationSources": source_files,
                 "scope": "CONTROLLED_HOST_COLLECTOR_NOT_PROFESSIONAL_LIVE_ACCEPTANCE"}
        write(output / "hosted-proof.json", proof)
        write(output / "host-qualification.json", envelope(output / "hosted-proof.json"))
        ready_env = dict(env, FH_OBSERVATION_QUALIFICATION_FILE=str(output / "host-qualification.json"),
                         FH_OBSERVATION_QUALIFICATION_SHA256=digest(output / "host-qualification.json"),
                         FH_HISTORY_DIR=str(output / "qualified-history"))
        accepted = java(qualified, "AgentScopeAdmission", ready_env, output / "qualified-admission.json")
        expect(accepted.returncode == 0, "QUALIFIED_HOST_ADMISSION_FAILED")
        expect(json.loads(accepted.stdout)["hostObservationAssessment"]["status"] == "READY", "HOST_REQUIRED_NOT_READY")
        final_run = output / "qualified-jvm"
        final_run.mkdir()
        with host(qualified, ready_env, final_run) as (url, _):
            selected = open_session(url)
            fixture.enqueue(completion(tool="account_balance_query"))
            fixture.enqueue(completion("Qualified Host balance 12345.67 CNY."))
            status, result = http(url + f"/sessions/{selected}/chat", {"query": "Balance", "runId": "qualified-balance"})
            expect(status == 200 and result["diagnosticFailures"] == 0, "QUALIFIED_HOST_NATIVE_QUERY")
        write(output / "qualified-host.json", {"status": "PASS", "response": result})
        write(output / "summary.json", {"status": "PASS", "checks": len(checks), "hostQualificationSha256": digest(output / "host-qualification.json"),
              "nativeQualification": env["FH_AGENTSCOPE_QUALIFICATION_SHA256"], "modelHttp": len(fixture.model_requests),
              "bankHttp": len(fixture.bank_requests), "realModelUsed": False, "qualifiedCandidate": str(qualified)})
        return evidence, env
    finally:
        write(output / "raw-host-http.json", HOST_HTTP)
        write(output / "raw-model-http.json", fixture.model_requests)
        write(output / "raw-bank-http.json", fixture.bank_requests)
        fixture.close()


def read_jsonl(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line]
