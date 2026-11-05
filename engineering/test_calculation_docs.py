"""Executable guide entrypoints and its contract example, rather than word-count checks."""
from pathlib import Path
import json
import re
import subprocess
import sys
import unittest
import jsonschema

ROOT=Path(__file__).resolve().parents[1]


class CalculationDocsTest(unittest.TestCase):
    def test_guide_entrypoints_are_real_and_side_effect_free_in_help_mode(self):
        guide=(ROOT/'docs/public/developer-guide.md').read_text(encoding='utf-8')
        section=guide.split('### 16.8 ',1)[1]
        commands=re.findall(r'^python (engineering/[a-z_]+\.py) --help$',section,re.M)
        self.assertEqual(2,len(commands))
        for script in commands:
            result=subprocess.run([sys.executable,str(ROOT/script),'--help'],cwd=ROOT,capture_output=True,text=True,timeout=30)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertIn('usage:',result.stdout)

    def test_contract_raw_example_validates_against_authoritative_schema(self):
        contract=(ROOT/'docs/calculation-readonly-v1.md').read_text(encoding='utf-8')
        raw=json.loads(re.findall(r'```json\s*\n(.*?)\n```',contract,re.S)[-1])
        schema=json.loads((ROOT/'contracts/json-schema/calculation/raw-value.v1.schema.json').read_text())
        jsonschema.validate(raw,schema)
        self.assertEqual('missing',raw['kind'])

    def test_architecture_links_to_installed_implementation_files(self):
        text=(ROOT/'docs/current-architecture.md').read_text(encoding='utf-8')
        section=text.split('### 原算法受管计算候选',1)[1]
        for relative in ['packages/java/calculation','providers/original-calculation','integrations/original-calculation-java']:
            self.assertIn('`'+relative.split('/')[-1]+'`',section)
            self.assertTrue((ROOT/relative/'pom.xml').is_file())
        self.assertTrue((ROOT/'docs/calculation-readonly-v1.md').is_file())


if __name__=='__main__':unittest.main()
