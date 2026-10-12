"""Real generated SDK host; delayed bank HTTP response, explicitly controlled model."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import threading
import time

from verify_local_service import Endpoints, expiry, request


def verify(candidate, output, mode="policy"):
    candidate = candidate.resolve()
    fixture = Endpoints()
    policy = candidate / "race-access-policy.json"
    value = {"schemaVersion": "local-simulator-access.v0.1", "bankUrl": fixture.base, "subjects": {
        fixture.customer: {"enabled": True, "expiresAt": expiry(),
                          "scopes": ["wealth:facts:read", "wealth:customer:read", "account:read"]}}}

    def save():
        temp = policy.with_suffix(".tmp")
        temp.write_text(json.dumps(value)); temp.chmod(0o600); temp.replace(policy)

    save()
    token = secrets.token_urlsafe(40)
    env = dict(os.environ, FH_BANK_URL=fixture.base, FH_BANK_LOGIN="demo", FH_BANK_SECRET="demo123",
               FH_ACCESS_POLICY=str(policy), FH_HOST_TOKEN=token, FH_PORT="0", FH_MODEL_MODE="CONTROLLED_TEST",
               FH_MODEL_URL=fixture.base + "/v1", FH_MODEL_KEY="explicit-test-not-a-key", FH_MODEL_NAME="race-test")
    info = candidate / "instance.json"
    info.unlink(missing_ok=True)
    ready, release = threading.Event(), threading.Event()
    fixture.pause_source = (ready, release)
    result, errors = [], []
    report = {"kind": "CONTROLLED_AUTHORIZATION_TIMING", "mode": mode, "status": "FAIL",
              "lockSha256": hashlib.sha256((candidate / "service-lock.json").read_bytes()).hexdigest()}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.with_suffix(".host.log").open("wb") as log:
        child = subprocess.Popen(["java", "-Xmx512m", "-cp", str(next((candidate / "target").glob("*.jar")))
            + os.pathsep + str(candidate / "target/dependency/*"),
            "io.github.bitlhk.finance.builder.local.LocalAgentHost", str(candidate)], cwd=candidate, env=env,
            stdout=log, stderr=subprocess.STDOUT)
        worker = None
        try:
            deadline = time.monotonic() + 30
            while not info.exists():
                assert child.poll() is None and time.monotonic() < deadline, "HOST_START_FAILED"
                time.sleep(.05)
            instance = json.loads(info.read_text())
            base = "http://127.0.0.1:" + str(instance["port"])
            status, health = request(base, token, "/health")
            assert status == 200 and health["artifactLock"] == json.loads((candidate / "service-lock.json").read_text())
            status, opened = request(base, token, "/sessions", {})
            assert status == 200
            sid = opened["sessionId"]

            def chat():
                try: result.append(request(base, token, f"/sessions/{sid}/chat", {"query": "Describe my banking facts"}))
                except Exception as failure: errors.append(type(failure).__name__)

            worker = threading.Thread(target=chat)
            worker.start()
            assert ready.wait(15), "BANK_REQUEST_NOT_RECEIVED"
            # Wealth dispatches two reads concurrently. Hold BOTH at the bank before revoking,
            # so an already-dispatched second request is not mislabelled as a post-revocation read.
            selected_tool = fixture.models[0]["tools"][0]["function"]["name"]
            expected_reads = 2 if selected_tool == "wealth_facts_describe" else 1
            deadline = time.monotonic() + 5
            while len(fixture.reads) < expected_reads and time.monotonic() < deadline: time.sleep(.01)
            assert len(fixture.reads) == expected_reads, "INFLIGHT_BANK_BARRIER_NOT_REACHED"
            before = len(fixture.models)
            reads = len(fixture.reads)
            assert before > 0 and reads > 0
            if mode == "policy": value["subjects"][fixture.customer]["enabled"] = False; save()
            elif mode == "source": fixture.sessions.clear()
            elif mode == "policy_unavailable": policy.unlink()
            else: raise ValueError("INVALID_MODE")
            release.set(); worker.join(30)
            assert not worker.is_alive() and not errors and len(result) == 1
            after = fixture.models[before:]
            report.update(buildId=instance["buildId"], expectedInflightBankReads=expected_reads, modelRequestsBeforeRevocation=before,
                          modelRequestsAfterRevocation=len(after), businessReadsBeforeRevocation=reads,
                          businessReadsAfterRevocation=len(fixture.reads) - reads, responseStatus=result[0][0],
                          clearConversation=result[0][1].get("clearConversation"),
                          financialPayloadAfterRevocation=any("12345.67" in json.dumps(m) or fixture.customer in json.dumps(m) for m in after))
            assert not after, "MODEL_REQUEST_AFTER_REVOCATION"
            assert result[0][0] == 403 and result[0][1].get("clearConversation"), "AUTHORIZATION_NOT_REJECTED"
            assert len(fixture.reads) == reads, "EXTRA_BUSINESS_READ"
            # Restoring policy must not resurrect the revoked Runtime session/history.
            value["subjects"][fixture.customer]["enabled"] = True; save()
            status, _ = request(base, token, f"/sessions/{sid}/chat", {"query": "Repeat the previous amount"})
            assert status == 403 and len(fixture.models) == before
            status, health = request(base, token, "/health")
            assert status == 200 and health["activeSessions"] == 0
            report.update(status="PASS", sessionCleared=True, restoredPolicyCannotReviveSession=True)
        finally:
            release.set()
            if worker: worker.join(30)
            child.terminate()
            try: child.wait(timeout=15)
            except subprocess.TimeoutExpired: child.kill(); child.wait()
            fixture.close(); policy.unlink(missing_ok=True)
            output.write_text(json.dumps(report, indent=2) + "\n")
            print(json.dumps(report))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mode", choices=["policy", "source", "policy_unavailable"], default="policy")
    args = parser.parse_args()
    verify(args.candidate, args.output, args.mode)
