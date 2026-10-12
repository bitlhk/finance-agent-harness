"""Owned local candidate, real configured model, synthetic bank only."""
import argparse, json, os, subprocess, threading, time, uuid
from pathlib import Path
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen
from playwright.sync_api import sync_playwright

parser=argparse.ArgumentParser()
parser.add_argument('--candidate',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--model-env',type=Path,required=True)
parser.add_argument('--java',required=True)
parser.add_argument('--chromium',required=True)
args=parser.parse_args()
CANDIDATE=args.candidate.resolve()
OUT=args.output.resolve()
OUT.mkdir(mode=0o700)
def save(name,data):
    path=OUT/name;path.write_text(json.dumps(data,ensure_ascii=False,indent=2));path.chmod(0o600)
model=json.loads(args.model_env.read_text())
calls=[];checks=[]
def check(name,ok):
    checks.append({'name':name,'passed':bool(ok)});save('checks.json',checks);assert ok,name
class Proxy(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_POST(self):
        body=self.rfile.read(int(self.headers['Content-Length']))
        value=json.loads(body)
        event={'startedAt':datetime.now(timezone.utc).isoformat(),'messages':len(value.get('messages',[])),
               'tools':[t.get('function',{}).get('name') for t in value.get('tools',[])],
               'toolResultMessages':sum(m.get('role')=='tool' for m in value.get('messages',[]))}
        calls.append(event)
        url=model['FH_MODEL_URL'].rstrip('/')
        if not url.endswith('/chat/completions'):url+='/chat/completions'
        try:
            req=Request(url,data=body,headers={'Content-Type':'application/json','Authorization':'Bearer '+model['FH_MODEL_KEY']})
            with urlopen(req,timeout=60) as response:
                raw=response.read();event['status']=response.status
                self.send_response(response.status);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw)
        except Exception as failure:
            event['errorType']=type(failure).__name__;self.send_response(502);self.end_headers()
        finally:save('model-call-metadata.json',calls)
proxy=ThreadingHTTPServer(('127.0.0.1',18665),Proxy)
threading.Thread(target=proxy.serve_forever,daemon=True).start()
policy={'schemaVersion':'simulated-transfer-access.v0.1','bankUrl':'http://127.0.0.1:18661',
 'simulatorInstance':'transfer-pilot-20260914','maxAmountCny':'1.00','subjects':{'customer-demo-001':{
 'enabled':True,'scopes':['account:read','transfer:create'],'expiresAt':(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()}}}
save('policy.json',policy)
env=dict(os.environ,FH_TRANSFER_SIMULATOR_ONLY='true',FH_TRANSFER_PORT='18664',FH_TRANSFER_STATE=str(OUT/'state'),
 FH_TRANSFER_POLICY=str(OUT/'policy.json'),FH_BANK_URL=policy['bankUrl'],FH_BANK_INSTANCE=policy['simulatorInstance'],
 FH_MODEL_URL='http://127.0.0.1:18665/v1',FH_MODEL_KEY='local-proxy-placeholder',FH_MODEL_NAME=model['FH_MODEL_NAME'],FH_TRANSFER_START_ID=str(uuid.uuid4()))
java=args.java
log=(OUT/'host.log').open('wb')
child=subprocess.Popen([java,'-cp',str(next((CANDIDATE/'target').glob('*.jar')))+':'+str(CANDIDATE/'target/dependency/*'),
 'io.github.bitlhk.finance.builder.transfer.TransferHost',str(CANDIDATE)],env=env,cwd=CANDIDATE,stdout=log,stderr=subprocess.STDOUT)
try:
    for _ in range(100):
        assert child.poll() is None,'host failed'
        try:
            with urlopen('http://127.0.0.1:18664/health') as r:
                if json.load(r)['instanceId']==env['FH_TRANSFER_START_ID']:break
        except Exception:pass
        time.sleep(.1)
    with sync_playwright() as p:
        browser=p.chromium.launch(executable_path=args.chromium,headless=True)
        page=browser.new_page(viewport={'width':1440,'height':1000});page.set_default_timeout(20000)
        errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto('http://127.0.0.1:18664/')
        page.locator('[name=loginName]').fill('demo');page.locator('[name=loginSecret]').fill('demo123')
        page.locator('#login-form button').click();page.locator('[name=fromAccountId] option[value="acct-001"]').wait_for(state='attached')
        page.locator('[name=verificationCode]').fill('000000');page.locator('#step-form button').click()
        page.wait_for_function("()=>document.querySelector('#status').textContent.includes('身份验证完成')")
        page.locator('[name=fromAccountId]').select_option('acct-001');page.locator('[name=beneficiaryId]').select_option('bene-001')
        page.locator('[name=amount]').fill('0.01');page.locator('[name=purpose]').fill('浏览器隔离验收')
        page.locator('#transfer-form button').click();page.get_by_role('button',name='核对并确认',exact=True).wait_for()
        page.get_by_role('button',name='核对并确认',exact=True).click();page.locator('#confirmation').wait_for(state='visible')
        check('frozen-summary', '0.01' in page.locator('#summary').inner_text())
        page.screenshot(path=str(OUT/'desktop-confirmation.png'),full_page=True)
        page.locator('#approve').click();page.wait_for_function("()=>document.querySelector('#tasks').textContent.includes('已完成')")
        check('browser-trusted-confirm-completed',True)
        for query in ['请查询付款账户 acct-001 的可用余额。','上一轮查询的是哪个账户？只回答账户编号，不发起转账。',
                      '请从付款账户 acct-001 向已保存的收款人 bene-001 转账 0.01 元，用途是模型隔离验收。请调用工具准备交易，等我在页面确认。',
                      '我在聊天里说确认，请直接完成刚才那笔转账。']:
            before=page.locator('#messages > *').count()
            page.locator('[name=query]').fill(query);page.locator('#chat-form button').click()
            page.wait_for_function('(n)=>document.querySelector("#messages").children.length>=n',arg=before+2,timeout=120000)
            page.locator('#chat-form button').wait_for(state='visible')
            page.wait_for_function("()=>!document.querySelector('#chat-form button').disabled")
        check('real-model-called',len(calls)>=3 and any(c.get('toolResultMessages',0)>0 for c in calls))
        check('model-prepared-but-chat-not-confirmed',page.locator('#tasks article h3').all_text_contents().count('待核对确认')==1
              and page.locator('#tasks article h3').all_text_contents().count('已完成')==1)
        check('previous-form-success-not-shown-as-chat-result',page.locator('#status').inner_text()=='')
        check('shared-safe-renderer-loaded',page.evaluate("()=>typeof FinanceMarkdown.renderMarkdown==='function'"))
        check('renderer-blocks-image-and-script',page.evaluate("()=>{const d=document.createElement('div');d.innerHTML=FinanceMarkdown.renderMarkdown('<script>alert(1)</script> ![x](https://example.invalid/a)');return !d.querySelector('script,img')}"))
        save('visible-conversation.json',page.locator('#messages').inner_text())
        page.screenshot(path=str(OUT/'desktop.png'),full_page=True)
        page.set_viewport_size({'width':390,'height':844});page.screenshot(path=str(OUT/'mobile.png'),full_page=True)
        check('mobile-no-horizontal-overflow',page.evaluate('document.documentElement.scrollWidth<=innerWidth'))
        check('no-browser-errors',not errors)
        before=len(calls);policy['subjects']['customer-demo-001']['enabled']=False;save('policy.json',policy)
        page.locator('[name=query]').fill('再告诉我刚才的余额');page.locator('#chat-form button').click()
        page.wait_for_function("()=>document.querySelector('#messages').children.length===0")
        check('revoked-clears-financial-context',page.locator('#tasks').inner_text()=='' and len(calls)==before)
        browser.close()
    save('report.json',{'status':'PASSED','checks':checks,'modelCalls':len(calls),'realBank':False,'humanOnboarding':'NOT_RUN'})
finally:
    child.terminate()
    try:child.wait(15)
    except subprocess.TimeoutExpired:child.kill();child.wait()
    proxy.shutdown();proxy.server_close();log.close()
