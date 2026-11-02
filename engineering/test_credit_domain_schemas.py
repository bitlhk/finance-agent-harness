"""Domain unions keep strict variants while participating in the public registry."""

import copy
import json
from pathlib import Path
import unittest
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
BINDING = dict(
    tenant_id="tenant-1",
    principal_id="manager-1",
    customer_id="customer-1",
    case_id="case-1",
    observed_at="2026-06-12T00:00:00Z",
    visible_source_ids=["ledger"],
    snapshot_digest="a" * 64,
)
RECEIPT = dict(
    receipt_id="a" * 64,
    dataset_version="a" * 64,
    case_id="case-1",
    customer_id="customer-1",
    group_id="group-1",
    snapshot_revision=0,
    input_digest="b" * 64,
    model_digest="c" * 64,
    protocol_digest="d" * 64,
    observed_at="2026-06-12T00:00:00Z",
    expires_at="2026-06-13T00:00:00Z",
    method="model",
    results=[],
    evidence_refs=[],
)
CASES = [
    ("credit-card-query", "SourceBinding", BINDING),
    ("credit-card-query", "ForecastReceipt", RECEIPT),
    ("credit-card-benefits", "RecommendationRequest", {"operation_key": "operation-1"}),
    (
        "credit-card-benefits",
        "EngineResult",
        {"engine": "llm", "status": "complete", "elapsed_ms": 1, "provenance": {}},
    ),
]


class CreditDomainSchemas(unittest.TestCase):
    def schema(self, name):
        return json.loads(
            (ROOT / "contracts/json-schema" / f"{name}.v1.schema.json").read_text(
                encoding="utf-8"
            )
        )

    def test_registry_ids_and_strict_roots(self):
        schemas = [
            self.schema(n) for n in ["credit-card-query", "credit-card-benefits"]
        ]
        self.assertEqual(2, len({s["$id"] for s in schemas}))
        for s in schemas:
            Draft202012Validator.check_schema(s)
            self.assertEqual("object", s["type"])
            self.assertIs(False, s["additionalProperties"])
            self.assertIsInstance(s["required"], list)

    def test_all_variants_and_required_fields(self):
        for name, variant, valid in CASES:
            with self.subTest(variant=variant):
                schema = self.schema(name)
                validator = Draft202012Validator(schema, format_checker=FormatChecker())
                validator.validate(valid)
                for required in schema["$defs"][variant]["required"]:
                    missing = dict(valid)
                    missing.pop(required)
                    self.assertFalse(validator.is_valid(missing), (variant, required))
                for extra in ["unexpected", "schema_version"]:
                    self.assertFalse(
                        validator.is_valid(dict(valid, **{extra: "wrong"}))
                    )
                for key in schema["$defs"][variant]["required"]:
                    bad = dict(valid)
                    bad[key] = None
                    self.assertFalse(validator.is_valid(bad), (variant, key))
                self.assertFalse(validator.is_valid([]))
                self.assertFalse(validator.is_valid({}))

    def test_cross_variant_and_invalid_result_fields(self):
        q = Draft202012Validator(
            self.schema("credit-card-query"), format_checker=FormatChecker()
        )
        b = Draft202012Validator(
            self.schema("credit-card-benefits"), format_checker=FormatChecker()
        )
        self.assertFalse(q.is_valid(dict(BINDING, **RECEIPT)))
        self.assertFalse(q.is_valid(dict(RECEIPT, schema_version="2.0")))
        self.assertFalse(q.is_valid(dict(RECEIPT, observed_at="tomorrow")))
        self.assertFalse(b.is_valid(dict(CASES[-1][2], status="unknown")))
        self.assertFalse(b.is_valid(dict(CASES[-1][2], elapsed_ms=-1)))
        self.assertFalse(b.is_valid(dict(CASES[-1][2], operation_key="operation-1")))
        nested = copy.deepcopy(RECEIPT)
        nested["results"] = [{"horizon_days": 1, "unexpected": "value"}]
        self.assertFalse(q.is_valid(nested))


if __name__ == "__main__":
    unittest.main()
