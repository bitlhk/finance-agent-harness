"""Fixed packaged Host + real managed model + loopback synthetic bank; no build or SSH."""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from agentscope_host_checks import host, java, read_jsonl
from verify_agentscope_host import digest, read, write

SUBJECT = "host-live-synthetic-customer"
CAPS = {"balance": "account.balance.query", "summary": "finance.transaction-summary.query",
        "analysis": "finance.spending-analysis.query"}
CASES = [
    ("balance", [("\u8bf7\u67e5\u8be2\u6211\u672c\u4eba\u7684\u8d26\u6237\u4f59\u989d\uff0c\u8bf4\u660e\u5e01\u79cd\u548c\u6570\u636e\u6765\u6e90\u3002", {CAPS["balance"]}, None)]),
    ("compound", [("\u8bf7\u6c47\u603b2026\u5e748\u6708\u7684\u6536\u5165\u548c\u652f\u51fa\uff0c\u518d\u770b\u4e3b\u8981\u82b1\u5728\u54ea\u4e9b\u7c7b\u522b\u3002", {CAPS["summary"], CAPS["analysis"]}, "2026-08")]),
    ("clarification", [("\u67e5\u4e00\u4e0b\u516b\u6708\u7684\u6536\u652f\u6c47\u603b\u3002", set(), None),
                       ("2026\u5e74\u3002\u53ea\u8981\u6536\u652f\u6c47\u603b\u3002", {CAPS["summary"]}, "2026-08")]),
    ("multi-turn", [("\u53ea\u6c47\u603b2026\u5e748\u6708\u7684\u6536\u652f\uff0c\u4e0d\u7528\u5206\u6790\u5206\u7c7b\u3002", {CAPS["summary"]}, "2026-08"),
                    ("\u6539\u4e3a2026\u5e747\u6708\uff0c\u4ecd\u7136\u53ea\u8981\u6536\u652f\u6c47\u603b\u3002", {CAPS["summary"]}, "2026-07")]),
]


