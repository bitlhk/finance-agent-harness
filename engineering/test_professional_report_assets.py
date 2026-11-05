"""Report assembly metadata; engineering evidence is never professional certification."""
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import jsonschema

ROOT=Path(__file__).resolve().parents[1]


def read(path):return json.loads((ROOT/path).read_text(encoding='utf8'))


def test_catalog_two_profiles_and_original_entries_remain_available():
    catalog=read('catalog/finance-assets.v1.json')
    jsonschema.validate(catalog,read('contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json'))
    profiles={p['id'] for p in catalog['profiles']}
    assert {'wealth-kyc-report-readonly','market-risk-report-readonly','original-calculation-readonly','financial-material-processing'}<=profiles
    for group,identity in [('runtimeIntegrations','professional-report-java'),('providers','professional-report-readonly'),('domainPacks','professional-report')]:
        rows=[r for r in catalog[group] if r['id']==identity]
        assert len(rows)==1
        assert set(rows[0]['compatibility']['profiles'])=={'wealth-kyc-report-readonly','market-risk-report-readonly'}


def test_pack_is_readonly_and_model_cannot_choose_privileged_fields():
    pack=read('capability-packs/professional-report-readonly/pack.yaml')
    jsonschema.validate(pack,read('contracts/json-schema/capability/capability-pack.v1.schema.json'))
    assert {c['capabilityId'] for c in pack['capabilities']}=={'professional.report.wealth.query','professional.report.market.query'}
    for c in pack['capabilities']:
        assert c['operation']=='QUERY' and c['requiredScopes']==['professional-report:read']
        validator=jsonschema.Draft202012Validator(c['inputSchema'])
        for field in ['customerRef','ownerRef','script','endpoint','authorityRef']:
            assert not validator.is_valid({field:'forged'})
    domain=read('domain-packs/professional-report/pack.yaml')
    jsonschema.validate(domain,read('contracts/json-schema/domain/domain-pack.v1.schema.json'))
    assert domain['skills']==[]  # Private original method is installed separately.
    assembly=read('capability-packs/professional-report-readonly/assembly.v0.1.json')
    assert assembly['defaultDomainPacks']==[]


def test_report_reactor_and_service_loader_entries_exist():
    modules=[e.text for e in ET.parse(ROOT/'pom.xml').getroot().findall('{*}modules/{*}module')]
    for module in ['packages/java/professional-report','providers/professional-report-readonly','integrations/professional-report-java']:
        assert modules.count(module)==1
        assert (ROOT/module/'pom.xml').is_file()
    spi=ROOT/'integrations/professional-report-java/src/main/resources/META-INF/services/io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01'
    assert spi.read_text().strip()=='io.github.bitlhk.finance.harness.integrations.report.ReportScenarioExtensionV01'


def test_registry_and_governance_example_bind_the_actual_current_catalog():
    relative='metadata/development-support/professional-report-readonly/0.1.0.json'
    index=read('metadata/development-support/index.v0.1.json')
    matches=[r for r in index['declarations'] if r['source']=='repo://'+relative]
    assert len(matches)==1
    assert matches[0]['digest']=='sha256:'+hashlib.sha256((ROOT/relative).read_bytes()).hexdigest()
    catalog=read('catalog/finance-assets.v1.json');proposal=read('examples/governance/finance-asset-change-proposal.v1.json')
    assert proposal['catalog']['catalogVersion']==catalog['catalogVersion']
    assert proposal['catalog']['catalogRevision']==catalog['catalogRevision']
    assert proposal['catalog']['digest']=='sha256:'+hashlib.sha256((ROOT/'catalog/finance-assets.v1.json').read_bytes()).hexdigest()
