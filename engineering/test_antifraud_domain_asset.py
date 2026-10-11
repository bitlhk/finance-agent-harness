"""Candidate asset regression; installation and inference require separate evidence."""

import hashlib
import json
from pathlib import Path
import unittest

import yaml

ROOT = Path(__file__).resolve().parents[1]
DRAFT = ROOT / "domain-packs/anti-fraud-customer"
SOURCE_REVISION = "884947047fca4bf6563c0068f7cf11656ae964d3"


class AntifraudDomainAssetTest(unittest.TestCase):
    def setUp(self):
        self.pack = yaml.safe_load((DRAFT / "pack.yaml").read_text(encoding="utf-8"))
        self.skill_text = (DRAFT / self.pack["skills"][0]["entry"]).read_text(encoding="utf-8")
        self.skill = yaml.safe_load(self.skill_text.split("---", 2)[1])

    def test_installable_customer_skill_with_query_only_tools(self):
        self.assertEqual(self.pack["packId"], "transfer-anti-fraud")
        self.assertEqual(self.pack["version"], "0.1.1-rc.1")
        self.assertEqual(self.pack["profile"], "customer-self-service")
        self.assertEqual(self.skill["metadata"]["profile"], self.pack["profile"])
        self.assertEqual(self.skill["version"], self.pack["version"])
        self.assertEqual(self.skill["allowed-tools"].split(), ["fraud_transfer_risk_assess"])
        self.assertEqual(self.pack["capabilities"], ["fraud.transfer-risk.assess"])
        self.assertEqual(self.pack["capabilityPackRefs"], [{"id": "fraud-risk-readonly", "version": "0.1.0-rc.1"}])
        self.assertEqual(self.pack["skills"][0]["directory"], self.skill["name"])

    def test_derivation_records_fixed_revision_and_existing_sources(self):
        self.assertEqual(len(self.pack["sourceReferences"]), 2)
        for source in self.pack["sourceReferences"]:
            prefix = f"finance-agent-harness@{SOURCE_REVISION}/"
            self.assertTrue(source.startswith(prefix))
            self.assertTrue((ROOT / source.removeprefix(prefix)).is_file())

    def test_published_pack_remains_exact_original(self):
        # Canonical LF bytes from the fixed source, independent of Windows checkout CRLF.
        text = (ROOT / "domain-packs/anti-fraud/pack.yaml").read_text(encoding="utf-8")
        self.assertEqual(hashlib.sha256(text.encode()).hexdigest(), OLD_PACK_SHA256)
        original = yaml.safe_load(text)
        self.assertEqual(original["version"], "0.1.0")
        self.assertNotIn("skills", original)

    def test_catalog_keeps_distinct_old_and_draft_versions(self):
        catalog = json.loads((ROOT / "catalog/finance-assets.v1.json").read_text(encoding="utf-8"))
        assets = [item for item in catalog["domainPacks"] if item["id"] == "transfer-anti-fraud"]
        self.assertEqual(len(assets), 2)
        self.assertEqual({item["version"] for item in assets}, {"0.1.0", "0.1.1-rc.1"})
        for asset in assets:
            pack = yaml.safe_load((ROOT / asset["source"].removeprefix("repo://") / "pack.yaml").read_text(encoding="utf-8"))
            self.assertEqual(asset["version"], pack["version"])
            self.assertEqual(asset["profile"], pack["profile"])
            self.assertEqual(asset["requiresCapabilities"], pack["capabilities"])

    def test_accepted_content_boundaries_remain_explicit(self):
        # Presence protects reviewed text, not a claim of model compliance.
        for clause in ("缺少账户引用时先询问", "不猜账户", "不猜收款人", "命中已知风险",
                       "当前未命中", "系统不可用", "来源和时间", "未命中不等于安全",
                       "银行预检", "不自行放行或阻断", "不获得确认权", "独立 Harness TASK 与可信 Host"):
            with self.subTest(clause=clause):
                self.assertIn(clause, self.skill_text)

    def test_draft_and_output_examples_are_not_execution_claims(self):
        self.assertIn("虚构示意，不能作为真实回执", self.skill_text)
        self.assertIn("尚未由 Harness 维护者评审合入或发布", (DRAFT / "README.md").read_text(encoding="utf-8"))


OLD_PACK_SHA256 = "9809a66cdca7d05c20c811c598057a8a257bf20e80538fd6041ded61416fe8e4"

if __name__ == "__main__":
    unittest.main()
