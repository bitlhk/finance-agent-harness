"""Negative parser fixtures, not native qualification evidence."""
import json
from pathlib import Path
import tempfile
import unittest

from engineering.qualify_agentscope_method import CHECKS, package


class MethodQualificationPackagingTest(unittest.TestCase):
    def fixture(self, root):
        evidence = root / "evidence"
        evidence.mkdir()
        value = {"test": "parser fixture only", "status": "PASS", "checkIds": sorted(CHECKS),
                 "runtimeArtifacts": [{"role": "SDK", "coordinate": "test", "version": "test", "digest": "test"}],
                 "collectorDeclaration": {"collectorId": "test", "capabilities": []},
                 "collectorIdentity": "test", "qualificationIdentity": "test"}
        (evidence / "raw.json").write_text(json.dumps(value))
        return evidence, value

    def test_complete_packaging_does_not_claim_fresh_jvm_or_host_admission(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            evidence, _ = self.fixture(root)
            result = package(evidence, root / "packaged")
            self.assertEqual("NOT_RUN", result["freshJvmAdmission"])
            self.assertEqual("NOT_RUN", result["generatedHostQualification"])
            self.assertEqual(12, result["checks"])
            self.assertEqual((evidence / "raw.json").read_bytes(), (root / "packaged/raw/raw.json").read_bytes())

    def test_failed_missing_mixed_or_provider_proof_is_rejected(self):
        for mode in ("failed", "missing", "mixed", "provider", "symlink"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                evidence, value = self.fixture(root)
                if mode == "failed": value["status"] = "FAIL"
                if mode == "missing": value["checkIds"].pop()
                if mode == "provider": value["runtimeArtifacts"][0]["role"] = "PROVIDER:source"
                if mode == "mixed":
                    other = dict(value, qualificationIdentity="another-build")
                    (evidence / "other.json").write_text(json.dumps(other))
                (evidence / "raw.json").write_text(json.dumps(value))
                if mode == "symlink": (evidence / "link.json").symlink_to(evidence / "raw.json")
                with self.assertRaises(ValueError): package(evidence, root / "packaged")
                self.assertFalse((root / "packaged").exists())


if __name__ == "__main__":
    unittest.main()
