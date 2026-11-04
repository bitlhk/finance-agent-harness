"""Regression for the CI-to-native-test prerequisite boundary, without real models."""
from pathlib import Path
import os
import re
import sys
import tempfile
import unittest
from unittest.mock import patch

import yaml

import prepare_original_material_tool as material

ROOT = Path(__file__).resolve().parents[1]


class OriginalMaterialCiTest(unittest.TestCase):
    def test_ci_supplies_every_property_consumed_by_the_actual_native_test(self):
        source = (ROOT / "integrations/financial-material-java/src/test/java/io/github/bitlhk/finance/harness/integrations/material/MaterialProcessingNativeTest.java").read_text(encoding="utf-8")
        consumed = set(re.findall(r'required\("(fh\.[^"]+)"\)', source))
        self.assertEqual(set(material.PROPERTIES), consumed)
        job = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))["jobs"]["offline-contracts-and-java"]
        self.assertEqual(job["strategy"]["matrix"]["python"], ["3.10", "3.12"])
        steps = job["steps"]
        prepare = next(i for i, s in enumerate(steps) if s.get("name") == "Prepare pinned original material tool")
        verify = next(i for i, s in enumerate(steps) if s.get("name") == "Unit tests and local HTTP fault injection")
        self.assertLess(prepare, verify)
        self.assertIn('--github-env "$GITHUB_ENV"', steps[prepare]["run"])
        for prop, variable in material.PROPERTIES.items():
            self.assertIn('"-D' + prop + '=$' + variable + '"', steps[verify]["run"])
        for step in (steps[prepare], steps[verify]):
            self.assertNotIn("continue-on-error", step)
            self.assertNotIn("skipTests", step["run"])
        upload = next(s for s in steps if s.get("name") == "Retain test results")
        self.assertIn("original-material/preparation.json", upload["with"]["path"])
        self.assertIn("original-material/native-evidence/", upload["with"]["path"])

    def test_export_preserves_all_four_paths_including_spaces(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            values = {prop: root / ("path with spaces " + str(i)) for i, prop in enumerate(material.PROPERTIES)}
            output = root / "env"
            material.export_properties(values, output)
            actual = dict(line.split("=", 1) for line in output.read_text(encoding="utf-8").splitlines())
            self.assertEqual(actual, {var: str(values[prop].absolute()) for prop, var in material.PROPERTIES.items()})

    def test_export_does_not_replace_venv_python_with_symlink_target(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            base = root / "base-python"
            base.write_text("synthetic interpreter target", encoding="utf-8")
            python = root / ".venv" / "bin" / "python"
            python.parent.mkdir(parents=True)
            try:
                python.symlink_to(base)
            except OSError as error:
                if os.name == "nt" and getattr(error, "winerror", None) == 1314:
                    self.skipTest("Windows account cannot create symbolic links")
                raise
            self.assertNotEqual(python.absolute(), python.resolve())
            values = {prop: root / prop for prop in material.PROPERTIES}
            values["fh.python"] = python
            output = root / "env"
            material.export_properties(values, output)
            actual = dict(line.split("=", 1) for line in output.read_text(encoding="utf-8").splitlines())
            self.assertEqual(actual["FH_MATERIAL_PYTHON"], str(python.absolute()))
            self.assertNotEqual(actual["FH_MATERIAL_PYTHON"], str(base.resolve()))

    def test_invalid_export_does_not_append_partial_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "env"
            output.write_text("EXISTING=preserved\n", encoding="utf-8")
            valid = {prop: root / prop for prop in material.PROPERTIES}
            bad = [dict(valid, **{"fh.materialEvidence": "bad\nINJECTED=1"}),
                   {k: v for k, v in valid.items() if k != "fh.python"},
                   dict(valid, unexpected=root)]
            for values in bad:
                with self.subTest(values=tuple(values)), self.assertRaises(ValueError):
                    material.export_properties(values, output)
                self.assertEqual(output.read_text(encoding="utf-8"), "EXISTING=preserved\n")

    def test_failed_preparation_does_not_export_success(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "env"
            args = ["prepare", "--workspace", str(Path(directory) / "new"), "--github-env", str(output)]
            with patch.object(sys, "argv", args), patch.object(material, "prepare", side_effect=RuntimeError("fixed-source unavailable")):
                with self.assertRaises(RuntimeError):
                    material.main()
            self.assertFalse(output.exists())

    def test_existing_workspace_is_rejected_before_any_checkout(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(material, "fixed_checkout") as checkout:
            with self.assertRaises(FileExistsError):
                material.prepare(directory)
            checkout.assert_not_called()


if __name__ == "__main__":
    unittest.main()
