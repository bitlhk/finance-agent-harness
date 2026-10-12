import json
import hashlib
import subprocess
import sys

import pytest

from finance_solution_builder import workbench
from finance_solution_builder.workbench_api import WorkbenchApi
from finance_solution_builder.service import write_json


@pytest.fixture
def jobs(tmp_path):
    jobs = workbench.Jobs(tmp_path, tmp_path)
    jobs.jobs['agent-delete'] = dict(id='agent-delete', owner='owner', status='BUILT',
                                   capabilities=['account.balance.query'], createdAt=1)
    jobs.save(jobs.jobs['agent-delete'])
    root = jobs.path('agent-delete')
    root.mkdir(parents=True)
    (root / 'large.jar').write_bytes(b'generated-artifact')
    (root / 'instance.log').write_text('private-client-data')
    write_json(root / 'build-report.json', {'status': 'BUILT', 'lockSha256': 'a' * 64,
                                          'private': 'private-client-data'})
    write_json(jobs.inputs / 'agent-delete.json', {'id': 'agent-delete'})
    yield jobs
    jobs.processes.clear()
    jobs.close()


def delete(jobs, owner='owner'):
    return WorkbenchApi(jobs).post('/api/v1/builds/agent-delete/delete', {'buildId': 'agent-delete'}, owner)


def task_scope(jobs):
    root = (jobs.data_dir / 'task-state' / hashlib.sha256(b'owner').hexdigest()
            / 'agent-delete')
    tasks = root / 'tasks' / 'session-one'
    for directory in ('handles', 'confirmations', 'checkpoints'):
        (tasks / directory).mkdir(parents=True, exist_ok=True)
    for name in ('scope.json', 'identity.json', 'source-session.json', 'host.lock'):
        (root / name).write_text('{}')
    return root


def test_delete_stopped_agent_with_only_idle_task_scope(jobs):
    jobs.jobs['agent-delete']['status'] = 'STOPPED'
    scope = task_scope(jobs)
    (scope / 'tasks/session-one/checkpoints/.lock').touch()
    code, result = delete(jobs)
    assert code == 200 and result['status'] == 'DELETED'
    assert not scope.exists()
    assert not jobs.path('agent-delete').exists()


def test_delete_preserves_task_checkpoint(jobs):
    jobs.jobs['agent-delete']['status'] = 'STOPPED'
    scope = task_scope(jobs)
    checkpoint = scope / 'tasks/session-one/checkpoints/task.json'
    checkpoint.write_text('{"status":"UNKNOWN"}')
    with pytest.raises(ValueError, match='TASK_RECOVERY_STATE_RETAINED'):
        delete(jobs)
    assert checkpoint.is_file()
    assert jobs.path('agent-delete').exists()
    from finance_solution_builder.workbench_api import public_error
    status, body = public_error(ValueError('TASK_RECOVERY_STATE_RETAINED'))
    assert status == 409 and body['code'] == 'TASK_RECOVERY_STATE_RETAINED'


def test_delete_rejects_unrecognized_task_state(jobs):
    jobs.jobs['agent-delete']['status'] = 'STOPPED'
    scope = task_scope(jobs)
    (scope / 'unexpected.json').write_text('{}')
    with pytest.raises(ValueError, match='TASK_RECOVERY_STATE_RETAINED'):
        delete(jobs)
    assert scope.exists()


def test_delete_rejects_task_state_symlink(jobs, tmp_path):
    jobs.jobs['agent-delete']['status'] = 'STOPPED'
    scope = task_scope(jobs)
    external = tmp_path / 'external.json'
    external.write_text('keep')
    (scope / 'tasks/session-one/handles/link.json').symlink_to(external)
    with pytest.raises(ValueError, match='DELETE_PATH_INVALID'):
        delete(jobs)
    assert external.read_text() == 'keep'
    assert jobs.path('agent-delete').exists()


