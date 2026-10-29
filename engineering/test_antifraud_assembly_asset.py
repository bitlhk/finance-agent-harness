"""Antifraud component admission; optional real Builder integration, no model calls."""

from copy import deepcopy
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest

import jsonschema
import yaml

ROOT = Path(__file__).resolve().parents[1]
PACK = "capability-packs/fraud-risk-readonly"
PROVIDER = "providers/fraud-mining"
CAPABILITY = "fraud.transfer-risk.assess"


class AntifraudAssemblyAssetTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((ROOT / "catalog/finance-assets.v1.json").read_text(encoding="utf-8"))
        self.pack = yaml.safe_load((ROOT / PACK / "pack.yaml").read_text(encoding="utf-8"))
        self.declarations = [json.loads((ROOT / path / "assembly.v0.1.json").read_text(encoding="utf-8"))
                             for path in (PACK, PROVIDER)]
        schema = json.loads((ROOT / "contracts/json-schema/catalog/finance-component-assembly.v0.1.schema.json").read_text(encoding="utf-8"))
        self.validator = jsonschema.Draft202012Validator(schema)

    def test_declarations_are_schema_valid_and_catalog_owned(self):
        for declaration, group in zip(self.declarations, ("capabilityPacks", "providers"), strict=True):
            self.validator.validate(declaration)
            identity = declaration["owner"]
            matches = [a for a in self.catalog[group] if (a["id"], a["version"]) == (identity["id"], identity["version"])]
            self.assertEqual(len(matches), 1)
            self.assertEqual(set(matches[0]["capabilities"]),
                             {CAPABILITY} if group == "capabilityPacks" else {CAPABILITY, "fraud.risk-evidence.query"})

    def test_provider_covers_whole_pack_without_balance_or_task(self):
        self.assertEqual([c["capabilityId"] for c in self.pack["capabilities"]], [CAPABILITY])
        self.assertEqual(self.pack["capabilities"][0]["operation"], "QUERY")
        self.assertEqual(self.pack["capabilities"][0]["requiredScopes"], ["fraud:read"])
        self.assertIn({
            "pack": {"id": self.pack["packId"], "version": self.pack["version"]},
            "capabilityIds": [CAPABILITY], "operation": "QUERY"}, self.declarations[1]["supports"])
        self.assertIn({"pack": {"id": "fraud-risk-evidence-query", "version": "0.1.0"},
                       "capabilityIds": ["fraud.risk-evidence.query"], "operation": "QUERY"},
                      self.declarations[1]["supports"])
        self.assertEqual(len(self.declarations[1]["supports"]), 2)

    def test_candidate_domain_depends_only_on_readonly_pack(self):
        domain = yaml.safe_load((ROOT / "domain-packs/anti-fraud-customer/pack.yaml").read_text(encoding="utf-8"))
        self.assertEqual(domain["capabilityPackRefs"], [self.declarations[0]["owner"]])
        self.assertEqual(self.declarations[0]["defaultDomainPacks"], [{"id": domain["packId"], "version": domain["version"]}])
        self.assertTrue(domain["version"].endswith("-rc.1"))
        self.assertTrue(self.pack["version"].endswith("-rc.1"))

    def test_provider_maven_identity_matches_actual_runtime(self):
        declaration = self.declarations[1]
        self.assertEqual(declaration["artifactRef"], "maven://io.github.bitlhk.finance:finance-provider-fraud-mining:0.1.0-SNAPSHOT")
        java = (ROOT / PROVIDER / "src/main/java/io/github/bitlhk/finance/harness/providers/fraud/FraudMiningProvider.java").read_text(encoding="utf-8")
        self.assertIn('PROVIDER_ID = "fraud-mining-reference"', java)
        self.assertIn('PROVIDER_VERSION = "0.1.0-SNAPSHOT"', java)


