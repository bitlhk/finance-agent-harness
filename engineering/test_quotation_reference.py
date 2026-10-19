"""Failure-path tests for the H1 verifier, not SDK/Host qualification."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from engineering import verify_quotation_reference as v


class QuotationVerificationTest(unittest.TestCase):
    def setUp(self):
        self.private = tempfile.TemporaryDirectory()
        self.addCleanup(self.private.cleanup)
        self.root = Path(self.private.name)
        self.manifest = json.loads((v.FIXTURES / "cases.v1.json").read_text(encoding="utf-8"))

    def write_suites(self):
        paths = []
        for module, classes in v.SUITES.items():
            for classname, expected in classes.items():
                names = sorted(expected) if expected else [
                    "frozenIndependentCases()[" + str(i + 1) + "]" for i in range(len(self.manifest["cases"]))]
                node = ET.Element("testsuite", name=classname, tests=str(len(names)), failures="0", errors="0", skipped="0")
                for name in names:
                    ET.SubElement(node, "testcase", name=name, classname=classname)
                path = self.root / module / "target/surefire-reports" / ("TEST-" + classname + ".xml")
                path.parent.mkdir(parents=True, exist_ok=True)
                ET.ElementTree(node).write(path)
                paths.append(path)
        return paths

    def test_full_matrix_is_required_not_pass_text(self):
        paths = self.write_suites()
        self.assertEqual(35, sum(row["tests"] for row in v.junit_results(self.root, self.manifest)))
        node = ET.parse(paths[0]).getroot()
        node.remove(node.findall("testcase")[0])
        ET.ElementTree(node).write(paths[0])
        with self.assertRaises(ValueError):
            v.junit_results(self.root, self.manifest)

    def test_missing_failed_skipped_duplicate_or_wrong_suite_cannot_pass(self):
        for kind in ("missing", "failed", "skipped", "duplicate", "wrong"):
            with self.subTest(kind=kind):
                paths = self.write_suites()
                path = paths[-1]
                node = ET.parse(path).getroot()
                if kind == "missing":
                    path.unlink()
                else:
                    if kind == "failed":
                        ET.SubElement(node.findall("testcase")[0], "failure")
                    if kind == "skipped":
                        node.set("skipped", "1")
                    if kind == "duplicate":
                        node.append(copy.deepcopy(node.findall("testcase")[0]))
                    if kind == "wrong":
                        node.set("name", "fabricated-pass")
                    ET.ElementTree(node).write(path)
                with self.assertRaises((ValueError, FileNotFoundError)):
                    v.junit_results(self.root, self.manifest)

    def test_source_or_input_change_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "SOURCE_OR_INPUT_CHANGED"):
            v.require_same_identity({"sha256": "a"}, {"sha256": "b"})

    def test_frozen_oracle_is_exact(self):
        v.require_frozen()
        for path in v.FROZEN:
            source = self.root / path
            source.parent.mkdir(parents=True, exist_ok=True)
            source.write_bytes((v.ROOT / path).read_bytes())
        v.require_frozen(self.root)
        source.write_bytes(source.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "FROZEN_INPUT_MISMATCH"):
            v.require_frozen(self.root)

    def test_missing_toolchain_is_failure_and_h2_h3_remain_not_run(self):
        with patch.object(v.shutil, "which", return_value=None):
            result = v.verify(self.root / "report.json")
        self.assertEqual("FAIL", result["status"])
        self.assertEqual("OFFLINE_TOOLCHAIN_NOT_CONFIGURED", result["error"])
        for key in ("hostSdkQualification", "publicDisclosure", "builderQualification", "businessNetwork"):
            self.assertEqual("NOT_RUN", result[key])

    def test_arbitrary_report_text_is_not_a_verifier_input(self):
        path = self.root / "report.json"
        path.write_text('{"status":"PASS"}', encoding="utf-8")
        with patch.object(v.shutil, "which", return_value=None):
            self.assertEqual("FAIL", v.verify(path)["status"])


if __name__ == "__main__":
    unittest.main()
