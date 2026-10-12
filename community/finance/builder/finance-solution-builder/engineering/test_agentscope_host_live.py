"""Offline checks for the LIVE driver; these are not real-model acceptance."""
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.request

from verify_agentscope_host_live import SyntheticBank, model_configuration, inspect_turn, CAPS
from verify_agentscope_host import write


class LiveDriverTest(unittest.TestCase):
    def test_local_registry_keeps_secret_out_of_recorded_metadata(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write(root / "registry.json", {"schemaVersion": "finance-model-bindings.v1", "bindings": [{
                "modelRef": "model-fixture", "revision": "one", "enabled": True,
                "protocol": "OPENAI_CHAT_COMPLETIONS", "provider": "OpenAI", "modelName": "not-live",
                "urlEnvironment": "FH_CONN_TEST_URL", "keyEnvironment": "FH_CONN_TEST_KEY",
                "ignoredPrivateMetadata": "secret-test-only"}]})
            args = SimpleNamespace(model_env=None, model_registry=root / "registry.json", model_ref="model-fixture")
            with patch.dict(os.environ, {"FH_CONN_TEST_URL": "https://example.invalid/v1/",
                                        "FH_CONN_TEST_KEY": "secret-test-only"}, clear=True):
                env, registry, selection, metadata, secret = model_configuration(args)
            self.assertEqual("https://example.invalid/v1/", env["FH_CONN_TEST_URL"])
            self.assertFalse(metadata["endpointPathNormalized"])
            self.assertNotIn(secret, json.dumps([registry, selection, metadata]))
            with patch.dict(os.environ, {"FH_CONN_TEST_URL": "https://example.invalid/v1/chat/completions",
                                        "FH_CONN_TEST_KEY": "secret-test-only"}, clear=True):
                with self.assertRaisesRegex(ValueError, "LIVE_MANAGED_BASE_URL_REQUIRED"):
                    model_configuration(args)
            with patch.dict(os.environ, {}, clear=True):
                with self.assertRaisesRegex(ValueError, "LOCAL_MANAGED_MODEL_CREDENTIALS_REQUIRED"):
                    model_configuration(args)

    def test_month_projection_changes_only_synthetic_fixture_dates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            write(root / "conformance/fixtures/cashflow/source/analysis.json", {
                "period": "2026-08", "totalExpense": "1000.50",
                "trend": [{"period": "unused"} for _ in range(6)]})
            bank = SyntheticBank(root)
            try:
                with urllib.request.urlopen(bank.url + "/api/v1/finance/spending-analysis?period=2026-07") as response:
                    value = json.load(response)
                self.assertEqual("2026-07", value["data"]["period"])
                self.assertEqual("2026-02", value["data"]["trend"][0]["period"])
                self.assertEqual("2026-07", value["data"]["trend"][-1]["period"])
                self.assertEqual("1000.50", value["data"]["totalExpense"])
                self.assertEqual(1, len(bank.rows))
            finally:
                bank.close()

    def test_missing_year_requires_no_bank_reads(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = {"executions": [], "diagnosticFailures": 0,
                      "evidenceKind": "CONFIGURED_MODEL_WITH_LOCAL_BANK_SIMULATOR", "response": "Which year?"}
            self.assertEqual("PASS", inspect_turn(root, "clarification", result, set(), None, [])["status"])
            self.assertEqual("FAIL", inspect_turn(root, "unexpected-read", result, set(), None,
                             [{"business": True, "readiness": False}])["status"])
            self.assertEqual("FAIL", inspect_turn(root, "missing-tool", result, {CAPS["balance"]}, None, [])["status"])


if __name__ == "__main__":
    unittest.main()
