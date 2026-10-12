"""Isolated UI regression: real bundles, synthetic API/SSE, no model/bank calls."""
import argparse
import json
import mimetypes
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import sync_playwright
from finance_solution_builder.workbench_contracts import catalog


def verify(harness, output, chromium):
    root = Path(__file__).resolve().parents[1]
    output.mkdir(parents=True, exist_ok=True)
    checks = []
    def check(name, value):
        assert value, name
        checks.append(name)
        print(name, flush=True)
    cat = catalog(harness)
    for m in cat['models']:
        m['bindingStatus'] = 'CONFIGURED_UNTESTED' if m['modelRef'] == 'model-ea-002' else 'UNBOUND'
    records = [dict(buildId=f'agent-{n}', buildStatus='SUCCEEDED',
                    instance={'status': 'READY' if n == 3 else 'STOPPED'},
                    selection={'capabilityIds':['account.balance.query'],
                               'interfaceConfig':{'assistantName':f'测试助手 {n}'}}) for n in range(4)]
    attempts = []
    terminal = 'DESKTOP_WEB'
    ends = []
    text = '\n\n'.join(f'第 {n} 项资产记录，来源：受控测试；金额 123.45 CNY。' for n in range(35))
    snapshot = [{'role':'user','text':'历史问题'}, {'role':'assistant','text':text}]
    def route(r):
        nonlocal records
        path = urlparse(r.request.url).path
        data = None
        if path == '/api/bootstrap': data = {'csrf':'test','deploymentMode':'LOCAL'}
        elif path == '/api/v1/catalog': data = cat
        elif path == '/api/v1/connections': data = {'connections':[]}
        elif path == '/api/v1/builds': data = {'builds':records}
        elif path == '/api/v1/assets': data = {'assets':[]}
        elif path.endswith('/delete'):
            id = path.split('/')[-2]; attempts.append(id)
            if id in ('agent-1','agent-3'):
                r.fulfill(status=409,json={'error':{'code':'INSTANCE_RUNNING'}}); return
            records = [b for b in records if b['buildId'] != id]; data = {}
        elif '/api/' in path:
            action = path.split('/')[-1]
            if action == 'config':
                data = {'configuration':{'terminal':terminal,'theme':'FINANCE_RED','assistantName':'金融助手',
                        'shortcuts':['BALANCE'],'fontSize':'STANDARD','sourcePanelExpanded':True},
                        'capabilities':['account.balance.query'],'sessionId':'session-test'}
            elif action == 'snapshot': data = {'messages':snapshot,'lifecycle':{'status':'ACTIVE'}}
            elif action == 'start': data = {'sessionId':'session-test','lifecycle':{'status':'ACTIVE'}}
            elif action == 'ag-ui':
                body = r.request.post_data_json; run = body['runId']
                events = [{'type':'RUN_STARTED','threadId':'session-test','runId':run},
                          {'type':'CUSTOM','name':'finance.display','value':{'text':text,'display':{'schemaVersion':'finance-display.v0.1','cards':[]}}},
                          {'type':'RUN_FINISHED','threadId':'session-test','runId':run}]
                r.fulfill(content_type='text/event-stream',body=''.join('data: '+json.dumps(e)+'\n\n' for e in events)); return
            else:
                if action == 'end': ends.append(path)
                data = {'status':'ACTIVE','remainingSeconds':300}
        if data is not None: r.fulfill(json=data); return
        directory = root/'examples'/('agent-experience' if path.startswith('/experience/') else 'local-workbench')
        file = directory/(path.split('/')[-1] or 'index.html')
        if not file.is_file(): r.fulfill(status=404); return
        r.fulfill(content_type=mimetypes.guess_type(file.name)[0] or 'application/octet-stream',body=file.read_bytes())
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=chromium,headless=True)
        context = browser.new_context(viewport={'width':1440,'height':950})
        context.route('https://studio.test/**',route)
        page = context.new_page(); errors = []
        page.on('pageerror',lambda e: errors.append(str(e)))
        page.goto('https://studio.test/')
        page.locator('[data-catalog="models"]').click()
        check('only_configured_model_selectable',page.locator('[data-pick]:enabled').count()==1)
        check('unbound_model_not_selected',page.locator('[data-pick="model-ea-001"]').is_disabled())
        page.screenshot(path=str(output/'models.png'))
        page.keyboard.press('Escape')
        page.locator('.sidebar-nav [data-nav="agents"]').click()
        page.locator('#select-all-agents').check()
        check('select_all_excludes_running',page.locator('[data-agent-checkbox]:checked').count()==3)
        page.locator('#select-all-agents').uncheck()
        check('clear_selection_disables_delete',page.locator('[data-action="batch-delete"]').is_disabled())
        page.locator('[data-select-agent="agent-0"] h2').click()
        page.locator('[data-select-agent="agent-1"]').focus(); page.keyboard.press('Space')
        check('card_and_keyboard_multiselect',page.locator('[data-agent-checkbox]:checked').count()==2)
        check('running_not_selectable',page.locator('[data-agent-checkbox="agent-3"]').is_disabled())
        page.screenshot(path=str(output/'selection.png'))
        page.locator('[data-action="batch-delete"]').click()
        check('nothing_deleted_before_confirmation',not attempts)
        page.locator('#batch-delete-confirm').click()
        page.wait_for_function("document.querySelector('#toast').textContent.includes('1 个未删除')")
        check('partial_failure_and_unselected_preserved',attempts==['agent-0','agent-1'] and {b['buildId'] for b in records}=={'agent-1','agent-2','agent-3'})
        check('failed_selection_retained',page.locator('[data-agent-checkbox="agent-1"]').is_checked())
        for terminal,width in [('DESKTOP_WEB',1440),('MOBILE_WEB',390),('ATM_WEB',1280)]:
            page.set_viewport_size({'width':width,'height':950})
            page.goto('https://studio.test/experience/test/')
            if terminal == 'ATM_WEB': page.locator('[data-action="start"]').click()
            else:
                page.locator('.assistant-message').wait_for()
                page.wait_for_timeout(100)
                check(terminal+'_restored_at_latest',page.locator('.messages').evaluate('(e)=>e.scrollHeight-e.clientHeight-e.scrollTop<3'))
                if terminal == 'DESKTOP_WEB':
                    page.locator('.messages').evaluate('(e)=>e.scrollTop=100')
                    page.locator('[data-action="panel"]').click()
                    check('reading_position_preserved',abs(page.locator('.messages').evaluate('(e)=>e.scrollTop')-100)<3)
                page.locator('#query').fill('新消息')
                check(terminal+'_no_inner_focus_outline',page.locator('#query').evaluate('(e)=>getComputedStyle(e).outlineStyle')=='none')
                page.locator('[type="submit"]').click()
            if terminal == 'ATM_WEB': page.locator('[data-query="BALANCE"]').click()
            page.wait_for_function("!document.querySelector('.run-progress') && document.querySelector('.assistant-message')")
            page.wait_for_timeout(100)
            check(terminal+'_new_reply_at_bottom',page.locator('.messages').evaluate('(e)=>e.scrollHeight-e.clientHeight-e.scrollTop<3'))
            check(terminal+'_no_horizontal_overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
            page.screenshot(path=str(output/(terminal+'.png')))
            if terminal == 'MOBILE_WEB':
                page.set_viewport_size({'width':320,'height':740})
                check('small_mobile_header_fits',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
                page.screenshot(path=str(output/'mobile-320.png'))
            before = len(ends)
            page.locator('[data-action="home"]').click()
            page.locator('.welcome').wait_for()
            check(terminal+'_home_ends_session',len(ends)==before+1 and page.locator('.assistant-message').count()==0)
        page.locator('[data-action="workbench"]').click()
        page.wait_for_url('https://studio.test/')
        page.locator('[data-catalog="models"]').wait_for()
        check('returns_to_workbench',page.locator('[data-catalog="models"]').count()==1)
        check('no_browser_errors',not errors)
        browser.close()
    (output/'checks.json').write_text(json.dumps({'mode':'ISOLATED_UI_SYNTHETIC_API','checks':checks},ensure_ascii=False,indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--harness',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--chromium',required=True)
    a=parser.parse_args(); verify(a.harness,a.output,a.chromium)
