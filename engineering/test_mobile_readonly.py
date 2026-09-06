import unittest

from run_mobile_readonly import SCENARIOS, DENIED, validate_mobile_records


def records():
    return {name: {"scenario": name, "path": "MOBILE_ADAPTER_HARNESS_CONTROLLED_HTTP",
                   "realAuthorization": "NOT_RUN", "agentConversation": "NOT_RUN", "sourceWriteAttempts": 0,
                   "sourceReads": 2 if name not in DENIED or name in {"REVOKED", "BAD_RESPONSE"} else 0,
                   "result": {"status": "REJECTED" if name in DENIED else "COMPLETED",
                              "executionEvidence": [{"fixture": True}],
                              "data": {} if name in DENIED else {"description": {"suitability": "NOT_EVALUATED"}}}}
            for name in SCENARIOS}


class MobileEvidenceTests(unittest.TestCase):
    def test_complete_controlled_set(self):
        validate_mobile_records(records())

    def test_missing_case_is_not_pass(self):
        for payload in ({}, {k: v for k, v in records().items() if k != "WRONG_SESSION"}):
            with self.assertRaises(ValueError):
                validate_mobile_records(payload)

    def test_denied_read_or_fact_fails(self):
        for field, value in (("sourceReads", 1), ("sourceWriteAttempts", 1)):
            payload = records(); payload["WRONG_SUBJECT"][field] = value
            with self.assertRaises(ValueError): validate_mobile_records(payload)
        payload = records(); payload["NO_GRANT"]["result"]["data"]["facts"] = {}
        with self.assertRaises(ValueError): validate_mobile_records(payload)

    def test_revocation_cannot_read_again(self):
        payload = records(); payload["REVOKED"]["sourceReads"] = 4
        with self.assertRaises(ValueError): validate_mobile_records(payload)

    def test_no_live_or_conversation_promotion(self):
        for field in ("realAuthorization", "agentConversation"):
            payload = records(); payload["NORMAL"][field] = "PASS"
            with self.assertRaises(ValueError): validate_mobile_records(payload)


if __name__ == "__main__":
    unittest.main()
