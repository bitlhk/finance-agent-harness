"""Controlled HTTP evidence for the generated native Python Candidate; never a live-model fallback."""
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


def verify(candidate, output):
    output.mkdir(parents=True, exist_ok=True)
    fixture = Endpoints()
    policy = output / 'policy.json'
    def authorize(enabled=True, scopes=None):
        value = {'schemaVersion': 'local-simulator-access.v0.1', 'bankUrl': fixture.base,
                 'subjects': {fixture.customer: {'enabled': enabled, 'expiresAt': expiry(),
                    'scopes': ['account:read'] if scopes is None else scopes}}}
        temporary = policy.with_suffix('.tmp')
        temporary.write_text(json.dumps(value))
        temporary.replace(policy)
    authorize()
    token = secrets.token_urlsafe(48)
    environment = dict(os.environ, FH_MODEL_URL=fixture.base+'/v1', FH_MODEL_NAME='explicit-boundary-test',
        FH_MODEL_KEY='private-test-key-marker', FH_MODEL_MODE='CONTROLLED_TEST', FH_HOST_TOKEN=token,
        FH_PORT='0', FH_BANK_URL=fixture.base, FH_BANK_LOGIN='test', FH_BANK_SECRET='private-bank-secret-marker',
        FH_ACCESS_POLICY=str(policy), FH_HISTORY_DIR=str(output/'history'), FH_HISTORY_OWNER='controlled-owner',
        FH_EXPECTED_LOCK=hashlib.sha256((candidate/'service-lock.json').read_bytes()).hexdigest())
    (candidate/'instance.json').unlink(missing_ok=True)
    checks=[]
    def check(name, passed):
        checks.append({'name':name,'passed':bool(passed)})
        (output/'checks.json').write_text(json.dumps(checks,indent=2))
        assert passed, name
    stream=(output/'host.log').open('wb')
    child=subprocess.Popen([str(candidate/'.venv/bin/python'),'-I',str(candidate/'start_python.py')],
                           cwd=candidate,env=environment,stdout=stream,stderr=stream)
    try:
        deadline=time.monotonic()+40
        while time.monotonic()<deadline:
            if child.poll() is not None: raise RuntimeError('HOST_START_FAILED')
            if (candidate/'instance.json').exists(): break
            time.sleep(.1)
        info=json.loads((candidate/'instance.json').read_text())
        base='http://127.0.0.1:'+str(info['port'])
        code,health=request(base,token,'/health')
        check('native-python-runtime',code==200 and health['runtime']=='openjiuwen-python-0.1.18')
        check('balance-only-no-task-tools',health['selectedCapabilities']==['account.balance.query'])
        code,opened=request(base,token,'/sessions',{})
        check('assembler-and-native-tool-installed',code==200 and 'javaBridge' in opened.get('installations',{}))
        sid=opened['sessionId']
        code,result=request(base,token,f'/sessions/{sid}/chat',{'query':'balance-probe UNIQUE_ROUND_ONE'})
        check('native-agent-tool-roundtrip',code==200 and len(fixture.models)==2 and len(fixture.reads)==1)
        check('canonical-financial-display',result.get('display',{}).get('schemaVersion')=='finance-display.v0.1'
              and bool(result['display']['cards']) and '12345.67' in json.dumps(result['display']))
        check('execution-evidence-returned',bool(result.get('toolResults'))
              and bool(result.get('executionObservation',{}).get('requestId')))
        code,snapshot=request(base,token,f'/sessions/{sid}/snapshot',{})
        check('display-history-snapshot',code==200 and len(snapshot['messages'])==2
              and snapshot['messages'][1]['display']==result['display'])
        check('bank-fact-in-next-model-request','12345.67' in json.dumps(fixture.models[-1]))
        boundary=len(fixture.models)
        code,result=request(base,token,f'/sessions/{sid}/chat',{'query':'balance-probe UNIQUE_ROUND_TWO'})
        check('second-round-history',code==200 and 'UNIQUE_ROUND_ONE' in json.dumps(fixture.models[boundary])
              and '12345.67' in json.dumps(fixture.models[boundary]))
        code,partial_session=request(base,token,'/sessions',{})
        check('partial-session-open',code==200)
        partial_sid=partial_session['sessionId']
        def fail_after_tool(body):
            if body['messages'][-1]['role']=='tool':
                return 500,{'error':{'message':'private-after-tool-error'}}
            return 200,{'id':'partial-tool-call','object':'chat.completion','created':int(time.time()),
                'model':'explicit-boundary-test','choices':[{'index':0,'message':{'role':'assistant','content':None,
                    'tool_calls':[{'id':'call-partial','type':'function','function':{'name':'account_balance_query','arguments':'{}'}}]},
                    'finish_reason':'tool_calls'}],'usage':{'prompt_tokens':1,'completion_tokens':1,'total_tokens':2}}
        fixture.model_responder=fail_after_tool
        code,result=request(base,token,f'/sessions/{partial_sid}/chat',{'query':'balance-probe PARTIAL_AFTER_TOOL'})
        check('tool-fact-survives-model-failure',code==200 and result.get('status')=='PARTIAL'
              and result.get('failureCode')=='MODEL_RESPONSE_UNAVAILABLE_AFTER_TOOL_SUCCESS'
              and bool(result.get('executionObservation',{}).get('requestId'))
              and bool(result.get('display',{}).get('cards')) and '12345.67' in json.dumps(result['display']))
        code,snapshot=request(base,token,f'/sessions/{partial_sid}/snapshot',{})
        check('partial-session-remains-actionable',code==200 and len(snapshot.get('messages',[]))==2)
        fixture.model_responder=None
        fixture.final_text='余额查询完成，另有一笔历史转账 66 元。'
        code,guard_session=request(base,token,'/sessions',{})
        check('fact-guard-session-open',code==200)
        code,result=request(base,token,f"/sessions/{guard_session['sessionId']}/chat",{'query':'balance-probe FACT_GUARD'})
        check('unsupported-model-amount-blocked',code==200 and result.get('status')=='PARTIAL'
              and result.get('failureCode')=='MODEL_RESPONSE_FACT_CONFLICT'
              and '66 元' not in result.get('response','') and '12345.67' in json.dumps(result['display']))
        fixture.final_text='CONTROLLED_TEST_COMPLETION'
        before=len(fixture.reads)
        code,_=request(base,token,'/sessions',{'principalId':'other'})
        check('caller-cannot-set-principal',code==400 and len(fixture.reads)==before)
        code,other=request(base,token,'/sessions',{})
        check('second-session-open',code==200)
        boundary=len(fixture.models)
        code,_=request(base,token,f"/sessions/{other['sessionId']}/chat",{'query':'balance-probe ISOLATED_SESSION'})
        check('session-history-isolated',code==200 and 'UNIQUE_ROUND_ONE' not in json.dumps(fixture.models[boundary]))
        ready,release=threading.Event(),threading.Event()
        fixture.pause_source=(ready,release)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(request,base,token,f'/sessions/{sid}/chat',{'query':'balance-probe REVOKE_WHILE_BANK_PENDING'})
            check('source-request-observed',ready.wait(15))
            before_models=len(fixture.models)
            authorize(False)
            release.set()
            code,result=future.result(30)
        check('late-bank-result-no-model-dispatch',code==403 and len(fixture.models)==before_models)
        check('revoked-output-cleared',result.get('clearConversation') is True and '12345.67' not in json.dumps(result))
        before=len(fixture.reads)
        code,_=request(base,token,f'/sessions/{sid}/chat',{'query':'previous balance?'})
        check('revoked-session-not-reused',code==403 and len(fixture.reads)==before)
        authorize(True, ['wealth:facts:read'])
        code,_=request(base,token,'/sessions',{})
        check('insufficient-grants-no-bank-read',code==403 and len(fixture.reads)==before)
        check('zero-business-writes',not fixture.writes)
        check('java-history-created',bool(list((output/'history').rglob('*.jsonl'))))
        authorize()
        code,opened=request(base,token,'/sessions',{})
        check('fresh-session-after-revocation',code==200)
        fixture.model_responder=lambda body:(500,{'error':{'message':'private-test-key-marker'}})
        before=len(fixture.reads)
        code,result=request(base,token,f"/sessions/{opened['sessionId']}/chat",{'query':'model failure'})
        check('model-failure-closes-session',code==502 and result.get('clearConversation')
              and len(fixture.reads)==before and 'private-test-key-marker' not in json.dumps(result))
        code,_=request(base,token,f"/sessions/{opened['sessionId']}/chat",{'query':'retry'})
        check('failed-session-not-reused',code==403 and len(fixture.reads)==before)
    finally:
        child.terminate()
        try: child.wait(15)
        except subprocess.TimeoutExpired: child.kill(); child.wait(5)
        fixture.close(); stream.close()
        text=(output/'host.log').read_text(errors='replace')
        check('no-sensitive-sdk-body-logs',all(value not in text for value in
            ['12345.67','UNIQUE_ROUND_ONE','private-test-key-marker','private-bank-secret-marker']))
    return checks


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--candidate',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    values=verify(args.candidate.resolve(),args.output.resolve())
    print(json.dumps({'passed':len(values),'model':'CONTROLLED_HTTP','runtime':'NATIVE_REACT_AGENT_RUNNER'}))
