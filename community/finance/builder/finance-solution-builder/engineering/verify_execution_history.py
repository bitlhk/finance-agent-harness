"""Actual generated Host, controlled HTTP/model, crash/restart and historical read checks."""
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
from verify_local_service import Endpoints, request, expiry
from finance_solution_builder.execution_history import ExecutionHistory


def host_command(candidate, python):
    if python:
        return [str(candidate/'.venv/bin/python'),'-I',str(candidate/'start_python.py')]
    jar=next((candidate/'target').glob('*.jar'))
    return ['java','-Xmx512m','-cp',str(jar)+os.pathsep+str(candidate/'target/dependency/*'),
            'io.github.bitlhk.finance.builder.local.LocalAgentHost',str(candidate)]


def verify(candidate, harness, output):
    candidate = candidate.resolve(); output.mkdir(parents=True, exist_ok=False)
    python = json.loads((candidate/'service-lock.json').read_text())['runtime']['language']=='python'
    fixture = Endpoints()
    fixture.cashflow = {k: json.loads((harness / 'conformance/fixtures/cashflow/source' / (k + '.json')).read_text())
                        for k in ('summary', 'analysis', 'summary-policy', 'analysis-policy')}
    owner = 'controlled-review-owner'; store = ExecutionHistory(output)
    policy = output / 'policy.json'; token = secrets.token_urlsafe(40)
    def grant(enabled=True):
        value = {'schemaVersion':'local-simulator-access.v0.1','bankUrl':fixture.base,
                 'subjects':{fixture.customer:{'enabled':enabled,'expiresAt':expiry(),
                             'scopes':['cashflow:read','account:read','wealth:facts:read','wealth:customer:read']}}}
        temp=policy.with_suffix('.tmp');temp.write_text(json.dumps(value));temp.chmod(0o600);temp.replace(policy)
    grant()
    env = dict(os.environ, FH_BANK_URL=fixture.base,FH_BANK_LOGIN='demo',FH_BANK_SECRET='synthetic',
               FH_ACCESS_POLICY=str(policy),FH_HOST_TOKEN=token,FH_MODEL_URL=fixture.base+'/v1',
               FH_MODEL_KEY='controlled-not-secret',FH_MODEL_NAME='explicit-boundary-test',FH_MODEL_MODE='CONTROLLED_TEST',
               FH_PORT='0',FH_HISTORY_DIR=str(store.directory(owner)),FH_HISTORY_OWNER=hashlib.sha256(owner.encode()).hexdigest())
    processes=[];logs=[];checks=[]; pool=ThreadPoolExecutor(max_workers=3)
    def check(name, condition):
        if not condition: raise AssertionError(name)
        checks.append(name)
    def start(extra=None):
        info=candidate/'instance.json';info.unlink(missing_ok=True)
        log=(output/f'host-{len(processes)}.log').open('wb');logs.append(log)
        command=host_command(candidate, python)
        child=subprocess.Popen(command,cwd=candidate,env=dict(env,**(extra or {})),stdout=log,stderr=subprocess.STDOUT)
        processes.append(child);deadline=time.monotonic()+30
        while not info.exists():
            if child.poll() is not None or time.monotonic()>deadline: raise AssertionError('HOST_START_FAILED')
            time.sleep(.05)
        instance=json.loads(info.read_text());check('actual_pid_'+str(len(processes)),instance['pid']==child.pid)
        return child,'http://127.0.0.1:'+str(instance['port'])
    def session(base):
        code,value=request(base,token,'/sessions',{});check('authorized_session_'+str(len(checks)),code==200);return value['sessionId']
    def chat(base,sid,text='summary-probe 2026-08',run=None):
        return request(base,token,f'/sessions/{sid}/chat',{'query':text,**({'runId':run} if run else {})})
    try:
        child,base=start();sid=session(base)
        ready,release=threading.Event(),threading.Event();fixture.pause_source=(ready,release)
        future=pool.submit(chat,base,sid)
        check('source_received',ready.wait(10));time.sleep(.25);release.set()
        code,value=future.result(20);fixture.pause_source=None
        check('normal_source_slow_completed',code==200)
        identity=value['executionObservation']['requestId']; detail=store.get(owner,identity)
        check('actual_http_span',any(s['component']=='provider-http' and (s['durationNanos'] or 0)>200_000_000 for s in detail['spans']))
        check('version_digests_verified',all(v['verified'] for v in detail['versions'].values()))
        if python:
            capability_spans={s['spanId'] for s in detail['spans'] if s['component']=='capability'}
            check('capability_and_provider_parent_link',any(s['component']=='provider-http' and
                  s['parentSpanId'] in capability_spans for s in detail['spans']))
        else:
            check('tool_and_capability_parent_link',any(s['component']=='capability' and s['parentSpanId'] in
                  {t['spanId'] for t in detail['spans'] if t['component']=='runtime-tool'} for s in detail['spans']))
        check('no_customer_bodies', '8749.50' not in json.dumps(detail) and fixture.customer not in json.dumps(detail))
        ready,release=threading.Event(),threading.Event();fixture.pause_final=(ready,release)
        delayed=pool.submit(chat,base,sid)
        check('model_final_request_entered',ready.wait(10));time.sleep(.25);release.set()
        code,value=delayed.result(20);fixture.pause_final=None
        check('model_slow_completed',code==200)
        model_detail=store.get(owner,value['executionObservation']['requestId'])
        check('model_callback_interval_not_first_token',any(s['component']=='model' and (s['durationNanos'] or 0)>200_000_000
              and s['dispatch']=='NOT_OBSERVED' for s in model_detail['spans']))
        # Global lock waiting, with two pre-created independently authorized sessions.
        sid2=session(base);ready,release=threading.Event(),threading.Event();fixture.pause_source=(ready,release)
        first=pool.submit(chat,base,sid);check('concurrent_source_entered',ready.wait(10))
        second=pool.submit(chat,base,sid2);time.sleep(.25);release.set()
        one=first.result(20);two=second.result(20);fixture.pause_source=None
        check('two_sessions_complete',one[0]==two[0]==200)
        second_detail=store.get(owner,two[1]['executionObservation']['requestId'])
        if not python:
            check('native_lock_wait_recorded',any(s['operation']=='native-lock-wait' and
                  (s['durationNanos'] or 0)>150_000_000 for s in second_detail['spans']))
        # A response already in flight is denied before the next model call.
        ready,release=threading.Event(),threading.Event();fixture.pause_source=(ready,release)
        denied=pool.submit(chat,base,sid2);check('revocation_source_entered',ready.wait(10))
        before_models=len(fixture.models);grant(False);release.set()
        check('revocation_denied',denied.result(20)[0]==403);fixture.pause_source=None
        check('no_model_request_after_revocation',len(fixture.models)==before_models)
        rejected=[store.get(owner,r['requestId']) for r in store.list(owner)['records'] if r['status']=='DENIED']
        if python:
            check('python_does_not_invent_model_dispatch',all(s['dispatch']=='NOT_OBSERVED'
                  for r in rejected for s in r['spans'] if s['component']=='model'))
        else:
            check('force_finish_is_not_sent',any(s['component']=='model' and s['dispatch']=='NOT_SENT'
                  for r in rejected for s in r['spans']))
        grant();sid3=session(base)
        ready,release=threading.Event(),threading.Event();fixture.pause_source=(ready,release)
        pending=pool.submit(chat,base,sid3,'summary-probe 2026-08','cancel-probe')
        check('cancel_source_entered',ready.wait(10))
        cancel_code,cancel_value=request(base,token,f'/sessions/{sid3}/cancel',{'runId':'cancel-probe'})
        if python:
            check('cancel_request_ends_python_session',cancel_code==200 and
                  cancel_value.get('status')=='CANCEL_REQUESTED' and cancel_value.get('clearConversation') is True)
        else:
            check('cancel_request_not_bank_cancellation',cancel_code==200 and cancel_value['sourceRequestMayHaveCompleted'])
        release.set();cancelled_status=pending.result(20)[0]
        check('local_cancelled_response',cancelled_status in ({403,409,502} if python else {409}));fixture.pause_source=None
        # Persist a start, kill only this isolated child, restart, then read without invoking anything.
        sid4=session(base);ready,release=threading.Event(),threading.Event();fixture.pause_source=(ready,release)
        future=pool.submit(chat,base,sid4);check('crash_source_entered',ready.wait(10))
        partial=[r for r in store.list(owner)['records'] if r['status']=='UNFINISHED'];check('incremental_before_finish',bool(partial))
        child.kill();child.wait(timeout=10);release.set()
        try: future.result(20)
        except Exception: pass
        fixture.pause_source=None
        replacement,base2=start()
        counts=(len(fixture.models),len(fixture.reads),len(fixture.writes))
        recovered=store.get(owner,partial[0]['requestId'])
        recovered_after_restart=(recovered['status']=='INTERRUPTED' and recovered['durationNanos'] is None
                                 and recovered['recoveredAt'])
        bridge_closed_incomplete=(python and recovered['status']=='INCOMPLETE' and recovered['complete'] is True
                                  and recovered['durationNanos'] is not None and recovered['recoveredAt'] is None)
        check('restart_preserves_incomplete',recovered_after_restart or bridge_closed_incomplete)
        check('historical_read_no_execution',counts==(len(fixture.models),len(fixture.reads),len(fixture.writes)))
        check('different_owner_no_history',store.list('other-owner')['records']==[])
        check('prior_completed_survives_restart',store.get(owner,identity)['status']=='COMPLETED')
        error_sid=session(base2)
        fixture.model_responder=lambda body:(500,{'error':{'message':'synthetic-private-model-error'}})
        check('model_error_remains_error',chat(base2,error_sid)[0]==502)
        fixture.model_responder=None
        errors=[store.get(owner,r['requestId']) for r in store.list(owner)['records'] if r['status']=='ERROR']
        model_failure_observed=(any(s['component']=='model' and s['outcome']=='RETURNED' for r in errors for s in r['spans'])
                                if python else
                                any(s['component']=='model' and s['outcome']=='ERROR' for r in errors for s in r['spans']))
        check('model_exception_recorded_without_body',model_failure_observed
              and 'synthetic-private-model-error' not in json.dumps(errors))
        # Storage failures are isolated by path/config, never by filling the host disk.
        blocker=output/'not-a-directory';blocker.write_text('controlled storage failure')
        for label,settings in (
            ('unavailable',{'FH_HISTORY_DIR':str(blocker/'child')}),
            ('quota',{'FH_HISTORY_DIR':str(output/'quota-history'),'FH_HISTORY_TOTAL_BYTES':'262144'}),
            ('request-quota',{'FH_HISTORY_DIR':str(output/'small-history'),'FH_HISTORY_REQUEST_BYTES':'4096'}),
            ('disabled',{'FH_HISTORY_ENABLED':'false'})):
            process,fault_base=start(settings);fault_sid=session(fault_base)
            fault_code,fault_value=chat(fault_base,fault_sid)
            check(label+'_business_still_completed',fault_code==200)
            check(label+'_observable_degradation',fault_value['executionObservation']['archiveStatus']==('DISABLED' if label=='disabled' else 'DEGRADED'))
            process.terminate();process.wait(timeout=10)
        check('zero_business_writes',fixture.writes==[])
        report={'checks':checks,'mode':'CONTROLLED_HTTP_AND_MODEL','realModel':'NOT_RUN',
                'candidateLockSha256':hashlib.sha256((candidate/'service-lock.json').read_bytes()).hexdigest(),
                'slowRequestId':identity,'interruptedRequestId':partial[0]['requestId'],
                'actualDayRetention':'NOT_RUN','humanOnboarding':'PENDING'}
        (output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
        print(json.dumps({'checks':len(checks),'report':str(output/'report.json')}))
    finally:
        if fixture.pause_source: fixture.pause_source[1].set()
        for child in processes:
            if child.poll() is None:
                child.terminate()
                try:child.wait(timeout=10)
                except subprocess.TimeoutExpired:child.kill();child.wait()
        pool.shutdown(wait=True);fixture.close()
        for log in logs:log.close()
        policy.unlink(missing_ok=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('candidate',type=Path);parser.add_argument('--harness',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args();verify(args.candidate,args.harness,args.output)
