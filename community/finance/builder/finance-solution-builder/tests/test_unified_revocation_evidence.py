import importlib.util
import json
from pathlib import Path

FILE = Path(__file__).resolve().parents[1] / 'engineering/unified_browser_selection.py'
spec = importlib.util.spec_from_file_location('unified_browser_selection', FILE)
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)


def test_old_denial_or_failed_request_is_not_current_authorization_evidence(tmp_path):
    file = tmp_path/'request.jsonl'
    denial = dict(component='host',operation='authorization',phase='END',outcome='DENIED')
    file.write_text(json.dumps(denial)+'\n')
    before = helper.history_offsets(tmp_path)
    assert not helper.observed_authorization_denial(tmp_path,before)
    with file.open('a') as stream:
        stream.write(json.dumps(dict(denial,operation='request'))+'\n')
        stream.write(json.dumps(dict(denial,outcome='COMPLETED'))+'\n')
    assert not helper.observed_authorization_denial(tmp_path,before)
    with file.open('a') as stream:
        stream.write(json.dumps(denial)+'\n')
    assert helper.observed_authorization_denial(tmp_path,before)


def test_new_request_denial_is_observed(tmp_path):
    before = helper.history_offsets(tmp_path)
    (tmp_path/'new.jsonl').write_text(json.dumps(dict(component='host',operation='authorization',phase='END',outcome='DENIED'))+'\n')
    assert helper.observed_authorization_denial(tmp_path,before)
