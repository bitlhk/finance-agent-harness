"""Private loopback reviewer workspace. Never rewrites source evidence or grants anonymous body access."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import time
import urllib.request


def bind_model(env,private):
    if env.get('FH_MODEL_REGISTRY') or not all(env.get(k) for k in ('FH_MODEL_URL','FH_MODEL_KEY','FH_MODEL_NAME')):
        return
    catalog=Path(env.get('FH_MODEL_CATALOG',Path(__file__).resolve().parents[1]/'examples/model-catalog/ea-models.json'))
    model=next((m for m in json.loads(catalog.read_text())['models'] if m['modelName']==env['FH_MODEL_NAME']),None)
    if model is None:raise ValueError('REVIEW_MODEL_NOT_IN_CATALOG')
    path=private/'model-bindings.json'
    path.write_text(json.dumps({'schemaVersion':'finance-model-bindings.v1','bindings':[{
        'modelRef':model['modelRef'],'revision':'snapshot-review-v1','enabled':True,
        'protocol':'OPENAI_CHAT_COMPLETIONS','provider':model['provider'],'modelName':model['modelName'],
        'urlEnvironment':'FH_CONN_REVIEW_URL','keyEnvironment':'FH_CONN_REVIEW_KEY'}]}))
    path.chmod(0o600)
    env.update(FH_MODEL_REGISTRY=str(path),FH_CONN_REVIEW_URL=env['FH_MODEL_URL'],FH_CONN_REVIEW_KEY=env['FH_MODEL_KEY'])


def login_server(config):
    value=json.loads(config.read_text())
    class Login(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_GET(self):
            if self.path != '/'+value['ticket'] or time.time()>value['expiresAt']:
                self.send_error(404);return
            self.send_response(303)
            self.send_header('Set-Cookie','fh_local='+value['cookie']+'; HttpOnly; SameSite=Strict; Path=/')
            self.send_header('Cache-Control','no-store')
            self.send_header('Location',value['workbenchUrl'])
            self.end_headers()
    HTTPServer(('127.0.0.1',value['port']),Login).serve_forever()


def prepare(harness,sources,output,port,login_port):
    os.umask(0o077);output.mkdir(parents=True,exist_ok=False)
    workspace=output/'workspace';records=workspace/'workbench-records';records.mkdir(parents=True)
    secret=secrets.token_hex(32).encode();(records/'.local-session-key').write_bytes(secret)
    owner=secrets.token_hex(16);owner_ref=hashlib.sha256(owner.encode()).hexdigest()
    target=workspace/'execution-history'/owner_ref;target.mkdir(parents=True)
    provenance=[]
    for source in sources:
        source=source.resolve()
        for file in source.rglob('*'):
            if not file.is_file() or file.is_symlink() or file.name=='.quota.lock':continue
            relative=file.relative_to(source);dest=target/relative
            if dest.exists():raise ValueError('DUPLICATE_REVIEW_RECORD')
            dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(file,dest)
        provenance.append({'source':str(source),'mode':'SYNTHETIC_COPY_OWNER_REBOUND_ONLY'})
    for record in target.glob('*.jsonl'):
        rows=[json.loads(line) for line in record.read_text().splitlines()]
        rows[0]['ownerRef']=owner_ref
        for ref in rows:
            if ref.get('recordType')!='model-snapshot':continue
            body=target/'model-snapshots'/f"{ref['requestId']}.{ref['callId']}.{ref['part']}.json"
            if not body.exists():continue
            payload=json.loads(body.read_text());payload['ownerRef']=owner_ref
            raw=json.dumps(payload,ensure_ascii=False).encode();body.write_bytes(raw)
            ref['digest']=hashlib.sha256(raw).hexdigest()
        record.write_text('\n'.join(json.dumps(row) for row in rows)+'\n')
    url=f'http://127.0.0.1:{port}/'
    cookie=owner+'.'+hmac.new(secret,owner.encode(),hashlib.sha256).hexdigest()
    config={'port':login_port,'ticket':secrets.token_urlsafe(32),'expiresAt':time.time()+24*3600,
            'cookie':cookie,'workbenchUrl':url}
    private=output/'private';private.mkdir()
    config_path=private/'login.json';config_path.write_text(json.dumps(config))
    (private/'login-url.txt').write_text(f"http://127.0.0.1:{login_port}/{config['ticket']}\n")
    (private/'browser-state.json').write_text(json.dumps({'cookies':[{'name':'fh_local','value':cookie,
        'domain':'127.0.0.1','path':'/','httpOnly':True,'secure':False,'sameSite':'Strict','expires':-1}], 'origins':[]}))
    env=dict(os.environ,FH_SNAPSHOT_VIEW_OWNERS=owner_ref,FH_SNAPSHOTS_ENABLED='true',
             FH_SNAPSHOT_CAPTURE_OWNERS=owner_ref)
    bind_model(env,private)
    if env.get('FH_BANK_URL'):
        policy=private/'synthetic-access-policy.json'
        policy.write_text(json.dumps({'schemaVersion':'local-simulator-access.v0.1','bankUrl':env['FH_BANK_URL'],
            'subjects':{'customer-demo-001':{'enabled':True,
                'expiresAt':(datetime.now(timezone.utc)+timedelta(hours=24)).isoformat(),
                'scopes':['cashflow:read','account:read','wealth:facts:read','wealth:customer:read']}}}))
        env.update(FH_ACCESS_POLICY=str(policy),FH_CASHFLOW_ACCESS_POLICY=str(policy))
    processes=[]
    try:
        for name,command in [('workbench',[sys.executable,'-m','finance_solution_builder.workbench',
            '--harness-repo',str(harness),'--data-dir',str(workspace),'--port',str(port)]),
            ('login',[sys.executable,str(Path(__file__).resolve()),'--login-server',str(config_path)])]:
            with (output/(name+'.log')).open('wb') as log:
                child=subprocess.Popen(command,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            processes.append(child)
        for attempt in range(100):
            if any(child.poll() is not None for child in processes):raise RuntimeError('REVIEW_SERVER_START_FAILED')
            try:
                urllib.request.urlopen(url,timeout=1).close();break
            except OSError:
                if attempt==99:raise
                time.sleep(.1)
        info={'url':url,'pids':[c.pid for c in processes],'ownerRef':owner_ref,'records':len(list(target.glob('*.jsonl'))),
              'sources':provenance,'loginFile':str(private/'login-url.txt'),
              'browserState':str(private/'browser-state.json'),'scope':'LOOPBACK_SYNTHETIC_REVIEW_ONLY',
              'modelOrBankOnHistoryRead':'NO_EXECUTION_PATH'}
        (output/'review.json').write_text(json.dumps(info,indent=2));print(json.dumps({k:v for k,v in info.items() if k!='ownerRef'}))
    except BaseException:
        for child in processes:
            if child.poll() is None:child.terminate();child.wait(timeout=10)
        raise


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--login-server',type=Path)
    parser.add_argument('--harness',type=Path);parser.add_argument('--source',action='append',type=Path)
    parser.add_argument('--output',type=Path);parser.add_argument('--port',type=int,default=18428)
    parser.add_argument('--login-port',type=int,default=18429);args=parser.parse_args()
    if args.login_server:login_server(args.login_server)
    else:prepare(args.harness,args.source,args.output,args.port,args.login_port)