class SyntheticBank:
    """Only fixture HTTP; business mapping remains in the unchanged Provider JAR."""
    def __init__(self, harness):
        self.rows, self.sessions = [], {}
        bank = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self): self.respond()
            def do_POST(self): self.respond()
            def do_DELETE(self): self.respond()

            def respond(self):
                self.rfile.read(int(self.headers.get("Content-Length", 0)))
                parsed = urllib.parse.urlsplit(self.path)
                route, status = parsed.path, 200
                business = route.startswith(("/api/v1/accounts", "/api/v1/finance/"))
                row = {"method": self.command, "path": self.path, "requestId": self.headers.get("X-Request-Id", ""),
                       "startedAtNs": time.time_ns(), "business": business, "readiness": route.endswith("/readiness")}
                bank.rows.append(row)
                if route == "/health":
                    answer = {"service": "non-ai-banking", "providerMode": "simulator"}
                elif route == "/api/v1/sessions" and self.command == "POST":
                    token = "synthetic-" + secrets.token_hex(16)
                    state = {"sessionId": token, "customerRef": SUBJECT,
                             "expiresAt": (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()}
                    bank.sessions[token] = state
                    answer = {"status": "SUCCESS", "data": state}
                elif route == "/api/v1/sessions/current":
                    token = self.headers.get("X-Session-Id")
                    if self.command == "DELETE":
                        bank.sessions.pop(token, None)
                        answer = {"status": "SUCCESS", "data": {}}
                    elif token not in bank.sessions:
                        status, answer = 401, {"status": "FAILED"}
                    else:
                        answer = {"status": "SUCCESS", "data": bank.sessions[token]}
                elif self.command != "GET":
                    status, answer = 405, {"error": "READ_ONLY"}
                elif route in {"/api/v1/accounts", "/api/v1/accounts/fixture-account"}:
                    account = {"accountId": "fixture-account", "currency": "CNY", "availableBalance": "12345.67"}
                    answer = {"status": "SUCCESS", "sourceSystem": "controlled-bank", "asOf": "2026-09-20T00:00:00Z",
                              "data": {"accounts": [account]} if route == "/api/v1/accounts" else account}
                elif route.startswith("/api/v1/finance/"):
                    name = "analysis" if "spending-analysis" in route else "summary"
                    policy = route.endswith("/readiness")
                    data = read(harness / f"conformance/fixtures/cashflow/source/{name}{'-policy' if policy else ''}.json")
                    if not policy:
                        period = urllib.parse.parse_qs(parsed.query).get("period", ["2026-08"])[0]
                        year, month = map(int, period.split("-"))
                        data["period"] = period
                        for index, item in enumerate(data.get("trend", [])):
                            ordinal = year * 12 + month - 1 - (5 - index)
                            item["period"] = f"{ordinal // 12:04d}-{ordinal % 12 + 1:02d}"
                    answer = data if policy else {"status": "SUCCESS", "data": data,
                                                  "sourceSystem": "controlled-cashflow", "asOf": "2026-09-01T01:00:00Z"}
                else:
                    status, answer = 404, {"error": "FIXTURE_ROUTE_UNKNOWN"}
                row.update(status=status, finishedAtNs=time.time_ns())
                if business: row["response"] = answer
                payload = json.dumps(answer).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                try: self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError): pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def model_configuration(args):
    env = os.environ.copy()
    if args.model_env:
        for line in args.model_env.read_text().splitlines():
            if not line.strip() or line.lstrip().startswith("#"): continue
            key, separator, value = line.partition("=")
            if not separator or not key.strip().startswith("FH_"):
                raise ValueError("MODEL_ENV_FORMAT_INVALID")
            words = shlex.split(value)
            if len(words) != 1: raise ValueError("MODEL_ENV_FORMAT_INVALID")
            env[key.strip()] = words[0]
    registry = read(args.model_registry)
    bindings = [b for b in registry["bindings"] if b["modelRef"] == args.model_ref]
    if registry.get("schemaVersion") != "finance-model-bindings.v1" or len(bindings) != 1:
        raise ValueError("MANAGED_MODEL_BINDING_INVALID")
    binding = bindings[0]
    if not binding["enabled"] or binding["protocol"] != "OPENAI_CHAT_COMPLETIONS":
        raise ValueError("MANAGED_MODEL_DISABLED_OR_PROTOCOL_UNSUPPORTED")
    base, key = env.get(binding["urlEnvironment"]), env.get(binding["keyEnvironment"])
    if not base or not key: raise ValueError("LOCAL_MANAGED_MODEL_CREDENTIALS_REQUIRED")
    uri = urllib.parse.urlsplit(base)
    if uri.scheme != "https" or not uri.hostname or uri.username or uri.query or uri.fragment:
        raise ValueError("LIVE_MANAGED_HTTPS_BASE_REQUIRED")
    # Pass the exact managed value used by ModelConnections; never repair it only in tests.
    if uri.path.rstrip("/").endswith("/chat/completions"):
        raise ValueError("LIVE_MANAGED_BASE_URL_REQUIRED")
    checksum = hashlib.sha256()
    fields = ("modelRef", "revision", "protocol", "provider", "modelName", "urlEnvironment", "keyEnvironment")
    for value in [str(binding[k]) for k in fields] + [base]:
        encoded = value.encode()
        checksum.update(str(len(encoded)).encode() + b":" + encoded)
    version = "sha256:" + checksum.hexdigest()
    registry = {"schemaVersion": "finance-model-bindings.v1",
                "bindings": [{name: binding[name] for name in (*fields, "enabled")}]}
    selection = {"schemaVersion": "finance-model-selection.v1", "selectedModelRefs": [args.model_ref],
                 "defaultModelRef": args.model_ref, "connections": [{"modelRef": args.model_ref,
                 "provider": binding["provider"], "modelName": binding["modelName"], "configVersion": version}]}
    metadata = {"modelRef": args.model_ref, "revision": binding["revision"], "modelName": binding["modelName"],
                "endpointDigest": hashlib.sha256(base.encode()).hexdigest(), "configVersion": version,
                "endpointPathNormalized": False, "credentialsPersisted": False,
                "sourceRegistrySha256": digest(args.model_registry)}
    return env, registry, selection, metadata, key


