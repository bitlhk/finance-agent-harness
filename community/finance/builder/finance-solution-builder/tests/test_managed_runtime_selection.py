from copy import deepcopy
import base64
import json
from pathlib import Path
import shutil
import sys
import types

import pytest

from finance_solution_builder import managed_runtime_support as support
from finance_solution_builder.component_assembly import AssemblyRejected, ComponentAssembly
from finance_solution_builder.component_plan import qualify_solution
from finance_solution_builder.unified_catalog import unified_catalog
from finance_solution_builder.workbench_contracts import (
    ApiError, ManagedRuntimeWorkbenchSelection, UnifiedWorkbenchSelection, parse_selection, preflight,
)
from tests.test_component_assembly import PROJECT, root
from tests.test_managed_selection import selection_for
from tests.test_unified_selection import frozen_method


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value))


@pytest.fixture
def candidate(root, tmp_path, monkeypatch):
    """Declarations only; this fixture is not a qualified or executable Agent."""
    project = tmp_path / 'project'
    shutil.copytree(PROJECT / 'templates/local-readonly-service', project / 'templates/local-readonly-service')
    shutil.copytree(PROJECT / 'managed-support', project / 'managed-support')
    # Candidate-only tests retain an isolated declaration; the shipped index is AVAILABLE.
    index_path = project / support.INDEX
    index = json.loads(index_path.read_text())
    index['runtimes'][0].update(status='CANDIDATE', enabled=False)
    write(index_path, index)
    host = project / 'templates/agentscope-query-service'
    host.mkdir(parents=True)
    (host / 'pom.xml').write_text('<project/>')
    value = json.loads((project / 'templates/local-readonly-service/host-support.v0.1.json').read_text())
    value['runtime'] = deepcopy(support.RUNTIME)
    write(host / 'host-support.v0.1.json', value)
    catalog_path = root / 'catalog/finance-assets.v1.json'
    catalog = json.loads(catalog_path.read_text())
    for entries in catalog.values():
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if isinstance(entry, dict) and 'runtimeFamilies' in entry.get('compatibility', {}):
                entry['compatibility']['runtimeFamilies'].append('agentscope')
    write(catalog_path, catalog)
    record = support.declarations(project)[0]
    sdk_profile = {'schemaVersion': 'agentscope-query-support.v0.1', 'family': 'agentscope', 'language': 'java',
                   'sdkVersion': '2.0.3', 'operation': 'QUERY', 'profiles': [{
                       'profile': record['hosts'][0]['profileRef'],
                       'allowedCapabilityIds': ['account.balance.query', 'finance.transaction-summary.query', 'finance.spending-analysis.query']}],
                   'providers': [{'id': 'non-ai-banking-reference', 'version': '0.1.0-SNAPSHOT',
                                  'artifactRef': 'maven://io.github.bitlhk.finance:finance-provider-non-ai-banking:0.1.0-SNAPSHOT',
                                  'connectionKind': 'synthetic-bank-http.v1'}]}
    write(project / 'templates' / record['supportProfileSource'].removeprefix('builder-template://'), sdk_profile)
    monkeypatch.setenv(support.CANDIDATE_ENV, support.RUNTIME_ID)
    return project, root


def body(candidate, caps=None, **updates):
    project, harness = candidate
    caps = caps or ['account.balance.query']
    directory = unified_catalog(project, harness)['templateModuleDirectory']
    record = support.declarations(project)[0]
    return {'extension_type': 'finance-workbench.selection', 'schema_version': '0.10',
            'runtimeId': record['id'], 'hostId': record['hosts'][0]['id'], 'profileRef': record['hosts'][0]['profileRef'],
            'applicationTemplateId': 'banking-readonly-web-v1', 'bankConnectionId': 'local-simulator',
            'scenarioTemplateId': 'mobile-banking', 'directoryRevision': directory['revision'],
            'selectedModelRefs': ['model-a'], 'defaultModelRef': 'model-a', 'modelCatalogRevision': 'a' * 64,
            'moduleIds': [m['id'] for m in directory['modules'] if set(m['capabilityIds']) & set(caps)],
            'packBindings': selection_for(caps)['packBindings'], 'capabilityIds': sorted(caps), **updates}


def resolve(candidate, request=None, snapshot=None):
    return parse_selection(request or body(candidate)).resolve_details(*candidate, 'managed-scope-test', snapshot)


