"""Python 3.10+ regression for actual Java output and lossless instant ordering."""
import json
import unittest
from datetime import datetime, timezone

from verify_contracts import ROOT
from verify_wealth_kit import instant_key


class WealthTimeTest(unittest.TestCase):
    def test_actual_java_outputs(self):
        path = ROOT / "integrations/openjiuwen-java/target/java-instant-cases.json"
        self.assertTrue(path.is_file(), "Run Maven tests first; Java timestamp fixture is required")
        cases = json.loads(path.read_text())
        self.assertEqual(10, len(cases))
        observed_lengths = set()
        for case in cases:
            with self.subTest(timestamp=case["timestamp"]):
                self.assertEqual(
                    (datetime(2026, 9, 6, 3, 27, 24, tzinfo=timezone.utc), case["nanoseconds"]),
                    instant_key(case["timestamp"]),
                )
                observed_lengths.add(len(case["timestamp"].split(".")[1][:-1]) if "." in case["timestamp"] else 0)
        self.assertEqual({0, 3, 6, 9}, observed_lengths)

    def test_fractional_widths_and_offsets(self):
        for digits in range(1, 10):
            fraction = "123456789"[:digits]
            with self.subTest(digits=digits):
                self.assertEqual(int(fraction.ljust(9, "0")), instant_key("2026-09-06T03:27:24." + fraction + "Z")[1])
        self.assertEqual(instant_key("2026-09-06T03:27:24.100Z"), instant_key("2026-09-06T11:27:24.1+08:00"))

    def test_nanosecond_order_is_not_rounded_to_microseconds(self):
        before = instant_key("2026-09-06T03:27:24.274594001Z")
        after = instant_key("2026-09-06T03:27:24.274594002Z")
        self.assertLess(before, after)
        self.assertFalse(before >= after)
        self.assertLess(instant_key("2026-09-06T03:27:24.999999999Z"), instant_key("2026-09-06T03:27:25Z"))

    def test_invalid_or_ambiguous_instants_are_rejected(self):
        for invalid in [None, "", "2026-09-06T03:27:24", "2026-09-06", "2026-02-30T03:27:24Z",
                        "2026-09-06T03:27:24.1234567890Z", "2026-09-06T03:27:24+24:00", "2026-09-06T03:27:24+08:60"]:
            with self.subTest(value=invalid):
                self.assertRaises(ValueError, instant_key, invalid)


if __name__ == "__main__":
    unittest.main()
