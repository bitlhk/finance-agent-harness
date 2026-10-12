import hashlib
import json
from pathlib import Path
import subprocess
import pytest
from test_execution_history import saved
from finance_solution_builder.execution_history import display_view


def labels():
    return {'schemaVersion': 'finance-observation-display.v0.1', 'capabilities': [
        {'capabilityId': 'new.query', 'packId': 'customer-pack', 'version': '2.0', 'title': 'Custom inquiry'}],
        'sources': [{'providerId': 'custom-source', 'version': '2.0', 'operations': [
            {'operationId': 'inventory', 'method': 'GET', 'route': '/api/v1/accounts',
             'title': 'Inventory facts', 'description': 'Provider-owned explanation'}]}]}


def test_frozen_description_is_verified_and_tamper_does_not_fall_back(tmp_path):
    store, directory, request = saved(tmp_path)
    data = json.dumps(labels()).encode()
    label_file = directory / (request + '.labels.json')
    label_file.write_bytes(data)
    history = directory / (request + '.jsonl')
    records = [json.loads(line) for line in history.read_text().splitlines()]
    installation = b'{"fixture":"installation"}'
    (directory / (request + '.runtime-lock.json')).write_bytes(installation)
    records.insert(-1, {'recordType': 'installation-display', 'requestId': request,
                       'displayDigest': hashlib.sha256(data).hexdigest(),
                       'installationDigest': hashlib.sha256(installation).hexdigest()})
    records.insert(-1, {'recordType': 'observation-labels', 'requestId': request, 'state': 'RECORDED',
                       'digest': hashlib.sha256(data).hexdigest()})
    history.write_text('\n'.join(map(json.dumps, records)) + '\n')
    assert store.get('owner-a', request)['display']['state'] == 'FROZEN'
    assert store.get('owner-a', request)['display']['capabilities'][0]['title'] == 'Custom inquiry'
    replacement = labels()
    replacement['capabilities'][0]['title'] = 'New version description'
    label_file.write_text(json.dumps(replacement))
    result = store.get('owner-a', request)
    assert result['display']['state'] == 'UNAVAILABLE'
    assert result['display']['capabilities'] == []
    assert result['status'] == 'COMPLETED'
    label_file.unlink()
    assert store.get('owner-a', request)['display']['state'] == 'UNAVAILABLE'


def test_legacy_record_explicitly_marks_interpretation(tmp_path):
    store, _, request = saved(tmp_path)
    assert store.get('owner-a', request)['display']['state'] == 'LEGACY_FALLBACK'


def test_new_request_denied_before_assembly_is_not_legacy(tmp_path):
    store, directory, request = saved(tmp_path)
    path = directory / (request + '.jsonl')
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    rows[0]['observationDisplayVersion'] = 'finance-observation-display.v0.1'
    path.write_text('\n'.join(map(json.dumps, rows)) + '\n')
    assert store.get('owner-a', request)['display']['state'] == 'UNAVAILABLE'


@pytest.mark.parametrize('field,value', [('route', '/accounts?id=customer'), ('method', 'get'),
    ('title', '\x7f'), ('title', ' '), ('title', 'x'*501), ('operationId', None)])
def test_malformed_display_is_rejected(field, value):
    data = labels()
    data['sources'][0]['operations'][0][field] = value
    with pytest.raises(ValueError):
        display_view(data)


def test_later_component_version_does_not_relabel_frozen_history():
    old = display_view(labels())
    new = labels()
    new['sources'][0]['version'] = '3.0'
    new['sources'][0]['operations'][0]['title'] = 'New meaning'
    assert display_view(new)['sources'][0]['version'] == '3.0'
    assert old['sources'][0]['version'] == '2.0'
    assert old['sources'][0]['operations'][0]['title'] == 'Inventory facts'


def test_component_html_is_displayed_as_text():
    module = Path(__file__).resolve().parents[1] / 'browser/execution-history.js'
    value = labels()
    value['state'] = 'FROZEN'
    value['sources'][0]['operations'][0]['title'] = '<img src=x onerror=alert(1)>'
    record = {'requestId': 'request', 'gaps': [], 'display': value, 'spans': [
        {'spanId': 'span', 'component': 'provider-http', 'startNanos': 0, 'durationNanos': 1,
         'links': {'providerId': 'custom-source', 'method': 'GET', 'route': '/api/v1/accounts'}}]}
    script = f"""
      import {{executionHistory}} from {json.dumps(module.as_uri())};
      const esc = v => String(v??'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
      const view = executionHistory({{api:async()=>({json.dumps(record)}),esc,icon:()=>'',render:()=>{{}}}});
      view.click({{target:{{closest: selector=>selector.includes('data-history-id')?{{dataset:{{historyId:'request'}}}}:null}}}});
      await new Promise(resolve=>setTimeout(resolve,0));
      console.log(view.html());
    """
    html = subprocess.check_output(['node', '--input-type=module', '-e', script], text=True)
    assert '<img' not in html and '&lt;img' in html


def test_new_provider_and_capability_render_without_ui_dictionary_change():
    module = Path(__file__).resolve().parents[1] / 'browser/history-labels.js'
    value = labels()
    value['state'] = 'FROZEN'
    script = f"""
      import {{describeSpan}} from {json.dumps(module.as_uri())};
      const display = {json.dumps(value)};
      const span = {{component:'provider-http',links:{{providerId:'custom-source',method:'GET',route:'/api/v1/accounts'}}}};
      console.log(JSON.stringify([
        describeSpan(span,[],display),
        describeSpan({{...span,links:{{...span.links,providerId:'different-source'}}}},[],display),
        describeSpan({{component:'capability',links:{{capabilityId:'new.query'}}}},[],display),
        describeSpan(span)
      ]));
    """
    rows = json.loads(subprocess.check_output(['node', '--input-type=module', '-e', script], text=True))
    assert rows[0][0] == 'Inventory facts' and 'custom-source@2.0' in rows[0][1]
    assert rows[1][0] not in {'Inventory facts', rows[3][0]}
    assert rows[2][0] == 'Custom inquiry'
