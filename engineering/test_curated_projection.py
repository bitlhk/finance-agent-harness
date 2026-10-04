"""Provider-owned fact declarations, not a native execution qualification."""
import json
from pathlib import Path
import unittest


class CuratedProjectionTest(unittest.TestCase):
    def test_material_exclusion_is_an_optional_scalar_not_a_whole_record(self):
        root = Path(__file__).resolve().parents[1]
        projection = json.loads((root / 'providers/curated-synthetic-mcp/eval-projection.v1.json').read_text())
        rule = projection['tools']['audit_case_context']
        self.assertEqual(projection['version'], '0.2.2')
        self.assertEqual(rule['optionalFields'], {'materialExcluded': '/materials/*/excluded'})
        self.assertNotIn('materialExcluded', rule['fields'])
        self.assertEqual(rule['fields']['materialId'], '/materials/*/id')
        self.assertEqual(rule['fields']['amount'], '/materials/*/amount')
        self.assertEqual(rule['resourcePath'], '/reference')
        self.assertEqual(projection['scope'], 'SYNTHETIC_EVALUATION_ONLY')


if __name__ == '__main__':
    unittest.main()