def test_shipped_agentscope_runtime_is_available_after_task_qualification():
    record = support.declarations(PROJECT)[0]
    assert record['status'] == 'AVAILABLE' and record['enabled'] is True
    assert record['description'] == 'Java · 2.0.3 · 查询与办理'


@pytest.mark.parametrize('caps', [['account.balance.query'], ['finance.transaction-summary.query', 'finance.spending-analysis.query'],
                                 ['account.balance.query', 'finance.transaction-summary.query', 'finance.spending-analysis.query']])
def test_explicit_native_binding_preserves_original_assets_and_freezes_display(candidate, caps, monkeypatch):
    request = body(candidate, caps)
    def forbidden(*args, **kwargs):
        pytest.fail('new runtime cannot first select the legacy Host')
    monkeypatch.setattr('finance_solution_builder.managed_selection.solution_from_selection', forbidden)
    selected = parse_selection(request)
    assert isinstance(selected, ManagedRuntimeWorkbenchSelection) and isinstance(selected, UnifiedWorkbenchSelection)
    solution, directory, plan = selected.resolve_details(*candidate, 'managed-scope-test')
    assert solution['runtime'] == support.RUNTIME == plan['runtime']
    assert set(solution['capabilityBindings']) == set(caps)
    assert plan['runtimeBinding'] == directory['runtimeBinding']
    assert plan['managedTemplate']['source'] == 'builder-template://agentscope-query-service'
    assert directory['runtimePresentation'] == plan['runtimeBinding']['runtimePresentation']
    assert directory['runtimePresentation']['label'] == 'AgentScope Java'
    assert directory['runtimePresentation']['runtimeId'] == support.RUNTIME_ID
    assert directory['runtimePresentation']['description'] == support.declarations(candidate[0])[0]['description']
    assert plan['runtimeBinding']['capabilitySelection'] == 'ATOMIC_PACK'
    assert plan['evidence']['installation'] == 'NOT_OBSERVED'
    assert 'runtimeObservationAdmission' not in plan
    assert solution == selected.resolve_details(*candidate, 'managed-scope-test')[0]


def test_catalog_discovery_does_not_enable_scope_or_all_financial_components(candidate, monkeypatch):
    monkeypatch.delenv(support.CANDIDATE_ENV)
    catalog = unified_catalog(*candidate)
    directory = catalog['templateModuleDirectory']
    runtime = next(r for r in directory['runtimes'] if r['id'] == support.RUNTIME_ID)
    assert runtime['status'] == 'CANDIDATE' and runtime['enabled'] is False
    assert runtime['selectionSchemaVersion'] == '0.10' and runtime['invocationProtocol'] == 'finance-chat'
    assert runtime['hostOptions'][0]['profileRef']['id'] == 'local-read-only'
    assert runtime['description'] == support.declarations(candidate[0])[0]['description']
    assert {r['id'] for r in catalog['directory']['runtimes']} == {r['id'] for r in directory['runtimes']}
    old = next(r for r in directory['runtimes'] if r['id'] == 'openjiuwen-python-0.1.18')
    assert old['selectionSchemaVersion'] == '0.9'
    assert old['description'] == 'Python · 0.1.18 · 只读查询'
    assert old['architectureLabel'] == 'Python Agent · Harness 执行桥'
    java = next(r for r in directory['runtimes'] if r['id'] == 'openjiuwen-java-0.1.15')
    assert java['description'] == 'Java · 0.1.15 · 查询与办理'
    assert java['architectureLabel'] == '原生 Java Agent'
    wealth = next(e for e in catalog['entries'] if e['pack']['id'] == 'wealth-facts-description')
    assert support.RUNTIME_ID not in wealth['runtimeIds']
    balance = next(e for e in catalog['entries'] if e['pack']['id'] == 'account-balance-readonly')
    assert support.RUNTIME_ID in balance['runtimeIds'] and balance['runtimeHostOptions']


