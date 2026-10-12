"""A1 three-composition acceptance using native SDK hosts and synthetic HTTP sources."""
import argparse
import ast
import copy
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


QUERIES = {'account.balance.query': 'balance-probe', 'wealth.facts.describe': 'wealth-probe',
    'finance.transaction-summary.query': 'summary-probe 2026-08',
    'finance.spending-analysis.query': 'analysis-probe 2026-08'}


def verify(candidate, output, harness=None):
    output.mkdir(parents=True, exist_ok=True)
    fixture=Endpoints(); policy=output/'policy.json'; checks=[]; results={}
    manifest=json.loads((candidate/'service-lock.json').read_text())
    capabilities=set(manifest['capabilities'])
    python=manifest['runtime']['language']=='python'
    plan=json.loads((candidate/'installation-plan.json').read_text()) if python else None
    scopes=['account:read','wealth:facts:read','wealth:customer:read','cashflow:read']
    if any(c.startswith('finance.') for c in capabilities):
        assert harness is not None, 'cashflow source fixtures require --harness'
        fixture.cashflow={name:json.loads((harness/'conformance/fixtures/cashflow/source'/(name+'.json')).read_text())
                          for name in ('summary','analysis','summary-policy','analysis-policy')}
    def grant(enabled=True, selected=None):
        temporary=policy.with_suffix('.tmp')
        temporary.write_text(json.dumps({'schemaVersion':'local-simulator-access.v0.1','bankUrl':fixture.base,
            'subjects':{fixture.customer:{'enabled':enabled,'expiresAt':expiry(),'scopes':scopes if selected is None else selected}}}))
        temporary.replace(policy)
    def check(name,value):
        checks.append(dict(name=name,passed=bool(value)))
        (output/'checks.json').write_text(json.dumps(checks,indent=2)); assert value,name
    grant(); token=secrets.token_urlsafe(48)
    environment=dict(os.environ,FH_MODEL_URL=fixture.base+'/v1',FH_MODEL_NAME='controlled-a1',FH_MODEL_KEY='private-a1-key',
        FH_MODEL_MODE='CONTROLLED_TEST',FH_HOST_TOKEN=token,FH_PORT='0',FH_BANK_URL=fixture.base,FH_BANK_LOGIN='test',
        FH_BANK_SECRET='private-bank-key',FH_ACCESS_POLICY=str(policy),FH_HISTORY_DIR=str(output/'history'),
        FH_HISTORY_OWNER='a1-owner',FH_EXPECTED_LOCK=hashlib.sha256((candidate/'service-lock.json').read_bytes()).hexdigest())
    command=([str(candidate/'.venv/bin/python'),'-I',str(candidate/'start_python.py')] if python else
        ['java','-Xmx256m','-cp',str(next((candidate/'target').glob('*.jar')))+os.pathsep+str(candidate/'target/dependency/*'),
         'io.github.bitlhk.finance.builder.local.LocalAgentHost',str(candidate)])
    (candidate/'instance.json').unlink(missing_ok=True)
    stream=(output/'host.log').open('wb'); child=subprocess.Popen(command,cwd=candidate,env=environment,stdout=stream,stderr=stream)
    try:
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            if child.poll() is not None: raise RuntimeError('HOST_START_FAILED')
            if (candidate/'instance.json').exists(): break
            time.sleep(.1)
        info=json.loads((candidate/'instance.json').read_text());base='http://127.0.0.1:'+str(info['port'])
        def opened():
            code,data=request(base,token,'/sessions',{});check('session-open',code==200)
            if python:
                installed=data['installations']
                check('built-plan-equals-executing-bindings',installed['javaBridge']==plan)
                check('native-tools-equal-plan',set(installed['pythonTools'])=={x['definition']['toolName'] for x in plan['capabilities']})
                check('no-task-or-unknown-tools',set(x['definition']['capabilityId'] for x in plan['capabilities'])==capabilities
                    and all(x['definition']['operation']=='QUERY' for x in plan['capabilities']))
            return data['sessionId']
        sid=opened()
        for cap in sorted(capabilities):
            query=QUERIES[cap]
            before=len(fixture.models)
            code,result=request(base,token,f'/sessions/{sid}/chat',{'query':query+' A1_FIRST_ROUND'})
            check(cap+'-roundtrip',code==200 and len(fixture.models)>=before+2)
            tools=result['toolResults']; execution=next(t for t in tools if t.get('data',{}).get('capabilityId')==cap)
            results[cap]=execution
            expected={'account.balance.query':'12345.67','wealth.facts.describe':'1000000.12',
                      'finance.transaction-summary.query':'8749.50','finance.spending-analysis.query':'8999.50'}[cap]
            check(cap+'-fact-reaches-model',expected in json.dumps(fixture.models[-1]))
            check(cap+'-typed-display',expected in json.dumps(result.get('display',{})))
            if python:
                content=fixture.models[-1]['messages'][-1]['content']
                try: body=json.loads(content)
                except json.JSONDecodeError: body=ast.literal_eval(content)
                check('model-result-excludes-ui-and-trace-envelope',not {'cards','evidence','checkpoint','taskId'}&set(body))
                check('model-business-data-excludes-host-metadata',not {'harness','traceId','requestId'}&set(body.get('data',{})))
            before=len(fixture.models)
            code,_=request(base,token,f'/sessions/{sid}/chat',{'query':query+' A1_SECOND_ROUND'})
            check('prior-context-not-just-tool-turn',code==200 and 'A1_FIRST_ROUND' in json.dumps(fixture.models[before]))
            fixture.zero=True
            original_cashflow=copy.deepcopy(fixture.cashflow)
            if cap.startswith('finance.'):
                source=fixture.cashflow['summary' if 'summary' in cap else 'analysis']
                source.update(totalIncome='0.00',totalExpense='0.00',netCashflow='0.00')
                if 'analysis' in cap:
                    code,conflict=request(base,token,f'/sessions/{sid}/chat',{'query':query+' inconsistent totals'})
                    rejected=next(t for t in conflict['toolResults'] if t.get('data',{}).get('capabilityId')==cap)
                    check('cashflow-conflicting-totals-rejected',code==200 and rejected['data']['resultStatus']=='FAILED')
                    results[cap+'-conflict']=rejected
                    for item in source['categories']:
                        item.update(income='0.00',expense='0.00')
                    for item in source['trend']:
                        item.update(income='0.00',expense='0.00',netCashflow='0.00')
            code,zero=request(base,token,f'/sessions/{sid}/chat',{'query':query+' zero'})
            from decimal import Decimal
            data=next(t['data'] for t in zero['toolResults'] if t.get('data',{}).get('capabilityId')==cap)
            zero_amount=(data['accounts'][0]['availableBalance'] if cap=='account.balance.query'
                         else data['facts']['assets']['amounts'][0]['money']['value'] if cap=='wealth.facts.describe'
                         else data['facts']['totals']['netCashflow'])
            check(cap+'-zero-not-missing',code==200 and data['resultStatus']=='COMPLETED' and Decimal(str(zero_amount))==0)
            results[cap+'-zero']=zero['toolResults'];fixture.zero=False
            fixture.cashflow=original_cashflow
            if cap.startswith('finance.'):
                fixture.cashflow['summary' if 'summary' in cap else 'analysis'].pop('totalExpense')
                code,missing=request(base,token,f'/sessions/{sid}/chat',{'query':query+' missing'})
                facts=next(t['data']['facts'] for t in missing['toolResults'] if t.get('data',{}).get('capabilityId')==cap)
                check(cap+'-missing-not-zero',code==200 and 'expense' not in facts['totals'])
                results[cap+'-missing']=missing['toolResults'];fixture.cashflow=copy.deepcopy(original_cashflow)
                before_reads=len(fixture.reads)
                code,invalid=request(base,token,f'/sessions/{sid}/chat',{'query':query.replace('2026-08','2026-13')})
                check(cap+'-invalid-month-no-business-read',len(fixture.reads)==before_reads)
                check('cashflow-no-raw-customer-data-in-model',not any(v in json.dumps(fixture.models)
                      for v in ('DO_NOT_EXPOSE_RAW','synthetic-private-account')))
        if 'wealth.facts.describe' in capabilities:
            fixture.missing_risk=True
            code,missing=request(base,token,f'/sessions/{sid}/chat',{'query':'wealth-probe missing'})
            risk=next(t['data']['facts']['risk'] for t in missing['toolResults']
                      if t.get('data',{}).get('capabilityId')=='wealth.facts.describe')
            check('wealth-source-unavailable-not-zero',code==200 and risk['availability']=='UNAVAILABLE' and not risk.get('level'))
            results['wealth-missing']=missing['toolResults'];fixture.missing_risk=False
        before=len(fixture.reads)
        code,_=request(base,token,'/sessions',{'principalId':'other'})
        check('caller-cannot-select-identity',code==400 and len(fixture.reads)==before)
        other=opened();boundary=len(fixture.models)
        query=QUERIES[sorted(capabilities)[0]]
        code,_=request(base,token,f'/sessions/{other}/chat',{'query':query+' A1_OTHER_SESSION'})
        check('native-context-isolation',code==200 and 'A1_FIRST_ROUND' not in json.dumps(fixture.models[boundary]))
        before=len(fixture.reads)
        if 'wealth.facts.describe' in capabilities:
            prior_models=len(fixture.models)
            grant(selected=['account:read','wealth:facts:read','cashflow:read'])
            code,denied=request(base,token,f'/sessions/{sid}/chat',{'query':'repeat previous wealth facts'})
            check('source-grant-revocation-blocks-old-context',code==403 and len(fixture.reads)==before
                and len(fixture.models)==prior_models and denied.get('clearConversation'))
            grant()
        grant(selected=[])
        code,_=request(base,token,'/sessions',{})
        check('missing-selected-scope-denied-before-business',code==403 and len(fixture.reads)==before)
        grant();sid=opened()
        ready,release=threading.Event(),threading.Event();fixture.pause_source=(ready,release)
        with ThreadPoolExecutor(max_workers=1) as pool:
            future=pool.submit(request,base,token,f'/sessions/{sid}/chat',{'query':query+' revoke-race'})
            check('bank-request-pending',ready.wait(20));before=len(fixture.models);grant(False);release.set()
            code,denied=future.result(40)
        check('revoked-source-result-never-enters-model',code==403 and len(fixture.models)==before)
        check('revocation-clears-output',denied.get('clearConversation') and '12345.67' not in json.dumps(denied)
              and '1000000.12' not in json.dumps(denied))
        before=len(fixture.reads)
        code,_=request(base,token,f'/sessions/{sid}/chat',{'query':'repeat old facts'})
        check('revoked-history-not-reused',code==403 and len(fixture.reads)==before)
        check('zero-business-writes',not fixture.writes)
        (output/'business-results.json').write_text(json.dumps(results,indent=2))
        (output/'calls.json').write_text(json.dumps(dict(reads=fixture.reads,modelRequests=len(fixture.models),writes=fixture.writes),indent=2))
    finally:
        child.terminate()
        try: child.wait(15)
        except subprocess.TimeoutExpired: child.kill();child.wait(5)
        fixture.close();stream.close()
        text=(output/'host.log').read_text(errors='replace')
        check('no-sensitive-sdk-logs',all(v not in text for v in ['private-a1-key','private-bank-key','A1_FIRST_ROUND','12345.67']))
    return checks


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--candidate',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--harness',type=Path)
    args=parser.parse_args();checks=verify(args.candidate.resolve(),args.output.resolve(),args.harness)
    print(json.dumps(dict(checks=len(checks),mode='CONTROLLED_HTTP_NATIVE_RUNTIME')))
