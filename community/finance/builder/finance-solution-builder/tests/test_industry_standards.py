import json
from pathlib import Path
from urllib.parse import urlparse

from finance_solution_builder.template_modules import load_template_modules


PROJECT = Path(__file__).resolve().parents[1]


def test_reference_records_have_explicit_scope_and_official_sources():
    document = json.loads((PROJECT / 'browser/industry-standards.v1.json').read_text())
    assert document['schemaVersion'] == 'finance-studio-industry-references.v1'
    assert '不代表产品认证' in document['boundary']
    assert '26 个业务模块来自项目目录' in document['boundary']
    records = {record['id']: record for record in document['references']}
    assert set(records) == {'bian-service-landscape', 'iso20022-business-model', 'gbt27926-1', 'wcag'}
    expected_hosts = {'bian.org', 'www.iso20022.org', 'std.samr.gov.cn', 'www.w3.org'}
    for record in records.values():
        assert set(record) == {'id', 'title', 'version', 'category', 'purpose', 'boundary', 'verification', 'verifiedAt', 'url'}
        assert all(isinstance(value, str) and value.strip() for value in record.values())
        assert record['verifiedAt'] == '2026-09-10'
        assert urlparse(record['url']).scheme == 'https'
    assert {urlparse(record['url']).hostname for record in records.values()} == expected_hosts
    assert records['bian-service-landscape']['version'] == '14.0'
    assert '全文条款未核实' in records['gbt27926-1']['verification']
    assert '未固定仓库快照' in records['iso20022-business-model']['version']
    assert '不是 ATM 硬件' in records['wcag']['boundary']


def test_service_labels_preserve_identity_aliases_and_business_scope():
    directory = load_template_modules()
    modules = {module['id']: module for module in directory['modules']}
    assert len(modules) == 26
    for module_id, prefix in [('agent.account', '账户'), ('agent.transfer', '转账'), ('agent.creditcard', '信用卡')]:
        assert modules[module_id]['label'] == prefix + '服务'
        assert modules[module_id]['aliases'] == [prefix + '助手']
    assert modules['agent.finance_assistant']['label'] == '收支分析'
    assert modules['agent.finance_assistant']['aliases'] == ['金融助手']
    assert modules['agent.finance_assistant']['componentIds'] == ['finance.cashflow.readonly']
    assert modules['agent.wealth_aggregate']['label'] == '财富聚合服务'
    assert {module['id'] for module in modules.values() if module['componentIds']} == {'agent.account', 'agent.wealth_aggregate', 'agent.finance_assistant'}
    assert not modules['agent.transfer']['componentIds']
    assert not modules['agent.creditcard']['componentIds']
