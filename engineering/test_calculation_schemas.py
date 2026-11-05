"""Finite calculation contracts; no model or original algorithm is executed."""
import copy
import json
import unittest
from pathlib import Path
from jsonschema import Draft202012Validator

ROOT=Path(__file__).resolve().parents[1]
SCHEMAS=ROOT/'contracts/json-schema/calculation'

class CalculationSchemaTest(unittest.TestCase):
    def setUp(self):
        self.schema=json.loads((SCHEMAS/'request.v1.schema.json').read_text(encoding='utf-8'))
        self.validator=Draft202012Validator(self.schema)
        self.valid=dict(schemaVersion='finance-original-calculation-request.v1',assetId='portfolio',operationId='portfolio',dataAsOf=None,
            input=dict(returns=dict(columns=['A'],index=['2024-01-01'],data=[[.1]]),weights={'A':1}),parameters=dict(action='returns'))
    def test_all_schemas_are_well_formed(self):
        for p in SCHEMAS.glob('*.schema.json'):
            Draft202012Validator.check_schema(json.loads(p.read_text(encoding='utf-8')))
    def test_valid_request(self):
        self.validator.validate(self.valid)
    def test_all_eight_original_fixture_requests(self):
        cases=json.loads((ROOT/'conformance/original-calculation-readonly/schema-cases.v1.json').read_text(encoding='utf-8'))
        self.assertEqual(8,len(cases['positiveRequests']))
        self.assertEqual(7,len({r['operationId'] for r in cases['positiveRequests']}))
        for request in cases['positiveRequests']:
            self.validator.validate(request)
    def test_unknown_code_and_mismatched_asset_rejected(self):
        for key,value in [('factor_code','bad'),('module','bad'),('operationId','anything'),('assetId','debt')]:
            bad=copy.deepcopy(self.valid);bad[key]=value
            self.assertTrue(list(self.validator.iter_errors(bad)),key)
    def test_unknown_parameters_and_missing_input_rejected(self):
        bad=copy.deepcopy(self.valid);bad['parameters']['expression']='bad'
        self.assertTrue(list(self.validator.iter_errors(bad)))
        bad=copy.deepcopy(self.valid);del bad['input']
        self.assertTrue(list(self.validator.iter_errors(bad)))

if __name__=='__main__': unittest.main()
