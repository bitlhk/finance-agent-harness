"""The standalone Builder candidate uses the same frozen Harness acceptance matrix."""
import json
import unittest
from unittest.mock import patch
from verify_contracts import ROOT
from verify_wealth_kit import READONLY_CASES, verify_suite


class ReadonlyMatrixTest(unittest.TestCase):
    def test_native_case_manifest_matches_frozen_verifier(self):
        cases = json.loads((ROOT / "examples/solutions/wealth-facts-kit/customer-readonly-cases.json").read_text())
        self.assertEqual(READONLY_CASES, [c["binding"] + "-" + c["scenario"] for c in cases])
        self.assertTrue(all(c["mode"] == "CONTROLLED" and c["profile"] == "CUSTOMER" for c in cases))

    def test_empty_or_reduced_suite_is_not_pass(self):
        with patch("verify_wealth_kit.verify") as verify:
            for names in ([], READONLY_CASES[:-1], READONLY_CASES + [READONLY_CASES[0]]):
                with self.subTest(names=names), self.assertRaises(AssertionError):
                    verify_suite(ROOT, ROOT / "not-used", names)
            verify.assert_not_called()


if __name__ == "__main__":
    unittest.main()