def prepare(args, output, selection):
    source = args.candidate.resolve()
    lock = read(source / "service-lock.json")
    for name, expected in lock["files"].items():
        file = source / name
        if not file.resolve().is_relative_to(source) or file.is_symlink() or digest(file) != expected:
            raise ValueError("FIXED_CANDIDATE_FILE_MISMATCH")
    target = output / "candidate"
    shutil.copytree(source, target, ignore=shutil.ignore_patterns("runtime-evidence", "instance.json", "component-installation-plan.json"))
    lock["files"].pop("component-installation-plan.json", None)
    lock.pop("componentAdmission", None)
    write(target / "model-selection.json", selection)
    ui = read(target / "ui-config.json") if (target / "ui-config.json").exists() else {}
    ui["sessionPolicy"] = {"idleSeconds": 900, "warningSeconds": 30, "absoluteSeconds": 3600}
    write(target / "ui-config.json", ui)
    lock["modelSelection"] = selection
    for name in ("model-selection.json", "ui-config.json"): lock["files"][name] = digest(target / name)
    write(target / "service-lock.json", lock)
    write(output / "artifact-projection.json", {"sourceLockSha256": digest(source / "service-lock.json"),
          "runLockSha256": digest(target / "service-lock.json"), "jarBytesChanged": False,
          "configurationChanges": ["model-selection.json", "ui-config.json", "regenerated-installation-plan"],
          "hostTemplateDigest": lock["hostTemplateDigest"],
          "jars": {k: v for k, v in lock["files"].items() if k.endswith(".jar")}})
    return target


def request(url, token, body=None):
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try: response = urllib.request.urlopen(req, timeout=240)
    except urllib.error.HTTPError as failure: response = failure
    with response: return response.status, json.loads(response.read())


def inspect_turn(output, name, result, expected, period, bank_rows):
    executions = result.get("executions", [])
    checks = {"coverage": {e["request"]["capabilityId"] for e in executions} == expected,
              "nativeCompletions": all(e["result"]["status"] == "COMPLETED" for e in executions),
              "parameters": all(e["request"]["parameters"] == ({} if period is None else {"period": period}) for e in executions),
              "uniqueChildIds": len({e["request"]["context"]["requestId"] for e in executions}) == len(executions),
              "uniqueToolIds": len({e["toolCallId"] for e in executions}) == len(executions),
              "snapshotCallback": result.get("diagnosticFailures") == 0,
              "liveMode": result.get("evidenceKind") == "CONFIGURED_MODEL_WITH_LOCAL_BANK_SIMULATOR"}
    reads = [r for r in bank_rows if r["business"] and not r["readiness"]]
    checks["bankReads"] = len(reads) == len(executions)
    for index, execution in enumerate(executions):
        cap, value, receipt = execution["request"]["capabilityId"], execution["result"], execution["receipt"]
        request_id = execution["request"]["context"]["requestId"]
        checks[f"receipt-{index}"] = receipt["requestId"] == request_id == value["requestId"] and any(r["requestId"] == request_id for r in reads)
        checks[f"source-{index}"] = receipt["sourceSystem"] == value["sourceSystem"] == ("controlled-bank" if cap == CAPS["balance"] else "controlled-cashflow")
        if cap == CAPS["balance"]:
            checks[f"facts-{index}"] = value["data"]["accounts"][0]["availableBalance"] == "12345.67"
        else:
            checks[f"period-{index}"] = value["data"]["facts"]["period"] == period
            facts = value["data"]["facts"]
            expected_totals = {"income": "10000.00", "expense": "1250.50", "netCashflow": "8749.50"} if cap == CAPS["summary"] else {
                "income": "10000.00", "expense": "1000.50", "netCashflow": "8999.50"}
            checks[f"totals-{index}"] = facts["totals"] == expected_totals
            checks[f"gaps-{index}"] = len(facts["gaps"]) > 0 and value["data"]["description"]["completeness"] == "PARTIAL"
            checks[f"source-version-{index}"] = facts["source"]["recordVersion"] == (
                "financial-summary-v2" if cap == CAPS["summary"] else "spending-analysis-v1")
    report = {"status": "PASS" if all(checks.values()) else "FAIL", "checks": checks,
              "nativeToolCalls": len(executions), "extraNativeToolCalls": len(executions) - len(expected),
              "bankCustomerReads": len(reads), "sdkTransportRetries": "CHECK_TRACE_NOT_INFERRED_FROM_TOOL_REPEATS",
              "answerProfessionalReview": "PENDING", "response": result.get("response", "")}
    write(output / f"{name}-checks.json", report)
    return report


