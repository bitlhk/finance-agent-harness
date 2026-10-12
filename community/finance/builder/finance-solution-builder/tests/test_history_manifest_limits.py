import hashlib
import json
import os

import pytest

from finance_solution_builder.execution_history import ExecutionHistory, MAX_MANIFEST_BYTES
from test_execution_history import saved


def replace_lock(directory, identity, data):
    path = directory / (identity + '.jsonl')
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    rows[0]['serviceLockDigest'] = hashlib.sha256(data).hexdigest()
    path.write_text('\n'.join(json.dumps(row) for row in rows) + '\n')
    (directory / (identity + '.service-lock.json')).write_bytes(data)


def test_65849_manifest_is_verified_without_http(tmp_path, monkeypatch):
    store, directory, identity = saved(tmp_path)
    monkeypatch.setattr('urllib.request.urlopen', lambda *a, **kw: pytest.fail('history cannot execute HTTP'))
    data = json.dumps({'runtime': {'language': 'python'}, 'padding': ''}, separators=(',', ':')).encode()
    data = data[:-2] + b'x' * (65849 - len(data)) + data[-2:]
    assert len(data) == 65849
    replace_lock(directory, identity, data)
    result = store.get('owner-a', identity)
    assert result['complete']
    assert result['versions']['service']['verified']
    assert result['versions']['service']['runtime']['language'] == 'python'


@pytest.mark.parametrize('data', [b'{"truncated":', b'[]', b'{}{}', b'x' * (MAX_MANIFEST_BYTES + 1)])
def test_invalid_manifest_is_a_gap_not_a_failed_business_result(tmp_path, data):
    store, directory, identity = saved(tmp_path)
    replace_lock(directory, identity, data)
    result = store.get('owner-a', identity)
    assert result['status'] == 'COMPLETED'
    assert not result['complete']
    assert 'SERVICE_LOCK_UNAVAILABLE' in result['gaps']
    assert 'service' not in result['versions']


def test_manifest_read_fault_is_explicit_gap(tmp_path, monkeypatch):
    store, directory, identity = saved(tmp_path)
    read = ExecutionHistory._read
    def failed(path, *args):
        if path.name.endswith('.service-lock.json'):
            raise OSError('injected filesystem read error')
        return read(path, *args)
    monkeypatch.setattr(ExecutionHistory, '_read', staticmethod(failed))
    result = store.get('owner-a', identity)
    assert result['status'] == 'COMPLETED' and not result['complete']
    assert 'SERVICE_LOCK_UNAVAILABLE' in result['gaps']


def test_manifest_byte_tamper_still_fails_integrity(tmp_path):
    store, directory, identity = saved(tmp_path)
    (directory / (identity + '.service-lock.json')).write_text('{"runtime":{"language":"other"}}')
    result = store.get('owner-a', identity)
    assert not result['versions']['service']['verified']
    assert 'SERVICE_LOCK_DIGEST_UNVERIFIED' in result['gaps']


def test_missing_history_preserves_safe_api_error(tmp_path):
    with pytest.raises(PermissionError, match='^HISTORY_UNAVAILABLE$'):
        ExecutionHistory._read(tmp_path / 'missing.jsonl')


@pytest.mark.parametrize('fault', ['deleted', 'symlink', 'replaced'])
def test_history_open_race_preserves_nofollow_and_safe_api_error(tmp_path, monkeypatch, fault):
    path = tmp_path / 'history.jsonl'
    path.write_text('{}')
    other = tmp_path / 'other.jsonl'
    other.write_text('{"private":"must not be returned"}')
    original_open = os.open
    def race(target, flags, *args, **kwargs):
        if target == path:
            path.unlink()
            if fault == 'symlink':
                path.symlink_to(other)
            elif fault == 'replaced':
                other.rename(path)
        return original_open(target, flags, *args, **kwargs)
    monkeypatch.setattr(os, 'open', race)
    with pytest.raises(PermissionError, match='^HISTORY_UNAVAILABLE$'):
        ExecutionHistory._read(path)