@unittest.skipUnless(os.environ.get("FINANCE_BUILDER_ASSEMBLY_TEST") == "1", "opt-in real Builder workspace integration")
class RealBuilderAntifraudAssemblyTest(unittest.TestCase):
    def setUp(self):
        # Opt-in requires actual Builder/Base source on PYTHONPATH; no copied implementation.
        from finance_solution_builder.component_assembly import AssemblyRejected, ComponentAssembly

        self.assembly_class = ComponentAssembly
        self.rejected = AssemblyRejected
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for path in ("catalog", "contracts", PACK, PROVIDER, "domain-packs/anti-fraud-customer",
                     "capability-packs/risk-review-basics", "providers/non-ai-banking"):
            shutil.copytree(ROOT / path, self.root / path)
        self.host = {
            "schemaVersion": "finance-host-support.v0.1",
            "runtime": {"family": "openjiuwen", "language": "java",
                        "templateRef": "test-template@1.0", "integrationRef": "openjiuwen-java@0.1.0-SNAPSHOT"},
            "profile": "customer-self-service",
            "features": ["query.v1", "trusted-grants.v1", "observation.v0.1"],
            "operations": ["QUERY"], "connectionKinds": ["fraud-mining-readonly.v1"],
        }
        self.selection = [{"pack": {"id": "fraud-risk-readonly", "version": "0.1.0-rc.1"},
                           "provider": {"id": "fraud-mining-reference", "version": "0.1.0-SNAPSHOT"}}]

    def select(self, selection=None):
        return self.assembly_class(self.root).select(selection or self.selection, host=self.host,
                                                   connection_kind="fraud-mining-readonly.v1")

    def test_real_generic_builder_selects_pack_provider_and_default_domain(self):
        result = self.select()
        self.assertEqual(result["domainPackRefs"][0]["version"], "0.1.1-rc.1")
        self.assertEqual(result["capabilityPackRefs"][0]["id"], "fraud-risk-readonly")
        self.assertEqual(result["providerRefs"][0]["id"], "fraud-mining-reference")

    def test_main_provider_profiles_are_not_silently_widened(self):
        self.assertEqual(self.select()["domainPackRefs"][0]["version"], "0.1.1-rc.1")
        for profile in ("local-read-only", "local-simulator-task"):
            with self.subTest(profile=profile):
                self.host["profile"] = profile
                with self.assertRaisesRegex(self.rejected, "ASSEMBLY_ASSET_INCOMPATIBLE"):
                    self.select()

    def test_generic_builder_catalog_with_matching_host_declaration(self):
        from finance_solution_builder import managed_selection

        # Exercise real Builder code with an explicit deployment-owned declaration.
        # This is not proof that a generated Host is installed or qualified.
        source_project = Path(managed_selection.__file__).resolve().parents[2]
        project = self.root / "builder-project"
        template = project / "templates/local-readonly-service"
        template.mkdir(parents=True)
        (template / "host-support.v0.1.json").write_text(json.dumps(self.host), encoding="utf-8")
        shutil.copyfile(source_project / "templates/local-readonly-service/selection-support.v0.1.json",
                        template / "selection-support.v0.1.json")
        catalog = managed_selection.selection_catalog(project, ROOT)
        entries = [entry for entry in catalog["entries"] if entry["pack"] == self.selection[0]["pack"]]
        self.assertEqual(len(entries), 1)
        self.assertIn(self.selection[0]["provider"], entries[0]["providers"])

    def test_missing_each_declaration_fails_after_success_baseline(self):
        self.select()
        for path in (PACK, PROVIDER):
            with self.subTest(path=path):
                file = self.root / path / "assembly.v0.1.json"
                contents = file.read_bytes()
                file.unlink()
                try:
                    with self.assertRaises(self.rejected):
                        self.select()
                finally:
                    file.write_bytes(contents)

    def test_owner_version_mismatch_fails(self):
        self.select()
        file = self.root / PROVIDER / "assembly.v0.1.json"
        value = json.loads(file.read_text(encoding="utf-8"))
        value["owner"]["version"] = "0.9.9"
        file.write_text(json.dumps(value), encoding="utf-8")
        with self.assertRaisesRegex(self.rejected, "ASSEMBLY_OWNER_MISMATCH"):
            self.select()

    def test_bank_provider_cannot_cover_antifraud(self):
        self.select()
        selection = deepcopy(self.selection)
        selection[0]["provider"]["id"] = "non-ai-banking-reference"
        with self.assertRaisesRegex(self.rejected, "ASSEMBLY_PROVIDER_(CONNECTION|SUPPORT)_MISMATCH"):
            self.select(selection)

    def test_historical_mixed_pack_not_admitted(self):
        self.select()
        selection = deepcopy(self.selection)
        selection[0]["pack"] = {"id": "risk-review-basics", "version": "0.1.0"}
        with self.assertRaises(self.rejected):
            self.select(selection)

    def test_adding_balance_to_candidate_fails_closed(self):
        self.select()
        file = self.root / PACK / "pack.yaml"
        pack = yaml.safe_load(file.read_text(encoding="utf-8"))
        extra = deepcopy(pack["capabilities"][0])
        extra["capabilityId"] = "account.balance.query"
        extra["toolName"] = "account_balance_query"
        pack["capabilities"].append(extra)
        file.write_text(yaml.safe_dump(pack, allow_unicode=True), encoding="utf-8")
        with self.assertRaisesRegex(self.rejected, "ASSEMBLY_CATALOG_CAPABILITY_MISMATCH"):
            self.select()


if __name__ == "__main__":
    unittest.main()
