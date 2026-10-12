import pytest

from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.service import digest, solution_for, write_json
from finance_solution_builder.python_service import python_solution
from finance_solution_builder.workbench import ENV_KEYS, Jobs
from test_directory import PROJECT


@pytest.mark.parametrize('language', ['java', 'python'])
def test_service_entry_forwards_explicit_observation_asset(harness, tmp_path, monkeypatch, language):
    import finance_solution_builder.service as service
    import finance_solution_builder.candidate_backend.openjiuwen_python as python_backend
    builder = SolutionBuilder(PROJECT, harness, service_output_root=tmp_path)
    value = (solution_for if language == 'java' else python_solution)(['account.balance.query'], 'observation-test')
    source = tmp_path / 'solution.json'
    write_json(source, value)
    monkeypatch.setattr(builder, '_load_and_validate', lambda path: value)
    selected = harness / 'observation-packs/readonly/selection.json'
    captured = {}
    def generate(*args, **kwargs):
        captured.update(kwargs)
        return 'GENERATED'
    if language == 'java':
        monkeypatch.setattr(service, 'build_service', generate)
    else:
        monkeypatch.setattr(python_backend, 'build_python_service', generate)
    assert builder.build(source, tmp_path / 'output', observation_requirements=selected) == 'GENERATED'
    assert captured['observation_requirements'] == selected


@pytest.mark.parametrize('enabled', [False, True])
def test_workbench_uses_only_operator_observation_selection(tmp_path, monkeypatch, enabled):
    jobs = Jobs(tmp_path, tmp_path)
    selected = tmp_path / 'observation-packs/readonly/selection.json'
    monkeypatch.delenv('FH_OBSERVATION_REQUIREMENTS', raising=False)
    if enabled:
        monkeypatch.setenv('FH_OBSERVATION_REQUIREMENTS', str(selected))
    captured = {}
    monkeypatch.setattr(jobs.builder, 'build', lambda *args, **kwargs: captured.update(kwargs))
    jobs.jobs['agent-test'] = {'id': 'agent-test', 'owner': 'owner', 'status': 'QUEUED',
                             'observationRequirements': '/untrusted/request/value'}
    try:
        jobs.build('agent-test')
        assert jobs.jobs['agent-test']['status'] == 'BUILT'
        assert captured.get('observation_requirements') == (selected if enabled else None)
    finally:
        jobs.close()


@pytest.mark.parametrize('language', ['java', 'python'])
def test_owned_host_receives_qualification_not_arbitrary_environment(tmp_path, monkeypatch, language):
    jobs = Jobs(tmp_path, tmp_path)
    candidate = jobs.path('agent-test') / 'candidate'
    (candidate / 'target').mkdir(parents=True)
    (candidate / 'target/service.jar').write_bytes(b'no-execution-probe')
    write_json(candidate / 'service-lock.json', {'runtime': {'language': language}})
    write_json(candidate.parent / 'build-report.json', {'lockSha256': digest(candidate / 'service-lock.json')})
    jobs.jobs['agent-test'] = {'id': 'agent-test', 'owner': 'owner', 'status': 'BUILT'}
    for key in ENV_KEYS:
        monkeypatch.setenv(key, 'isolated-test')
    monkeypatch.setenv('FH_OBSERVATION_QUALIFICATION_FILE', '/trusted/qualification.json')
    monkeypatch.setenv('FH_OBSERVATION_QUALIFICATION_SHA256', 'a' * 64)
    monkeypatch.setenv('FH_UNRELATED_SECRET', 'must-not-forward')
    captured = {}
    def stop_before_process(*args, **kwargs):
        captured.update(kwargs['env'])
        raise RuntimeError('NO_PROCESS_PROBE')
    monkeypatch.setattr('finance_solution_builder.workbench.spawn_process', stop_before_process)
    try:
        with pytest.raises(RuntimeError, match='NO_PROCESS_PROBE'):
            jobs.start('agent-test', 'owner')
        assert captured['FH_OBSERVATION_QUALIFICATION_FILE'] == '/trusted/qualification.json'
        assert captured['FH_OBSERVATION_QUALIFICATION_SHA256'] == 'a' * 64
        assert 'FH_UNRELATED_SECRET' not in captured
    finally:
        jobs.close()
