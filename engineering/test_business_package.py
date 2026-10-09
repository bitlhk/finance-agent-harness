import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from business_package import PackageError, build, inspect


class BusinessPackageTest(unittest.TestCase):
    def source(self, root: Path):
        (root / "skill").mkdir(); (root / "cases").mkdir()
        (root / "skill/SKILL.md").write_text("# 资产核验\n只读取已授权的财富事实。")
        (root / "cases/normal.json").write_text(json.dumps({"query": "核对资产", "expected": "SOURCE_FACTS"}))
        (root / "validation-summary.json").write_text(json.dumps({"status": "PASS", "environment": "CONTROLLED_TEST",
                                                                   "professionalQuality": "NOT_EVALUATED"}))
        source = {"schemaVersion": "finance-business-component-package-source.v0.1",
          "package": {"id": "wealth-asset-check", "version": "1.0.0", "displayName": "资产信息核验",
                      "maintainer": "finance-harness", "visibility": "TEAM"},
          "skill": {"entry": "skill/SKILL.md", "sourceKind": "BUSINESS_DERIVED",
                    "basePackageRefs": [{"id": "wealth-customer-context", "version": "0.1.0"}]},
          "bindings": [{"capabilityId": "wealth.facts.describe", "capabilityPack": {"id": "wealth-customer-context", "version": "0.1.0"},
                        "toolName": "wealth_facts_describe", "operation": "QUERY", "protocol": "finance-provider-spi.v1",
                        "inputSchemaRef": "capability-pack://wealth.facts.describe/input",
                        "outputSchemaRef": "schema://wealth-facts.v0.1", "confirmation": "CONFIRMED"}],
          "requirements": {"runtimeFamilies": ["openjiuwen"], "runtimeLanguages": ["java"], "execution": ["QUERY"],
                           "observation": ["AUTHORIZATION", "TOOL_CALL", "BUSINESS_RESULT", "INSTALLATION_LOCK"],
                           "requiredScopes": ["wealth:read"]},
          "cases": [{"id": "normal", "path": "cases/normal.json"}],
          "validation": {"status": "PASS", "summaryPath": "validation-summary.json", "professionalQuality": "NOT_EVALUATED"},
          "files": ["skill/SKILL.md", "cases/normal.json", "validation-summary.json"]}
        (root / "package-source.json").write_text(json.dumps(source))

    def test_deterministic_build_and_inspect(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.source(root)
            first, second = root / "one.zip", root / "two.zip"
            a = build(root, first); b = build(root, second)
            self.assertEqual(a["archiveSha256"], b["archiveSha256"])
            self.assertEqual(inspect(first)["manifest"]["package"]["id"], "wealth-asset-check")

    def test_tamper_and_traversal_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.source(root)
            package = root / "valid.zip"; build(root, package)
            tampered = root / "tampered.zip"
            with zipfile.ZipFile(package) as source, zipfile.ZipFile(tampered, "w") as target:
                for item in source.infolist():
                    raw = source.read(item)
                    target.writestr(item.filename, b"changed" if item.filename == "skill/SKILL.md" else raw)
            with self.assertRaisesRegex(PackageError, "DIGEST"): inspect(tampered)
            traversal = root / "traversal.zip"
            with zipfile.ZipFile(traversal, "w") as archive: archive.writestr("../SKILL.md", "bad")
            with self.assertRaises(PackageError): inspect(traversal)

    def test_readonly_template_is_a_preserved_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.source(root)
            (root / 'templates').mkdir()
            (root / 'templates/answer.md').write_text('Output structure, not executable code.')
            definition = json.loads((root / 'package-source.json').read_text())
            definition['files'].append('templates/answer.md')
            (root / 'package-source.json').write_text(json.dumps(definition))
            package = root / 'template.zip'
            build(root, package)
            manifest = inspect(package)['manifest']
            self.assertEqual(next(f['role'] for f in manifest['files'] if f['path'] == 'templates/answer.md'), 'REFERENCE')
            from business_package import safe_name
            for name in ('templates/code.py', 'templates/code.js', 'templates/code.sh'):
                with self.assertRaises(PackageError): safe_name(name)


if __name__ == "__main__":
    unittest.main()
