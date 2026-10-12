"""Validate the portable Java SSE endpoint using a compiled, locked candidate."""
import json
import os
import secrets
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from ag_ui.core import Event
from pydantic import TypeAdapter
from finance_solution_builder.service import MAIN, digest, stop_owned_process
from verify_local_service import request


def verify(candidate, environment, fixture, grant):
    child = None
    token = secrets.token_urlsafe(40)
    env = dict(environment, FH_HOST_TOKEN=token, FH_EXPECTED_LOCK=digest(candidate / "service-lock.json"), FH_PORT="0")
    checks = []
    def check(name, condition):
        if not condition: raise AssertionError(name)
        checks.append(name)
    with tempfile.TemporaryFile() as log:
        try:
            grant(True)
            (candidate / "instance.json").unlink(missing_ok=True)
            jar = next((candidate / "target").glob("*.jar"))
            child = subprocess.Popen(["java", "-Xmx512m", "-cp", str(jar) + os.pathsep + str(candidate / "target/dependency/*"),
                                      MAIN, str(candidate)], env=env, cwd=candidate, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 40
            while not (candidate / "instance.json").exists():
                if child.poll() is not None or time.monotonic() > deadline: raise AssertionError("NATIVE_START_FAILED")
                time.sleep(.1)
            instance = json.loads((candidate / "instance.json").read_text())
            base = "http://127.0.0.1:" + str(instance["port"])
            code, session = request(base, token, "/sessions", {})
            check("native_session", code == 200)
            sid = session["sessionId"]
            body = dict(threadId=sid, runId="native-run", state={}, tools=[], context=[],
                        forwardedProps={"instanceId": instance["instanceId"]},
                        messages=[dict(id="native-message", role="user", content="native-agui-probe")])
            def run(payload):
                req = urllib.request.Request(base + "/sessions/" + sid + "/ag-ui", data=json.dumps(payload).encode(),
                      headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
                try: response = urllib.request.urlopen(req, timeout=60)
                except urllib.error.HTTPError as error: response = error
                with response:
                    raw = response.read().decode()
                    events = [json.loads(line[6:]) for line in raw.splitlines() if line.startswith("data: ")]
                    for event in events: TypeAdapter(Event).validate_python(event)
                    return response.code, events, raw
            code, events, _ = run(body)
            check("native_official_event_schemas", code == 200 and events[0]["type"] == "RUN_STARTED"
                  and events[-1]["type"] == "RUN_FINISHED" and any(e["type"] == "TEXT_MESSAGE_CONTENT" for e in events))
            before = (len(fixture.reads), len(fixture.models))
            check("native_state_injection_denied", run({**body, "state": {"principal": "other"}})[0] == 400)
            check("native_wrong_instance_denied", run({**body, "forwardedProps": {"instanceId": "other"}})[0] == 400)
            check("native_bad_input_no_calls", before == (len(fixture.reads), len(fixture.models)))
            ready, release = threading.Event(), threading.Event()
            fixture.pause_source = ready, release
            result = []
            worker = threading.Thread(target=lambda: result.append(run({**body, "runId": "native-race"})))
            worker.start()
            try:
                check("native_source_arrived", ready.wait(20))
                calls = len(fixture.models); grant(False); release.set(); worker.join(45)
                check("native_revocation_no_new_model", not worker.is_alive() and len(fixture.models) == calls)
                check("native_revocation_no_financial_events", result[0][1][-1]["type"] == "RUN_ERROR"
                      and "TEXT_MESSAGE_CONTENT" not in result[0][2] and "finance.evidence" not in result[0][2])
            finally:
                release.set(); worker.join(60); fixture.pause_source = None
        finally:
            stop_owned_process(child)
    return checks
