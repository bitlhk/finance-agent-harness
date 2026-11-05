from pathlib import Path
import importlib.util
import json
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'engineering'))
import prepare_original_calculation_tool as preparation


class CalculationPreparationTest(unittest.TestCase):
    def test_worker_is_independent_of_control_matrix(self):
        with patch.object(preparation.subprocess,'check_output',return_value='3.12.14\n') as run:
            self.assertEqual('3.12.14',preparation.worker_version('/owned/python312'))
            self.assertEqual('/owned/python312',run.call_args.args[0][0])
        with patch.object(preparation.subprocess,'check_output',return_value='3.10.19\n'):
            with self.assertRaisesRegex(ValueError,'WORKER_PYTHON_312_REQUIRED'):
                preparation.worker_version('/owned/control-python')

    def test_exports_only_nonsecret_single_line_pins(self):
        with tempfile.TemporaryDirectory() as temporary:
            output=Path(temporary)/'github-env';config=dict(toolRoot='/owned/release',toolReleaseDigest='a'*64)
            preparation.export_properties('/owned/config.json',config,output)
            values=output.read_text(encoding='utf-8')
            self.assertIn('FH_CALCULATION_CONFIG=',values)
            self.assertNotIn('KEY',values)
            config['toolRoot']='/owned/release\nINJECT=1'
            before=output.read_bytes()
            with self.assertRaises(ValueError):preparation.export_properties('/owned/config.json',config,output)
            self.assertEqual(before,output.read_bytes())

    def test_local_wrong_revision_stops_before_installation(self):
        with patch.object(preparation.subprocess,'check_output',side_effect=['0'*40,preparation.BUILDER_URL]) as run:
            with self.assertRaisesRegex(ValueError,'BUILDER_IDENTITY_MISMATCH'):
                preparation.local_builder(Path('/owned/builder'))
            self.assertEqual(2,run.call_count)


if __name__=='__main__':unittest.main()
