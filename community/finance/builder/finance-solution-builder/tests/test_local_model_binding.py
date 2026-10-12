import json

import pytest

from finance_solution_builder.local_model_binding import bind_configured_model
from finance_solution_builder.model_registry import ModelRegistry, ModelRegistryError
from finance_solution_builder.workbench_contracts import start_readiness


def legacy_env():
    return {'FH_MODEL_NAME': 'deepseek-v4-flash', 'FH_MODEL_URL': 'https://example.test/v1',
            'FH_MODEL_KEY': 'private-canary', 'FH_BANK_URL': 'http://127.0.0.1:12345',
            'FH_BANK_LOGIN': 'demo', 'FH_BANK_SECRET': 'fixture'}


def test_explicit_binding_is_single_model_and_secret_free(tmp_path):
    env = legacy_env()
    before = ModelRegistry(env)
    old = before.snapshot(['model-ea-002'], 'model-ea-002', before.catalog_revision)
    assert not start_readiness(old, env)['configurationReady']
    bound = bind_configured_model(env, tmp_path, 'model-ea-002')
    registry = ModelRegistry(bound)
    assert registry.status('model-ea-002') == 'CONFIGURED_UNTESTED'
    assert registry.status('model-ea-001') == 'UNBOUND'
    assert env == legacy_env()
    text = (tmp_path / 'model-bindings.json').read_text()
    assert 'private-canary' not in text and 'example.test' not in text
    assert (tmp_path / 'model-bindings.json').stat().st_mode & 0o777 == 0o600
    policy = tmp_path / 'policy.json'
    policy.write_text('{}')
    bound['FH_ACCESS_POLICY'] = str(policy)
    readiness = start_readiness(old, bound)
    assert readiness['blockers'][0]['code'] == 'MODEL_CONFIGURATION_CHANGED'
    assert readiness['blockers'][0]['requiresRebuild']
    fresh = registry.snapshot(['model-ea-002'], 'model-ea-002', registry.catalog_revision)
    assert start_readiness(fresh, bound)['configurationReady']
    assert start_readiness(fresh, bound)['connectivityStatus'] == 'NOT_CHECKED'
    assert bind_configured_model(env, tmp_path, 'model-ea-002') == {k:v for k,v in bound.items() if k != 'FH_ACCESS_POLICY'}


@pytest.mark.parametrize('changes,ref', [({}, 'model-ea-001'), ({'FH_MODEL_KEY': ''}, 'model-ea-002'),
    ({'FH_MODEL_URL': 'https://name:password@example.test'}, 'model-ea-002')])
def test_invalid_binding_does_not_write_config(tmp_path, changes, ref):
    with pytest.raises(ModelRegistryError):
        bind_configured_model(legacy_env() | changes, tmp_path, ref)
    assert not (tmp_path / 'model-bindings.json').exists()


def test_disabled_registry_cannot_be_overwritten_by_restart(tmp_path):
    bound = bind_configured_model(legacy_env(), tmp_path, 'model-ea-002')
    path = tmp_path / 'model-bindings.json'
    data = json.loads(path.read_text())
    data['bindings'][0]['enabled'] = False
    path.write_text(json.dumps(data))
    with pytest.raises(ModelRegistryError, match='EXISTING_REGISTRY_USE_DIRECTLY'):
        bind_configured_model(legacy_env(), tmp_path, 'model-ea-002')
    assert ModelRegistry(bound).status('model-ea-002') == 'DISABLED'
