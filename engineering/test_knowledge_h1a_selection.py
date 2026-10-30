import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class KnowledgeSelection(unittest.TestCase):
    def test_pure_selection_and_independent_gold(self):
        solution = json.loads((ROOT/'providers/knowledge-service/h1a/solution.json').read_text())
        cases = json.loads((ROOT/'conformance/knowledge-evidence-h1a/cases.json').read_text())
        self.assertEqual(solution['evaluationPackRef']['id'], cases['id'])
        self.assertEqual(solution['capabilityBindings'], {'knowledge.evidence.search':'knowledge-service'})
        self.assertEqual(solution['domainPackRefs'], [])
        self.assertEqual(len(solution['providerRefs']), 1)
        self.assertNotIn('wealth-facts-controlled', json.dumps(solution))
        self.assertNotIn('account.balance', json.dumps(solution))
        self.assertEqual(len(cases['queries']), 3)
        self.assertEqual(cases['acceptance'], 'NOT_EXECUTED_BY_AGENT')
        self.assertEqual(cases['manifest'], '8f5dc67b321edb48b0636581d63834c62b175a90d5dcad624b6460ff989606f3')


if __name__ == '__main__':
    unittest.main()