def test_delete_rejects_task_scope_locked_by_other_process(jobs):
    jobs.jobs['agent-delete']['status'] = 'STOPPED'
    scope = task_scope(jobs)
    holder = subprocess.Popen(
        [sys.executable, '-c',
         'import fcntl,sys; file=open(sys.argv[1], "r+"); '
         'fcntl.lockf(file, fcntl.LOCK_EX); print("locked", flush=True); sys.stdin.read(1)',
         str(scope / 'host.lock')],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
    )
    try:
        assert holder.stdout.readline().strip() == 'locked'
        with pytest.raises(ValueError, match='TASK_STATE_IN_USE'):
            delete(jobs)
        assert scope.exists()
    finally:
        holder.stdin.write('x')
        holder.stdin.flush()
        holder.wait(timeout=5)


def test_delete_reclaims_only_owned_build_and_keeps_minimal_receipt(jobs):
    sibling = jobs.path('agent-keep')
    sibling.mkdir()
    (sibling / 'keep').touch()
    code, result = delete(jobs)
    assert code == 200 and result['status'] == 'DELETED'
    assert not jobs.path('agent-delete').exists()
    assert not (jobs.inputs / 'agent-delete.json').exists()
    assert not (jobs.records / 'agent-delete.json').exists()
    assert (sibling / 'keep').exists()
    receipts = list((jobs.project / 'build/deletion-receipts').glob('*.json'))
    assert len(receipts) == 1
    text = receipts[0].read_text()
    assert 'private-client-data' not in text and 'owner' not in text
    assert json.loads(text)['artifactLockSha256'] == 'a' * 64
    assert WorkbenchApi(jobs).get('/api/v1/builds', 'owner')[1]['builds'] == []
    with pytest.raises(PermissionError):
        delete(jobs)
    restored = workbench.Jobs(jobs.project, jobs.project)
    try:
        assert 'agent-delete' not in restored.jobs
    finally:
        restored.close()


@pytest.mark.parametrize('status', ['BUILDING', 'QUEUED', 'RUNNING', 'STARTING', 'STOPPING', 'UNMANAGED_AFTER_RESTART'])
def test_delete_rejects_active_or_unmanaged_instance(jobs, status):
    jobs.jobs['agent-delete']['status'] = status
    with pytest.raises(ValueError, match='AGENT_STOP_REQUIRED'):
        delete(jobs)
    assert jobs.path('agent-delete').exists()


def test_delete_rechecks_live_process_even_if_record_is_stopped(jobs):
    jobs.jobs['agent-delete']['status'] = 'STOPPED'
    jobs.processes['agent-delete'] = type('Child', (), {'poll': lambda self: None})()
    with pytest.raises(ValueError, match='AGENT_STOP_REQUIRED'):
        delete(jobs)
    assert jobs.path('agent-delete').exists()


def test_delete_owner_and_confirmation_boundaries(jobs):
    with pytest.raises(PermissionError):
        delete(jobs, 'other')
    with pytest.raises(Exception, match='REQUEST_INVALID'):
        WorkbenchApi(jobs).post('/api/v1/builds/agent-delete/delete', {'buildId': 'agent-keep'}, 'owner')
    assert jobs.path('agent-delete').exists()


def test_delete_does_not_follow_external_artifact_symlink(jobs, tmp_path):
    external = tmp_path / 'external'
    external.mkdir()
    (external / 'keep').touch()
    (jobs.path('agent-delete') / 'external').symlink_to(external, target_is_directory=True)
    delete(jobs)
    assert (external / 'keep').exists()


def test_delete_rejects_redirected_build_root(jobs, tmp_path):
    root = jobs.path('agent-delete')
    moved = root.with_name('agent-kept')
    root.rename(moved)
    root.symlink_to(moved, target_is_directory=True)
    with pytest.raises(ValueError, match='DELETE_PATH_INVALID'):
        delete(jobs)
    assert (moved / 'large.jar').exists()