def inspect_archive(directory, outputs, secret):
    checks, rows, snapshots = {}, {}, []
    for file in sorted(directory.glob("*.jsonl")):
        records = read_jsonl(file)
        rows[file.stem] = records
        events = [r for r in records if "spanId" in r and "phase" in r]
        starts = {r["spanId"] for r in events if r["phase"] == "START"}
        ends = {r["spanId"] for r in events if r["phase"] == "END"}
        checks[file.stem + ":parent"] = all(not r.get("parentSpanId") or r["parentSpanId"] in starts for r in events)
        checks[file.stem + ":closed"] = starts == ends
        checks[file.stem + ":terminal"] = sum(r.get("recordType") == "terminal" for r in records) == 1
        for index, row in enumerate(records):
            if row.get("recordType") == "model-snapshot-storage":
                checks[file.stem + ":storage:" + row["callId"] + row["part"]] = row["state"] == "WRITTEN" and any(
                    r.get("recordType") == "model-snapshot" and all(r.get(k) == row.get(k) for k in ("callId", "part", "digest"))
                    for r in records[:index])
    all_events = [r for records in rows.values() for r in records if "phase" in r]
    model_spans = {r["spanId"] for r in all_events if r["component"] == "model" and r["phase"] == "START"}
    transports = [r for r in all_events if r["component"] == "model-http" and r["phase"] == "END"]
    for file in sorted((directory / "model-snapshots").glob("*.json")):
        body = read(file)
        snapshots.append(body)
        refs = [r for r in rows.get(body["requestId"], []) if r.get("recordType") == "model-snapshot"
                and all(r.get(k) == body.get(k) for k in ("callId", "part"))]
        checks[file.name + ":correlation"] = body["spanId"] in model_spans and body["dispatch"] == "NOT_OBSERVED"
        checks[file.name + ":digest"] = len(refs) == 1 and refs[0].get("digest") == digest(file)
        checks[file.name + ":redaction"] = secret not in file.read_text() and SUBJECT not in file.read_text()
    checks["snapshot-pairs"] = len(snapshots) == 2 * len(model_spans) and len(model_spans) > 0
    checks["model-http"] = len(transports) == len(model_spans) and all(r["outcome"] == "RETURNED" and r["links"].get("httpStatus") == "200" for r in transports)
    checks["actual-turns"] = all(r.get("executionObservation", {}).get("requestId") in rows for r in outputs)
    return {"status": "PASS" if all(checks.values()) else "FAIL", "checks": checks, "requestCount": len(rows),
            "modelCalls": len(model_spans), "clientTransportExchanges": len(transports), "snapshotBodies": len(snapshots),
            "dispatchCoverage": "CHECKED_CLIENT_TRANSPORT_INVOCATION_NOT_REMOTE_ACCEPTANCE"}