@pytest.mark.parametrize('configured', ['', 'other-runtime', 'agentscope-java-2.0.30', support.RUNTIME_ID])
def test_only_explicit_candidate_environment_projects_selectable_runtime(candidate, configured):
    project, harness = candidate
    original = (project / support.INDEX).read_bytes()
    expected = configured == support.RUNTIME_ID
    catalog = unified_catalog(project, harness, environment={support.CANDIDATE_ENV: configured})
    for view in ('directory', 'templateModuleDirectory'):
        runtime = next(r for r in catalog[view]['runtimes'] if r['id'] == support.RUNTIME_ID)
        assert runtime['status'] == ('AVAILABLE' if expected else 'CANDIDATE')
        assert runtime['enabled'] is expected and runtime['candidateEnabled'] is expected
        assert runtime['declaredStatus'] == 'CANDIDATE' and runtime['declaredEnabled'] is False
        assert runtime['requiredObservation'] == 'BACKEND_PREFLIGHT_REQUIRED'
        assert runtime['installation'] == 'NOT_OBSERVED'
    balance = next(e for e in catalog['entries'] if e['pack']['id'] == 'account-balance-readonly')
    compatibility = next(r for r in balance['runtimeCompatibility'] if r['runtimeId'] == support.RUNTIME_ID)
    assert compatibility['enabled'] is expected and compatibility['candidateEnabled'] is expected
    assert compatibility['qualification'] == 'DECLARATION_COMPATIBLE'
    assert (project / support.INDEX).read_bytes() == original


@pytest.mark.parametrize('change', [{'mainClass': 'unsafe.Main'}, {'templatePath': '/tmp/host'},
                                   {'runtimeId': 'agentscope-java-2.0.4'}, {'runtimeId': 'openjiuwen-java-0.1.15'},
                                   {'grantedScopes': ['all']}, {'schema_version': '0.11'}])
def test_requests_cannot_choose_code_or_unknown_versions(candidate, change):
    with pytest.raises(ApiError, match='SELECTION_INVALID'):
        parse_selection(body(candidate, **change))


def test_old_09_still_rejects_scope_and_new_fields(candidate):
    value = body(candidate, schema_version='0.9')
    with pytest.raises(ApiError, match='SELECTION_INVALID'):
        parse_selection(value)
    value.update(runtimeId='openjiuwen-java-0.1.15')
    value.pop('hostId'); value.pop('profileRef')
    old = parse_selection(value)
    assert type(old) is UnifiedWorkbenchSelection
    assert old.resolve(*candidate, 'old-version')['runtime']['family'] == 'openjiuwen'


def test_candidate_is_disabled_without_operator_opt_in(candidate, monkeypatch):
    monkeypatch.delenv(support.CANDIDATE_ENV)
    with pytest.raises(AssemblyRejected, match='MANAGED_RUNTIME_DISABLED'):
        resolve(candidate)


@pytest.mark.parametrize('change', [{'hostId': 'different-host'}, {'profileRef': {'id': 'local-read-only', 'version': '0.1.0', 'source': 'repo://profiles/other.md'}},
                                   {'profileRef': {'id': 'local-read-only', 'version': '9', 'source': 'repo://profiles/local-read-only.md'}},
                                   {'presentation': 'ATM_WEB'}])
def test_explicit_host_profile_and_presentation_mismatch(candidate, change):
    with pytest.raises(ValueError, match='MANAGED_HOST_BINDING_MISMATCH'):
        resolve(candidate, body(candidate, **change))


def test_no_matching_sdk_source_and_wrong_sdk_version_are_rejected(candidate):
    project, harness = candidate
    record = support.declarations(project)[0]
    path = project / 'templates' / record['supportProfileSource'].removeprefix('builder-template://')
    value = json.loads(path.read_text())
    value['sdkVersion'] = '2.0.4'
    write(path, value)
    with pytest.raises(AssemblyRejected, match='MANAGED_RUNTIME_VERSION_MISMATCH'):
        support.supported_selection(project, ComponentAssembly(harness), record, record['hosts'][0], selection_for(['account.balance.query'])['packBindings'])


@pytest.mark.parametrize('source', ['repo://integrations/agentscope-java/reference/support.json',
    'builder-template://other-host/agentscope-support.v0.1.json',
    'builder-template://agentscope-query-service/../support.json', 'file:///tmp/support.json'])
def test_runtime_declaration_rejects_unfixed_support_source(candidate, source):
    project, _ = candidate
    path = project / support.INDEX
    document = json.loads(path.read_bytes())
    document['runtimes'][0]['supportProfileSource'] = source
    write(path, document)
    with pytest.raises(AssemblyRejected, match='MANAGED_RUNTIME_DECLARATION_INVALID'):
        support.declarations(project)


