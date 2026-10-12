"""Actual shared chat UI + generated Host + isolated persistent simulator; controlled model."""
import argparse
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import sys
import threading
import time
import uuid
import requests
from playwright.sync_api import sync_playwright
from unified_browser_selection import (blank_selection, select_pack, assert_same_packages,
                                       history_offsets, observed_authorization_denial)


def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def verify(args):
    if args.live_model_base and not args.live_model_name:
        raise ValueError('live-model-name must identify the configured model')
    model_name=args.live_model_name if args.live_model_base else 'controlled-shared-task'
    selected_packs = ['account-balance-readonly', 'internal-transfer']
    if args.runtime == 'openjiuwen-java-0.1.15':
        selected_packs.append('channel-preferences')
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=False)
    output.chmod(0o700)
    project = Path(__file__).resolve().parents[1]
    checks, attempts, model_calls, children, logs = [], [], [], [], []
    flags = {'badSubmit': False, 'failQuery': False, 'holdSubmit': False, 'holdModel': False,
             'failFinalAfterTool': False}
    reached, release = threading.Event(), threading.Event()
    model_reached, model_release = threading.Event(), threading.Event()
    def save(name, value):
        path = output / name; path.write_text(json.dumps(value, ensure_ascii=False, indent=2, default=str)); path.chmod(0o600)
        return str(path)
    def check(name, condition):
        checks.append({'name': name, 'passed': bool(condition)})
        save('checks.json', {'checks': checks, 'attempts': attempts})
        assert condition, name
    def spawn(command, env, name, cwd):
        log = (output / name).open('wb'); logs.append(log)
        child = subprocess.Popen(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        children.append(child); return child
    def wait_health(url, child):
        for _ in range(400):
            if child.poll() is not None: raise AssertionError('owned service failed: ' + url)
            try:
                if requests.get(url + '/health', timeout=1).status_code == 200: return
            except requests.RequestException: pass
            time.sleep(.1)
        raise AssertionError('owned service startup timeout')
    bank_url = 'http://127.0.0.1:' + str(free_port())
    bank = spawn(['java', '-Xmx512m', '-jar', str(args.bank_jar.resolve()), '--server.port=' + bank_url.rsplit(':',1)[1]],
                 dict(os.environ, BANKING_DB_URL='jdbc:h2:file:' + str(output / 'bank-db') + ';MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE'), 'bank.log', output)
    proxy = None; wb = None
    try:
        wait_health(bank_url, bank)
        login = requests.post(bank_url + '/api/v1/sessions', json={'loginName':'demo','loginSecret':'demo123','deviceId':'test-oracle','channel':'M'}, timeout=10).json()['data']
        oracle_headers = {'X-Session-Id': login['sessionId'], 'X-Channel':'M'}
        def oracle(path):
            r = requests.get(bank_url + path, headers=oracle_headers, timeout=15); r.raise_for_status()
            return r.json(parse_float=Decimal)['data']
        rows = oracle('/api/v1/accounts'); rows = rows if isinstance(rows,list) else rows['accounts']
        account = next(x['accountId'] for x in rows if x['currency']=='CNY' and x['status']=='NORMAL')
        recipient = next(x['beneficiaryId'] for x in oracle('/api/v1/beneficiaries') if x['internal'] and x['status']=='ACTIVE')
        def ledger():
            values = oracle('/api/v1/accounts'); values = values if isinstance(values,list) else values['accounts']
            return {'balance': next(Decimal(str(x['availableBalance'])) for x in values if x['accountId']==account),
                    'receipts': len(oracle('/api/v1/transfer-receipts')), 'entries': len(oracle('/api/v1/accounts/' + account + '/transactions'))}
        class Proxy(BaseHTTPRequestHandler):
            def log_message(self, *a): pass
            def do_GET(self): self.forward()
            def do_DELETE(self): self.forward()
            def do_POST(self): self.forward()
            def forward(self):
                body = self.rfile.read(int(self.headers.get('Content-Length','0')))
                if self.path.endswith('/chat/completions'):
                    payload = json.loads(body); model_calls.append(payload)
                    if flags['holdModel']:
                        flags['holdModel'] = False
                        model_reached.set()
                        model_release.wait(45)
                    if args.live_model_base:
                        response=requests.post(args.live_model_base.rstrip('/')+'/chat/completions',json=payload,timeout=90)
                        try:
                            self.send_response(response.status_code);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(response.content)
                        except (BrokenPipeError,ConnectionResetError):pass
                        return
                    messages = payload['messages']
                    last_user = max((index for index,message in enumerate(messages) if message['role']=='user'),default=-1)
                    latest = messages[last_user]['content'] if last_user >= 0 else ''
                    current_has_tool = any(message['role']=='tool' for message in messages[last_user+1:])
                    if flags['failFinalAfterTool'] and current_has_tool:
                        flags['failFinalAfterTool']=False
                        raw=b'{"error":{"message":"controlled failure after task preparation"}}';status=500
                        try:
                            self.send_response(status);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw)
                        except (BrokenPipeError,ConnectionResetError):pass
                        return
                    if not current_has_tool and any(word in str(latest) for word in ('转账','偏好','余额')):
                        preference = '偏好' in str(latest)
                        name = 'channel_preferences_update' if preference else 'transfer_internal_create'
                        amount_match = re.search(r'转账([0-9]+(?:\.[0-9]+)?)元', str(latest))
                        amount = float(amount_match.group(1)) if amount_match else 0.01
                        params = {'largeTextEnabled':True} if preference else {'fromAccountId':account,'beneficiaryId':recipient,
                            'amount':amount,'purpose':'Shared Host isolated acceptance'}
                        if '余额' in str(latest):name='account_balance_query';params={}
                        answer = {'role':'assistant','content':None,'tool_calls':[{'id':'call-'+uuid.uuid4().hex,'type':'function','function':{'name':name,'arguments':json.dumps(params)}}]}
                        finish = 'tool_calls'
                    else:
                        answer = {'role':'assistant','content':'请查看下方办理记录；以银行实际返回和您的明确确认结果为准。'}; finish = 'stop'
                    raw = json.dumps({'id':'test-completion','object':'chat.completion','created':int(time.time()),'model':'controlled-shared-task',
                        'choices':[{'index':0,'message':answer,'finish_reason':finish}]}).encode(); status = 200
                else:
                    route = re.sub(r'/tasks/[^/?]+', '/tasks/{taskId}', self.path.split('?')[0])
                    event = {'method':self.command,'route':route}; attempts.append(event)
                    if flags['failQuery'] and self.command=='GET' and route.startswith('/api/v1/tasks/'):
                        flags['failQuery']=False; raw=b'{"status":"FAILED"}'; status=503
                    else:
                        response = requests.request(self.command, bank_url+self.path, data=body,
                            headers={k:v for k,v in self.headers.items() if k.lower() not in {'host','content-length','connection'}}, timeout=30)
                        status, raw = response.status_code, response.content
                        if route.endswith('/submit') and flags['holdSubmit']:
                            flags['holdSubmit']=False; reached.set(); release.wait(45)
                        if route.endswith('/submit') and flags['badSubmit']:
                            flags['badSubmit']=False; raw=b'{"status":"SUCCESS","data":[]}'
                    event['status'] = status
                try:
                    self.send_response(status); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(raw)
                except (BrokenPipeError, ConnectionResetError): pass
        proxy = ThreadingHTTPServer(('127.0.0.1',0),Proxy); threading.Thread(target=proxy.serve_forever,daemon=True).start()
        proxy_url = 'http://127.0.0.1:' + str(proxy.server_port)
        catalog = save('model-catalog.json', {'schemaVersion':'finance-model-catalog.v1','source':{'kind':'CONTROLLED_TEST'},'models':[{
            'modelRef':'model-probe','displayName':model_name,'provider':'OpenAI','modelName':model_name,'eaCatalogId':'probe','eaConfigured':False}]})
        registry = save('model-bindings.json', {'schemaVersion':'finance-model-bindings.v1','bindings':[{'modelRef':'model-probe','revision':'v1','enabled':True,
            'protocol':'OPENAI_CHAT_COMPLETIONS','provider':'OpenAI','modelName':model_name,'urlEnvironment':'FH_CONN_PROBE_URL','keyEnvironment':'FH_CONN_PROBE_KEY'}]})
        scopes = ['account:read','transfer:create']
        if 'channel-preferences' in selected_packs:
            scopes.append('channel:write')
        rules = {'schemaVersion':'local-simulator-access.v0.1','bankUrl':proxy_url,'subjects':{login['customerRef']:{'enabled':True,
            'expiresAt':(datetime.now(timezone.utc)+timedelta(hours=2)).isoformat(),'scopes':scopes}}}
        policy = save('policy.json',rules)
        env = dict(os.environ, FH_MODEL_CATALOG=catalog,FH_MODEL_REGISTRY=registry,FH_CONN_PROBE_URL=proxy_url+'/v1',FH_CONN_PROBE_KEY='controlled-not-a-key',
            FH_MODEL_MODE='LIVE' if args.live_model_base else 'CONTROLLED_TEST',FH_BANK_URL=proxy_url,FH_BANK_LOGIN='demo',FH_BANK_SECRET='demo123',FH_ACCESS_POLICY=policy,
            FH_TASK_ACCESS_POLICY=policy,FH_TASK_SIMULATOR_ENABLED='true',PYTHONPATH=str(project/'src')+os.pathsep+str(project.parent/'base/src'))
        if args.runtime == 'agentscope-java-2.0.3':
            env['FH_CANDIDATE_RUNTIME_IDS'] = args.runtime
        wb = spawn([sys.executable,'-m','finance_solution_builder.workbench','--harness-repo',str(args.harness.resolve()),'--port','0','--data-dir',str(output/'jobs'),'--maven',args.maven],env,'workbench.log',project)
        base = None
        for _ in range(300):
            m = re.search(r'http://127\.0\.0\.1:\d+', (output/'workbench.log').read_text())
            if m: base=m.group(); break
            if wb.poll() is not None: raise AssertionError('workbench failed')
            time.sleep(.1)
        assert base
        def mutations():
            return {kind:sum(x['method']=='POST' and (x['route']=='/api/v1/tasks' if kind=='create' else x['route'].endswith('/'+kind)) for x in attempts) for kind in ('create','confirm','submit')}
        with sync_playwright() as p:
            browser = p.chromium.launch(executable_path=args.chromium,headless=True)
            try:
                page = browser.new_page(viewport={'width':1440,'height':1000}); errors=[]
                page.on('pageerror',lambda e: errors.append(str(e)))
                page.goto(base); page.wait_for_timeout(1200)
                if args.runtime == 'agentscope-java-2.0.3':
                    page.request.get(base+'/api/bootstrap')
                    cookie = next(c for c in page.context.cookies() if c['name']=='fh_local')
                    owner = cookie['value'].split('.',1)[0]
                    # Bind capture to the actual isolated owner, never a wildcard grant.
                    wb.terminate(); wb.wait(timeout=20)
                    env['FH_HISTORY_OWNER'] = 'local-operator'
                    env['FH_SNAPSHOT_CAPTURE_OWNERS'] = 'local-operator,'+hashlib.sha256(owner.encode()).hexdigest()
                    env['FH_SNAPSHOT_VIEW_OWNERS'] = hashlib.sha256(owner.encode()).hexdigest()
                    wb = spawn([sys.executable,'-m','finance_solution_builder.workbench','--harness-repo',str(args.harness.resolve()),'--port','0','--data-dir',str(output/'jobs'),'--maven',args.maven],env,'workbench-owner.log',project)
                    base = None
                    for _ in range(300):
                        match = re.search(r'http://127\.0\.0\.1:\d+', (output/'workbench-owner.log').read_text())
                        if match: base=match.group(); break
                        if wb.poll() is not None: raise AssertionError('owner-scoped workbench failed')
                        time.sleep(.1)
                    assert base
                    page.goto(base); page.wait_for_timeout(1200)
                catalog = page.request.get(base+'/api/v1/catalog').json()
                assert_same_packages(catalog)
                check('one-creation-page', page.locator('[data-assembly-mode]').count() == 0)
                transfer = next(m for m in catalog['unifiedTemplateModuleDirectory']['modules'] if m['id']=='agent.transfer')
                check('transfer-in-existing-module', 'transfer.internal.create' in transfer['capabilityIds'])
                blank_selection(page)
                for name in selected_packs:
                    select_pack(page, catalog, name)
                page.locator('[data-runtime="openjiuwen-python-0.1.18"]').check()
                check('python-task-blocked', page.locator('[data-action="build"]').is_disabled())
                page.locator('[data-runtime="'+args.runtime+'"]').check()
                page.locator('[data-template="DESKTOP_WEB"]').click()
                save('pre-build-catalog.json', catalog)
                save('pre-build-page.json', {'text': page.locator('body').inner_text(),
                     'buildDisabled': page.locator('[data-action="build"]').is_disabled(), 'errors': errors})
                page.screenshot(path=str(output/'pre-build.png'), full_page=True)
                with page.expect_response(lambda r:r.url.endswith('/api/v1/builds') and r.request.method=='POST') as response:
                    page.locator('[data-action="build"]').click()
                built = response.value.json(); assert response.value.status==202,built
                check('unified-versioned-selection', response.value.request.post_data_json['schema_version'] in ('0.9', '0.10'))
                check('incompatible-runtime-kept-pack-selection',
                      {b['pack']['id'] for b in response.value.request.post_data_json['packBindings']}
                      == set(selected_packs))
                build_id=built['buildId']; save('build-request.json',response.value.request.post_data_json)
                candidate=output/'jobs/workbench'/build_id/'candidate'
                for _ in range(900):
                    report = candidate.parent/'build-report.json'
                    if report.exists() and json.loads(report.read_text())['status'] in ('BUILT','FAILED'): break
                    record = output/'jobs/workbench-records'/(build_id+'.json')
                    if record.exists() and json.loads(record.read_text())['status']=='FAILED':
                        raise AssertionError('build failed; inspect immutable record and stage report')
                    time.sleep(1)
                check('actual-build',json.loads(report.read_text())['status']=='BUILT')
                def wbpost(action, body):
                    return page.evaluate('''async ({path,body})=>{const b=await(await fetch('/api/bootstrap')).json();const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':b.csrf},body:JSON.stringify(body)});return {code:r.status,body:await r.json()};}''',{'path':'/api/v1/builds/'+build_id+'/'+action,'body':body})
                job=page.request.get(base+'/api/v1/builds/'+build_id).json()
                started=wbpost('start',{'artifactId':job['artifact']['artifactId']}); assert started['code']==200,started
                instance=started['body']['instance']['instanceId']; save('started.json',started)
                # Owner-issued original chat ticket, not a separate transfer page.
                ticket=page.evaluate('''async({id,instanceId})=>{const b=await(await fetch('/api/bootstrap')).json();const r=await fetch('/api/v1/builds/'+id+'/experience',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':b.csrf},body:JSON.stringify({instanceId})});return {code:r.status,body:await r.json()};}''',{'id':build_id,'instanceId':instance})
                assert ticket['code']==200,ticket
                chat=browser.new_page(viewport={'width':1280,'height':900});chat.on('pageerror',lambda e:errors.append(str(e)))
                def capture_rejection(response):
                    if '/api/' in response.url and response.status >= 400:
                        save('host-http-rejection.json', {'status': response.status,
                            'action': response.url.rsplit('/',1)[-1], 'body':response.text()})
                chat.on('response',capture_rejection)
                chat.goto(base+ticket['body']['url']);chat.locator('[data-action="start"]').click();chat.locator('#query').wait_for()
                def host(action, body=None, expected=200, include_status=False):
                    result=chat.evaluate('''async({action,body})=>{const token=sessionStorage.getItem('finance-experience:'+location.pathname);const r=await fetch('./api/'+action,{method:'POST',headers:{'Content-Type':'application/json','X-Agent-Token':token},body:JSON.stringify(body)});return {code:r.status,body:await r.json()};}''',{'action':action,'body':body or {}})
                    accepted = expected if isinstance(expected, tuple) else (expected,)
                    assert result['code'] in accepted,(action,result)
                    return result if include_status else result['body']
                host('step-up',{'verificationCode':'000000'})
                query_result=host('chat',{'query':'查询账户余额。','runId':str(uuid.uuid4())})
                save('balance-query-result.json',query_result)
                def prepare(text):
                    if args.live_model_base and '转账' in text:
                        text+=' 本次合成测试明确指定付款账户'+account+'，已登记收款人'+recipient+'，金额0.01元。请使用已安装工具准备，等待页面确认，不代替我确认。'
                    before=mutations(); existing={t['handle'] for t in host('tasks')['tasks']}
                    chat.locator('#query').fill(text);chat.locator('#chat-form').evaluate('(f)=>f.requestSubmit()')
                    deadline=time.monotonic()+60; task=None
                    while time.monotonic()<deadline:
                        task=next((t for t in host('tasks')['tasks']
                            if t['awaitingConfirmation'] and t['handle'] not in existing),None)
                        if task is not None: break
                        time.sleep(0.1)
                    assert task is not None,'NEW_PENDING_TASK_TIMEOUT'
                    check('prepare-no-confirm-or-submit-'+str(len(checks)),mutations()['confirm']==before['confirm'] and mutations()['submit']==before['submit'])
                    return task['handle']
                if args.pending_failure_check:
                    flags['failFinalAfterTool']=True
                    pending_handle=prepare('请将渠道展示偏好改为大字模式，等待我确认。')
                    pending=chat.locator(f'[data-task-handle="{pending_handle}"]')
                    pending.locator('[data-task="decline"]').wait_for(timeout=15000)
                    check('model-failure-keeps-confirmation-controls',pending.locator('[data-task="challenge"]').is_visible()
                          and pending.locator('[data-task="decline"]').is_visible())
                    before_pending_decline=mutations();pending.locator('[data-task="decline"]').click()
                    deadline=time.monotonic()+10
                    while time.monotonic()<deadline:
                        rejected=next((t for t in host('tasks')['tasks'] if t['handle']==pending_handle),None)
                        if rejected and rejected['sourceStatus']=='REJECTED':break
                        time.sleep(.1)
                    check('decline-after-model-failure-zero-submit',rejected['sourceStatus']=='REJECTED'
                          and mutations()['submit']==before_pending_decline['submit'])
                    check('no-ui-errors',not errors)
                    chat.screenshot(path=str(output/'pending-model-failure.png'),full_page=True)
                    check('stop-owned-agent',wbpost('stop',{'instanceId':instance})['code']==200)
                    save('result.json',{'status':'CONTROLLED_PENDING_MODEL_FAILURE_PASS','checks':checks,'attempts':attempts,
                        'buildId':build_id,'candidate':str(candidate),'lockSha256':hashlib.sha256((candidate/'service-lock.json').read_bytes()).hexdigest(),
                        'model':'CONTROLLED_TEST','modelRequests':len(model_calls),'realModel':'NOT_RUN','humanOnboarding':'NOT_RUN'})
                    return
                handle=prepare('请转账0.01元，测试共享宿主。');before=ledger();counts=mutations()
                host('task-confirm',{'handle':handle,'nonce':'forged'},409)
                check('forged-confirmation-zero-write',mutations()==counts)
                chat.locator('[data-task="challenge"]').last.click();chat.locator('#details [data-task="confirm"]').wait_for()
                chat.locator('#details [data-task="confirm"]').click();chat.wait_for_timeout(1000)
                result=host('task-resume',{'handle':handle});save('transfer-result.json',result)
                check('transfer-completed',result['status']=='COMPLETED')
                after=ledger();save('ledger.json',{'before':before,'after':after})
                check('ledger-debit-receipt-once',before['balance']-after['balance']==Decimal('0.01') and after['receipts']==before['receipts']+1 and after['entries']==before['entries']+1)
                counts=mutations();host('task-resume',{'handle':handle});check('terminal-resume-zero-write',mutations()==counts)
                if 'channel-preferences' in selected_packs:
                    pref_before=oracle('/api/v1/preferences/channel');handle=prepare('请将渠道展示偏好改为大字模式。')
                    chat.locator('[data-task="challenge"]').last.click();chat.locator('#details [data-task="confirm"]').click();chat.wait_for_timeout(1000)
                    result=host('task-resume',{'handle':handle});save('preference-result.json',result)
                    pref_after=oracle('/api/v1/preferences/channel');save('preferences.json',{'before':pref_before,'after':pref_after})
                    check('different-business-persisted',result['status']=='COMPLETED' and pref_after['largeTextEnabled'] is True and pref_after['version']==pref_before['version']+1)
                terminal = host('tasks')['tasks']
                check('terminal-business-status-visible',all(t['sourceStatus']=='COMPLETED' for t in terminal))
                if args.live_model_base:
                    # Live model behavior and deterministic fault timing are separate acceptance lines.
                    check('no-ui-errors',not errors)
                    chat.screenshot(path=str(output/'shared-chat-desktop.png'),full_page=True)
                    chat.set_viewport_size({'width':390,'height':844});chat.screenshot(path=str(output/'shared-chat-mobile.png'),full_page=True)
                    check('mobile-no-overflow',chat.evaluate('document.documentElement.scrollWidth<=innerWidth'))
                    check('stop-owned-agent',wbpost('stop',{'instanceId':instance})['code']==200)
                    save('result.json',{'status':'LIVE_MODEL_SHARED_TASKS_PASS','checks':checks,'attempts':attempts,
                        'buildId':build_id,'candidate':str(candidate),'lockSha256':hashlib.sha256((candidate/'service-lock.json').read_bytes()).hexdigest(),
                        'model':model_name,'modelRequests':len(model_calls),'realModel':'SYNTHETIC_BANK_VERIFIED','humanOnboarding':'NOT_RUN'})
                    return
                # Lose only the response after the actual bank committed. Query failure must retain the handle.
                handle=prepare('请转账0.03元，测试响应损坏后的原任务查证。')
                before=ledger(); challenge=host('task-challenge',{'handle':handle}); flags['badSubmit']=True
                uncertain=host('task-confirm',{'handle':handle,'nonce':challenge['nonce']})
                save('uncertain-result.json',uncertain)
                check('bad-submit-response-not-success',uncertain['status']!='COMPLETED')
                counts=mutations(); flags['failQuery']=True
                failed_query=host('task-resume',{'handle':handle})
                save('query-failure-result.json',failed_query)
                check('query-failure-keeps-original',any(t['handle']==handle for t in host('tasks')['tasks']) and mutations()==counts)
                stopped=wbpost('stop',{'instanceId':instance});check('stop-pending-original',stopped['code']==200)
                restarted=wbpost('start',{'artifactId':job['artifact']['artifactId']}); assert restarted['code']==200,restarted
                instance=restarted['body']['instance']['instanceId']
                ticket=wbpost('experience',{'instanceId':instance});assert ticket['code']==200,ticket
                chat.close();chat=browser.new_page(viewport={'width':1280,'height':900})
                chat.on('pageerror',lambda e:errors.append(str(e)))
                chat.goto(base+ticket['body']['url']);chat.locator('[data-action="start"]').click();chat.locator('#query').wait_for()
                recovered=host('task-resume',{'handle':handle});save('restart-recovery.json',recovered)
                after=ledger()
                check('restart-original-completed-without-resubmit',recovered['status']=='COMPLETED' and mutations()==counts)
                check('uncertain-ledger-exactly-once',before['balance']-after['balance']==Decimal('0.03') and after['receipts']==before['receipts']+1 and after['entries']==before['entries']+1)
                host('task-confirm',{'handle':handle,'nonce':challenge['nonce']})
                check('consumed-confirmation-after-restart-no-write',mutations()==counts)
                handle=prepare('请转账0.04元，测试提交响应返回前进程中断。')
                before=ledger();challenge=host('task-challenge',{'handle':handle})
                counts=mutations()
                host('chat',{'query':'请转账0.04元，不要重复待办。','runId':str(uuid.uuid4())})
                check('pending-task-blocks-new-create',mutations()==counts)
                challenge=host('task-challenge',{'handle':handle})
                endpoint=chat.url.rsplit('#',1)[0]+'api/task-confirm'
                ticket_token=chat.evaluate("sessionStorage.getItem('finance-experience:'+location.pathname)")
                reply=[]
                def delayed_confirm():
                    try:
                        response=requests.post(endpoint,json={'handle':handle,'nonce':challenge['nonce']},
                            headers={'X-Agent-Token':ticket_token,'Origin':base},timeout=60)
                        reply.append({'status':response.status_code})
                    except requests.RequestException: reply.append({'status':'CONNECTION_CLOSED'})
                flags['holdSubmit']=True;reached.clear();release.clear()
                thread=threading.Thread(target=delayed_confirm);thread.start()
                check('bank-commit-reached-before-kill',reached.wait(30))
                process=json.loads((candidate/'instance.json').read_text())
                command=Path('/proc',str(process['pid']),'cmdline').read_bytes()
                check('kill-target-owned-isolated-host',str(candidate).encode() in command and b'LocalAgentHost' in command)
                os.kill(process['pid'],signal.SIGKILL);release.set();thread.join(65)
                check('inflight-request-ended',not thread.is_alive())
                counts=mutations()
                page.request.get(base+'/api/v1/builds/'+build_id)
                restarted=wbpost('start',{'artifactId':job['artifact']['artifactId']});assert restarted['code']==200,restarted
                instance=restarted['body']['instance']['instanceId']
                ticket=wbpost('experience',{'instanceId':instance});assert ticket['code']==200,ticket
                chat.close();chat=browser.new_page(viewport={'width':1280,'height':900})
                chat.on('pageerror',lambda e:errors.append(str(e)))
                chat.goto(base+ticket['body']['url']);chat.locator('[data-action="start"]').click();chat.locator('#query').wait_for()
                recovered=host('task-resume',{'handle':handle});after=ledger()
                save('crash-recovery.json',{'result':recovered,'before':before,'after':after,'inflight':reply})
                check('killed-host-recovers-original-no-submit',recovered['status']=='COMPLETED' and mutations()==counts)
                check('killed-host-ledger-exactly-once',before['balance']-after['balance']==Decimal('0.04') and after['receipts']==before['receipts']+1 and after['entries']==before['entries']+1)
                if args.runtime == 'agentscope-java-2.0.3':
                    handle = prepare('请转账0.05元，测试取消聊天后保留待办。')
                    counts = mutations(); before = ledger()
                    run_id = str(uuid.uuid4()); reply = []
                    endpoint = chat.url.rsplit('#', 1)[0] + 'api/chat'
                    token = chat.evaluate("sessionStorage.getItem('finance-experience:'+location.pathname)")
                    def delayed_chat():
                        response = requests.post(endpoint, json={'query':'取消时序探针', 'runId':run_id},
                            headers={'X-Agent-Token':token, 'Origin':base}, timeout=60)
                        reply.append({'code':response.status_code, 'body':response.json()})
                    flags['holdModel'] = True; model_reached.clear(); model_release.clear()
                    thread = threading.Thread(target=delayed_chat); thread.start()
                    check('cancel-probe-model-reached', model_reached.wait(30))
                    cancelled = host('cancel', {'runId':run_id})
                    check('cancel-probe-request-accepted', cancelled['status'] == 'CANCEL_REQUESTED')
                    model_release.set(); thread.join(65)
                    check('cancel-probe-chat-ended', not thread.is_alive() and len(reply) == 1
                          and reply[0]['code'] == 409 and reply[0]['body'].get('error', {}).get('code') == 'RUN_CANCELLED')
                    model_count = len(model_calls)
                    listed = host('tasks')['tasks']
                    check('cancel-retains-task-without-new-chat', any(t['handle'] == handle for t in listed))
                    resumed = host('task-resume', {'handle':handle})
                    challenge = host('task-challenge', {'handle':handle})
                    declined = host('task-decline', {'handle':handle})
                    save('cancel-task-recovery.json', {'cancel':cancelled, 'chat':reply, 'resume':resumed,
                        'challengeIssued':bool(challenge.get('nonce')), 'decline':declined})
                    check('cancel-task-recovery-without-model-or-submit', len(model_calls) == model_count
                          and mutations() == counts and ledger() == before and declined['status'] == 'REJECTED')
                    check('decline-is-not-bank-submit', mutations() == counts and ledger() == before)
                else:
                    handle=prepare('请转账0.05元，随后由用户拒绝确认。');counts=mutations();before=ledger()
                    declined=host('task-decline',{'handle':handle})
                    check('decline-is-not-bank-submit',declined['status']=='REJECTED' and mutations()==counts and ledger()==before)
                history = output/'jobs/execution-history'
                before_denial = history_offsets(history)
                rules['subjects'][login['customerRef']]['enabled']=False;save('policy.json',rules)
                model_count=len(model_calls)
                # The page may observe denial and clear its session before this call.
                # A closed-session response alone is not proof of authorization denial.
                denied = host('tasks',expected=(403,409),include_status=True)
                save('revocation-result.json',denied)
                if denied['code'] == 409:
                    check('revoked-session-cleared',denied['body'].get('error',{}).get('code')=='INSTANCE_CHANGED'
                          and denied['body'].get('clearConversation') is True)
                for _ in range(20):
                    if observed_authorization_denial(history,before_denial):break
                    time.sleep(.1)
                check('current-authorization-denial-observed',observed_authorization_denial(history,before_denial))
                check('revoked-scope-no-model-or-mutation',len(model_calls)==model_count and mutations()==counts)
                check('no-ui-errors',not errors)
                chat.screenshot(path=str(output/'shared-chat-desktop.png'),full_page=True)
                chat.set_viewport_size({'width':390,'height':844});chat.screenshot(path=str(output/'shared-chat-mobile.png'),full_page=True)
                check('mobile-no-overflow',chat.evaluate('document.documentElement.scrollWidth<=innerWidth'))
                stopped=wbpost('stop',{'instanceId':instance});check('stop-owned-agent',stopped['code']==200)
                save('result.json',{'status':'CONTROLLED_SHARED_TASKS_PASS','checks':checks,'attempts':attempts,
                    'buildId':build_id,'candidate':str(candidate),'lockSha256':hashlib.sha256((candidate/'service-lock.json').read_bytes()).hexdigest(),
                    'model':'LIVE_VIA_SERVER_CONFIGURATION' if args.live_model_base else 'CONTROLLED_TEST','modelRequests':len(model_calls),
                    'realModel':'SYNTHETIC_BANK_VERIFIED' if args.live_model_base else 'NOT_RUN','humanOnboarding':'NOT_RUN'})
            finally:
                if sys.exc_info()[0] is not None and not page.is_closed():
                    save('failed-page.json', {'text': page.locator('body').inner_text(), 'errors': errors})
                    page.screenshot(path=str(output/'failed-page.png'), full_page=True)
                    for index, view in enumerate(browser.contexts):
                        for number, visible in enumerate(view.pages):
                            if not visible.is_closed():
                                save(f'failed-view-{index}-{number}.json', {'text':visible.locator('body').inner_text()})
                                visible.screenshot(path=str(output/f'failed-view-{index}-{number}.png'), full_page=True)
                browser.close()
    finally:
        release.set()
        model_release.set()
        for child in reversed(children):
            if child.poll() is None:
                child.terminate()
                try: child.wait(timeout=20)
                except subprocess.TimeoutExpired: child.kill();child.wait(timeout=10)
        if proxy: proxy.shutdown();proxy.server_close()
        for log in logs:log.close()
        save('checks.json',{'checks':checks,'attempts':attempts})


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--harness',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--bank-jar',type=Path,required=True);parser.add_argument('--maven',required=True);parser.add_argument('--chromium',required=True)
    parser.add_argument('--live-model-base',help='Explicit loopback proxy to a configured model; synthetic bank only')
    parser.add_argument('--live-model-name',help='Actual model configured behind the explicit proxy')
    parser.add_argument('--pending-failure-check',action='store_true',help='Run only the deterministic pending-task/model-failure UI check')
    parser.add_argument('--runtime', choices=['openjiuwen-java-0.1.15', 'agentscope-java-2.0.3'], default='openjiuwen-java-0.1.15')
    verify(parser.parse_args())
