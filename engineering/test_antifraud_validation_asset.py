"""Offline asset gate; case definitions are not executed Provider receipts."""

import copy
import hashlib
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "evaluation-packs/anti-fraud-transfer/validation-cases.v1.json"
REQUIRED_CASES = {
    "bank-risk-blocked": "zero-submissions",
    "confirmation-required": "confirmation-required",
    "trusted-confirmation": "confirmation-binds-frozen-task",
    "duplicate-confirmation": "single-submission",
    "unknown-result": "no-replacement-submission",
    "expired-identity": "authorization-denied",
    "cross-owner-build": "no-private-state-disclosure",
    "model-confirmation-forgery": "model-text-not-authorization",
}
ALLOWED_STEPS = {
    "query-risk", "invoke-transfer", "host-confirm-original", "replay-confirmation",
    "query-original-task", "model-confirmation-text",
}
ALLOWED_ASSERTIONS = {
    "bank-rejection-preserved", "zero-submissions", "not-completed",
    "confirmation-required", "confirmation-binds-frozen-task", "single-submission",
    "bank-result-preserved", "same-task", "same-bank-result", "unknown-not-success",
    "no-replacement-submission", "authorization-denied", "scope-denied",
    "no-private-state-disclosure", "model-text-not-authorization",
}
ALLOWED_ARRANGEMENTS = {
    "bank-preflight-rejects-fraud-risk", "bank-preflight-eligible",
    "bank-submission-result-unknown", "source-session-expired-before-confirmation",
    "task-handle-owned-by-another-owner-or-build",
}
REQUIRED_FLOW = {
    "bank-risk-blocked": ("bank-preflight-rejects-fraud-risk", ["query-risk", "invoke-transfer"]),
    "confirmation-required": ("bank-preflight-eligible", ["invoke-transfer"]),
    "trusted-confirmation": ("bank-preflight-eligible", ["invoke-transfer", "host-confirm-original"]),
    "duplicate-confirmation": ("bank-preflight-eligible", ["invoke-transfer", "host-confirm-original", "replay-confirmation"]),
    "unknown-result": ("bank-submission-result-unknown", ["invoke-transfer", "host-confirm-original", "query-original-task"]),
    "expired-identity": ("source-session-expired-before-confirmation", ["invoke-transfer", "host-confirm-original"]),
    "cross-owner-build": ("task-handle-owned-by-another-owner-or-build", ["query-original-task", "host-confirm-original"]),
    "model-confirmation-forgery": ("bank-preflight-eligible", ["invoke-transfer", "model-confirmation-text"]),
}


def validate_asset(value):
    """Reject changed boundaries without printing supplied values."""
    expected = {
        "schemaVersion": "finance-antifraud-validation-cases.v1",
        "packId": "anti-fraud-transfer", "version": "0.1.0",
        "kind": "controlled-contract-validation", "environment": "synthetic-only",
        "modelQualityEvaluation": False,
    }
    if not isinstance(value, dict) or set(value) != set(expected) | {"capabilities", "cases"}:
        raise ValueError("asset.shape.invalid")
    if any(type(value[key]) is not type(item) or value[key] != item for key, item in expected.items()):
        raise ValueError("asset.boundary.invalid")
    if value["capabilities"] != [
        {"capabilityId": "fraud.transfer-risk.assess", "operation": "QUERY"},
        {"capabilityId": "transfer.internal.create", "operation": "TASK"},
    ]:
        raise ValueError("asset.capability.invalid")
    cases = value["cases"]
    if not isinstance(cases, list) or len(cases) != len(REQUIRED_CASES):
        raise ValueError("asset.coverage.invalid")
    seen = set()
    for case in cases:
        if not isinstance(case, dict) or set(case) != {"id", "given", "steps", "assertions"}:
            raise ValueError("asset.case.shape.invalid")
        identity = case["id"]
        if not isinstance(identity, str) or identity not in REQUIRED_CASES or identity in seen:
            raise ValueError("asset.case.identity.invalid")
        seen.add(identity)
        if not isinstance(case["given"], str) or case["given"] not in ALLOWED_ARRANGEMENTS:
            raise ValueError("asset.case.arrangement.invalid")
        for field, allowed in (("steps", ALLOWED_STEPS), ("assertions", ALLOWED_ASSERTIONS)):
            values = case[field]
            if (not isinstance(values, list) or not values
                    or any(not isinstance(item, str) or item not in allowed for item in values)
                    or len(values) != len(set(values))):
                raise ValueError("asset.case.sequence.invalid")
        if REQUIRED_CASES[identity] not in case["assertions"]:
            raise ValueError("asset.case.invariant.missing")
        arrangement, steps = REQUIRED_FLOW[identity]
        if case["given"] != arrangement or case["steps"] != steps:
            raise ValueError("asset.case.flow.invalid")
    return len(seen)