def test_frozen_managed_support_does_not_read_cli_source(candidate, tmp_path):
    project, harness = candidate
    assert harness.resolve() == (tmp_path / 'harness').resolve()
    record = support.declarations(project)[0]
    host = record['hosts'][0]
    path = project / 'templates/agentscope-query-service/host-support.v0.1.json'
    before = support.freeze_binding(project, record, host, path, harness=harness)
    write(harness / 'integrations/agentscope-java/reference/support.json', {'unrelatedCli': 'changed'})
    after = support.freeze_binding(project, record, host, path, harness=harness)
    assert after == before and before['supportProfile']['source'] == support.SUPPORT_PROFILE_SOURCE
    _, selected = support.supported_selection(project, ComponentAssembly(harness), record, host,
        selection_for(['account.balance.query'])['packBindings'])
    assert selected['evidence']['supportProfile'] == before['supportProfile']
    assert not any('integrations/agentscope-java/reference' in key for key in selected['evidence']['files'])


def test_missing_host_observation_feature_and_task_never_qualify(candidate):
    project, harness = candidate
    record = support.declarations(project)[0]
    path = project / 'templates/agentscope-query-service/host-support.v0.1.json'
    value = json.loads(path.read_text())
    value['features'].remove('observation.v0.1')
    write(path, value)
    with pytest.raises(AssemblyRejected, match='MANAGED_HOST_BINDING_MISMATCH'):
        support.resolve_binding(project, support.RUNTIME_ID, record['hosts'][0]['id'], record['hosts'][0]['profileRef'])
    value['features'].append('observation.v0.1')
    write(path, value)
    import yaml
    packpath = harness / 'capability-packs/account-balance-readonly/pack.yaml'
    pack = yaml.safe_load(packpath.read_text())
    pack['capabilities'][0]['operation'] = 'TASK'
    packpath.write_text(yaml.safe_dump(pack))
    with pytest.raises(AssemblyRejected, match='ASSEMBLY_OPERATION_UNSUPPORTED'):
        support.supported_selection(project, ComponentAssembly(harness), record, record['hosts'][0], selection_for(['account.balance.query'])['packBindings'])


def test_two_hosts_same_profile_require_exact_host_and_duplicate_full_binding_rejected(candidate):
    project, _ = candidate
    path = project / support.INDEX
    index = json.loads(path.read_text())
    other = deepcopy(index['runtimes'][0]['hosts'][0])
    query_profile = deepcopy(other['profileRef'])
    other.update(id='other-scope', templateSource='builder-template://other-scope')
    shutil.copytree(project / 'templates/agentscope-query-service', project / 'templates/other-scope')
    index['runtimes'][0]['hosts'].insert(0, other)
    write(path, index)
    request = body(candidate, hostId='agentscope-query-service', profileRef=query_profile)
    assert resolve(candidate, request)[2]['runtimeBinding']['managedTemplate']['source'] == 'builder-template://agentscope-query-service'
    index['runtimes'][0]['hosts'].reverse()
    write(path, index)
    assert resolve(candidate, body(candidate, hostId='agentscope-query-service', profileRef=query_profile))[2]['runtimeBinding']['hostId'] == 'agentscope-query-service'
    original = next(host for host in index['runtimes'][0]['hosts'] if host['id'] == 'agentscope-query-service')
    index['runtimes'][0]['hosts'].append(deepcopy(original))
    write(path, index)
    with pytest.raises(AssemblyRejected, match='MANAGED_HOST_AMBIGUOUS'):
        resolve(candidate, body(candidate, hostId=original['id'], profileRef=query_profile))


def test_support_symlink_or_parent_symlink_cannot_select_host(candidate, tmp_path):
    project, _ = candidate
    host = project / 'templates/agentscope-query-service'
    destination = tmp_path / 'external-host'
    shutil.move(host, destination)
    host.symlink_to(destination, target_is_directory=True)
    record = support.declarations(project)[0]
    with pytest.raises(AssemblyRejected, match='MANAGED_HOST_SOURCE_INVALID'):
        support.resolve_binding(project, record['id'], record['hosts'][0]['id'], record['hosts'][0]['profileRef'])


def test_frozen_template_and_support_tampering_is_rejected(candidate):
    project, harness = candidate
    solution, _, plan = resolve(candidate)
    (project / 'templates/agentscope-query-service/pom.xml').write_text('<changed/>')
    with pytest.raises(AssemblyRejected, match='MANAGED_HOST_BINDING_MISMATCH'):
        qualify_solution(project, harness, solution, runtime_binding=plan['runtimeBinding'])


