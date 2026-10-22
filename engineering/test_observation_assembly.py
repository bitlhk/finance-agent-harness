"""Independent observation contracts: no Builder, SDK, bank or model required."""
import copy
import hashlib
import os
import tempfile
import unittest
from pathlib import Path

from observation_assembly import (
    AssemblyError, LIMIT, local_path, read_json, source_digest,
    validate_assembly, validate_context,
)

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "observation-packs/native-analysis/assembly.v0.1.json"


class ObservationAssemblyTest(unittest.TestCase):
    def setUp(self):
        self.manifest = read_json(MANIFEST)

    def test_declared_cases(self):
        fixtures = read_json(ROOT / "contracts/fixtures/observation/assembly-cases.v0.1.json")
        for case in fixtures["cases"]:
            with self.subTest(case=case["name"]):
                document = copy.deepcopy(self.manifest)
                if case.get("operation") != "none":
                    target = document
                    for part in case["path"][:-1]:
                        target = target[part]
                    target[case["path"][-1]] = case["value"]
                before = copy.deepcopy(document)
                try:
                    result = validate_assembly(ROOT, document,
                                               compatibility=self.manifest["compatibility"])
                    actual = result["status"]
                except AssemblyError as exc:
                    actual = str(exc)
                self.assertEqual(case["expected"], actual)
                self.assertEqual(before, document)

    def test_missing_host_authorization(self):
        self.manifest["hostBindings"].remove("authorization")
        with self.assertRaisesRegex(AssemblyError, "^MISSING_HOST_BINDING$"):
            validate_assembly(ROOT, self.manifest)

    def test_limits_and_unknown_purpose(self):
        for change in ("limit", "purpose", "kind"):
            probe = copy.deepcopy(self.manifest)
            if change == "limit":
                probe["components"] *= 17
            elif change == "purpose":
                probe["consumerPurposes"]["developer"] = ["evaluation"]
            else:
                probe["contracts"][0]["kind"] = "tree"
            with self.subTest(change=change), self.assertRaises(AssemblyError):
                validate_assembly(ROOT, probe)

    def test_path_rejections(self):
        for source in ("repo://../outside", "repo://a/../b", "repo://a//b",
                       "repo://a/./b", "https://example.org/a", "repo://C:/a",
                       "repo://a\\b", "repo://missing", "repo://a/"):
            with self.subTest(source=source), self.assertRaises(AssemblyError):
                local_path(ROOT, source)

    def test_bounded_json_and_duplicates(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("OBSERVATION_TEST_TMP")) as directory:
            path = Path(directory) / "input.json"
            for raw, code in [(b'{"a":1,"a":2}', "DUPLICATE_KEY"),
                              (b'{"a":NaN}', "INVALID_DOCUMENT"),
                              (b" " * (LIMIT + 1), "RESOURCE_LIMIT")]:
                path.write_bytes(raw)
                with self.subTest(code=code), self.assertRaisesRegex(AssemblyError, "^" + code + "$"):
                    read_json(path)

    def test_raw_tree_digest_and_drift(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("OBSERVATION_TEST_TMP")) as directory:
            root = Path(directory)
            (root / "source").mkdir()
            (root / "source/a").write_bytes(b"first")
            reference = {"source": "repo://source", "kind": "tree"}
            before = source_digest(root, reference)
            self.assertEqual("sha256:" + hashlib.sha256(b"a\0first\0").hexdigest(), before)
            (root / "source/a").write_bytes(b"second")
            self.assertNotEqual(before, source_digest(root, reference))
            (root / "source/large").write_bytes(b"x" * (LIMIT + 1))
            with self.assertRaisesRegex(AssemblyError, "^RESOURCE_LIMIT$"):
                source_digest(root, reference)

    def test_nested_symlink_rejected(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("OBSERVATION_TEST_TMP")) as directory:
            root = Path(directory)
            (root / "source").mkdir()
            (root / "outside").write_bytes(b"private")
            try:
                (root / "source/link").symlink_to(root / "outside")
            except OSError:
                self.skipTest("Platform does not permit creating a symlink")
            with self.assertRaisesRegex(AssemblyError, "^UNSAFE_SOURCE$"):
                source_digest(root, {"source": "repo://source", "kind": "tree"})

    def context(self):
        digest = "sha256:" + "a" * 64
        return {"schemaVersion": "finance-financial-evidence-context.v0.1",
                "contextId": "ctx", "ownerRef": "owner", "purpose": "development-context",
                "parentLockDigest": digest, "reportDigest": digest,
                "samples": [{"sampleId": "s", "invocationId": "i", "requestId": "r",
                             "instanceId": "host", "processBootId": "boot",
                             "runtimeLockDigest": digest, "businessResult": "UNKNOWN",
                             "technicalResult": "SUCCEEDED", "coverage": "PARTIAL",
                             "evidenceRefs": [{"id": "e", "digest": digest}]}]}

    def test_context_structure_is_not_authorization_or_score(self):
        document = self.context()
        self.assertEqual("CONTEXT_STRUCTURE_VALID", validate_context(ROOT, document)["status"])
        for key in ("authorized", "score", "holdout", "body", "credentials", "command"):
            bad = copy.deepcopy(document)
            bad["samples"][0][key] = "not-allowed"
            with self.subTest(key=key), self.assertRaisesRegex(AssemblyError, "^SCHEMA_MISMATCH$"):
                validate_context(ROOT, bad)

    def test_context_duplicate_sample_and_evidence(self):
        document = self.context()
        document["samples"].append(dict(document["samples"][0], invocationId="other"))
        with self.assertRaisesRegex(AssemblyError, "^DUPLICATE_SAMPLE$"):
            validate_context(ROOT, document)
        document = self.context()
        document["samples"][0]["evidenceRefs"].append(
            {"id": "e", "digest": "sha256:" + "b" * 64})
        with self.assertRaisesRegex(AssemblyError, "^DUPLICATE_REFERENCE$"):
            validate_context(ROOT, document)

    def test_context_aggregate_limit(self):
        document = self.context()
        sample = document["samples"][0]
        sample["evidenceRefs"] = [{"id": str(i), "digest": document["reportDigest"]}
                                  for i in range(129)]
        document["samples"].append(dict(sample, sampleId="s2"))
        with self.assertRaisesRegex(AssemblyError, "^RESOURCE_LIMIT$"):
            validate_context(ROOT, document)


if __name__ == "__main__":
    unittest.main()
