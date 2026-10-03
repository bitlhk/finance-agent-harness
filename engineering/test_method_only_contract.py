"""Method-only is explicit, not a fallback for missing tools or providers."""
import copy
import json
from pathlib import Path
import unittest

import jsonschema
import yaml

ROOT = Path(__file__).resolve().parents[1]


class MethodOnlyContractTest(unittest.TestCase):
    def valid(self, name, value):
        schema = json.loads((ROOT / "contracts/json-schema" / name).read_text())
        return jsonschema.Draft202012Validator(schema).is_valid(value)

    def solution(self):
        value = yaml.safe_load((ROOT / "examples/solutions/wealth-facts-kit/solution.yaml").read_text())
        value.update(capabilityPackRefs=[], providerRefs=[], capabilityBindings={})
        return value

    def test_explicit_method_only_solution(self):
        self.assertTrue(self.valid("solution/solution-build-input.v1.schema.json", self.solution()))

    def test_empty_or_inconsistent_solution_is_not_method_only(self):
        for change in ({"domainPackRefs": []}, {"capabilityBindings": {"account.balance.query": "missing"}},
                       {"capabilityPackRefs": [{"id": "missing", "version": "1", "source": "repo://missing"}]},
                       {"providerRefs": [{"id": "unused", "version": "1", "source": "repo://unused"}]}):
            with self.subTest(change=change):
                value = self.solution()
                value.update(change)
                self.assertFalse(self.valid("solution/solution-build-input.v1.schema.json", value))
        value = self.solution()
        del value["capabilityPackRefs"]
        self.assertFalse(self.valid("solution/solution-build-input.v1.schema.json", value))

    def test_method_only_domain_requires_skill_and_no_hidden_capability_dependency(self):
        value = {"schemaVersion": "finance-domain-pack.v1", "packId": "method-only", "version": "0.1.0",
                 "profile": "shared-read-only", "capabilities": [], "capabilityPackRefs": [],
                 "skills": [{"directory": "coach", "entry": "coach/SKILL.md"}], "scope": [], "outOfScope": []}
        self.assertTrue(self.valid("domain/domain-pack.v1.schema.json", value))
        for change in ({"skills": []}, {"capabilityPackRefs": [{"id": "hidden", "version": "1"}]}):
            invalid = copy.deepcopy(value)
            invalid.update(change)
            self.assertFalse(self.valid("domain/domain-pack.v1.schema.json", invalid))
        del value["skills"]
        self.assertFalse(self.valid("domain/domain-pack.v1.schema.json", value))


if __name__ == "__main__":
    unittest.main()