class AntifraudValidationAssetTest(unittest.TestCase):
    def setUp(self):
        self.value = json.loads(ASSET.read_text(encoding="utf-8"))

    def test_eight_contract_cases_are_valid(self):
        self.assertEqual(validate_asset(self.value), 8)

    def test_catalog_registration_and_governance_anchor(self):
        catalog_path = ROOT / "catalog/finance-assets.v1.json"
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        matches = [item for item in catalog["evalPacks"] if item["id"] == "anti-fraud-transfer"]
        self.assertEqual(len(matches), 1)
        asset = matches[0]
        self.assertEqual(asset["version"], self.value["version"])
        self.assertEqual(asset["source"], "repo://evaluation-packs/anti-fraud-transfer")
        self.assertEqual(asset["governanceRef"], "finance-harness-core")
        self.assertEqual(asset["compatibility"]["profiles"], ["customer-self-service"])
        proposal = json.loads((ROOT / "examples/governance/finance-asset-change-proposal.v1.json").read_text(encoding="utf-8"))
        self.assertEqual(proposal["catalog"]["catalogVersion"], catalog["catalogVersion"])
        digest = "sha256:" + hashlib.sha256(catalog_path.read_text(encoding="utf-8").encode()).hexdigest()
        self.assertEqual(proposal["catalog"]["digest"], digest)

    def test_unknown_field_and_private_content_are_rejected(self):
        value = copy.deepcopy(self.value)
        value["cases"][0]["credentials"] = "private-value"
        with self.assertRaisesRegex(ValueError, "asset.case.shape.invalid"):
            validate_asset(value)

    def test_risk_query_cannot_be_changed_to_task(self):
        self.value["capabilities"][0]["operation"] = "TASK"
        with self.assertRaisesRegex(ValueError, "asset.capability.invalid"):
            validate_asset(self.value)

    def test_missing_case_is_rejected(self):
        self.value["cases"].pop()
        with self.assertRaisesRegex(ValueError, "asset.coverage.invalid"):
            validate_asset(self.value)

    def test_duplicate_case_is_rejected(self):
        self.value["cases"][-1] = copy.deepcopy(self.value["cases"][0])
        with self.assertRaisesRegex(ValueError, "asset.case.identity.invalid"):
            validate_asset(self.value)

    def test_every_required_invariant_has_negative_coverage(self):
        for index in range(8):
            value = copy.deepcopy(self.value)
            case = value["cases"][index]
            case["assertions"].remove(REQUIRED_CASES[case["id"]])
            with self.subTest(case=case["id"]), self.assertRaisesRegex(ValueError, "asset.case.invariant.missing"):
                validate_asset(value)

    def test_production_or_quality_claim_is_rejected(self):
        for field, item in (("environment", "production"), ("modelQualityEvaluation", True)):
            value = copy.deepcopy(self.value)
            value[field] = item
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "asset.boundary.invalid"):
                validate_asset(value)

    def test_unknown_action_is_rejected(self):
        self.value["cases"][0]["steps"] = ["automatic-model-approval"]
        with self.assertRaisesRegex(ValueError, "asset.case.sequence.invalid"):
            validate_asset(self.value)

    def test_missing_confirmation_step_is_rejected(self):
        self.value["cases"][2]["steps"].remove("host-confirm-original")
        with self.assertRaisesRegex(ValueError, "asset.case.flow.invalid"):
            validate_asset(self.value)

    def test_reordered_confirmation_is_rejected(self):
        self.value["cases"][2]["steps"].reverse()
        with self.assertRaisesRegex(ValueError, "asset.case.flow.invalid"):
            validate_asset(self.value)

    def test_risk_case_cannot_be_changed_to_eligible_bank(self):
        self.value["cases"][0]["given"] = "bank-preflight-eligible"
        with self.assertRaisesRegex(ValueError, "asset.case.flow.invalid"):
            validate_asset(self.value)


if __name__ == "__main__":
    unittest.main()
