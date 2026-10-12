"""Native generated-host checks using explicit scripted model + source-contract fixtures.

No real bank/model, no production credentials, no performance or quality claim.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import threading
import time
import uuid
from verify_local_service import Endpoints, expiry, request, has_multiturn_context


def verify(candidate, harness):
    candidate = candidate.resolve()
    fixture = Endpoints()
    fixture.cashflow = {name: json.loads((harness / "conformance/fixtures/cashflow/source" / (name + ".json")).read_text())
                        for name in ("summary", "analysis", "summary-policy", "analysis-policy")}
    policy = candidate / "cashflow-test-policy.json"
    def save_policy(enabled=True, scopes=None):
        value = {"schemaVersion": "local-simulator-access.v0.1", "bankUrl": fixture.base,
                 "subjects": {fixture.customer: {"enabled": enabled, "expiresAt": expiry(),
                              "scopes": ["cashflow:read"] if scopes is None else scopes}}}
        temporary = policy.with_suffix(".tmp")
        temporary.write_text(json.dumps(value)); temporary.chmod(0o600); temporary.replace(policy)
    save_policy()
    token = secrets.token_urlsafe(40)
    env = dict(os.environ, FH_BANK_URL=fixture.base, FH_BANK_LOGIN="demo", FH_BANK_SECRET="synthetic",
               FH_ACCESS_POLICY=str(policy), FH_HOST_TOKEN=token, FH_MODEL_URL=fixture.base + "/v1",
               FH_MODEL_KEY="explicit-fixture-not-secret", FH_MODEL_NAME="explicit-boundary-test",
               FH_MODEL_MODE="CONTROLLED_TEST", FH_PORT="0")
    info = candidate / "instance.json"; info.unlink(missing_ok=True)
    jars = list((candidate / "target").glob("*.jar"))
    assert len(jars) == 1, "ONE_GENERATED_HOST_JAR_REQUIRED"
    log = (candidate / "cashflow-test-host.log").open("wb")
    child = subprocess.Popen(["java", "-Xmx512m", "-cp", str(jars[0]) + os.pathsep + str(candidate / "target/dependency/*"),
                              "io.github.bitlhk.finance.builder.local.LocalAgentHost", str(candidate)],
                             cwd=candidate, env=env, stdout=log, stderr=subprocess.STDOUT)
    evidence = {"model": "CONTROLLED_TEST", "bank": "CONTROLLED_CONTRACT_HTTP", "checks": [],
                "realModel": "NOT_RUN", "fullSimulator": "NOT_RUN", "independentHuman": "PENDING"}
    def check(name, valid):
        if not valid: raise AssertionError(name)
        evidence["checks"].append(name)
    try:
        deadline = time.monotonic() + 30
        while not info.exists():
            if child.poll() is not None or time.monotonic() > deadline: raise AssertionError("HOST_START_FAILED")
            time.sleep(.1)
        instance = json.loads(info.read_text()); base = "http://127.0.0.1:" + str(instance["port"])
        code, health = request(base, token, "/health")
        lock_bytes = (candidate / "service-lock.json").read_bytes()
        check("actual_instance_matches_lock", code == 200 and instance["pid"] == child.pid
              and health["artifactLock"] == json.loads(lock_bytes) and instance["buildId"] == health["buildId"])
        check("cashflow_jar_present", any("finance-harness-cashflow" in p for p in health["artifactLock"]["files"]))
        code, opened = request(base, token, "/sessions", {})
        check("session_authorized", code == 200)
        sid = opened["sessionId"]
        installed = [c for p in opened["installations"] for c in p["capabilities"]]
        check("only_two_cashflow_query_tools", {c["capabilityId"] for c in installed} ==
              {"finance.transaction-summary.query", "finance.spending-analysis.query"}
              and all(c["operation"] == "QUERY" for c in installed))
        turns = []; model_turns = []; marker = "cashflow-turn-" + uuid.uuid4().hex
        for question in ("summary-probe 2026-08 " + marker, "analysis-probe 2026-08 compare the coverage"):
            before = len(fixture.models)
            code, value = request(base, token, f"/sessions/{sid}/chat", {"query": question})
            check("native_cashflow_turn_" + str(len(turns) + 1), code == 200 and bool(value.get("toolResults")))
            turns.append(value); model_turns.append(copy.deepcopy(fixture.models[before:]))
        summary = turns[0]["toolResults"][0]["data"]["facts"]
        analysis = turns[1]["toolResults"][0]["data"]["facts"]
        check("summary_exact_scope", summary["totals"]["netCashflow"] == "8749.50" and summary["currency"] == "CNY"
              and summary["coverage"] == "OWN_BANK_ACCOUNT_STATEMENT"
              and "SOURCE_BOOKED_STATUS_NOT_VERIFIED" in summary["gaps"])
        check("analysis_preserves_different_coverage", analysis["totals"]["netCashflow"] == "8999.50"
              and analysis["trend"][-1]["totals"]["netCashflow"] == "8999.50"
              and analysis["coverage"] == "OWN_BANK_BOOKED_WITH_CATEGORIES")
        check("period_and_comparability_remain_unverified", "PERIOD_COVERAGE_NOT_VERIFIED" in summary["gaps"]
              and {"ANALYSIS_PERIOD_TIMEZONE_NOT_VERIFIED", "TREND_COMPARABILITY_NOT_VERIFIED"} <= set(analysis["gaps"]))
        for turn, facts in zip(turns, (summary, analysis)):
            cards = turn.get("display", {}).get("cards", [])
            check("projected_" + facts["section"], len(cards) == 1 and cards[0]["type"] == "cashflow"
                  and cards[0]["totals"] == facts["totals"] and cards[0]["period"] == "2026-08"
                  and cards[0]["gaps"] == facts["gaps"])
            check("no_private_subject_in_" + facts["section"], "subjectRef" not in json.dumps(cards)
                  and "synthetic-private-account" not in json.dumps(cards))
        check("native_multiturn_history", has_multiturn_context(*model_turns, marker))
        model_payload = json.dumps(fixture.models)
        check("domain_method_actually_loaded", "Credit-card" in model_payload and "classification" in model_payload.lower())
        check("raw_source_fields_not_sent_to_model", "DO_NOT_EXPOSE_RAW" not in model_payload
              and "synthetic-private-account" not in model_payload)
        before = len(fixture.reads)
        request(base, token, f"/sessions/{sid}/chat", {"query": "missing-period-probe"})
        check("missing_period_no_customer_read", len(fixture.reads) == before)
        request(base, token, f"/sessions/{sid}/chat", {"query": "disabled-tool-probe"})
        check("uninstalled_balance_not_callable", len(fixture.reads) == before)
        original_customer = fixture.customer
        fixture.customer = "customer-ungranted"
        code, _ = request(base, token, "/sessions", {})
        check("different_customer_denied_before_business_read", code == 403 and len(fixture.reads) == before)
        fixture.customer = original_customer
        code, fresh = request(base, token, "/sessions", {}); check("fresh_after_disabled_tool", code == 200)
        sid = fresh["sessionId"]
        original = copy.deepcopy(fixture.cashflow)
        before = len(fixture.reads)
        fixture.cashflow["summary-policy"]["policies"][0]["currency"] = "USD"
        # The source policy is authoritative; unsupported currency is not converted by the host.
        code, currency = request(base, token, f"/sessions/{sid}/chat", {"query": "summary-probe 2026-08"})
        check("unsupported_currency_has_no_financial_read", code == 200 and len(fixture.reads) == before)
        fixture.cashflow = copy.deepcopy(original)
        fixture.cashflow["summary"].pop("totalExpense")
        code, missing = request(base, token, f"/sessions/{sid}/chat", {"query": "summary-probe 2026-08"})
        result = missing.get("toolResults", [{}])[0]
        check("missing_not_zero_or_execution_unknown", code == 200 and result.get("status") == "COMPLETED"
              and result["data"]["description"]["completeness"] == "PARTIAL"
              and "expense" not in result["data"]["facts"]["totals"])
        save_policy(False); before = (len(fixture.models), len(fixture.reads))
        code, denied = request(base, token, f"/sessions/{sid}/chat", {"query": "Repeat the previous amount"})
        check("revocation_blocks_model_and_customer_reads", code == 403 and denied.get("clearConversation")
              and before == (len(fixture.models), len(fixture.reads)))
        save_policy(scopes=["account:read"]); before = len(fixture.reads)
        code, _ = request(base, token, "/sessions", {})
        check("pack_does_not_grant_cashflow_scope", code == 403 and len(fixture.reads) == before)
        save_policy(); code, fresh = request(base, token, "/sessions", {})
        check("fresh_for_inflight_revocation", code == 200)
        ready, release = threading.Event(), threading.Event(); fixture.pause_source = (ready, release)
        output = []
        worker = threading.Thread(target=lambda: output.append(request(base, token,
                                  f"/sessions/{fresh['sessionId']}/chat", {"query": "summary-probe 2026-08"})))
        worker.start()
        try:
            check("inflight_bank_request_observed", ready.wait(30))
            before = len(fixture.models); save_policy(False); release.set(); worker.join(30)
            check("revoked_tool_result_not_sent_to_model", not worker.is_alive() and len(output) == 1
                  and output[0][0] == 403 and len(fixture.models) == before)
        finally:
            release.set(); worker.join(30); fixture.pause_source = None
        check("zero_business_mutation_attempts", fixture.writes == [])
        evidence.update(buildId=instance["buildId"], lockSha256=hashlib.sha256(lock_bytes).hexdigest(),
                        turns=turns, businessReads=fixture.reads, policyReads=fixture.metadata_reads,
                        modelRequestCount=len(fixture.models))
        output = candidate / "cashflow-controlled-report.json"
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
        print(json.dumps({"report": str(output), "checks": len(evidence["checks"]), "model": "CONTROLLED_TEST"}))
    except Exception as failure:
        evidence.update(status="FAIL", failureType=type(failure).__name__, failure=str(failure))
        (candidate / "cashflow-controlled-report.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
        raise
    finally:
        child.terminate()
        try: child.wait(15)
        except subprocess.TimeoutExpired: child.kill(); child.wait()
        log.close(); fixture.close(); policy.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("candidate", type=Path); parser.add_argument("--harness", type=Path, required=True)
    args = parser.parse_args(); verify(args.candidate, args.harness)
