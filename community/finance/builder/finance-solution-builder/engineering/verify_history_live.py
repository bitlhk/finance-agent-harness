"""Explicit actual-model / full synthetic simulator smoke, never ordinary CI."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from verify_local_service import ReadOnlyRecorder, request


def verify(candidate, bank, output, reader):
    assert all(os.environ.get(k) for k in ('FH_MODEL_URL','FH_MODEL_KEY','FH_MODEL_NAME')), 'MODEL_CONFIGURATION_REQUIRED'
    output.mkdir(parents=True,exist_ok=False);output.chmod(0o700)
    spec=importlib.util.spec_from_file_location('standalone_history',reader);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    store=module.ExecutionHistory(output);owner='isolated-live-acceptance';proxy=ReadOnlyRecorder(bank)
    token=secrets.token_urlsafe(40);policy=output/'policy.json';child=None;checks=[]
    def check(name,value):
        assert value,name
        checks.append(name)
    def grant(enabled):
        temp=policy.with_suffix('.tmp');temp.write_text(json.dumps({'schemaVersion':'local-simulator-access.v0.1','bankUrl':proxy.base,
            'subjects':{'customer-demo-001':{'enabled':enabled,'expiresAt':(datetime.now(timezone.utc)+timedelta(minutes=30)).isoformat(),
            'scopes':['cashflow:read','account:read','wealth:facts:read','wealth:customer:read']}}}));temp.chmod(0o600);temp.replace(policy)
    grant(True)
    env=dict(os.environ,FH_BANK_URL=proxy.base,FH_BANK_LOGIN='demo',FH_BANK_SECRET='demo123',FH_ACCESS_POLICY=str(policy),
        FH_HOST_TOKEN=token,FH_PORT='0',FH_MODEL_MODE='LIVE',FH_HISTORY_DIR=str(store.directory(owner)),
        FH_HISTORY_OWNER=hashlib.sha256(owner.encode()).hexdigest())
    (candidate/'instance.json').unlink(missing_ok=True)
    try:
        with (output/'host.log').open('wb') as log:
            child=subprocess.Popen(['java','-Xmx512m','-cp',str(next((candidate/'target').glob('*.jar')))+os.pathsep+str(candidate/'target/dependency/*'),
                'io.github.bitlhk.finance.builder.local.LocalAgentHost',str(candidate)],cwd=candidate,env=env,stdout=log,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+30
            while not (candidate/'instance.json').exists():
                assert child.poll() is None and time.monotonic()<deadline;time.sleep(.05)
            instance=json.loads((candidate/'instance.json').read_text());check('new_isolated_pid',instance['pid']==child.pid)
            base='http://127.0.0.1:'+str(instance['port']);code,value=request(base,token,'/sessions',{});check('session_authorized',code==200)
            sid=value['sessionId'];roots=[];turns=[]
            cases = [('请调用收支汇总工具查询我2026年8月的收入、支出与结余，说明来源和缺口。','summary','finance_transaction_summary'),
                     ('再调用收支分类工具查看2026年8月的支出分类，不要混合不同统计口径。','analysis','finance_spending_analysis')]
            capabilities = json.loads((candidate/'service-lock.json').read_text()).get('capabilities', [])
            if 'account.balance.query' in capabilities:
                cases.append(('请通过账户余额查询工具查询我本人的账户余额，说明币种和来源。','balance','account_balance_query'))
            if 'wealth.facts.describe' in capabilities:
                cases.append(('请通过财富事实工具概述我已知资产范围、风险测评和信息缺口。','wealth','wealth_facts_describe'))
            for query,section,tool in cases:
                code,value=request(base,token,f'/sessions/{sid}/chat',{'query':query})
                check(section+'_actual_model_completed',code==200)
                cards=value['display']['cards']
                check(section+'_actual_tool_result',any(e.get('tool')==tool for e in value.get('events',[])))
                root=value['executionObservation']['requestId'];roots.append(root)
                deadline=time.monotonic()+3;detail=store.get(owner,root)
                while detail['status']=='UNFINISHED' and time.monotonic()<deadline:time.sleep(.02);detail=store.get(owner,root)
                check(section+'_history_complete',detail['status']=='COMPLETED' and detail['complete'])
                check(section+'_bank_attempt_recorded',any(s['component']=='provider-http' and s['dispatch']=='SENT' for s in detail['spans']))
                turns.append({'section':section,'requestId':root,'modelName':value['modelName'],
                    'responseDigest':hashlib.sha256(json.dumps(value['response'],sort_keys=True).encode()).hexdigest(),
                    'cardDigest':hashlib.sha256(json.dumps(cards,sort_keys=True).encode()).hexdigest(),
                    'historyDurationNanos':detail['durationNanos']})
            grant(False);before=len([c for c in proxy.calls if not c['identity']])
            code,_=request(base,token,f'/sessions/{sid}/chat',{'query':'继续查询同一月份'})
            check('revoked_request_rejected',code==403)
            check('no_business_read_after_revoke',before==len([c for c in proxy.calls if not c['identity']]))
            counts=len(proxy.calls)
            for root in roots:store.get(owner,root)
            check('historical_read_has_no_upstream_call',counts==len(proxy.calls))
            business=[c for c in proxy.calls if not c['identity']]
            check('zero_business_writes',all(c['method']=='GET' for c in business))
            report={'mode':'ACTUAL_CONFIGURED_MODEL_FULL_SYNTHETIC_BANK','checks':checks,'turns':turns,'businessCalls':business,
                'serviceLockSha256':hashlib.sha256((candidate/'service-lock.json').read_bytes()).hexdigest(),
                'modelQuality':'NOT_SCORED','humanAcceptance':'PENDING','actualDayRetention':'NOT_RUN'}
            (output/'report.json').write_text(json.dumps(report,indent=2,ensure_ascii=False));print(json.dumps({'checks':len(checks),'report':str(output/'report.json')}))
    finally:
        if child and child.poll() is None:
            child.terminate()
            try:child.wait(timeout=10)
            except subprocess.TimeoutExpired:child.kill();child.wait()
        proxy.close();policy.unlink(missing_ok=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('candidate',type=Path);parser.add_argument('--bank',required=True)
    parser.add_argument('--output',type=Path,required=True);parser.add_argument('--reader',type=Path,required=True)
    args=parser.parse_args();verify(args.candidate.resolve(),args.bank,args.output.resolve(),args.reader.resolve())
