"""Keep the candidate execution module independent of SDKs and bank adapters."""

from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class ExecutionModuleBoundaryTest(unittest.TestCase):
    def test_only_core_is_a_production_dependency(self):
        pom = ET.parse(ROOT / "packages/java/execution/pom.xml").getroot()
        production = []
        for dependency in pom.findall("m:dependencies/m:dependency", NS):
            if dependency.findtext("m:scope", default="compile", namespaces=NS) != "test":
                production.append((dependency.findtext("m:groupId", namespaces=NS),
                                   dependency.findtext("m:artifactId", namespaces=NS)))
        self.assertEqual([("${project.groupId}", "finance-harness-core")], production)

    def test_sources_do_not_import_runtime_or_bank_implementations(self):
        sources = list((ROOT / "packages/java/execution/src/main/java").rglob("*.java"))
        self.assertTrue(sources, "execution sources must exist")
        allowed = ("java.", "io.github.bitlhk.finance.harness.core.",
                   "io.github.bitlhk.finance.harness.execution.")
        for source in sources:
            imports = re.findall(r"^import\s+(?:static\s+)?([\w.*]+)\s*;",
                                 source.read_text(encoding="utf-8"), re.MULTILINE)
            for imported in imports:
                with self.subTest(source=source.relative_to(ROOT), imported=imported):
                    self.assertTrue(imported.startswith(allowed), imported)

    def test_core_does_not_depend_on_execution(self):
        pom = ET.parse(ROOT / "packages/java/core/pom.xml").getroot()
        artifacts = [node.text for node in pom.findall("m:dependencies/m:dependency/m:artifactId", NS)]
        self.assertNotIn("finance-harness-execution", artifacts)


if __name__ == "__main__":
    unittest.main()
