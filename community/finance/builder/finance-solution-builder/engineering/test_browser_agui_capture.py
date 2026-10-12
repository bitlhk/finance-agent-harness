"""Real loopback HTTP/browser probes of the test-only Fetch clone observer."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import threading
from urllib.request import urlopen

import pytest
from playwright.sync_api import sync_playwright

from browser_agui_capture import CAPTURE_AGUI, read_agui_capture


@pytest.fixture
def local_browser():
    calls, bank_calls, payloads = [], [], {}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            if self.path.startswith('/bank/read?'):
                bank_calls.append(self.path)
                raw = b'{"amount":"12345.67","currency":"CNY"}'
            else:
                calls.append(('GET', self.path))
                raw = b'<html><head><link rel="icon" href="data:,"></head><body><main id="answers"></main></body></html>'
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_POST(self):
            raw = self.rfile.read(int(self.headers.get('Content-Length', 0)))
            value = json.loads(raw)
            calls.append(('POST', self.path))
            run = value['runId']
            if self.path.endswith('/api/ag-ui'):
                with urlopen(f'http://127.0.0.1:{self.server.server_port}/bank/read?runId={run}') as response:
                    fact = json.load(response)
            else:
                fact = {'amount': '12345.67', 'currency': 'CNY'}
            text = '第' + run + '轮，账户余额：' + fact['amount'] + ' ' + fact['currency']
            events = [{'type': 'RUN_STARTED', 'runId': run, 'threadId': 'session'},
                      {'type': 'TEXT_MESSAGE_CONTENT', 'messageId': run, 'delta': text},
                      {'type': 'RUN_FINISHED', 'runId': run, 'threadId': 'session'}]
            data = ''.join('data: ' + json.dumps(e, ensure_ascii=False) + '\n\n' for e in events).encode('utf-8')
            if run == 'invalid': data = b'data: \xff\n\n'
            if run == 'oversize': data = b'x' * (2 * 1024 * 1024 + 1)
            if run == 'empty': data = b''
            payloads[run] = data
            self.send_response(204 if run == 'empty' else 200)
            # Deliberately omit charset: the observer must retain actual Fetch bytes.
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('X-Probe', 'original-response')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            try:
                for start in range(0, len(data), 65537):
                    self.wfile.write(data[start:start + 65537])
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever)
    thread.start()
    base = f'http://127.0.0.1:{server.server_port}'
    try:
        with sync_playwright() as pw:
            executable = os.environ.get('FH_TEST_CHROMIUM', '/home/ubuntu/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome')
            assert Path(executable).is_file(), 'TEST_CHROMIUM_REQUIRED'
            browser = pw.chromium.launch(executable_path=executable, headless=True)
            try:
                context = browser.new_context()
                context.add_init_script(CAPTURE_AGUI)
                page = context.new_page()
                errors = []
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.goto(base)
                yield page, base, calls, bank_calls, payloads, errors
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def fetch_once(page, base, run, path='/experience/own/api/ag-ui'):
    return page.evaluate('''async ({url,run}) => {
        const response = await fetch(url, {method:'POST', headers:{'Content-Type':'application/json'},
            body:JSON.stringify({query:'请查询账户余额', runId:run})});
        const bytes = new Uint8Array(await response.arrayBuffer());
        const text = new TextDecoder('utf-8').decode(bytes);
        const deltas = [];
        if (text.startsWith('data:') && run !== 'invalid') {
            for (const block of text.split('\\n\\n').filter(Boolean)) {
                const event = JSON.parse(block.slice(6));
                if (event.type === 'TEXT_MESSAGE_CONTENT') deltas.push(event.delta);
            }
        }
        const node = document.createElement('article'); node.dataset.runId = run;
        node.textContent = deltas.join(''); document.querySelector('#answers').append(node);
        return {status:response.status, url:response.url, contentType:response.headers.get('content-type'),
            marker:response.headers.get('x-probe'), text, byteLength:bytes.length,
            rawBase64:bytes.length < 10000 ? btoa(Array.from(bytes,x=>String.fromCharCode(x)).join('')) : null,
            dom:node.textContent, bodyUsed:response.bodyUsed};
    }''', {'url': base + path, 'run': run})


def test_two_real_turns_original_headers_raw_hash_dom_and_exact_two_bank_reads(local_browser):
    import base64
    page, base, calls, bank_calls, payloads, errors = local_browser
    initial_gets = [c for c in calls if c[0] == 'GET']
    evidence = []
    for run in ('one', 'two'):
        response = fetch_once(page, base, run)
        text, captured = read_agui_capture(page, run, response['status'], response['url'])
        raw = payloads[run]
        assert response['status'] == 200 and response['marker'] == 'original-response'
        assert response['contentType'] == captured['contentType'] == 'text/event-stream'
        assert response['bodyUsed'] is True
        assert base64.b64decode(response['rawBase64']) == raw
        assert text == response['text'] == raw.decode('utf-8')
        assert captured['byteLength'] == response['byteLength'] == len(raw)
        assert captured['sha256'] == hashlib.sha256(raw).hexdigest()
        delta = json.loads(raw.decode().split('\n\n')[1][6:])['delta']
        assert response['dom'] == page.locator('[data-run-id="' + run + '"]').text_content() == delta
        evidence.append({'runId': run, 'rawBytes': len(raw), 'sha256': captured['sha256'], 'domMatches': True})
    assert [c for c in calls if c[0] == 'GET'] == initial_gets
    assert len([c for c in calls if c[0] == 'POST']) == 2
    assert len(bank_calls) == 2
    assert not errors
    print(json.dumps({'turns': evidence, 'aguiPosts': 2, 'bankHttpGets': 2, 'additionalGets': 0,
                      'boundary': 'LOCAL_REAL_HTTP_FETCH_CLONE_NOT_MODEL_LIVE'}))


def test_non_target_request_is_not_captured(local_browser):
    page, base, calls, bank_calls, _, errors = local_browser
    for run, path in [('ordinary', '/ordinary'), ('suffix', '/not-api/ag-ui')]:
        fetch_once(page, base, run, path)
    assert page.evaluate('Object.keys(window.__fhAguiCaptures)') == []
    assert len([c for c in calls if c[0] == 'POST']) == 2
    assert bank_calls == [] and not errors


def test_duplicate_run_id_rejected_without_refetch(local_browser):
    page, base, calls, bank_calls, _, _ = local_browser
    fetch_once(page, base, 'duplicate')
    fetch_once(page, base, 'duplicate')
    with pytest.raises(AssertionError, match='DUPLICATE_CAPTURE_RUN'):
        read_agui_capture(page, 'duplicate', 200, base + '/experience/own/api/ag-ui')
    assert len([c for c in calls if c[0] == 'POST']) == len(bank_calls) == 2


@pytest.mark.parametrize('run', ['invalid', 'oversize'])
def test_invalid_utf8_and_size_limit_fail_without_changing_original_body(local_browser, run):
    page, base, calls, bank_calls, payloads, errors = local_browser
    response = fetch_once(page, base, run)
    assert response['byteLength'] == len(payloads[run]) and response['marker'] == 'original-response'
    with pytest.raises(AssertionError, match='CAPTURE_BODY_UNAVAILABLE'):
        read_agui_capture(page, run, response['status'], response['url'])
    assert len([c for c in calls if c[0] == 'POST']) == len(bank_calls) == 1
    assert not errors


def test_response_identity_mismatch_rejected(local_browser):
    page, base, _, _, _, _ = local_browser
    response = fetch_once(page, base, 'identity')
    for status, url in [(500, response['url']), (200, base + '/wrong')]:
        with pytest.raises(AssertionError, match='CAPTURE_RESPONSE_IDENTITY'):
            read_agui_capture(page, 'identity', status, url)


def test_empty_response_capture_finishes_without_unhandled_error(local_browser):
    page, base, calls, bank_calls, _, errors = local_browser
    response = fetch_once(page, base, 'empty')
    assert response['status'] == 204 and response['byteLength'] == 0
    page.wait_for_timeout(100)
    state = page.evaluate('window.__fhAguiCaptures.empty')
    assert state['done'] is True, 'EMPTY_RESPONSE_CAPTURE_MUST_TERMINATE'
    if not state.get('error'):
        from verify_agentscope_workbench import agui_result
        text, captured = read_agui_capture(page, 'empty', 204, response['url'])
        assert text == '' and captured['byteLength'] == 0
        with pytest.raises(AssertionError, match='AGUI_FAILED_OR_EMPTY'):
            agui_result(text, {'runId': 'empty'}, {'sessionId': 'session'})
    assert not errors
    assert len([c for c in calls if c[0] == 'POST']) == len(bank_calls) == 1
