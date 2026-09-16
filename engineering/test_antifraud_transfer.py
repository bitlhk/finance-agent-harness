import hashlib
import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE_ROOT = ROOT / "conformance" / "fixtures" / "anti-fraud-transfer" / "v0.1"


class AntiFraudTransferConformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.cases_path = FIXTURE_ROOT / "cases.json"
        cls.pack_path = FIXTURE_ROOT / "evaluation-pack.json"
        cls.cases = json.loads(cls.cases_path.read_text(encoding="utf-8"))
        cls.pack = json.loads(cls.pack_path.read_text(encoding="utf-8"))
        cls.catalog = json.loads(
            (ROOT / "catalog" / "finance-assets.v1.json").read_text(encoding="utf-8")
        )

    def test_cases_cover_required_closed_outcomes(self) -> None:
        by_id = {case["caseId"]: case for case in self.cases["cases"]}
        self.assertEqual(
            set(by_id),
            {
                "allow-to-preflight",
                "block-without-transfer",
                "risk-upstream-failed-closed",
                "processing-continues-original-task",
                "unknown-continues-original-task",
            },
        )
        self.assertEqual(by_id["block-without-transfer"]["expected"]["transferCalls"], 0)
        self.assertEqual(
            by_id["risk-upstream-failed-closed"]["expected"]["outcome"],
            "FAILED_CLOSED",
        )
        for case in by_id.values():
            allowed = case["risk"] == {"status": "COMPLETED", "decision": "ALLOW"}
            expected_calls = 1 if allowed else 0
            self.assertEqual(case["expected"]["transferCalls"], expected_calls)

    def test_evaluation_pack_is_bound_to_exact_case_bytes(self) -> None:
        partition = self.pack["partitions"][0]
        actual_digest = hashlib.sha256(self.cases_path.read_bytes()).hexdigest()
        self.assertEqual(partition["source"]["sha256"], actual_digest)
        self.assertEqual(partition["declaredSampleCount"], len(self.cases["cases"]))
        self.assertEqual(
            [sample["sampleId"] for sample in partition["samples"]],
            [case["caseId"] for case in self.cases["cases"]],
        )
        cases_by_id = {case["caseId"]: case for case in self.cases["cases"]}
        for sample in partition["samples"]:
            case = cases_by_id[sample["sampleId"]]
            payload = sample["payload"]
            self.assertEqual(payload["jsonrpc"], "2.0")
            self.assertEqual(payload["method"], "SendMessage")
            metadata = payload["params"]["metadata"]
            self.assertEqual(metadata["signals"]["intent"], "transfer.internal.create")
            self.assertEqual(metadata["arguments"], case["request"]["transfer"])
            self.assertEqual(
                payload["params"]["message"]["metadata"],
                metadata,
            )
            self.assertEqual(sample["expectedPath"], "/__financeBuilderEvidence")
            self.assertEqual(
                sample["expected"]["transferCalls"],
                case["expected"]["transferCalls"],
            )
            self.assertEqual(sample["expected"]["outcome"], case["expected"]["outcome"])
            self.assertEqual(
                sample["expected"]["riskDecision"],
                case["risk"]["decision"],
            )
            self.assertEqual(
                [call["capabilityId"] for call in sample["expected"]["providerCalls"]],
                ["fraud.transfer-risk.assess"]
                + (["transfer.internal.create"] if case["expected"]["transferCalls"] else []),
            )
            self.assertNotIn("risk", sample["payload"])
            self.assertNotIn("transfer", sample["payload"])
        mutated = self.cases_path.read_bytes() + b"\n"
        self.assertNotEqual(hashlib.sha256(mutated).hexdigest(), actual_digest)

    def test_catalog_publishes_controlled_pack_for_customer_profile(self) -> None:
        matches = [
            pack
            for pack in self.catalog["evalPacks"]
            if pack["id"] == "anti-fraud-transfer-controlled"
        ]
        self.assertEqual(len(matches), 1)
        self.assertEqual(matches[0]["version"], "0.1.0")
        self.assertEqual(
            matches[0]["source"],
            "repo://conformance/fixtures/anti-fraud-transfer/v0.1",
        )
        self.assertEqual(matches[0]["compatibility"]["profiles"], ["customer-self-service"])

    def test_fixture_is_public_safe_and_synthetic(self) -> None:
        serialized = json.dumps(
            {"cases": self.cases, "pack": self.pack},
            ensure_ascii=False,
        ).lower()
        for forbidden in ("api_key", "password", "access_token", "private_key"):
            self.assertNotIn(forbidden, serialized)
        for case in self.cases["cases"]:
            request = case["request"]
            self.assertTrue(request["accountRef"].startswith("synthetic-"))
            self.assertTrue(request["transfer"]["beneficiaryId"].startswith("synthetic-"))


if __name__ == "__main__":
    unittest.main()
