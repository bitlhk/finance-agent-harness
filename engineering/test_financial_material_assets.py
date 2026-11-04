"""Additive material catalogue, finite tool and original-evidence contract checks."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import jsonschema
import yaml

ROOT=Path(__file__).resolve().parents[1]
CAPS=['financial.material.extract','financial.material.locate']
def load(name):return yaml.safe_load((ROOT/name).read_text(encoding='utf-8'))
def validate(value,name):jsonschema.Draft202012Validator(load(name)).validate(value)

def test_catalog_is_additive_and_governance_identity_matches():
    catalog=load('catalog/finance-assets.v1.json')
    old=json.loads(subprocess.check_output(['git','show','fdb42d93b9f4577435f9a83bdcfdb8287a18b344:catalog/finance-assets.v1.json'],cwd=ROOT))
    validate(catalog,'contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json')
    for group in ('runtimeIntegrations','profiles','domainPacks','capabilityPacks','providers','evalPacks'):
        current={(v['id'],v['version']):v for v in catalog[group]}
        assert len(current)==len(catalog[group])
        for value in old[group]:assert current[(value['id'],value['version'])]==value
    proposal=load('examples/governance/finance-asset-change-proposal.v1.json')
    assert proposal['catalog']['catalogVersion']==catalog['catalogVersion']
    assert proposal['catalog']['digest']=='sha256:'+hashlib.sha256((ROOT/'catalog/finance-assets.v1.json').read_bytes()).hexdigest()

def test_pack_and_method_register_only_finite_queries():
    pack=load('capability-packs/financial-material-extraction-readonly/pack.yaml')
    method=load('domain-packs/financial-material-processing/pack.yaml')
    validate(pack,'contracts/json-schema/capability/capability-pack.v1.schema.json')
    validate(method,'contracts/json-schema/domain/domain-pack.v1.schema.json')
    assert [v['capabilityId'] for v in pack['capabilities']]==CAPS==method['capabilities']
    for value in pack['capabilities']:
        assert value['operation']=='QUERY'
        assert not value['inputSchema']['additionalProperties']
        assert not {'path','endpoint','principalId','documentRef'} & set(value['inputSchema']['properties'])
    assert (ROOT/'domain-packs/financial-material-processing'/method['skills'][0]['entry']).is_file()

def test_assembly_and_reactor_have_exact_modules():
    for name in ('providers/financial-material/assembly.v0.1.json','capability-packs/financial-material-extraction-readonly/assembly.v0.1.json'):
        validate(load(name),'contracts/json-schema/catalog/finance-component-assembly.v0.1.schema.json')
    provider=load('providers/financial-material/assembly.v0.1.json')
    assert provider['configurationFiles'][0]['pathEnvironment']=='FH_MATERIAL_BINDINGS'
    assert provider['supports'][0]['capabilityIds']==CAPS
    modules=[v.text for v in ET.parse(ROOT/'pom.xml').findall('{*}modules/{*}module')]
    for name in ('packages/java/financial-material','providers/financial-material','integrations/financial-material-java'):
        assert modules.count(name)==1

def test_domain_schemas_and_external_qualification_are_explicit():
    for name in ('document','query','result','correction'):
        jsonschema.Draft202012Validator.check_schema(load(f'contracts/json-schema/financial-material/{name}.v1.schema.json'))
    data={v['id']:v for v in load('conformance/financial-material-processing/cases.json')['cases']}
    assert data['empty-page']['expectedStatus']=='PARTIAL'
    assert data['directory-license']['expectedStatus']=='BLOCKED'
    assert data['real-vision-quality']['expectedStatus']=='BLOCKED'
