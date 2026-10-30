import json
from pathlib import Path
import unittest
import yaml

ROOT=Path(__file__).resolve().parents[1]


class KnowledgeEvidencePackTest(unittest.TestCase):
    def test_evidence_pack_contains_only_actual_document_tool(self):
        pack=yaml.safe_load((ROOT/'capability-packs/knowledge-evidence-readonly/pack.yaml').read_text())
        self.assertEqual([c['capabilityId'] for c in pack['capabilities']],['knowledge.evidence.search'])
        cap=pack['capabilities'][0]
        self.assertEqual(cap['operation'],'QUERY')
        self.assertEqual(cap['requiredScopes'],['knowledge:read'])
        self.assertEqual(set(cap['inputSchema']['properties']),{'question'})
        self.assertFalse(cap['inputSchema']['additionalProperties'])

    def test_provider_and_catalog_reference_same_pack(self):
        catalog=json.loads((ROOT/'catalog/finance-assets.v1.json').read_text())
        entry=next(p for p in catalog['capabilityPacks'] if p['id']=='knowledge-evidence-readonly')
        self.assertEqual(entry['capabilities'],['knowledge.evidence.search'])
        provider=json.loads((ROOT/'providers/knowledge-service/assembly.v0.1.json').read_text())
        support=next(s for s in provider['supports'] if s['pack']['id']==entry['id'])
        self.assertEqual(support['capabilityIds'],entry['capabilities'])
        self.assertEqual(support['pack']['version'],entry['version'])

    def test_does_not_relax_knowledge_binding_or_observation(self):
        base=ROOT/'capability-packs/knowledge-evidence-readonly'
        assembly=json.loads((base/'assembly.v0.1.json').read_text())
        self.assertIn('knowledge-binding.v0.1',assembly['requiredFeatures'])
        observation=json.loads((base/'observation.v0.1.json').read_text())
        self.assertEqual(observation['requirements'][0]['obligation'],'REQUIRED')
        self.assertEqual(observation['requirements'][0]['capabilityIds'],['knowledge.evidence.search'])

    def test_old_probe_pack_and_scores_remain_unchanged(self):
        pack=yaml.safe_load((ROOT/'capability-packs/knowledge-readonly/pack.yaml').read_text())
        self.assertEqual([c['capabilityId'] for c in pack['capabilities']],
                         ['knowledge.evidence.search','knowledge.rule.evaluate'])


if __name__=='__main__':unittest.main()
