import json
import hashlib
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
import jsonschema

ROOT=Path(__file__).resolve().parents[1]

def read(path):return json.loads((ROOT/path).read_text(encoding='utf-8'))

class OriginalCalculationAssetsTest(unittest.TestCase):
    def test_catalog_and_seven_finite_capabilities(self):
        catalog=read('catalog/finance-assets.v1.json')
        jsonschema.validate(catalog,read('contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json'))
        pack=read('capability-packs/original-calculation-readonly/pack.yaml')
        jsonschema.validate(pack,read('contracts/json-schema/capability/capability-pack.v1.schema.json'))
        self.assertEqual(7,len(pack['capabilities']))
        self.assertTrue(all(c['operation']=='QUERY' for c in pack['capabilities']))
        for c in pack['capabilities']:
            jsonschema.validate({},c['inputSchema'])
            self.assertFalse(jsonschema.Draft202012Validator(c['inputSchema']).is_valid({'module':'forged'}))
        self.assertTrue(any(x['id']=='account-balance-readonly' for x in catalog['capabilityPacks']))
    def test_domain_and_provider_metadata_are_closed(self):
        domain=read('domain-packs/original-calculation/pack.yaml')
        jsonschema.validate(domain,read('contracts/json-schema/domain/domain-pack.v1.schema.json'))
        schema=read('contracts/json-schema/catalog/finance-component-assembly.v0.1.schema.json')
        for path in ['providers/original-calculation/assembly.v0.1.json','capability-packs/original-calculation-readonly/assembly.v0.1.json']:
            jsonschema.validate(read(path),schema)
        self.assertEqual('original-calculation-readonly',domain['profile'])
        self.assertIn('原项目没有这份 Skill',(ROOT/'domain-packs/original-calculation/skill/SKILL.md').read_text(encoding='utf-8'))
    def test_actual_reactor_and_finite_cases(self):
        ns={'m':'http://maven.apache.org/POM/4.0.0'}
        modules=[n.text for n in ET.parse(ROOT/'pom.xml').getroot().findall('m:modules/m:module',ns)]
        for module in ['packages/java/calculation','providers/original-calculation','integrations/original-calculation-java']:
            self.assertEqual(1,modules.count(module));self.assertTrue((ROOT/module/'pom.xml').is_file())
        cases=read('conformance/original-calculation-readonly/cases.json')
        self.assertEqual(8,len(cases['assetIds']));self.assertEqual(11,len(cases['operations']))
    def test_own_support_is_cases_not_runtime_certification(self):
        support=read('metadata/development-support/original-calculation-readonly/0.1.0.json')
        jsonschema.validate(support,read('contracts/json-schema/catalog/finance-component-development-support.v0.1.schema.json'))
        self.assertTrue(support['methodCases'])
        self.assertEqual([],support['commands'])

    def test_support_registry_names_exact_current_declaration(self):
        index=read('metadata/development-support/index.v0.1.json')
        jsonschema.validate(index,read('contracts/json-schema/catalog/finance-development-support-index.v0.1.schema.json'))
        source='repo://metadata/development-support/original-calculation-readonly/0.1.0.json'
        matches=[r for r in index['declarations'] if r['source']==source]
        self.assertEqual(1,len(matches))
        self.assertEqual('sha256:'+hashlib.sha256((ROOT/source.removeprefix('repo://')).read_bytes()).hexdigest(),matches[0]['digest'])

if __name__=='__main__':unittest.main()
