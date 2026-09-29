"""Independent metadata contract checks; no runtime/business pass is inferred."""
import copy
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest

import jsonschema
import yaml

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "contracts/json-schema/catalog"


def digest(raw):
    return "sha256:" + hashlib.sha256(raw).hexdigest()


def tree_digest(root):
    value = hashlib.sha256()
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        if any(part in {".git", "target", "__pycache__"} for part in path.parts):
            continue
        value.update(path.relative_to(root).as_posix().encode() + b"\0" + path.read_bytes() + b"\0")
    return "sha256:" + value.hexdigest()


class DevelopmentSupportTests(unittest.TestCase):
    def setUp(self):
        self.schema = json.loads((SCHEMAS / "finance-component-development-support.v0.1.schema.json").read_text())
        self.index_schema = json.loads((SCHEMAS / "finance-development-support-index.v0.1.schema.json").read_text())
        self.index = json.loads((ROOT / "metadata/development-support/index.v0.1.json").read_text())
        self.declarations = [json.loads((ROOT / r["source"].removeprefix("repo://")).read_text())
                             for r in self.index["declarations"]]

    def test_formal_schemas_and_independent_index(self):
        for schema in (self.schema, self.index_schema):
            jsonschema.Draft202012Validator.check_schema(schema)
        jsonschema.validate(self.index, self.index_schema)
        for ref, declaration in zip(self.index["declarations"], self.declarations):
            jsonschema.validate(declaration, self.schema)
            path = ROOT / ref["source"].removeprefix("repo://")
            self.assertEqual(ref["digest"], digest(path.read_bytes()))
            subject = declaration["subject"]
            root = ROOT / subject["source"].removeprefix("repo://")
            self.assertFalse(path.is_relative_to(root))
            self.assertEqual(subject["digest"], tree_digest(root))
            manifest = yaml.safe_load((root / "pack.yaml").read_text())
            self.assertEqual((subject["id"], subject["version"]), (manifest["packId"], manifest["version"]))
            for case_ref in declaration["caseRefs"]:
                case = ROOT / case_ref["source"].removeprefix("repo://")
                self.assertEqual(case_ref["digest"], digest(case.read_bytes()))

    def test_targeted_provider_and_contract_commands_and_original_case_formats(self):
        balance, _, cashflow = self.declarations
        self.assertIn("sessionSubjectMismatchDoesNotReadAnyCustomerAccounts", balance["commands"][0]["command"])
        self.assertEqual(balance["caseRefs"][0]["format"], "finance-capability-smoke.v1")
        self.assertIn("-Dtest=CashflowProviderTest", cashflow["commands"][0]["command"])
        self.assertIn("-Dtest=CashflowContractTest", cashflow["commands"][1]["command"])
        self.assertEqual(len(cashflow["caseRefs"]), 5)

    def test_actual_three_businesses_have_generic_defaults(self):
        self.assertEqual({d["subject"]["id"] for d in self.declarations},
                         {"account-balance-readonly", "wealth-shared-facts", "cashflow-customer-self-service"})
        self.assertEqual([len(d["methodCases"]) for d in self.declarations], [1, 1, 2])
        for declaration in self.declarations:
            self.assertEqual(set(declaration["scope"]), {"capabilityIds"})
            self.assertTrue(declaration["commands"])
            self.assertNotIn("status", declaration)

    def test_wealth_support_versions_only_the_manual_workflow(self):
        path = ROOT / "metadata/development-support/wealth-shared-facts"
        previous = json.loads((path / "0.1.0.json").read_bytes())
        current = json.loads((path / "0.1.1.json").read_bytes())
        for field in ("schemaVersion", "subject", "scope", "methodCases", "caseRefs"):
            self.assertEqual(current[field], previous[field])
        self.assertEqual(current, self.declarations[1])
        commands = current["commands"]
        self.assertEqual(len(commands), 4)
        for number, command in enumerate(commands, 1):
            self.assertTrue(command["name"].startswith(f"{number}. "))
            self.assertEqual(command["sourceCommit"], "14c985bb02b76c9832e7a558fb0c0b2beeb86327")
            self.assertEqual(command["workingDirectory"], "repo://")
        self.assertIn("engineering/requirements-conformance.txt", commands[0]["command"])
        self.assertIn("mktemp -d", commands[1]["command"])
        self.assertIn("export WEALTH_M2=", commands[1]["command"])
        run = shlex.split(commands[2]["command"])
        self.assertEqual(run[:2], ["env", "-i"])
        self.assertIn("MAVEN_OPTS=-Dmaven.repo.local=${WEALTH_M2:?Run private cache preparation first}", run)
        self.assertEqual(run[-2:], ["bash", "examples/solutions/wealth-facts-kit/run-controlled.sh"])
        self.assertEqual(commands[3]["command"], ".venv/bin/python engineering/verify_wealth_kit.py")
        for relative in ("engineering/requirements-conformance.txt", run[-1], "engineering/verify_wealth_kit.py"):
            self.assertTrue((ROOT / relative).is_file())

    def test_prerequisite_notes_do_not_relax_the_strict_schema(self):
        for field in ("description", "notes"):
            value = copy.deepcopy(self.declarations[1])
            value[field] = "Unversioned extra prerequisite"
            with self.assertRaises(jsonschema.ValidationError):
                jsonschema.validate(value, self.schema)
            value = copy.deepcopy(self.declarations[1])
            value["commands"][0][field] = "Unversioned extra prerequisite"
            with self.assertRaises(jsonschema.ValidationError):
                jsonschema.validate(value, self.schema)

    def test_wealth_verification_requires_generated_reports(self):
        with tempfile.TemporaryDirectory() as directory:
            checked = subprocess.run([sys.executable, str(ROOT / "engineering/verify_wealth_kit.py"),
                                      "--runs", directory], capture_output=True, text=True, timeout=15)
        self.assertNotEqual(checked.returncode, 0)
        self.assertIn("FileNotFoundError", checked.stderr)
        self.assertIn("BANK_HTTP-NORMAL", checked.stderr)
        self.assertNotIn("PASS:", checked.stdout)

    def test_platform_pass_and_unknown_fields_are_not_declarations(self):
        for extra in ({"status": "PASS", "testsPassed": 1000}, {"runtimeVerified": True}):
            with self.assertRaises(jsonschema.ValidationError):
                jsonschema.validate({**self.declarations[0], **extra}, self.schema)
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate({**self.index, "methodCases": []}, self.index_schema)

    def test_case_format_is_explicit_and_scope_is_strict(self):
        for mutation in ("expectedBehavior", "runtime", "digest"):
            value = copy.deepcopy(self.declarations[0])
            if mutation == "expectedBehavior":
                value["methodCases"][0] = {"prompt": "query", "expectedBehavior": "answer"}
            elif mutation == "runtime":
                value["scope"]["runtime"] = "anything"
            else:
                value["subject"]["digest"] = "unfixed"
            with self.assertRaises(jsonschema.ValidationError):
                jsonschema.validate(value, self.schema)

    def test_old_strict_contracts_still_reject_development_support(self):
        domain = yaml.safe_load((ROOT / "domain-packs/wealth/shared-facts/pack.yaml").read_text())
        schema = json.loads((ROOT / "contracts/json-schema/domain/domain-pack.v1.schema.json").read_text())
        jsonschema.validate(domain, schema)
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate({**domain, "developmentSupport": self.declarations[1]}, schema)
        catalog = json.loads((ROOT / "catalog/finance-assets.v1.json").read_text())
        schema = json.loads((SCHEMAS / "finance-asset-catalog.v1.schema.json").read_text())
        jsonschema.validate(catalog, schema)
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate({**catalog, "developmentSupport": self.index}, schema)


if __name__ == "__main__":
    unittest.main()