def run(args):
    os.umask(0o077)
    env, registry, selection, metadata, secret = model_configuration(args)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    bank, results, replies = None, [], []
    try:
        candidate = prepare(args, output, selection)
        write(output / "model-binding.json", registry)
        write(output / "model-identity.json", metadata)
        native = args.native_bundle.resolve() / "qualification/agentscope-observation-qualification.json"
        token = secrets.token_urlsafe(40)
        bank = SyntheticBank(args.harness.resolve())
        write(output / "access-policy.json", {"schemaVersion": "local-simulator-access.v0.1", "bankUrl": bank.url,
              "subjects": {SUBJECT: {"enabled": True, "expiresAt": (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat(),
              "scopes": ["account:read", "cashflow:read"]}}})
        env.update(FH_MODEL_MODE="LIVE", FH_PORT="0", FH_HOST_TOKEN=token, FH_BANK_URL=bank.url,
                   FH_BANK_LOGIN="synthetic", FH_BANK_SECRET="synthetic-only", FH_ACCESS_POLICY=str(output / "access-policy.json"),
                   FH_HISTORY_DIR=str(output / "history"), FH_HISTORY_OWNER="host-live-review", FH_SNAPSHOTS_ENABLED="true",
                   FH_SNAPSHOT_CAPTURE_OWNERS="host-live-review", FH_MODEL_REGISTRY=str(output / "model-binding.json"),
                   FH_MODEL_TIMEOUT_SECONDS=str(args.model_timeout), FH_BUSINESS_DATE="2026-09-20", FH_BUSINESS_TIME_ZONE="Asia/Shanghai",
                   FH_AGENTSCOPE_QUALIFICATION_FILE=str(native), FH_AGENTSCOPE_QUALIFICATION_SHA256="sha256:" + digest(native),
                   FH_OBSERVATION_QUALIFICATION_FILE=str(args.host_qualification.resolve()),
                   FH_OBSERVATION_QUALIFICATION_SHA256=digest(args.host_qualification))
        env.pop("FH_EXPECTED_LOCK", None)
        admission = java(candidate, "AgentScopeAdmission", env, output / "admission.json")
        if admission.returncode != 0: raise RuntimeError("FIXED_HOST_ADMISSION_FAILED")
        if bank.rows: raise RuntimeError("ADMISSION_BANK_SIDE_EFFECT")
        if args.prepare_only:
            write(output / "summary.json", {"status": "PREPARED_ONLY", "modelInvocations": 0,
                  "mode": "LIVE_NOT_RUN", "model": metadata})
            return
        with host(candidate, env, output) as (url, _):
            for case, turns in CASES:
                status, session = request(url + "/sessions", token, {"modelRef": args.model_ref})
                write(output / f"{case}-session.json", {"status": status, "response": session})
                if status != 200:
                    results.append({"case": case, "status": "FAIL", "reason": "SESSION_OPEN_FAILED"})
                    continue
                for index, (query, expected, period) in enumerate(turns):
                    name = f"{case}-{index + 1}"
                    before, started = len(bank.rows), time.monotonic()
                    status, reply = request(url + f"/sessions/{session['sessionId']}/chat", token, {"query": query, "runId": name})
                    write(output / f"{name}-result.json", {"httpStatus": status, "query": query,
                          "elapsedSeconds": time.monotonic() - started, "response": reply})
                    if status != 200:
                        results.append({"case": name, "status": "FAIL", "httpStatus": status})
                        break
                    replies.append(reply)
                    result = inspect_turn(output, name, reply, expected, period, bank.rows[before:])
                    results.append({"case": name, **result})
                    print(json.dumps({"case": name, "status": result["status"], "nativeCalls": result["nativeToolCalls"]}), flush=True)
                status, snapshot = request(url + f"/sessions/{session['sessionId']}/snapshot", token, {})
                write(output / f"{case}-snapshot.json", {"httpStatus": status, "response": snapshot})
                successful = sum(r.get("case", "").startswith(case + "-") and r.get("httpStatus", 200) == 200 for r in results)
                if status != 200 or len(snapshot.get("messages", [])) != successful * 2:
                    results.append({"case": case + "-history", "status": "FAIL", "reason": "HOST_DISPLAY_HISTORY_MISMATCH"})
                request(url + f"/sessions/{session['sessionId']}/revoke", token, {})
        archive = inspect_archive(output / "history", replies, secret)
        write(output / "trace-snapshot-checks.json", archive)
        passed = len(results) == 6 and all(r["status"] == "PASS" for r in results) and archive["status"] == "PASS"
        write(output / "summary.json", {"status": "PASS" if passed else "FAIL", "mode": "REAL_MANAGED_MODEL_SYNTHETIC_LOCAL_BANK",
              "entrypoint": "LocalAgentHost", "isolatedCliUsed": False, "model": metadata, "modelTimeoutSeconds": args.model_timeout,
              "nativeQualificationSha256": digest(native), "hostQualificationSha256": digest(args.host_qualification),
              "cases": results, "trace": archive, "professionalReview": "PENDING", "automaticReruns": 0})
        if not passed: raise RuntimeError("LIVE_TECHNICAL_CHECKS_FAILED")
    except Exception as failure:
        write(output / "RUN-FAILED.json", {"status": "FAIL", "type": type(failure).__name__,
              "message": str(failure).replace(secret, "[REDACTED]"), "completedCases": results})
        raise
    finally:
        if bank:
            write(output / "bank-http.json", bank.rows)
            bank.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--harness", type=Path, required=True)
    parser.add_argument("--native-bundle", type=Path, required=True)
    parser.add_argument("--host-qualification", type=Path, required=True)
    parser.add_argument("--model-registry", type=Path, required=True)
    parser.add_argument("--model-ref", required=True)
    parser.add_argument("--model-env", type=Path)
    parser.add_argument("--model-timeout", type=int, choices=range(1, 61), default=45)
    parser.add_argument("--prepare-only", action="store_true")
    run(parser.parse_args())
