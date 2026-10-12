"""Isolated native Python snapshot faults; never uses a live model or production disk."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import threading
import time

from verify_local_service import Endpoints, expiry, request
from finance_solution_builder.execution_history import ExecutionHistory


def verify(candidate,harness,output):
    output.mkdir(parents=True,exist_ok=False)
    checks=[]
    def check(name,value):
        checks.append({'name':name,'passed':bool(value)})
        (output/'checks.json').write_text(json.dumps(checks,indent=2))
        assert value,name
    for mode in ('off','ungranted','on','quota','storage-failure','error','revoked','business-failure','hidden','tool-error'):
        root=output/mode;root.mkdir()
        fixture=Endpoints()
        fixture.cashflow={k:json.loads((harness/'conformance/fixtures/cashflow/source'/(k+'.json')).read_text())
            for k in ('summary','analysis','summary-policy','analysis-policy')}
        owner='a3-'+mode;owner_ref=hashlib.sha256(owner.encode()).hexdigest();store=ExecutionHistory(root)
        policy=root/'policy.json'
        def grant(enabled):
            temp=policy.with_suffix('.tmp');temp.write_text(json.dumps({'schemaVersion':'local-simulator-access.v0.1',
                'bankUrl':fixture.base,'subjects':{fixture.customer:{'enabled':enabled,'expiresAt':expiry(),
                'scopes':['account:read','wealth:facts:read','wealth:customer:read','cashflow:read']}}}));temp.replace(policy)
        grant(True);token=secrets.token_urlsafe(40)
        env=dict(os.environ,FH_MODEL_URL=fixture.base+'/v1',FH_MODEL_KEY='PRIVATE_MODEL_A3',FH_MODEL_NAME='controlled-a3',
            FH_MODEL_MODE='CONTROLLED_TEST',FH_BANK_URL=fixture.base,FH_BANK_LOGIN='test',FH_BANK_SECRET='PRIVATE_BANK_A3',
            FH_ACCESS_POLICY=str(policy),FH_HISTORY_DIR=str(store.directory(owner)),FH_HISTORY_OWNER=owner_ref,
            FH_HOST_TOKEN=token,FH_PORT='0',FH_SNAPSHOTS_ENABLED='false' if mode=='off' else 'true',
            FH_SNAPSHOT_CAPTURE_OWNERS='other-owner' if mode=='ungranted' else owner_ref,
            FH_SNAPSHOT_MAX_FILES='1' if mode=='quota' else '128')
        if mode=='storage-failure':
            directory=store.directory(owner);directory.mkdir(parents=True,exist_ok=True)
            (directory/'model-snapshots').write_text('ISOLATED_NON_DIRECTORY_FAULT')
        if mode=='error':fixture.model_responder=lambda body:(500,{'error':{'message':'PRIVATE_MODEL_A3 HIDDEN_REASONING'}})
        if mode=='hidden':fixture.model_responder=lambda body:(200,{'id':'synthetic-hidden','object':'chat.completion',
            'created':1,'model':'controlled-a3','choices':[{'index':0,'finish_reason':'stop','message':
            {'role':'assistant','content':'VISIBLE_A3','reasoning_content':'HIDDEN_REASONING'}}]})
        if mode=='business-failure':fixture.cashflow['analysis']['totalExpense']='1.00'
        info=candidate/'instance.json';info.unlink(missing_ok=True)
        log=(root/'host.log').open('wb')
        child=subprocess.Popen([str(candidate/'.venv/bin/python'),'-I',str(candidate/'start_python.py')],
            cwd=candidate,env=env,stdout=log,stderr=log)
        release=None
        try:
            deadline=time.monotonic()+45
            while not info.exists() and child.poll() is None and time.monotonic()<deadline:time.sleep(.05)
            check(mode+'-started',info.exists() and child.poll() is None)
            base='http://127.0.0.1:'+str(json.loads(info.read_text())['port'])
            code,opened=request(base,token,'/sessions',{});check(mode+'-authorized',code==200)
            sid=opened['sessionId'];route='/sessions/'+sid+'/chat'
            query='analysis-probe 2026-08' if mode=='business-failure' else 'balance-probe'
            if mode=='tool-error':query+=' selector-probe'
            if mode=='revoked':
                ready=threading.Event();release=threading.Event();fixture.pause_source=(ready,release)
                with ThreadPoolExecutor(max_workers=1) as pool:
                    future=pool.submit(request,base,token,route,{'query':query})
                    check('bank-request-in-flight',ready.wait(15));before=len(fixture.models)
                    grant(False);release.set();code,result=future.result(45)
                check('revoked-no-next-model-call',code==403 and len(fixture.models)==before)
                check('revoked-clears',result.get('clearConversation'))
            else:code,result=request(base,token,route,{'query':query})
            check(mode+'-business-outcome',code in ({200,502} if mode=='tool-error' else
                {502 if mode=='error' else 403 if mode=='revoked' else 200}))
            if mode=='tool-error':check('invalid-tool-zero-bank-reads',not fixture.reads)
            if mode=='business-failure':
                check('failed-business-not-successful-observation',any(t.get('data',{}).get('resultStatus')=='FAILED'
                    for t in result['toolResults']))
            if mode=='on':
                before=len(fixture.models)
                code,second=request(base,token,route,{'query':'balance-probe SECOND_A3'})
                check('multiturn-retains-previous-tool-facts',code==200 and '12345.67' in json.dumps(fixture.models[before]))
                code,other=request(base,token,'/sessions',{});before=len(fixture.models)
                code,_=request(base,token,'/sessions/'+other['sessionId']+'/chat',{'query':'balance-probe OTHER_SESSION'})
                check('session-context-isolated',code==200 and 'SECOND_A3' not in json.dumps(fixture.models[before]))
            child.terminate();child.wait(15);log.close()
            records=[store.get(owner,p.stem) for p in store.directory(owner).glob('*.jsonl')]
            refs=[(record['requestId'],ref) for record in records for ref in record.get('snapshots',[])]
            if mode in ('off','ungranted'):check(mode+'-no-snapshot-body',not refs)
            else:check(mode+'-metadata-recorded',bool(refs))
            previous=os.environ.get('FH_SNAPSHOT_VIEW_OWNERS');os.environ['FH_SNAPSHOT_VIEW_OWNERS']=owner_ref
            try:
                counts=(len(fixture.models),len(fixture.reads),len(fixture.writes))
                bodies=[store.snapshot(owner,rid,ref['callId'],ref['part']) for rid,ref in refs]
                check(mode+'-history-zero-business-calls',counts==(len(fixture.models),len(fixture.reads),len(fixture.writes)))
                if mode=='quota':check('quota-explicitly-missing',any(x['state']=='NOT_CAPTURED' for x in bodies))
                if mode=='storage-failure':check('storage-failure-no-fake-complete',all(x['state']=='NOT_CAPTURED' for x in bodies))
                if mode=='error':check('client-error-recorded',any(x['state']=='CLIENT_ERROR' for x in bodies))
                if mode=='hidden':check('visible-response-retained',any('VISIBLE_A3' in x.get('body','') for x in bodies))
                if mode=='on':
                    rid,ref=refs[0]
                    try:store.snapshot('other-owner',rid,ref['callId'],ref['part'])
                    except PermissionError:check('cross-owner-denied',True)
                    else:raise AssertionError('cross-owner')
                    os.environ['FH_SNAPSHOT_VIEW_OWNERS']=''
                    try:store.snapshot(owner,rid,ref['callId'],ref['part'])
                    except PermissionError:check('separate-view-permission',True)
                    else:raise AssertionError('view-grant')
            finally:
                if previous is None:os.environ.pop('FH_SNAPSHOT_VIEW_OWNERS',None)
                else:os.environ['FH_SNAPSHOT_VIEW_OWNERS']=previous
            text=(root/'host.log').read_text()
            snapshot_text=''.join(p.read_text() for p in store.directory(owner).rglob('*.json'))
            check(mode+'-secret-and-hidden-log-free',all(x not in text+snapshot_text for x in
                ('PRIVATE_MODEL_A3','PRIVATE_BANK_A3',token,'HIDDEN_REASONING')))
            check(mode+'-zero-writes',not fixture.writes)
        finally:
            if release:release.set()
            if child.poll() is None:child.terminate();child.wait(15)
            log.close();fixture.close()
    print(json.dumps({'checks':len(checks),'passed':True}))


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--candidate',type=Path,required=True)
    p.add_argument('--harness',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();verify(a.candidate,a.harness,a.output)
