"""Frozen quotation wire cases; this does not qualify a Runtime or Host."""
import copy
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "conformance/fixtures/quotation/v0.1"


def strict_pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def load(path):
    return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=strict_pairs)


def validators():
    checker = FormatChecker()
    if checker.conforms("2026-02-30T12:00:00Z", "date-time"):
        raise RuntimeError("DATE_TIME_VALIDATOR_REQUIRED")
    registry = Registry()
    schemas = {}
    for path in (ROOT / "contracts/json-schema").rglob("*.schema.json"):
        schema = load(path)
        Draft202012Validator.check_schema(schema)
        registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
        schemas[path.name] = schema
    return {
        key: Draft202012Validator(value, registry=registry, format_checker=checker)
        for key, value in schemas.items()
    }


class QuotationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.validators = validators()
        cls.manifest = load(FIXTURES / "cases.v1.json")

    def test_frozen_schema_oracle(self):
        self.assertIs(self.manifest["frozenBeforeImplementation"], True)
        cases = self.manifest["cases"]
        self.assertGreaterEqual(len(cases), 25)
        self.assertEqual(len(cases), len({case["id"] for case in cases}))
        validator = self.validators["quote-facts.v0.1.schema.json"]
        for case in cases:
            with self.subTest(case=case["id"]):
                self.assertEqual(case["schemaValid"], validator.is_valid(case["payload"]))
                self.assertIn(case["disclosureReason"], {
                    "ALLOWED", "SUBJECT_MISMATCH", "PRODUCT_MISMATCH",
                    "STRUCTURE_INVALID", "QUOTE_EXPIRED", "NOT_YET_VALID",
                    "PRICE_CONFLICT", "SOURCE_MISMATCH", "SOURCE_TIME_INVALID",
                    "UNAVAILABLE", "CLAIM_CONFLICT",
                })

    def test_schema_does_not_claim_semantic_equivalence(self):
        rows = self.manifest["cases"]
        self.assertGreaterEqual(sum(r["schemaValid"] and r["disclosureReason"] != "ALLOWED" for r in rows), 8)
        self.assertEqual({r["stage"] for r in self.manifest["deferred"]}, {"H2", "H3"})

    def test_reference_configuration_is_not_self_hashing_or_authorizing(self):
        config = load(FIXTURES / "reference-config.v0.1.json")
        validator = self.validators["quotation-reference-config.v0.1.schema.json"]
        validator.validate(config)
        for field in ("snapshotSha256", "grantedScopes", "approvedBy", "endpoint", "code"):
            invalid = copy.deepcopy(config)
            invalid[field] = "untrusted"
            with self.subTest(field=field):
                self.assertFalse(validator.is_valid(invalid))

    def test_condition_dictionary_is_finite_data(self):
        catalog = load(FIXTURES / "conditions.v0.1.json")
        self.assertIs(catalog["grantsPermissions"], False)
        self.assertEqual(["DEMO_MEMBER_ELIGIBLE"], [row["code"] for row in catalog["conditions"]])

    def test_fixture_loader_rejects_duplicate_keys(self):
        with self.assertRaisesRegex(ValueError, "DUPLICATE_JSON_KEY"):
            json.loads('{"state":"AVAILABLE","state":"EXPIRED"}', object_pairs_hook=strict_pairs)


if __name__ == "__main__":
    unittest.main()