def test_private_method_scope_retained_and_no_seed_reuse(candidate):
    snapshot = frozen_method(['account.balance.query'])
    ref = {k: snapshot[k] for k in ('assetId', 'version', 'releaseDigest')}
    solution, _, _ = resolve(candidate, body(candidate, methodRef=ref), snapshot)
    assert any(r['id'] == snapshot['assetId'] for r in solution['domainPackRefs'])
    changed = deepcopy(snapshot)
    changed['releaseDigest'] = 'sha256:' + 'b' * 64
    with pytest.raises(ValueError, match='ASSEMBLY_METHOD_SNAPSHOT_MISMATCH'):
        resolve(candidate, body(candidate, methodRef=ref), changed)
    with pytest.raises(ApiError, match='SELECTION_INVALID'):
        parse_selection(body(candidate, methodRef=ref, reuseFromBuildId='agent-' + 'a' * 16))


@pytest.mark.parametrize('backend_result', [None, {}, {'status': 'READY'}, {'status': 'READY', 'requiredSatisfied': False}])
def test_missing_or_unready_actual_required_admission_blocks_preflight(candidate, monkeypatch, backend_result):
    backend = types.ModuleType('finance_solution_builder.candidate_backend.agentscope_java')
    if backend_result is not None:
        backend.preflight_managed_admission = lambda *a, **k: backend_result
    monkeypatch.setitem(sys.modules, backend.__name__, backend)
    selected = parse_selection(body(candidate))
    with pytest.raises(ApiError, match='MANAGED_RUNTIME_QUALIFICATION_(REQUIRED|REJECTED)'):
        preflight(selected, *candidate, 'mvn', {support.CANDIDATE_ENV: support.RUNTIME_ID})


def test_failed_required_admission_never_reaches_queue(candidate, tmp_path, monkeypatch):
    from finance_solution_builder.workbench import Jobs
    from finance_solution_builder.workbench_api import WorkbenchApi
    backend = types.ModuleType('finance_solution_builder.candidate_backend.agentscope_java')
    backend.preflight_managed_admission = lambda *a, **k: {'status': 'BLOCKED', 'requiredSatisfied': False}
    monkeypatch.setitem(sys.modules, backend.__name__, backend)
    jobs = Jobs(*candidate, data_dir=tmp_path / 'jobs')
    monkeypatch.setattr(jobs.queue, 'submit', lambda *args: pytest.fail('unqualified request queued'))
    try:
        with pytest.raises(ApiError, match='MANAGED_RUNTIME_QUALIFICATION_REJECTED'):
            WorkbenchApi(jobs).post('/api/v1/builds', body(candidate), 'owner-a')
        assert not jobs.jobs and not list(jobs.inputs.iterdir())
    finally:
        jobs.close()


@pytest.mark.parametrize('code', sorted(support.SAFE_ERRORS))
def test_managed_safe_codes_remain_api_errors(candidate, monkeypatch, code):
    from finance_solution_builder.workbench_api import public_error
    selected = parse_selection(body(candidate))
    def rejected(*args, **kwargs):
        raise AssemblyRejected(code)
    monkeypatch.setattr(ManagedRuntimeWorkbenchSelection, 'resolve_details', rejected)
    with pytest.raises(ApiError) as caught:
        preflight(selected, *candidate, 'mvn', {support.CANDIDATE_ENV: support.RUNTIME_ID})
    assert caught.value.code == code and caught.value.status == 409
    assert public_error(caught.value) == (409, {'code': code, 'retryable': False})


def test_direct_queue_validation_retains_safe_code_and_does_not_write(candidate, tmp_path, monkeypatch):
    from finance_solution_builder.workbench import Jobs
    from finance_solution_builder.workbench_api import public_error
    request = body(candidate)
    selected = parse_selection(request)
    monkeypatch.delenv(support.CANDIDATE_ENV)
    jobs = Jobs(*candidate, data_dir=tmp_path / 'queue-validation')
    monkeypatch.setattr(jobs.queue, 'submit', lambda *args: pytest.fail('disabled runtime queued'))
    try:
        with pytest.raises(AssemblyRejected) as caught:
            jobs.create(selected.legacy(), 'owner', selection=request)
        assert public_error(caught.value) == (409, {'code': 'MANAGED_RUNTIME_DISABLED', 'retryable': False})
        assert not jobs.jobs and not list(jobs.inputs.iterdir()) and not list(jobs.records.iterdir())
    finally:
        jobs.close()


