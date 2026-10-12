"""Controlled model + real isolated bank; checks actual model HTTP boundary."""
import argparse, json, os, subprocess, threading, time, uuid
from pathlib import Path
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import requests
parser=argparse.ArgumentParser()
parser.add_argument('--candidate',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--java',required=True)
args=parser.parse_args()
OUT=args.output.resolve();OUT.mkdir(mode=0o700)
C=args.candidate.resolve()
calls=[];bank_calls=[];arrived=threading.Event();release=threading.Event()
def save(name,value):
    p=OUT/name;p.write_text(json.dumps(value,indent=2));p.chmod(0o600)
class Bank(BaseHTTPRequestHandler):
    def log_message(self,*a):pass
    def do_GET(self):self.forward()
    def do_POST(self):self.forward()
    def forward(self):
        body=self.rfile.read(int(self.headers.get('Content-Length','0')))
        r=requests.request(self.command,'http://127.0.0.1:18661'+self.path,data=body,
          headers={k:v for k,v in self.headers.items() if k.lower() not in {'host','content-length','connection'}},timeout=20)
        bank_calls.append({'method':self.command,'path':self.path.split('?')[0],'status':r.status_code})
        if self.command=='GET' and self.path.split('?')[0].startswith('/api/v1/accounts'):arrived.set();release.wait(30)
        try:self.send_response(r.status_code);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(r.content)
        except BrokenPipeError:pass
class Model(BaseHTTPRequestHandler):
    def log_message(self,*a):pass
    def do_POST(self):
        data=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        calls.append({'toolResults':sum(m.get('role')=='tool' for m in data['messages'])})
        body={'id':'controlled-call','object':'chat.completion','created':1,'model':'controlled',
          'choices':[{'index':0,'finish_reason':'tool_calls','message':{'role':'assistant','content':None,
          'tool_calls':[{'id':'call_1','type':'function','function':{'name':'account_balance_query','arguments':'{"accountId":"acct-001"}'}}]}}]}
        self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(json.dumps(body).encode())
servers=[ThreadingHTTPServer(('127.0.0.1',18667),Bank),ThreadingHTTPServer(('127.0.0.1',18668),Model)]
for s in servers:threading.Thread(target=s.serve_forever,daemon=True).start()
policy={'schemaVersion':'simulated-transfer-access.v0.1','bankUrl':'http://127.0.0.1:18667','simulatorInstance':'transfer-pilot-20260914',
 'maxAmountCny':'1.00','subjects':{'customer-demo-001':{'enabled':True,'scopes':['account:read','transfer:create'],
 'expiresAt':(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()}}}
save('policy.json',policy)
env=dict(os.environ,FH_TRANSFER_SIMULATOR_ONLY='true',FH_TRANSFER_PORT='18666',FH_TRANSFER_STATE=str(OUT/'state'),
 FH_TRANSFER_POLICY=str(OUT/'policy.json'),FH_BANK_URL=policy['bankUrl'],FH_BANK_INSTANCE=policy['simulatorInstance'],
 FH_MODEL_URL='http://127.0.0.1:18668/v1',FH_MODEL_NAME='controlled',FH_MODEL_KEY='controlled-placeholder',FH_TRANSFER_START_ID=str(uuid.uuid4()))
log=(OUT/'host.log').open('wb')
child=subprocess.Popen([args.java,'-cp',str(next((C/'target').glob('*.jar')))+':'+str(C/'target/dependency/*'),
 'io.github.bitlhk.finance.builder.transfer.TransferHost',str(C)],cwd=C,env=env,stdout=log,stderr=subprocess.STDOUT)
client=requests.Session();client.headers.update({'Origin':'http://127.0.0.1:18666','Content-Type':'application/json'})
def post(path,body):return client.post('http://127.0.0.1:18666/api/'+path,json=body,timeout=45)
result={}
try:
    for _ in range(100):
        assert child.poll() is None
        try:
            if requests.get('http://127.0.0.1:18666/health').json()['instanceId']==env['FH_TRANSFER_START_ID']:break
        except requests.RequestException:pass
        time.sleep(.1)
    login=post('login',{'loginName':'demo','loginSecret':'demo123'});login.raise_for_status()
    client.headers['X-CSRF-Token']=login.json()['csrf'];post('step-up',{'verificationCode':'000000'}).raise_for_status()
    def query():
        r=post('chat',{'query':'查询 acct-001 余额'});result.update(status=r.status_code,body=r.json())
    thread=threading.Thread(target=query);thread.start()
    assert arrived.wait(20),'bank read did not arrive'
    assert len(calls)==1,'unexpected prior model calls'
    policy['subjects']['customer-demo-001']['enabled']=False;save('policy.json',policy)
    release.set();thread.join(30)
    assert not thread.is_alive()
    assert result['status']==403 and result['body']['clearConversation']
    assert len(calls)==1,'revoked financial result reached another model HTTP call'
    save('report.json',{'status':'PASSED','modelCalls':calls,'bankCalls':bank_calls,'result':result,
        'scope':'Actual Host; source request sent and response held, grant revoked before returning. Controlled model, synthetic bank.'})
finally:
    release.set();child.terminate()
    try:child.wait(15)
    except subprocess.TimeoutExpired:child.kill();child.wait()
    for s in servers:s.shutdown();s.server_close()
    log.close();save('model-calls.json',calls)
