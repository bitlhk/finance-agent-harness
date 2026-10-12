"""Offline acceptance-scoring regressions. No model, SDK, or product mutation."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from verify_agentscope_workbench import (
    BUSINESS_TIME, SUBJECT, TOOLS, agui_result, score_bank_turn, score_binding,
    score_body, score_pure_view, transport_counts,
)


def sha(value):
    return hashlib.sha256(value.encode()).hexdigest()


def turn_fixture(period="2026-08"):
    cards, rows = [], []
    for section, route, expense, net in (("SUMMARY", "transaction-summary", "1250.50", "8749.50"),
                                        ("ANALYSIS", "spending-analysis", "1000.50", "8999.50")):
        version = "financial-summary-v2" if section == "SUMMARY" else "spending-analysis-v1"
        data = {"period": period, "totalIncome": "10000.00", "totalExpense": expense, "netCashflow": net,
                "summaryVersion" if section == "SUMMARY" else "analysisVersion": version,
                "categories": [], "trend": []}
        source = {"sourceSystem": "controlled-cashflow", "asOf": "2026-09-01T01:00:00Z"}
        rows.append({"method": "GET", "path": "/api/v1/finance/" + route + "?period=" + period,
                     "business": True, "readiness": False, "requestId": section, "status": 200,
                     "response": {"status": "SUCCESS", "data": data, **source}})
        cards.append({"type": "cashflow", "section": section, "currency": "CNY", "period": period,
                      "totals": {"income": "10000.00", "expense": expense, "netCashflow": net},
                      "source": {"system": source["sourceSystem"], "asOf": source["asOf"], "recordVersion": version},
                      "gaps": ["PERIOD_COVERAGE_NOT_VERIFIED", "CREDIT_CARD_BILLS_NOT_ADDED_TO_CASHFLOW"],
                      "categories": [], "trend": []})
    return {"display": {"cards": cards}}, set(TOOLS) - {"account.balance.query"}, rows


def agui_fixture():
    request, started = {"query": "balance", "runId": "run"}, {"sessionId": "session", "instanceId": "instance"}
    result = {"status": "COMPLETED", "runId": "run", "sessionId": "session", "text": "Balance **12345.67 CNY**.",
              "display": {"messageId": "message", "runId": "run", "cards": []},
              "executionObservation": {"requestId": "request"}}
    events = [{"type": "RUN_STARTED", "runId": "run", "threadId": "session"},
              {"type": "TEXT_MESSAGE_START", "messageId": "message"},
              {"type": "TEXT_MESSAGE_CONTENT", "messageId": "message", "delta": result["text"]},
              {"type": "TEXT_MESSAGE_END", "messageId": "message"},
              {"type": "CUSTOM", "name": "finance.display", "value": result},
              {"type": "RUN_FINISHED", "runId": "run", "threadId": "session"}]
    return request, started, result, events


def sse(events):
    return "".join("data: " + json.dumps(e) + "\n\n" for e in events)


class AguiScoringTests(unittest.TestCase):
    def test_actual_event_text_and_markdown_body(self):
        request, started, expected, events = agui_fixture()
        result, _ = agui_result(sse(events), request, started)
        self.assertEqual(expected, result)
        score_body(result["text"], "Balance 12345.67 CNY.", "Balance 12345.67 CNY.")

    def test_reject_header_blank_path_or_different_dom(self):
        for text, dom, rendered in (("Answer", "金融助手 ModelName", "Answer"),
                                   ("Answer", "", "Answer"), ("", "金融助手", "金融助手"),
                                   ("/home/ubuntu/runtime/responses/abc.response.json", "/home/ubuntu/runtime/responses/abc.response.json", "/home/ubuntu/runtime/responses/abc.response.json"),
                                   ("July answer", "August answer", "July answer")):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                score_body(text, dom, rendered)

    def test_reject_mismatched_run_session_message_text_and_incomplete_sse(self):
        for field in ("run", "session", "message", "delta", "display", "request", "unfinished", "error"):
            request, started, _, events = agui_fixture()
            if field == "run": events[-1]["runId"] = "other"
            if field == "session": events[0]["threadId"] = "other"
            if field == "message": events[2]["messageId"] = "other"
            if field == "delta": events[2]["delta"] = "wrong answer"
            if field == "display": events[4]["value"]["runId"] = "other"
            if field == "request": events[4]["value"]["executionObservation"] = {}
            if field == "unfinished": events.pop()
            if field == "error": events.insert(1, {"type": "RUN_ERROR"})
            with self.subTest(field=field), self.assertRaises(AssertionError):
                agui_result(sse(events), request, started)


class BankScoringTests(unittest.TestCase):
    def test_actual_account_detail_contract_and_mismatched_account(self):
        source = {"sourceSystem": "synthetic-bank", "asOf": "2026-09-20T00:00:00Z"}
        data = {"accountId": "synthetic-account", "availableBalance": "123.45", "currency": "CNY"}
        result = {"display": {"cards": [{"type": "balance", "source": source,
                    "rows": [{"currency": "CNY", "amount": "123.45"}]}]}}
        row = {"method": "GET", "path": "/api/v1/accounts/synthetic-account", "requestId": "detail",
               "business": True, "readiness": False, "status": 200,
               "response": {"status": "SUCCESS", **source, "data": data}}
        self.assertEqual(1, score_bank_turn(result, {"account.balance.query"}, None, [row])["businessReads"])
        for invalid in ({"accounts": [data]}, {**data, "accountId": "different-account"}):
            rejected = deepcopy(row)
            rejected["response"]["data"] = invalid
            with self.assertRaisesRegex(AssertionError, "BANK_RESPONSE_ACCOUNT_MISMATCH"):
                score_bank_turn(result, {"account.balance.query"}, None, [rejected])

    def test_august_and_july_actual_http_and_fact_sources(self):
        for month in ("2026-08", "2026-07"):
            result, expected, rows = turn_fixture(month)
            self.assertEqual(2, score_bank_turn(result, expected, month, rows)["businessReads"])

    def test_order_is_reported_not_mandatory_protocol(self):
        result, expected, rows = turn_fixture()
        value = score_bank_turn(result, expected, "2026-08", list(reversed(rows)))
        self.assertEqual("finance.spending-analysis.query", value["httpCapabilityOrder"][0])

    def test_wrong_month_even_when_both_targets_completed(self):
        result, expected, rows = turn_fixture()
        with self.assertRaisesRegex(AssertionError, "HTTP_PARAMETERS"):
            score_bank_turn(result, expected, "2026-07", rows)

    def test_omitted_duplicate_or_extra_http_parameter_rejected(self):
        for query in ("", "?period=2026-08&period=2026-08", "?period=2026-08&customer=other"):
            result, expected, rows = turn_fixture()
            rows[0]["path"] = rows[0]["path"].split("?")[0] + query
            with self.subTest(query=query), self.assertRaisesRegex(AssertionError, "HTTP_PARAMETERS"):
                score_bank_turn(result, expected, "2026-08", rows)

    def test_reject_wrong_amount_period_source_gaps_duplicate_and_missing_target(self):
        for change in ("amount", "period", "source", "gaps", "repeat", "missing", "write"):
            result, expected, rows = turn_fixture()
            if change == "amount": result["display"]["cards"][0]["totals"]["expense"] = "0.00"
            if change == "period": result["display"]["cards"][0]["period"] = "2026-07"
            if change == "source": result["display"]["cards"][0]["source"]["system"] = "invented"
            if change == "gaps": result["display"]["cards"][0]["gaps"] = []
            if change == "repeat": rows.append(deepcopy(rows[0]))
            if change == "missing": rows.pop()
            if change == "write": rows[0]["method"] = "POST"
            with self.subTest(change=change), self.assertRaises(AssertionError):
                score_bank_turn(result, expected, "2026-08", rows)


class BindingScoringTests(unittest.TestCase):
    def fixture(self):
        result, expected, rows = turn_fixture()
        result.update(runId="run", executionObservation={"requestId": "request"})
        started = {"sessionId": "session", "instanceId": "instance"}
        lock = {"solutionId": "build", "runtime": {"family": "agentscope", "language": "java",
                "templateRef": "maven://io.agentscope:agentscope-core:2.0.3"}, "installations": [{
                "integrationId": "agentscope-java", "capabilities": [{"capabilityId": c, "toolName": TOOLS[c],
                "operation": "QUERY", "providerId": "non-ai-banking-reference"} for c in sorted(expected)]}]}
        lock_raw, service_raw = json.dumps(lock).encode(), b'{"service":"fixed"}'
        detail = {"requestId": "request", "buildId": "build", "instanceId": "instance", "processBootId": "boot",
                  "sessionRef": sha("session"), "subjectRef": sha(SUBJECT), "modelContext": {"clientRunRef": sha("run")},
                  "complete": True, "status": "COMPLETED", "businessResults": [
                      {"capabilityId": c, "businessStatus": "COMPLETED"} for c in expected],
                  "versions": {kind: {"verified": True, "digest": hashlib.sha256(raw).hexdigest()}
                               for kind, raw in (("runtime", lock_raw), ("service", service_raw))},
                  "spans": [{"component": "provider-http", "dispatch": "SENT", "links": {
                      "harnessRequestId": row["requestId"], "route": row["path"].split("?")[0]}} for row in rows]}
        return detail, result, expected, "build", started, lock_raw, service_raw, rows

    def test_current_lock_session_request_and_provider_link(self):
        score_binding(*self.fixture())

    def test_wrong_frozen_or_current_identity_rejected(self):
        for field in ("requestId", "buildId", "instanceId", "processBootId", "sessionRef", "subjectRef"):
            args = self.fixture()
            args[0][field] = "" if field == "processBootId" else "other"
            with self.subTest(field=field), self.assertRaisesRegex(AssertionError, "CURRENT_IDENTITY"):
                score_binding(*args)

    def test_old_runtime_task_extra_installation_and_wrong_http_link_rejected(self):
        for change in ("runtime", "task", "extra", "digest", "link"):
            args = list(self.fixture())
            lock = json.loads(args[5])
            if change == "runtime": lock["runtime"]["family"] = "openjiuwen"
            if change == "task": lock["installations"][0]["capabilities"][0]["operation"] = "TASK"
            if change == "extra": lock["installations"][0]["capabilities"].append(deepcopy(lock["installations"][0]["capabilities"][0]))
            if change == "digest": args[0]["versions"]["runtime"]["digest"] = "other"
            if change == "link": args[0]["spans"][0]["links"]["harnessRequestId"] = "other-turn"
            if change in {"runtime", "task", "extra"}:
                args[5] = json.dumps(lock).encode()
                args[0]["versions"]["runtime"]["digest"] = hashlib.sha256(args[5]).hexdigest()
            with self.subTest(change=change), self.assertRaises(AssertionError):
                score_binding(*args)


class TransportAndDateTests(unittest.TestCase):
    def test_actual_jobs_strict_date_source_contract(self):
        from finance_solution_builder.workbench import trusted_business_time_environment
        self.assertEqual(BUSINESS_TIME, trusted_business_time_environment(BUSINESS_TIME))
        wrong = dict(BUSINESS_TIME)
        wrong["FH_BUSINESS_TIME_SOURCE"] = wrong.pop("FH_BUSINESS_DATE_SOURCE")
        with self.assertRaisesRegex(ValueError, "CONFIGURATION_INCOMPLETE"):
            trusted_business_time_environment(wrong)

    def test_sent_signal_and_end_count_once_no_remote_claim(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            rows = [{"component": "model-http", "dispatch": "SENT", "phase": phase,
                     "requestId": "r", "processBootId": "boot", "spanId": "s"} for phase in ("SIGNAL", "END")]
            path = root / "r.jsonl"
            path.write_text("".join(json.dumps(row) + "\n" for row in rows))
            before = {"bankBusiness": 2, "model": transport_counts(root)}
            self.assertEqual(1, before["model"]["sentAttempts"])
            self.assertEqual("NOT_OBSERVED", before["model"]["remoteServerCount"])
            score_pure_view(before, deepcopy(before))
            for field in ("bank", "model"):
                after = deepcopy(before)
                if field == "bank": after["bankBusiness"] += 1
                else: after["model"]["sentAttempts"] += 1
                with self.subTest(field=field), self.assertRaises(AssertionError): score_pure_view(before, after)
            path.write_text(json.dumps(rows[0]))
            with self.assertRaisesRegex(AssertionError, "PARTIAL_LINE"): transport_counts(root)

    def test_absent_model_evidence_cannot_prove_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            before = {"bankBusiness": 2, "model": transport_counts(Path(tmp))}
            with self.assertRaisesRegex(AssertionError, "EVIDENCE_MISSING"): score_pure_view(before, before)


if __name__ == "__main__":
    unittest.main()
