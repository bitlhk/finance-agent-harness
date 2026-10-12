import copy
import importlib.util
import pytest
from pathlib import Path


spec = importlib.util.spec_from_file_location('comparison', Path(__file__).resolve().parents[1]/'engineering/compare_readonly_business.py')
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


@pytest.mark.parametrize('capability', ['finance.transaction-summary.query', 'finance.spending-analysis.query'])
def test_cashflow_comparison_preserves_business_and_policy_time(capability):
    value = {'data': {'capabilityId': capability, 'resultStatus': 'COMPLETED',
        'sourceSystem': 'fixture', 'facts': {'amount': '90071992547409.91', 'currency': 'CNY',
        'scope': 'BANK', 'source': {'observedAt': 'first', 'asOf': '2026-09-01',
        'policyAsOf': '2026-08-01', 'policyRef': 'policy-v1'}}, 'description': {'gaps': []}}}
    changed = copy.deepcopy(value)
    changed['data']['facts']['source']['observedAt'] = 'second'
    assert comparison.business(value) == comparison.business(changed)
    assert value['data']['facts']['source']['observedAt'] == 'first'
    for field in ('asOf', 'policyAsOf', 'policyRef'):
        changed = copy.deepcopy(value)
        changed['data']['facts']['source'][field] = 'different'
        assert comparison.business(value) != comparison.business(changed)
    for field in ('amount', 'currency', 'scope'):
        changed = copy.deepcopy(value)
        changed['data']['facts'][field] = 'different'
        assert comparison.business(value) != comparison.business(changed)


def test_balance_comparison_keeps_money_identity_and_fact_time():
    value = {'data': {'capabilityId': 'account.balance.query', 'resultStatus': 'COMPLETED',
        'sourceSystem': 'fixture', 'asOf': '2026-09-01T00:00:00Z',
        'accounts': [{'id': 'a', 'availableBalance': '90071992547409.91', 'currency': 'CNY'}]}}
    for field, replacement in [('asOf', '2026-09-02T00:00:00Z'), ('accounts', []), ('resultStatus', 'FAILED')]:
        changed = copy.deepcopy(value)
        changed['data'][field] = replacement
        assert comparison.business(changed) != comparison.business(value)


def test_wealth_comparison_keeps_semantics_but_not_observation_identity():
    value = {'data': {'capabilityId': 'wealth.facts.describe', 'resultStatus': 'COMPLETED',
        'sourceSystem': 'fixture', 'facts': {'subject': {'id': 'a'}, 'assets': {'scope': 'BANK'},
        'risk': {'availability': 'UNAVAILABLE'}, 'source': {'observedAt': 'first', 'payloadSha256': 'first',
        'contract': 'v1'}}, 'description': {'suitability': 'NOT_EVALUATED'}, 'missingInputs': ['risk'],
        'sourceReceipt': {'status': 'COMPLETED', 'sourceSystem': 'bank', 'asOf': '2026-09-01'}}}
    changed = copy.deepcopy(value)
    changed['data']['facts']['source'].update(observedAt='second', payloadSha256='second')
    assert comparison.business(value) == comparison.business(changed)
    for field, replacement in [('subject', {'id': 'b'}), ('assets', {'scope': 'HOUSEHOLD'}),
                               ('risk', {'availability': 'AVAILABLE'})]:
        changed = copy.deepcopy(value)
        changed['data']['facts'][field] = replacement
        assert comparison.business(value) != comparison.business(changed)
