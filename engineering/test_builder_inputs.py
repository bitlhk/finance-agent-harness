"""Validate source provenance and explicit runtime resolution using the real input schema."""
import copy
import json
import unittest

from jsonschema import Draft202012Validator
from verify_contracts import ROOT


class BuilderInputTest(unittest.TestCase):
    def setUp(self):
        self.schema = Draft202012Validator(json.loads((ROOT / "contracts/json-schema/solution/solution-build-input.v1.schema.json").read_text()))
        self.inputs = [json.loads((ROOT / "examples/solutions" / scenario / "solution-build-input.v1.json").read_text())
                       for scenario in ("insurance-advisor-previsit", "wealth-manager-previsit")]

    def test_sources_and_runtime_coordinates_are_separate(self):
        for value in self.inputs:
            with self.subTest(solution=value["solutionId"]):
                self.schema.validate(value)
                runtime = value["runtime"]
                self.assertTrue(runtime["integrationRef"].startswith("repo://"))
                self.assertEqual("maven://com.openjiuwen:agent-core-java:0.1.15", runtime["resolvedArtifacts"]["templateRef"])
                self.assertEqual("maven://io.github.bitlhk.finance:finance-harness-openjiuwen-java:0.1.0-SNAPSHOT",
                                 runtime["resolvedArtifacts"]["integrationRef"])
        self.assertIn("#agents/domain-runtime", self.inputs[1]["runtime"]["templateRef"])
        self.assertEqual("repo://integrations/employee-agent-java", self.inputs[1]["runtime"]["integrationRef"])

    def test_incomplete_unknown_or_source_shaped_resolution_is_invalid(self):
        for change in ("missing", "null", "unknown", "source"):
            value = copy.deepcopy(self.inputs[0])
            resolved = value["runtime"]["resolvedArtifacts"]
            if change == "missing":
                del resolved["templateRef"]
            elif change == "null":
                value["runtime"]["resolvedArtifacts"] = None
            elif change == "unknown":
                resolved["trusted"] = True
            else:
                resolved["templateRef"] = "repo://not-an-artifact"
            with self.subTest(change=change):
                self.assertFalse(self.schema.is_valid(value))


if __name__ == "__main__":
    unittest.main()
