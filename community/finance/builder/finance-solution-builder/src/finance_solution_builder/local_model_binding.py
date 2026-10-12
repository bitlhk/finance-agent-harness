"""Explicit operator migration of one configured legacy model, never a fallback."""
import json
from pathlib import Path

from .model_registry import ModelRegistry, ModelRegistryError
from .service import write_json


def bind_configured_model(environment, workspace: Path, model_ref: str):
    env = dict(environment)
    if env.get('FH_MODEL_REGISTRY'):
        raise ModelRegistryError('EXISTING_REGISTRY_USE_DIRECTLY')
    registry = ModelRegistry(env)
    model = registry.models.get(model_ref)
    if (model is None or model['modelName'] != env.get('FH_MODEL_NAME')
            or model['provider'] not in {'OpenAI', 'OpenRouter'}):
        raise ModelRegistryError('MODEL_BINDING_IDENTITY_MISMATCH')
    env['FH_CONN_LOCAL_URL'] = env.get('FH_MODEL_URL', '')
    env['FH_CONN_LOCAL_KEY'] = env.get('FH_MODEL_KEY', '')
    binding = {'modelRef': model_ref, 'revision': 'local-v1', 'enabled': True,
               'protocol': 'OPENAI_CHAT_COMPLETIONS', 'provider': model['provider'],
               'modelName': model['modelName'], 'urlEnvironment': 'FH_CONN_LOCAL_URL',
               'keyEnvironment': 'FH_CONN_LOCAL_KEY'}
    registry.bindings[model_ref] = binding
    # Validate the copied environment rather than inferring connectivity from EA metadata.
    registry.env = env
    if registry.status(model_ref) != 'CONFIGURED_UNTESTED':
        raise ModelRegistryError('MODEL_CONNECTION_UNAVAILABLE')
    path = workspace / 'model-bindings.json'
    document = {'schemaVersion': 'finance-model-bindings.v1', 'bindings': [binding]}
    if path.exists():
        if path.is_symlink() or json.loads(path.read_text()) != document:
            raise ModelRegistryError('EXISTING_REGISTRY_USE_DIRECTLY')
    else:
        workspace.mkdir(parents=True, exist_ok=True)
        write_json(path, document)
    path.chmod(0o600)
    env['FH_MODEL_REGISTRY'] = str(path.resolve())
    return env
