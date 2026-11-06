from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]


class ProfessionalReportDocsTest(unittest.TestCase):
    def test_guide_names_actual_modules_and_separate_qualifications(self):
        text=(ROOT/'docs/professional-report-readonly-v1.md').read_text(encoding='utf-8')
        for word in ('wealth-kyc-report-readonly','market-risk-report-readonly','HOST_INSTANCE','ReportVersions','NOT_RUN','BLOCKED','ReActAgent','Core/Execution'):
            self.assertIn(word,text)
        self.assertIn('professional-report', (ROOT/'docs/current-architecture.md').read_text(encoding='utf-8'))
        for module in ('packages/java/professional-report','providers/professional-report-readonly','integrations/professional-report-java'):
            self.assertTrue((ROOT/module/'pom.xml').is_file())


if __name__=='__main__':unittest.main()