def test_partial_cashflow_pack_rejected_before_queue_without_silent_expansion(candidate, tmp_path, monkeypatch):
    from finance_solution_builder.workbench import Jobs
    from finance_solution_builder.workbench_api import WorkbenchApi
    request = body(candidate, ['finance.transaction-summary.query', 'finance.spending-analysis.query'])
    request['capabilityIds'] = ['finance.transaction-summary.query']
    original = deepcopy(request)
    jobs = Jobs(*candidate, data_dir=tmp_path / 'partial-jobs')
    monkeypatch.setattr(jobs.queue, 'submit', lambda *args: pytest.fail('partial package queued'))
    try:
        with pytest.raises(ApiError, match='MODULE_SELECTION_MISMATCH'):
            WorkbenchApi(jobs).post('/api/v1/builds', request, 'owner')
        assert request == original and not jobs.jobs and not list(jobs.inputs.iterdir())
    finally:
        jobs.close()


def test_frozen_sdk_support_source_changes_rejected(candidate):
    project, harness = candidate
    solution, _, plan = resolve(candidate)
    profile_path = project / 'templates' / plan['runtimeBinding']['supportProfile']['source'].removeprefix('builder-template://')
    value = json.loads(profile_path.read_text())
    value['profiles'][0]['allowedCapabilityIds'].remove('account.balance.query')
    write(profile_path, value)
    with pytest.raises(AssemblyRejected, match='MANAGED_HOST_BINDING_MISMATCH'):
        qualify_solution(project, harness, solution, runtime_binding=plan['runtimeBinding'])


def test_shared_source_closure_participates_in_binding_and_catalog_revision(candidate):
    project, harness = candidate
    template = project / 'templates/agentscope-query-service'
    common = project / 'templates/local-readonly-service'
    (common / 'shared.txt').write_text('first shared content')
    write(template / 'shared-sources.json', {'schemaVersion': 'finance-template-shared-sources.v0.1',
        'sourceTemplate': 'local-readonly-service', 'javaPackage': 'io/test', 'main': [], 'test': [],
        'files': ['shared.txt'], 'excludedRuntimeSources': []})
    solution, _, plan = resolve(candidate)
    old_directory = unified_catalog(project, harness)['templateModuleDirectory']['revision']
    assert plan['managedTemplate']['sharedSources']['builder-template://local-readonly-service/shared.txt']
    assert plan['managedTemplate']['digest'] != plan['managedTemplate']['localDigest']
    (common / 'shared.txt').write_text('second shared content')
    assert unified_catalog(project, harness)['templateModuleDirectory']['revision'] != old_directory
    with pytest.raises(AssemblyRejected, match='MANAGED_HOST_BINDING_MISMATCH'):
        qualify_solution(project, harness, solution, runtime_binding=plan['runtimeBinding'])


def test_shared_source_manifest_cannot_escape_or_add_unknown_fields(candidate):
    project, _ = candidate
    manifest = {'schemaVersion': 'finance-template-shared-sources.v0.1', 'sourceTemplate': 'local-readonly-service',
                'javaPackage': 'io/test', 'main': [], 'test': [], 'files': ['../../secret'], 'excludedRuntimeSources': []}
    path = project / 'templates/agentscope-query-service/shared-sources.json'
    write(path, manifest)
    record, host, _, support_path = support.resolve_binding(project, support.RUNTIME_ID, 'agentscope-query-service',
        support.declarations(project)[0]['hosts'][0]['profileRef'])
    with pytest.raises(AssemblyRejected, match='MANAGED_HOST_SOURCE_INVALID'):
        support.freeze_binding(project, record, host, support_path)
    manifest['files'] = []
    manifest['mainClass'] = 'unsafe.Main'
    write(path, manifest)
    with pytest.raises(AssemblyRejected, match='MANAGED_HOST_SOURCE_INVALID'):
        support.freeze_binding(project, record, host, support_path)


