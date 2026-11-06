import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import prepare_professional_report_tool as report

ROOT=Path(__file__).resolve().parents[1]


class ProfessionalReportCiTest(unittest.TestCase):
    def test_ci_preserves_prior_inputs_and_wires_actual_report_config(self):
        workflow=(ROOT/'.github/workflows/ci.yml').read_text(encoding='utf-8')
        for value in ('prepare_original_market_risk_tool.py','prepare_original_material_tool.py','prepare_original_calculation_tool.py',
                      'prepare_professional_report_tool.py','-Dfh.reportConfig=$FH_REPORT_CONFIG','-Dfh.reportLifecycleEvidence=$FH_REPORT_LIFECYCLE_EVIDENCE'):
            self.assertIn(value,workflow)
        self.assertNotIn('-DskipTests',workflow)
        self.assertNotIn('--run-e2e',workflow)

    def test_unpublished_identity_never_silently_fetches_old_source(self):
        with tempfile.TemporaryDirectory() as tmp,patch.object(report,'BUILDER_REVISION','LOCAL_UNPUBLISHED_FEAT005'):
            with self.assertRaisesRegex(ValueError,'REPORT_SOURCE_NOT_YET_PUBLISHED'):report.prepare(Path(tmp)/'new','python')
            self.assertFalse((Path(tmp)/'new').exists())

    def test_local_source_must_be_exact_and_committed(self):
        with patch.object(report.subprocess,'check_output',return_value='different\n'):
            with self.assertRaisesRegex(ValueError,'REPORT_BUILDER_IDENTITY_MISMATCH'):report.require_builder(Path('.'))

    def test_ci_properties_do_not_include_credentials_and_reject_newlines(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);target=root/'env'
            report.export_properties(root/'execution.json',{},target)
            text=target.read_text();self.assertIn('FH_REPORT_CONFIG=',text);self.assertNotIn('KEY',text)
            with self.assertRaisesRegex(ValueError,'REPORT_CI_ENVIRONMENT_REJECTED'):report.export_properties(root/'bad\ninput',{},target)


if __name__=='__main__':unittest.main()
