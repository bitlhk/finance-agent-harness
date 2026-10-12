"""Seven actual generated read-only compositions; lightweight per-composition history assertions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from verify_local_service import Endpoints, expiry, request
from finance_solution_builder.execution_history import ExecutionHistory


def verify(harness, output):
    output.mkdir(parents=True, exist_ok=False)
    fixture = Endpoints()
    fixture.cashflow = {k: json.loads((harness / 'conformance/fixtures/cashflow/source' / (k+'.json')).read_text())
                        for k in ('summary','analysis','summary-policy','analysis-policy')}
    owner='compositions-owner'; store=ExecutionHistory(output); token=secrets.token_urlsafe(40)
    policy=output/'policy.json'
    policy.write_text(json.dumps({'schemaVersion':'local-simulator-access.v0.1','bankUrl':fixture.base,
        'subjects':{fixture.customer:{'enabled':True,'expiresAt':expiry(),
        'scopes':['cashflow:read','account:read','wealth:facts:read','wealth:customer:read']}}}))
    policy.chmod(0o600)
    env=dict(os.environ,FH_BANK_URL=fixture.base,FH_BANK_LOGIN='demo',FH_BANK_SECRET='synthetic',
             FH_ACCESS_POLICY=str(policy),FH_HOST_TOKEN=token,FH_MODEL_URL=fixture.base+'/v1',
             FH_MODEL_KEY='controlled-not-secret',FH_MODEL_NAME='composition-model',FH_MODEL_MODE='CONTROLLED_TEST',
             FH_PORT='0',FH_HISTORY_DIR=str(store.directory(owner)),FH_HISTORY_OWNER=hashlib.sha256(owner.encode()).hexdigest())
    cases=[]
    try:
        for unit in ['balance','wealth','cashflow','balance-wealth','balance-cashflow','wealth-cashflow','all']:
            target=output/unit
            subprocess.run([sys.executable,str(Path(__file__).with_name('build_history_candidate.py')),
                '--harness',str(harness),'--output',str(target),'--unit',unit],check=True)
            candidate=target/'candidate';lock=json.loads((candidate/'service-lock.json').read_text())
            assert not any('transfer' in c or 'create' in c for c in lock['capabilities'])
            log=(target/'host.log').open('wb')
            child=subprocess.Popen(['java','-Xmx512m','-cp',str(next((candidate/'target').glob('*.jar')))+os.pathsep+str(candidate/'target/dependency/*'),
                'io.github.bitlhk.finance.builder.local.LocalAgentHost',str(candidate)],cwd=candidate,env=env,stdout=log,stderr=subprocess.STDOUT)
            try:
                deadline=time.monotonic()+30
                while not (candidate/'instance.json').exists():
                    assert child.poll() is None and time.monotonic()<deadline
                    time.sleep(.05)
                instance=json.loads((candidate/'instance.json').read_text());assert instance['pid']==child.pid
                base='http://127.0.0.1:'+str(instance['port'])
                code,value=request(base,token,'/sessions',{});assert code==200,(unit,code,value)
                sid=value['sessionId'];ids=[]
                probes={'account.balance.query':'balance-probe','wealth.facts.describe':'wealth-probe',
                    'finance.transaction-summary.query':'summary-probe 2026-08','finance.spending-analysis.query':'analysis-probe 2026-08'}
                for capability in lock['capabilities']:
                    code,value=request(base,token,f'/sessions/{sid}/chat',{'query':probes[capability]})
                    assert code==200,(unit,capability,code,value)
                    root=value['executionObservation']['requestId'];detail=store.get(owner,root)
                    deadline=time.monotonic()+3
                    while detail['status']=='UNFINISHED' and time.monotonic()<deadline:
                        time.sleep(.02);detail=store.get(owner,root)
                    assert detail['status']=='COMPLETED' and detail['complete'],(unit,detail['gaps'])
                    assert any(s['links'].get('capabilityId')==capability for s in detail['spans'])
                    assert any(s['component']=='provider-http' for s in detail['spans'])
                    ids.append(root)
                before=(len(fixture.reads),len(fixture.models));store.list(owner)
                assert before==(len(fixture.reads),len(fixture.models))
                cases.append({'unit':unit,'capabilities':lock['capabilities'],'requestIds':ids,
                              'serviceLockSha256':hashlib.sha256((candidate/'service-lock.json').read_bytes()).hexdigest()})
                print('PASS '+unit,flush=True)
                (output/'report.json').write_text(json.dumps({'mode':'CONTROLLED','cases':cases,'businessWrites':len(fixture.writes)},indent=2))
            finally:
                if child.poll() is None:
                    child.terminate()
                    try:child.wait(timeout=10)
                    except subprocess.TimeoutExpired:child.kill();child.wait()
                log.close()
        assert not fixture.writes
    finally:
        fixture.close();policy.unlink(missing_ok=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--harness',required=True,type=Path);parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args();verify(args.harness.resolve(),args.output.resolve())