def publish_fixture_method(store, source=None, *, data=None):
    data = data or {'name': 'Runtime boundary fixture', 'content': '# Method\nUse only returned facts.',
                   'capabilityIds': ['account.balance.query'],
                   'cases': [{'name': 'balance', 'query': 'My balance?', 'expectedTerms': []}]}
    draft = store.create('owner', data, source=source)
    frozen = store.trial_input('owner', draft['assetId'], draft['revision'])
    trial = store.record_trial('owner', draft['assetId'], frozen, {
        'status': 'PASS', 'runtimeVerified': True, 'model': 'CONTROLLED_TEST',
        'trialRuntime': {'family': 'openjiuwen', 'language': 'java', 'source': 'GENERATED_JAVA_HOST'}})
    reference = store.publish('owner', draft['assetId'], draft['revision'], '1.0.0', trial['trialId'], data['capabilityIds'])
    reference = {k: reference[k] for k in ('assetId', 'version', 'releaseDigest')}
    return reference, *store.release_trial('owner', reference)


@pytest.mark.parametrize('requirements', [None, {}, {'runtimeFamilies': ['openjiuwen'], 'runtimeLanguages': ['java']},
    {'runtimeFamilies': ['agentscope'], 'runtimeLanguages': ['python']},
    {'runtimeFamilies': 'agentscope', 'runtimeLanguages': ['java']},
    {'runtimeFamilies': ['agentscope'], 'runtimeLanguages': []}])
def test_frozen_import_requirements_reject_at_selection_and_build(candidate, requirements):
    snapshot = frozen_method(['account.balance.query'])
    ref = {k: snapshot[k] for k in ('assetId', 'version', 'releaseDigest')}
    solution, _, plan = resolve(candidate, body(candidate, methodRef=ref), snapshot)
    snapshot['source'] = {'kind': 'BUSINESS_PACKAGE_IMPORTED', 'manifest': {'requirements': requirements}}
    original = deepcopy(snapshot)
    with pytest.raises(AssemblyRejected, match='MANAGED_METHOD_RUNTIME_UNSUPPORTED'):
        resolve(candidate, body(candidate, methodRef=ref), snapshot)
    with pytest.raises(AssemblyRejected, match='MANAGED_METHOD_RUNTIME_UNSUPPORTED'):
        qualify_solution(*candidate, solution, snapshot, runtime_binding=plan['runtimeBinding'])
    assert snapshot == original


def test_real_release_export_import_keeps_oj_requirements_and_blocks_scope_queue(candidate, tmp_path, monkeypatch):
    from finance_solution_builder.business_packages import export_release, draft_from_archive
    from finance_solution_builder.workbench import Jobs
    from finance_solution_builder.workbench_api import WorkbenchApi, public_error
    from tests.test_business_packages import _fixture_anchor
    project, harness = candidate
    fixture = PROJECT / 'tests/fixtures/business-assets'
    for name in ('engineering/business_package.py',
                 'contracts/json-schema/package/finance-business-component-package.v0.1.schema.json'):
        path = harness / name
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(fixture / name, path)
    anchor = _fixture_anchor(harness)
    jobs = Jobs(project, harness, data_dir=tmp_path / 'method-boundary', business_package_anchor=anchor)
    monkeypatch.setattr(jobs.queue, 'submit', lambda *args: pytest.fail('incompatible method queued'))
    monkeypatch.setattr(support, 'require_backend_admission', lambda *args, **kwargs: pytest.fail('incompatible method reached REQUIRED hook'))
    try:
        caps = ['finance.transaction-summary.query', 'finance.spending-analysis.query']
        data = {'name': 'Cashflow boundary fixture', 'content': '# Method\nUse only returned cashflow facts.',
                'capabilityIds': caps, 'cases': [{'name': 'cashflow', 'query': 'My cashflow?', 'expectedTerms': []}]}
        original_ref, original_release, original_trial = publish_fixture_method(jobs.method_assets, data=data)
        assert 'requirements' not in original_release and 'manifest' not in original_release['source']
        # A private method release has only trial provenance; do not invent package requirements.
        _, _, original_plan = resolve(candidate, body(candidate, caps, methodRef=original_ref), original_release)
        assert original_plan['evidence']['installation'] == 'NOT_OBSERVED'
        assert 'runtimeObservationAdmission' not in original_plan
        exported = export_release(original_release, original_trial, harness, anchor)
        imported = draft_from_archive(base64.b64decode(exported['base64']), 'Imported fixture', harness, anchor=anchor)
        assert imported['dependencyStatus']['status'] == 'ADMISSIBLE'
        ref, release, trial = publish_fixture_method(jobs.method_assets, imported['source'], data=imported['data'])
        assert release['source']['manifest'] == exported['manifest']
        assert release['source']['manifest']['requirements']['runtimeFamilies'] == ['openjiuwen']
        assert release['source']['manifest']['requirements']['runtimeLanguages'] == ['java']
        before = deepcopy(release)
        request = body(candidate, caps, methodRef=ref)
        with pytest.raises(ApiError, match='MANAGED_METHOD_RUNTIME_UNSUPPORTED') as caught:
            WorkbenchApi(jobs).post('/api/v1/builds', request, 'owner')
        assert public_error(caught.value) == (409, {'code': 'MANAGED_METHOD_RUNTIME_UNSUPPORTED', 'retryable': False})
        with pytest.raises(AssemblyRejected, match='MANAGED_METHOD_RUNTIME_UNSUPPORTED'):
            jobs.create(parse_selection(request).legacy(), 'owner', selection=request)
        old_request = dict(request, schema_version='0.9', runtimeId='openjiuwen-java-0.1.15')
        old_request.pop('hostId'); old_request.pop('profileRef')
        assert parse_selection(old_request).resolve(project, harness, 'old-oj-method', release)['runtime']['family'] == 'openjiuwen'
        assert jobs.method_assets.release_trial('owner', ref) == (before, trial)
        assert not jobs.jobs and not list(jobs.inputs.iterdir()) and not list(jobs.records.iterdir())
    finally:
        jobs.close()


