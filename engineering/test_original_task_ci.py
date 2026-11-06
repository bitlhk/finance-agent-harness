import importlib.util,sys,tempfile,json,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
import prepare_original_task_tool as task
class TaskCIInputs(unittest.TestCase):
    def test_pinned_source_has_no_implicit_local_fallback(self):
        self.assertEqual(task.BUILDER_REVISION,'2d09ee792eba2bc26b0c5ff50a692594fdb72e9f')
        self.assertEqual(task.UPSTREAM_REVISION,'0e158eee225c698774aa75671c944624cd36550b')
        self.assertEqual(task.TOOL_PATH,'community/finance/workshop/tools/managed-original-task-service')
    def test_exports_all_explicit_source_inputs_and_rejects_newlines(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'github-env';config=dict(originalPython='/python',toolRoot='/release',toolReleaseDigest='sha256:'+'a'*64)
            task.export_properties(Path(d)/'config.json',config,p)
            text=p.read_text();self.assertIn('FH_TASK_CONFIG=',text);self.assertIn('FH_TASK_SOURCE_RELEASE_DIGEST=',text)
            with self.assertRaises(ValueError):task.export_properties(Path(d)/'config',dict(config,toolRoot='bad\nvalue'),p)
    def test_workflow_has_separate_original_task_qualification(self):
        text=(ROOT.parent/'.github/workflows/ci.yml').read_text()
        self.assertIn('prepare_original_task_tool.py',text);self.assertIn('run_task_interaction_checks.py',text)
if __name__=='__main__':unittest.main()
