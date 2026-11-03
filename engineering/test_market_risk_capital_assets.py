"""Contract and closure checks for the additive original-tool candidate assets."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

import jsonschema
import pytest
import yaml

ROOT=Path(__file__).resolve().parents[1]
PROFILE='market-risk-capital-readonly'
CAPABILITY='market.risk.capital.query'


def load(relative):
    return yaml.safe_load((ROOT/relative).read_text(encoding='utf-8'))


def validate(value, schema):
    jsonschema.Draft202012Validator(load(schema),format_checker=jsonschema.FormatChecker()).validate(value)


def test_catalog_and_existing_entries_remain_valid():
    catalog=load('catalog/finance-assets.v1.json')
    validate(catalog,'contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json')
    for group in ('runtimeIntegrations','profiles','domainPacks','capabilityPacks','providers'):
        identities=[(v['id'],v['version']) for v in catalog[group]]
        assert len(identities)==len(set(identities))
    assert any(v['id']=='account-balance-readonly' for v in catalog['capabilityPacks'])
    assert any(v['id']=='non-ai-banking-reference' for v in catalog['providers'])
    assert any(v['id']=='wealth-shared-facts' for v in catalog['domainPacks'])


def test_capability_and_domain_use_existing_contracts():
    pack=load(f'capability-packs/{PROFILE}/pack.yaml')
    method=load('domain-packs/market-risk-capital/pack.yaml')
    validate(pack,'contracts/json-schema/capability/capability-pack.v1.schema.json')
    validate(method,'contracts/json-schema/domain/domain-pack.v1.schema.json')
    assert [v['capabilityId'] for v in pack['capabilities']]==[CAPABILITY]
    assert method['capabilities']==[CAPABILITY]
    assert pack['capabilities'][0]['operation']=='QUERY'
    query=pack['capabilities'][0]['inputSchema']
    jsonschema.validate({},query)
    with pytest.raises(jsonschema.ValidationError): jsonschema.validate({'snapshotRef':'forged'},query)
    entry=ROOT/'domain-packs/market-risk-capital'/method['skills'][0]['entry']
    text=entry.read_text(encoding='utf-8')
    assert '原项目没有这份 Skill' in text and '0e158eee225c698774aa75671c944624cd36550b' in text


def test_assembly_metadata_and_configuration_are_finite():
    schema='contracts/json-schema/catalog/finance-component-assembly.v0.1.schema.json'
    capability=load(f'capability-packs/{PROFILE}/assembly.v0.1.json')
    provider=load('providers/market-risk-capital/assembly.v0.1.json')
    validate(capability,schema);validate(provider,schema)
    assert capability['defaultDomainPacks']==[{'id':'market-risk-capital','version':'0.1.0'}]
    assert provider['supports'][0]['capabilityIds']==[CAPABILITY]
    assert provider['supports'][0]['operation']=='QUERY'
    assert provider['configurationFiles']==[{'id':'binding-file-sha256','pathEnvironment':'FH_MARKET_RISK_BINDINGS',
                                           'digestEnvironment':'FH_MARKET_RISK_BINDINGS_SHA256','maxBytes':262144}]


def test_candidate_closure_and_reactor_coordinates_match():
    catalog=load('catalog/finance-assets.v1.json')
    domain=next(v for v in catalog['domainPacks'] if v['id']=='market-risk-capital')
    capability=next(v for v in catalog['capabilityPacks'] if v['id']==PROFILE)
    provider=next(v for v in catalog['providers'] if v['id']=='market-risk-capital-original')
    assert domain['dependencies']==[{'kind':'capability-pack','id':PROFILE,'version':capability['version']}]
    assert domain['requiresCapabilities']==provider['capabilities']==capability['capabilities']==[CAPABILITY]
    for value in (domain,capability,provider):
        assert value['compatibility']['runtimeLanguages']==['java']
        assert value['compatibility']['profiles']==[PROFILE]
    namespace={'m':'http://maven.apache.org/POM/4.0.0'}
    root=ET.parse(ROOT/'pom.xml').getroot()
    modules=[m.text for m in root.findall('m:modules/m:module',namespace)]
    expected={'packages/java/market-risk-capital':'finance-harness-market-risk-capital',
              'providers/market-risk-capital':'finance-provider-market-risk-capital',
              'integrations/market-risk-capital-java':'finance-integration-market-risk-capital-java'}
    for module,artifact in expected.items():
        assert modules.count(module)==1
        assert ET.parse(ROOT/module/'pom.xml').getroot().findtext('m:artifactId',namespaces=namespace)==artifact


def test_original_cases_are_versioned_and_distinguish_zero_from_missing():
    cases=load(f'conformance/{PROFILE}/cases.json')
    assert cases['upstreamRevision']=='0e158eee225c698774aa75671c944624cd36550b'
    assert cases['originalFunction']=='mktrisk_engine.standard.compute_standard_capital'
    data={c['id']:c for c in cases['cases']}
    assert data['original-ten-positions']['expectedValues']['total']==40439000
    assert data['empty-authorized-snapshot']['expectedTotal']==0
    assert data['empty-authorized-snapshot']['expectedStatus']=='COMPLETED'
    assert data['positions-missing']['expectedStatus']=='FAILED'
    assert data['wrong-current-owner']['expectedStatus']=='FAILED'
