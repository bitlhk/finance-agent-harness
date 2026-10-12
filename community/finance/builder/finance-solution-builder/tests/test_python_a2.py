import pytest

from finance_solution_builder.python_service import python_solution, validate_python
from finance_solution_builder.service import CASHFLOW, solution_for
from finance_solution_builder.method_assets import runtime_reference, installation_digest, runtime_files, parse_content
from test_directory import PROJECT


def combinations():
    units = [['account.balance.query'], ['wealth.facts.describe'], sorted(CASHFLOW)]
    return [[c for i, unit in enumerate(units) if bits & (1 << i) for c in unit] for bits in range(1, 8)]


@pytest.mark.parametrize('caps', combinations())
def test_python_seven_units_share_java_recipe(caps):
    value = python_solution(caps, 'a2')
    java = solution_for(caps, 'a2')
    assert dict(value, runtime=java['runtime']) == java
    assert set(validate_python(value)) == set(caps)
    assert set(value['capabilityBindings']) == set(caps)


@pytest.mark.parametrize('caps', [['finance.transaction-summary.query'], ['finance.spending-analysis.query'],
    ['account.balance.query', 'finance.transaction-summary.query'], [], ['transfer.submit'], ['account.balance.query'] * 2])
def test_python_a2_rejects_incomplete_or_unknown_units(caps):
    with pytest.raises(ValueError):
        python_solution(caps, 'a2')


def test_python_method_uses_same_versioned_reference_not_runtime_code():
    data = parse_content({'name': 'Summary audit', 'content': 'Describe month and missing facts.',
        'capabilityIds': ['finance.transaction-summary.query'],
        'cases': [{'name': 'month', 'query': 'summary-probe 2026-08'}]})
    files = runtime_files('method-0123456789abcdef', '1.0.0', data)
    ref = runtime_reference(dict(assetId='method-0123456789abcdef', version='1.0.0',
                                installationDigest=installation_digest(files)))
    caps = sorted(CASHFLOW)
    value = python_solution(caps, 'a2-method', ref, data['capabilityIds'])
    java = solution_for(caps, 'a2-method', ref, data['capabilityIds'])
    assert dict(value, runtime=java['runtime']) == java
    assert value['domainPackRefs'] == [ref]
    assert set(validate_python(value)) == set(caps)
    with pytest.raises(ValueError):
        python_solution(['account.balance.query'], 'wrong-scope', ref, data['capabilityIds'])


def test_python_job_keeps_method_snapshot_and_original_reference(harness, tmp_path, monkeypatch):
    from finance_solution_builder.workbench import Jobs
    from finance_solution_builder import method_trials
    jobs = Jobs(PROJECT, harness, data_dir=tmp_path/'jobs')
    data = parse_content({'name': 'Balance SOP', 'content': 'Check scope first.',
        'capabilityIds': ['account.balance.query'], 'cases': [{'name': 'balance', 'query': 'balance-probe'}]})
    draft = jobs.method_assets.create('owner', data)
    trial = jobs.method_assets.record_trial('owner', draft['assetId'], draft, {'status':'PASS', 'runtimeVerified':True})
    release = jobs.method_assets.publish('owner', draft['assetId'], 1, '1.0.0', trial['trialId'])
    snapshot = jobs.method_assets.release('owner', {k: release[k] for k in ('assetId','version','releaseDigest')})
    observed = []
    monkeypatch.setattr(method_trials, 'reusable_seed', lambda *args: tmp_path/'java-seed')
    monkeypatch.setattr(jobs.builder, 'build', lambda *args, **kwargs: observed.append((args, kwargs)))
    try:
        job = jobs.create(dict(runtime='openjiuwen-python-0.1.18', model='configured-model',
            connection='local-simulator', capabilities=data['capabilityIds']), 'owner', method_snapshot=snapshot)
        jobs.queue.shutdown(wait=True)
        assert len(observed) == 1
        assert observed[0][1]['method_snapshot'] == snapshot
        import json
        solution = json.loads((jobs.inputs/(job['id']+'.json')).read_text())
        assert solution['domainPackRefs'] == [runtime_reference(snapshot)]
        assert solution['runtime']['language'] == 'python'
    finally:
        jobs.close()
