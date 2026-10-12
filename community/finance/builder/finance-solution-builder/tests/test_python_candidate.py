import json

import pytest

from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.python_service import RUNTIME_ID, python_solution, validate_python
from finance_solution_builder.candidate_backend.openjiuwen_python import OpenJiuwenPythonBackend
from finance_solution_builder.candidate_backend import BackendRegistry, CandidateBackend, CandidateTarget, LifecycleOperation, PreflightRequest
from finance_solution_builder.workbench import Jobs
from finance_solution_builder.workbench_api import WorkbenchApi
from finance_solution_builder.workbench_contracts import ApiError
from finance_solution_builder.workbench_contracts import InstalledRuntimeSelection, parse_selection
from test_template_modules import selection
from test_directory import PROJECT


def test_trusted_observation_configuration_reaches_owned_bridge(tmp_path, monkeypatch):
    import importlib.util
    source = PROJECT / 'templates/python-readonly-service/finance_python/bridge.py'
    spec = importlib.util.spec_from_file_location('observation_bridge_probe', source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    (tmp_path / 'target').mkdir()
    (tmp_path / 'target/probe.jar').write_bytes(b'isolated-placeholder-no-java-execution')
    monkeypatch.setenv('FH_OBSERVATION_QUALIFICATION_FILE', '/trusted/qualification.json')
    monkeypatch.setenv('FH_OBSERVATION_QUALIFICATION_SHA256', 'a' * 64)
    monkeypatch.setenv('FH_UNRELATED_PRIVATE_VALUE', 'not-forwarded')
    captured = {}
    def launch(*args, **kwargs):
        captured.update(kwargs['env'])
        raise RuntimeError('PROBE_NO_PROCESS')
    monkeypatch.setattr(module.subprocess, 'Popen', launch)
    with pytest.raises(RuntimeError, match='PROBE_NO_PROCESS'):
        module.Bridge(tmp_path)
    assert captured['FH_OBSERVATION_QUALIFICATION_FILE'] == '/trusted/qualification.json'
    assert captured['FH_OBSERVATION_QUALIFICATION_SHA256'] == 'a' * 64
    assert 'FH_UNRELATED_PRIVATE_VALUE' not in captured


def test_installed_lock_excludes_only_relocatable_metadata(tmp_path):
    from finance_solution_builder.python_service import installed_file_hashes
    metadata=tmp_path/'package-1.dist-info'
    metadata.mkdir()
    package=tmp_path/'package'
    package.mkdir()
    (metadata/'RECORD').write_text('path-dependent installer records')
    (metadata/'METADATA').write_text('Name: package')
    (package/'__init__.py').write_text('value=1')
    (package/'RECORD').write_text('real package data')
    before=installed_file_hashes(tmp_path)
    assert set(before)=={'package-1.dist-info/METADATA','package/__init__.py','package/RECORD'}
    (metadata/'RECORD').write_text('new path-dependent installer records')
    assert installed_file_hashes(tmp_path)==before
    (package/'__init__.py').write_text('value=2')
    assert installed_file_hashes(tmp_path)!=before


def test_candidate_python_environment_excludes_builder_import_paths():
    from finance_solution_builder.python_service import isolated_python_environment
    source = {'PYTHONPATH': '/builder/src', 'PYTHONHOME': '/builder/python',
              'VIRTUAL_ENV': '/builder/.venv', 'UV_PROJECT_ENVIRONMENT': '/builder/uv',
              'HTTPS_PROXY': 'http://proxy.test', 'PIP_INDEX_URL': 'https://index.test/simple'}
    result = isolated_python_environment(source)
    assert not {'PYTHONPATH', 'PYTHONHOME', 'VIRTUAL_ENV', 'UV_PROJECT_ENVIRONMENT'} & result.keys()
    assert result['HTTPS_PROXY'] == source['HTTPS_PROXY']
    assert result['PIP_INDEX_URL'] == source['PIP_INDEX_URL']


def test_local_wheel_source_must_be_readable(tmp_path, monkeypatch):
    from finance_solution_builder.python_service import require_readable_local_wheels
    (tmp_path / 'runtime.whl').write_bytes(b'fixture')
    monkeypatch.setattr('finance_solution_builder.python_service.os.access', lambda path, mode: False)
    with pytest.raises(PermissionError, match='PYTHON_RUNTIME_WHEEL_UNREADABLE'):
        require_readable_local_wheels({'PIP_FIND_LINKS': str(tmp_path)})


def test_python_finite_recipe():
    value=python_solution(['account.balance.query'],'test-python')
    assert validate_python(value)==['account.balance.query']
    assert value['runtime']['language']=='python'
    assert len(value['capabilityPackRefs'])==1
    assert value['capabilityPackRefs'][0]['id']=='account-balance-readonly'
    for capabilities in (['wealth.facts.describe'], ['account.balance.query','wealth.facts.describe'],
                         ['finance.transaction-summary.query','finance.spending-analysis.query']):
        assert set(validate_python(python_solution(capabilities,'test')))==set(capabilities)
    for capabilities in ([], ['unknown.query'], ['account.balance.query']*2,
                         ['finance.transaction-summary.query']):
        with pytest.raises(ValueError,match='PYTHON_COMPOSITION_UNSUPPORTED'):
            python_solution(capabilities,'test')
    value['runtime']['integrationRef']='pypi://openjiuwen:999'
    with pytest.raises(ValueError,match='PYTHON_COMPOSITION_UNSUPPORTED'):
        validate_python(value)


@pytest.mark.parametrize('modules,caps', [(['agent.account'], ['account.balance.query']),
    (['agent.wealth_aggregate'], ['wealth.facts.describe']),
    (['agent.account','agent.wealth_aggregate'], ['account.balance.query','wealth.facts.describe'])])
def test_python_page_selection_reaches_python_recipe(harness,tmp_path,monkeypatch,modules,caps):
    jobs=Jobs(PROJECT,harness,data_dir=tmp_path/'jobs')
    calls=[]
    monkeypatch.setattr(jobs.builder,'build',lambda *args,**kwargs:calls.append(args))
    try:
        body=selection(harness,runtimeId=RUNTIME_ID,moduleIds=modules,capabilityIds=caps)
        code,result=WorkbenchApi(jobs).post('/api/v1/builds',body,'owner')
        jobs.queue.shutdown(wait=True)
        assert code==202 and len(calls)==1
        value=json.loads((jobs.inputs/(result['buildId']+'.json')).read_text())
        assert value==python_solution(caps,result['buildId'])
    finally:
        jobs.close()


def test_python_incomplete_cashflow_rejected_before_queue(harness,tmp_path,monkeypatch):
    jobs=Jobs(PROJECT,harness,data_dir=tmp_path/'jobs')
    monkeypatch.setattr(jobs,'create',lambda *args,**kwargs:pytest.fail('invalid combination queued'))
    try:
        body=selection(harness,runtimeId=RUNTIME_ID,moduleIds=['agent.finance_assistant'],
            capabilityIds=['finance.transaction-summary.query'])
        with pytest.raises(ApiError):
            WorkbenchApi(jobs).post('/api/v1/builds',body,'owner')
    finally:
        jobs.close()


def test_python_backend_registry_and_preflight(harness,tmp_path):
    backend=OpenJiuwenPythonBackend(builder=SolutionBuilder(PROJECT,harness),
        solution=python_solution(['account.balance.query'],'agent-test'),output=tmp_path/'workbench/agent-test')
    assert isinstance(backend,CandidateBackend)
    target=CandidateTarget(language='python',runtime_family='openjiuwen',runtime_version='0.1.18',
        artifact_kind='directory-manifest',invocation_protocol='finance-chat',required_operations=tuple(LifecycleOperation),
        required_asset_ids=('account-balance-readonly',),required_capability_ids=('account.balance.query',),
        required_provider_operation_types=('query',),profile_id='local-read-only',host_wiring_ids=('owned-java-pipe',))
    assert BackendRegistry([backend]).select(target) is backend
    result=backend.preflight(PreflightRequest(task_id='agent-test',target=target,resolved_asset_ids=('account-balance-readonly',)))
    assert result.outcome.status.value=='passed'
    bad=target.model_copy(update={'runtime_version':'999'})
    result=backend.preflight(PreflightRequest(task_id='agent-test',target=bad,resolved_asset_ids=('account-balance-readonly',)))
    assert result.outcome.status.value=='blocked'


def test_frozen_python_startup_view_does_not_downcast_to_java(harness):
    selected=parse_selection(selection(harness,runtimeId=RUNTIME_ID))
    fields=InstalledRuntimeSelection.model_fields
    value={k:v for k,v in selected.model_dump().items() if k in fields}
    value['schema_version']='0.3'
    normalized=InstalledRuntimeSelection.model_validate(value)
    assert normalized.runtimeId==RUNTIME_ID and normalized.capabilityIds==['account.balance.query']
    value['capabilityIds']=['wealth.facts.describe']
    assert InstalledRuntimeSelection.model_validate(value).runtimeId==RUNTIME_ID
    value['capabilityIds']=['finance.transaction-summary.query','finance.spending-analysis.query']
    assert InstalledRuntimeSelection.model_validate(value).runtimeId==RUNTIME_ID
    value['capabilityIds']=['finance.transaction-summary.query']
    with pytest.raises(ValueError):
        InstalledRuntimeSelection.model_validate(value)
