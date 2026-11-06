"""固定新任务资产身份及权限边界；目录存在不代表业务资格通过。"""
from pathlib import Path
import json
import xml.etree.ElementTree as ET
import jsonschema

ROOT=Path(__file__).resolve().parents[1]


def read(path):
    return json.loads((ROOT/path).read_text(encoding='utf8'))


def test_catalog_pack_and_domain_keep_existing_assets():
    catalog=read('catalog/finance-assets.v1.json')
    jsonschema.validate(catalog,read('contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json'))
    assert {'original-task-review-managed','market-risk-report-readonly','wealth-kyc-report-readonly','original-calculation-readonly'} <= {x['id'] for x in catalog['profiles']}
    pack=read('capability-packs/original-task-managed/pack.yaml')
    jsonschema.validate(pack,read('contracts/json-schema/capability/capability-pack.v1.schema.json'))
    assert {c['capabilityId']:c['operation'] for c in pack['capabilities']}=={'custody.task.query':'QUERY','custody.task.action':'TASK'}
    action=next(c for c in pack['capabilities'] if c['operation']=='TASK')
    validator=jsonschema.Draft202012Validator(action['inputSchema'])
    base={'action':'claim','expectedVersion':'sha256:'+'1'*64,'materialVersion':'sha256:'+'2'*64,'parameters':{}}
    assert validator.is_valid(base)
    for key in ['principalId','customerRef','taskRef','requestId','executionConfirmed','endpoint','script']:
        assert not validator.is_valid({**base,key:'forged'})
    domain=read('domain-packs/task-interaction/pack.yaml')
    jsonschema.validate(domain,read('contracts/json-schema/domain/domain-pack.v1.schema.json'))
    assert domain['skills']==[]


def test_reactor_loader_and_optional_host_boundary():
    modules=[x.text for x in ET.parse(ROOT/'pom.xml').getroot().findall('{*}modules/{*}module')]
    for name in ['packages/java/task-interaction','providers/original-task-managed','integrations/task-interaction-java']:
        assert modules.count(name)==1
    assert 'ManagedOriginalTaskFactoryV01' in (ROOT/'providers/original-task-managed/src/main/resources/META-INF/services/io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01').read_text()
    assert 'TaskInteractionScenarioExtensionV01' in (ROOT/'integrations/task-interaction-java/src/main/resources/META-INF/services/io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01').read_text()
    source=(ROOT/'integrations/task-interaction-java/src/main/java/io/github/bitlhk/finance/harness/integrations/taskinteraction/TaskInteractionSession.java').read_text(encoding='utf8')
    assert 'TaskCoordination' in source and 'ProviderTaskExecution.execute' in source
    assert 'new FileTaskJournal' not in source  # Host injects the existing Journal.
    cases=read('conformance/original-task-managed/cases.json')
    assert cases['professionalQualification']=='BLOCKED' and cases['productionQualification']=='BLOCKED'
