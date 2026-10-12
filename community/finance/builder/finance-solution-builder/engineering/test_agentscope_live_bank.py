"""Real loopback HTTP fixture checks; no model or Provider execution."""
import json
from pathlib import Path
import tempfile
import unittest
import urllib.error
import urllib.request

from verify_agentscope_host_live import SyntheticBank


ACCOUNT = {"accountId": "fixture-account", "currency": "CNY", "availableBalance": "12345.67"}


class SyntheticAccountHttpTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.bank = SyntheticBank(Path(temporary.name))
        self.addCleanup(self.bank.close)

    def request(self, path, method="GET"):
        request = urllib.request.Request(self.bank.url + path, method=method,
            headers={"X-Request-Id": "account-http-test"})
        try:
            response = urllib.request.urlopen(request, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, json.load(response)

    def test_list_retains_original_envelope_and_query_handling(self):
        for path in ("/api/v1/accounts", "/api/v1/accounts?page=1&pageSize=10"):
            with self.subTest(path=path):
                status, answer = self.request(path)
                self.assertEqual(status, 200)
                self.assertEqual(answer, {"status": "SUCCESS", "sourceSystem": "controlled-bank",
                    "asOf": "2026-09-20T00:00:00Z", "data": {"accounts": [ACCOUNT]}})

    def test_remembered_list_account_returns_single_account_protocol(self):
        status, listing = self.request("/api/v1/accounts")
        self.assertEqual(status, 200)
        account = listing["data"]["accounts"][0]
        path = "/api/v1/accounts/" + account["accountId"]
        status, detail = self.request(path)
        self.assertEqual(status, 200)
        self.assertEqual(detail["status"], "SUCCESS")
        self.assertEqual(detail["data"], account)
        self.assertEqual(detail["data"]["accountId"], path.rsplit("/", 1)[1])
        self.assertNotIn("accounts", detail["data"])
        for key in ("sourceSystem", "asOf"):
            self.assertEqual(detail[key], listing[key])
        self.assertEqual(len(self.bank.rows), 2)
        row = self.bank.rows[-1]
        self.assertEqual((row["method"], row["path"], row["status"]), ("GET", path, 200))
        self.assertEqual(row["requestId"], "account-http-test")
        self.assertEqual(row["response"], detail)
        self.assertTrue(row["business"])
        self.assertLessEqual(row["startedAtNs"], row["finishedAtNs"])

    def test_unknown_accounts_and_similar_paths_do_not_return_another_account(self):
        for path in ("/api/v1/accounts/unknown-account", "/api/v1/accounts/fixture-account-other",
                     "/api/v1/accounts/fixture-account/extra", "/api/v1/accounts/",
                     "/api/v1/accounts-other"):
            with self.subTest(path=path):
                status, answer = self.request(path)
                self.assertEqual(status, 404)
                self.assertEqual(answer, {"error": "FIXTURE_ROUTE_UNKNOWN"})
                self.assertNotIn("fixture-account", json.dumps(answer))
                self.assertEqual(self.bank.rows[-1]["status"], 404)
                self.assertEqual(self.bank.rows[-1]["response"], answer)

    def test_account_routes_remain_read_only(self):
        for path in ("/api/v1/accounts", "/api/v1/accounts/fixture-account"):
            for method in ("POST", "DELETE"):
                with self.subTest(path=path, method=method):
                    self.assertEqual(self.request(path, method), (405, {"error": "READ_ONLY"}))


if __name__ == "__main__":
    unittest.main()