@pytest.mark.parametrize('entry', ['wrapper', 'selection-preflight'])
def test_real_backend_requalifies_forwarded_private_method(candidate, tmp_path, monkeypatch, entry):
    from finance_solution_builder.candidate_backend import agentscope_java as backend
    from finance_solution_builder.method_assets import MethodAssets
    ref, snapshot, _ = publish_fixture_method(MethodAssets(tmp_path / 'private-method'))
    selected = parse_selection(body(candidate, methodRef=ref))
    solution, _, plan = selected.resolve_details(*candidate, 'method-forwarding', snapshot)
    env = {name: 'isolated-test-not-a-qualification' for name in (
        'FH_AGENTSCOPE_ADMISSION_LOCK_SHA256', 'FH_OBSERVATION_REQUIREMENTS', *backend.QUALIFICATION_ENV)}
    env.update({support.CANDIDATE_ENV: support.RUNTIME_ID,
                'FH_AGENTSCOPE_ADMISSION_CANDIDATE': str(tmp_path / 'no-qualified-artifact')})
    qualified = []
    actual_qualify = backend.qualify_solution
    def track_actual_qualification(*args, **kwargs):
        result = actual_qualify(*args, **kwargs)
        qualified.append(deepcopy(args[3]))
        return result
    monkeypatch.setattr(backend, 'qualify_solution', track_actual_qualification)
    monkeypatch.setattr(backend.subprocess, 'run', lambda *a, **k: pytest.fail('unqualified artifact executed'))
    original = deepcopy(snapshot)
    if entry == 'wrapper':
        with pytest.raises(AssemblyRejected, match='MANAGED_RUNTIME_QUALIFICATION_REJECTED'):
            support.require_backend_admission(*candidate, solution, plan['runtimeBinding'], env, method_snapshot=snapshot)
    else:
        with pytest.raises(ApiError, match='MANAGED_RUNTIME_QUALIFICATION_REJECTED'):
            preflight(selected, *candidate, 'mvn', env, method_snapshot=snapshot)
    # The real backend's real assembly check must finish with this snapshot; no READY stub is used.
    assert qualified == [original] and snapshot == original
    qualified.clear()
    with pytest.raises(AssemblyRejected, match='MANAGED_RUNTIME_QUALIFICATION_REJECTED'):
        support.require_backend_admission(*candidate, solution, plan['runtimeBinding'], env)
    assert qualified == []
    incompatible = deepcopy(snapshot)
    incompatible['source'] = {'kind': 'BUSINESS_PACKAGE_IMPORTED', 'manifest': {
        'requirements': {'runtimeFamilies': ['openjiuwen'], 'runtimeLanguages': ['java']}}}
    with pytest.raises(AssemblyRejected, match='MANAGED_RUNTIME_QUALIFICATION_REJECTED'):
        support.require_backend_admission(*candidate, solution, plan['runtimeBinding'], env, method_snapshot=incompatible)
    assert qualified == []
