"""Real history API/browser with explicitly synthetic, isolated records; zero execution."""
import argparse
import hashlib
from http.server import ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import sys
import threading
import time
from unittest.mock import patch
import uuid

from playwright.sync_api import sync_playwright, expect
from finance_solution_builder.workbench import serve


def verify(harness, output, chrome):
    output.mkdir(parents=True, exist_ok=False)
    workspace = output / 'workspace'
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tests'))
    from test_execution_history import saved
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    servers = []
    class OwnedServer(ThreadingHTTPServer):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, **kwargs); servers.append(self)
    def launch():
        with patch('finance_solution_builder.workbench.signal.signal'), patch('finance_solution_builder.workbench.ThreadingHTTPServer', OwnedServer):
            serve(harness, port, data_dir=workspace)
    worker = threading.Thread(target=launch, daemon=True); worker.start()
    checks = []; timings = {}; errors = []; outgoing = []
    def check(name, condition):
        assert condition, name
        checks.append(name)
    previous_grant = os.environ.pop('FH_SNAPSHOT_VIEW_OWNERS', None)
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(executable_path=str(chrome), headless=True)
            context = browser.new_context(viewport={'width':1440,'height':1000})
            page = context.new_page(); base = f'http://127.0.0.1:{port}'
            page.on('pageerror', lambda e: errors.append(str(e)))
            for attempt in range(50):
                try: page.goto(base); break
                except Exception:
                    if attempt == 49: raise
                    time.sleep(.1)
            page.locator('[data-nav="history"]').wait_for()
            owner = next(c['value'].split('.')[0] for c in context.cookies() if c['name']=='fh_local')
            owner_hash = hashlib.sha256(owner.encode()).hexdigest()
            identities = []
            for index in range(25):
                _, directory, request = saved(workspace, owner)
                path = directory / (request + '.jsonl')
                rows = [json.loads(line) for line in path.read_text().splitlines()]
                rows[0]['createdAt'] = f'2026-09-13T00:00:{index:02}Z'
                path.write_text('\n'.join(map(json.dumps, rows))+'\n')
                identities.append(request)
            _, directory, request = saved(workspace, owner)
            path = directory / (request + '.jsonl')
            original = [json.loads(line) for line in path.read_text().splitlines()]
            header = original[0]; header['createdAt'] = '2026-09-14T10:00:00Z'
            header['capabilities'] = ['installed.balance', 'installed.transfer', 'installed.preference']
            header['requestType'] = 'CONVERSATION_TURN'
            events = []
            def event(span, component, operation, parent, start, length, links=None, outcome='COMPLETED'):
                for phase in ('START','END'):
                    events.append(dict(schemaVersion='finance-observation.v0.1', sequence=len(events)+1,
                        spanId=span, parentSpanId=parent, phase=phase, component=component, operation=operation,
                        elapsedNanos=start if phase=='START' else start+length, durationNanos=None if phase=='START' else length,
                        outcome='PENDING' if phase=='START' else outcome, links=links or {}, requestId=request,
                        processBootId='boot', instanceId='instance', at='2026-09-14T10:00:00Z', dispatch='NOT_OBSERVED'))
            event('root','host','request',None,0,30_000_000)
            event('model-a','model','attempt','root',1_000_000,8_000_000)
            event('tool-a','runtime-tool','execute','root',10_000_000,3_000_000)
            event('cap-a','capability','query','tool-a',10_100_000,2_500_000, {'capabilityId':'fixture.query','businessStatus':'FAILED'}, 'RETURNED')
            event('check-a','host','authorization','tool-a',10_200_000,200_000,{},'DENIED')
            event('source-a','provider-http','attempt','cap-a',10_400_000,300_000,{'httpStatus':'200'},'RETURNED')
            event('model-b','model','attempt','root',14_000_000,6_000_000)
            events.sort(key=lambda value:value['elapsedNanos'])
            for sequence, value in enumerate(events,1):value['sequence']=sequence
            # This fixture defines event facts for presentation tests, not a claimed real execution.
            rows = [header,*events,original[-1]]
            folder = directory/'model-snapshots'; folder.mkdir(exist_ok=True)
            calls = [str(uuid.uuid4()),str(uuid.uuid4())]
            for index, call in enumerate(calls):
                for part in ('request','response'):
                    body = dict(recordType='model-snapshot',requestId=request,callId=call,
                        spanId='model-a' if index==0 else 'model-b',part=part,
                        boundary='CLIENT_REQUEST_PREPARED' if part=='request' else 'PARSED_MODEL_RESPONSE',
                        state='CAPTURED',filter='SYNTHETIC_TEST',dispatch='NOT_OBSERVED',ownerRef=owner_hash,
                        changes=[],changesOmitted=0,body=json.dumps({'messages':[{'role':'user','content':'UNIQUE_SYNTHETIC_INPUT'},
                            {'role':'tool','content':'synthetic facts, not a bank response'}],
                            'tools':[{'function':{'name':'fixture_query','parameters':{'type':'object'}}}]} if part=='request'
                            else {'content':'UNIQUE_SYNTHETIC_OUTPUT','model':'fixture-model'}))
                    raw = json.dumps(body).encode(); digest = hashlib.sha256(raw).hexdigest()
                    ref = {k:v for k,v in body.items() if k not in {'body','ownerRef'}}
                    ref.update(digest=digest,storage='QUEUED'); rows.append(ref)
                    if index==0:
                        (folder/f'{request}.{call}.{part}.json').write_bytes(raw)
                        rows.append(dict(recordType='model-snapshot-storage',requestId=request,callId=call,part=part,
                                         spanId=body['spanId'],digest=digest,state='WRITTEN',at='2026-09-14T10:00:00Z'))
            path.write_text('\n'.join(map(json.dumps,rows))+'\n')
            failure_request = request
            _, slow_directory, slow_request = saved(workspace, owner)
            slow_path = slow_directory / (slow_request + '.jsonl')
            slow_original = [json.loads(line) for line in slow_path.read_text().splitlines()]
            slow_header = slow_original[0]; slow_header['createdAt'] = '2026-09-14T11:00:00Z'
            slow_header['requestType'] = 'CONVERSATION_TURN'; slow_header['agentName'] = '慢请求验证助手'
            request = slow_request; events = []
            event('slow-root','host','request',None,0,120_000_000)
            event('slow-model','model','attempt','slow-root',2_000_000,90_000_000)
            event('slow-tool','runtime-tool','execute','slow-root',94_000_000,20_000_000)
            event('slow-cap','capability','query','slow-tool',95_000_000,15_000_000,
                  {'capabilityId':'account.balance.query','businessStatus':'COMPLETED'},'RETURNED')
            events.sort(key=lambda value:value['elapsedNanos'])
            for sequence, value in enumerate(events,1):value['sequence']=sequence
            slow_path.write_text('\n'.join(map(json.dumps,[slow_header,*events,slow_original[-1]]))+'\n')
            request = failure_request
            page.on('request',lambda r:outgoing.append((r.method,r.url)))
            start=time.perf_counter();page.locator('[data-nav="history"]').click()
            page.locator('[data-history-id]').first.wait_for();timings['listBrowserMs']=(time.perf_counter()-start)*1000
            check('server_page_twenty',page.locator('[data-history-id]').count()==20)
            first=page.locator('[data-history-id]').evaluate_all('rows=>rows.map(row=>row.dataset.historyId)')
            check('recording_separate_from_execution','证据情况' in page.locator('.history-table').inner_text())
            check('beijing_time_fixed','北京时间 UTC+8' in page.locator('.execution-history').inner_text())
            check('installed_capabilities_not_execution_title','installed.balance' not in page.locator('.history-table').inner_text()
                  and 'installed.transfer' not in page.locator('.history-table').inner_text())
            page.screenshot(path=str(output/'desktop-list.png'),full_page=True)
            page.locator('[data-history-page="next"]').click()
            page.locator('.history-pagination').filter(has_text='第 2 页').wait_for()
            expect(page.locator('[data-history-id]')).to_have_count(7)
            second=page.locator('[data-history-id]').evaluate_all('rows=>rows.map(row=>row.dataset.historyId)')
            check('next_server_page_no_overlap',not set(first)&set(second))
            page.locator('[data-history-page="previous"]').click()
            expect(page.locator('[data-history-id]')).to_have_count(20)
            page.locator(f'[data-history-id="{slow_request}"]').click()
            page.locator('.history-step-evidence').wait_for()
            check('slow_request_defaults_to_model','模型处理与生成' in page.locator('.history-step-evidence').inner_text()
                  and '已确认问题' not in page.locator('.history-findings').inner_text())
            page.screenshot(path=str(output/'desktop-slow.png'),full_page=True)
            page.locator('[data-history-back]').click(); expect(page.locator('[data-history-id]')).to_have_count(20)
            start=time.perf_counter();page.locator(f'[data-history-id="{request}"]').click()
            page.locator('[data-history-tab="model"]').wait_for();timings['detailBrowserMs']=(time.perf_counter()-start)*1000
            check('detail_not_appended_to_list',page.locator('.history-table').count()==0)
            check('failure_visible_without_expansion','FAILED' in page.locator('.execution-history').inner_text()
                  and page.locator('.history-finding.is-error').count()>0
                  and page.locator('.history-step-evidence').count()==1)
            check('failure_defaults_before_longer_success','调用金融能力' in page.locator('.history-step-evidence').inner_text())
            page.locator('[data-history-span-select="tool-a"]').click()
            check('normal_checks_collapsed_failure_visible',page.locator('.history-normal-checks').count()==1
                  and page.locator('.history-failed-checks').count()==1)
            check('waterfall_uses_common_scale',page.locator('.history-water-row').count()==7
                  and page.locator('.history-bar').count()==7)
            page.screenshot(path=str(output/'desktop-process.png'),full_page=True)
            page.locator('[data-history-tab="model"]').click()
            page.locator('[data-snapshot-read]').click();page.locator('[role="alert"]').wait_for()
            check('separate_body_view_grant_required','授权' in page.locator('.execution-history').inner_text()
                  or '拒绝' in page.locator('.execution-history').inner_text())
            os.environ['FH_SNAPSHOT_VIEW_OWNERS']=owner_hash
            page.locator('[data-snapshot-read]').click()
            page.locator('.snapshot-content').filter(has_text='UNIQUE_SYNTHETIC_INPUT').wait_for()
            check('structured_messages_and_tools','工具定义' in page.locator('.snapshot-content').inner_text())
            page.locator('[data-history-part="response"]').click();page.locator('[data-snapshot-read]').click()
            page.locator('.snapshot-content').filter(has_text='UNIQUE_SYNTHETIC_OUTPUT').wait_for()
            check('parsed_response_content_preserved',True)
            page.screenshot(path=str(output/'desktop-model.png'),full_page=True)
            page.locator('[data-history-call]').nth(1).click();page.locator('[data-snapshot-read]').click()
            page.locator('.snapshot-content').wait_for()
            check('missing_body_not_fabricated','UNIQUE_SYNTHETIC_OUTPUT' not in page.locator('.snapshot-content').inner_text()
                  and '现有证据无法确认原因' in page.locator('.snapshot-content').inner_text())
            page.screenshot(path=str(output/'desktop-body-gap.png'),full_page=True)
            page.locator('[data-history-tab="technical"]').click()
            page.screenshot(path=str(output/'desktop-technical.png'),full_page=True)
            page.locator('[data-history-back]').click();page.locator('[data-history-id]').first.wait_for()
            check('back_preserves_page',page.locator('[data-history-id]').count()==20)
            page.set_viewport_size({'width':1024,'height':900})
            check('tablet_list_no_overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'))
            page.screenshot(path=str(output/'tablet-list.png'),full_page=True)
            page.set_viewport_size({'width':390,'height':844})
            check('mobile_list_no_overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'))
            page.screenshot(path=str(output/'mobile-list.png'),full_page=True)
            page.locator(f'[data-history-id="{request}"]').click();page.locator('[data-history-tab="model"]').wait_for()
            check('mobile_detail_no_overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'))
            page.screenshot(path=str(output/'mobile-process.png'),full_page=True)
            page.locator('[data-history-back]').click();page.set_viewport_size({'width':320,'height':760})
            check('narrow_mobile_no_overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth+1'))
            page.screenshot(path=str(output/'narrow-mobile-list.png'),full_page=True)
            other=browser.new_context();other_page=other.new_page();other_page.goto(base)
            other_page.locator('[data-nav="history"]').click()
            other_page.locator('.history-table').filter(has_text='暂无符合条件').wait_for()
            check('cross_owner_empty',other_page.locator('[data-history-id]').count()==0)
            check('cross_owner_detail_denied',other_page.request.get(base+'/api/v1/execution-history/'+request).status==403)
            check('inspection_zero_execution_routes',all(method=='GET' and not url.endswith(('/chat','/ag-ui','/start')) for method,url in outgoing))
            check('no_page_errors',not errors)
            browser.close()
        (output/'report.json').write_text(json.dumps({'mode':'SYNTHETIC_ISOLATED_HISTORY_REAL_API',
            'checks':checks,'timings':timings,'errors':errors,'requests':outgoing,
            'requestId':request,'slowRequestId':slow_request,'recordCount':27,'realModel':'NOT_RUN'},indent=2))
        print(json.dumps({'checks':checks,'timings':timings}))
    finally:
        if previous_grant is None:os.environ.pop('FH_SNAPSHOT_VIEW_OWNERS',None)
        else:os.environ['FH_SNAPSHOT_VIEW_OWNERS']=previous_grant
        for server in servers:server.shutdown()
        worker.join(timeout=20)


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    for name in ('harness','output','chrome'):parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args();verify(args.harness,args.output,args.chrome)
