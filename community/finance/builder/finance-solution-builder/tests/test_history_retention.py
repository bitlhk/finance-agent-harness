from datetime import datetime, timezone
import json
import os
from test_execution_history import saved
from finance_solution_builder.history_retention import prune


def test_prune_defaults_to_preview_and_preserves_live_process(tmp_path, monkeypatch):
    store, directory, identity = saved(tmp_path)
    path = directory / (identity + '.jsonl')
    records = [json.loads(line) for line in path.read_text().splitlines()]
    records[0]['pid'] = os.getpid()
    path.write_text('\n'.join(map(json.dumps, records)))
    now = datetime(2026, 12, 1, tzinfo=timezone.utc)
    assert prune(tmp_path, 'owner-a', now=now, apply=True)['eligible'] == []
    def absent(*args): raise ProcessLookupError()
    monkeypatch.setattr(os, 'kill', absent)
    assert prune(tmp_path, 'owner-a', now=now)['eligible'] == [identity]
    assert path.exists()
    assert prune(tmp_path, 'owner-b', now=now, apply=True)['deleted'] == []
    assert prune(tmp_path, 'owner-a', now=now, apply=True)['deleted'] == [identity]
    assert not path.exists()
    assert not (directory / (identity + '.service-lock.json')).exists()


def test_unfinished_and_recent_records_are_not_removed(tmp_path, monkeypatch):
    _, directory, identity = saved(tmp_path, terminal=False)
    path = directory / (identity + '.jsonl')
    records = [json.loads(line) for line in path.read_text().splitlines()]
    records[0]['pid'] = 99999999
    path.write_text('\n'.join(map(json.dumps, records)))
    def absent(*args): raise ProcessLookupError()
    monkeypatch.setattr(os, 'kill', absent)
    assert prune(tmp_path, 'owner-a', now=datetime(2026,12,1,tzinfo=timezone.utc), apply=True)['deleted'] == []
    assert path.exists()
