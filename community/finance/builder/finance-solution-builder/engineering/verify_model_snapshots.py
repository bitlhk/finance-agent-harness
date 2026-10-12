"""Generated non-streaming Host snapshot acceptance, using synthetic HTTP/model only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
from verify_local_service import Endpoints, request, expiry
from finance_solution_builder.execution_history import ExecutionHistory


def verify(candidate, harness, output, long_context=False):
    candidate = candidate.resolve(); output.mkdir(parents=True, exist_ok=False)
    python = json.loads((candidate/'service-lock.json').read_text())['runtime']['language']=='python'
    fixture = Endpoints()
    fixture.cashflow = {k: json.loads((harness / 'conformance/fixtures/cashflow/source' / (k + '.json')).read_text())
                        for k in ('summary', 'analysis', 'summary-policy', 'analysis-policy')}
    owner = 'snapshot-review-owner'; owner_ref = hashlib.sha256(owner.encode()).hexdigest()
    store = ExecutionHistory(output); token = secrets.token_urlsafe(40)
    policy = output / 'policy.json'
    policy.write_text(json.dumps({'schemaVersion': 'local-simulator-access.v0.1', 'bankUrl': fixture.base,
        'subjects': {fixture.customer: {'enabled': True, 'expiresAt': expiry(),
        'scopes': ['cashflow:read', 'account:read', 'wealth:facts:read', 'wealth:customer:read']}}}))
    policy.chmod(0o600)
    env = dict(os.environ, FH_BANK_URL=fixture.base, FH_BANK_LOGIN='demo', FH_BANK_SECRET='synthetic',
        FH_ACCESS_POLICY=str(policy), FH_HOST_TOKEN=token, FH_MODEL_URL=fixture.base+'/v1',
        FH_MODEL_KEY='snapshot-synthetic-secret', FH_MODEL_NAME='explicit-snapshot-probe', FH_MODEL_MODE='CONTROLLED_TEST',
        FH_PORT='0', FH_HISTORY_DIR=str(store.directory(owner)), FH_HISTORY_OWNER=owner_ref,
        FH_SNAPSHOTS_ENABLED='true', FH_SNAPSHOT_CAPTURE_OWNERS=owner_ref)
    checks = []; children = []; logs = []; snapshots = []
    def check(name, value):
        if not value: raise AssertionError(name)
        checks.append(name)
    def start():
        info = candidate / 'instance.json'; info.unlink(missing_ok=True)
        log = (output / f'host-{len(children)}.log').open('wb'); logs.append(log)
        jar = next((candidate/'target').glob('*.jar'))
        command=([str(candidate/'.venv/bin/python'),'-I',str(candidate/'start_python.py')] if python else
            ['java', '-Xmx512m', '-cp', str(jar)+os.pathsep+str(candidate/'target/dependency/*'),
             'io.github.bitlhk.finance.builder.local.LocalAgentHost', str(candidate)])
        child = subprocess.Popen(command, cwd=candidate, env=env, stdout=log, stderr=subprocess.STDOUT)
        children.append(child); deadline = time.monotonic()+30
        while not info.exists():
            if child.poll() is not None or time.monotonic()>deadline: raise AssertionError('HOST_START_FAILED')
            time.sleep(.05)
        return child, 'http://127.0.0.1:'+str(json.loads(info.read_text())['port'])
    previous = os.environ.pop('FH_SNAPSHOT_VIEW_OWNERS', None)
    try:
        child, base = start()
        for query, tool in (('balance-probe', 'account_balance_query'), ('wealth-probe', 'wealth_facts_describe'),
                            ('summary-probe 2026-08', 'finance_transaction_summary'), ('analysis-probe 2026-08', 'finance_spending_analysis')):
            code, session = request(base, token, '/sessions', {})
            check('session_'+str(len(checks)), code == 200)
            model_start = len(fixture.models)
            code, result = request(base, token, '/sessions/'+session['sessionId']+'/chat', {'query': query})
            check('chat_'+str(len(checks)), code == 200)
            if python:
                capabilities={'account_balance_query':'account.balance.query','wealth_facts_describe':'wealth.facts.describe',
                    'finance_transaction_summary':'finance.transaction-summary.query','finance_spending_analysis':'finance.spending-analysis.query'}
                check('selected_tool_'+str(len(checks)),any(e.get('data',{}).get('capabilityId')==capabilities[tool]
                    for e in result.get('toolResults',[])))
            else:
                check('selected_tool_'+str(len(checks)), any(e.get('tool') == tool for e in result.get('events', [])))
            identity = result['executionObservation']['requestId']
            record = store.get(owner, identity)
            check('snapshot_refs_'+str(len(checks)), len(record['snapshots']) >= 2)
            check('no_body_in_trace_'+str(len(checks)), 'snapshot-synthetic-secret' not in json.dumps(record))
            ref = record['snapshots'][0]
            try: store.snapshot(owner, identity, ref['callId'], ref['part'])
            except PermissionError: check('separate_view_grant_'+str(len(checks)), True)
            else: raise AssertionError('VIEW_GRANTED_BY_TRACE')
            os.environ['FH_SNAPSHOT_VIEW_OWNERS'] = owner_ref
            request_bodies=[]
            for ref in record['snapshots']:
                deadline = time.monotonic()+5
                while True:
                    body = store.snapshot(owner, identity, ref['callId'], ref['part'])
                    if body.get('body') is not None or time.monotonic()>deadline: break
                    time.sleep(.05)
                check('body_persisted_'+str(len(checks)), body.get('body') is not None)
                check('boundary_'+str(len(checks)), body['boundary'] in {'CLIENT_REQUEST_PREPARED', 'PARSED_MODEL_RESPONSE'} and body['dispatch']=='NOT_OBSERVED')
                check('redacted_'+str(len(checks)), all(x not in json.dumps(body) for x in
                    ('snapshot-synthetic-secret', fixture.customer, session['sessionId'])))
                if body['boundary']=='CLIENT_REQUEST_PREPARED': request_bodies.append(json.loads(body['body']))
                snapshots.append((identity, ref['callId'], ref['part']))
            sent=fixture.models[model_start:]
            check('all_prepared_requests_correlated_'+str(len(checks)),len(sent)==len(request_bodies))
            for received, captured in zip(sent,request_bodies):
                check('actual_tool_schemas_preserved_'+str(len(checks)),received['tools']==captured['tools'])
                for definition in captured['tools']:
                    schema=definition['function']['parameters']
                    check('required_fields_exist_'+str(len(checks)),set(schema.get('required',[]))<=set(schema.get('properties',{})))
            os.environ.pop('FH_SNAPSHOT_VIEW_OWNERS')
        truncated_request=None
        if long_context:
            code,session=request(base,token,'/sessions',{});check('long_session',code==200)
            os.environ['FH_SNAPSHOT_VIEW_OWNERS']=owner_ref
            for index in range(12):
                code,result=request(base,token,'/sessions/'+session['sessionId']+'/chat',
                    {'query':'summary-probe 2026-08 '+('合成数据'*400)})
                check('long_context_turn_'+str(index),code==200)
                identity=result['executionObservation']['requestId'];record=store.get(owner,identity)
                for ref in record['snapshots']:
                    deadline=time.monotonic()+5
                    while True:
                        body=store.snapshot(owner,identity,ref['callId'],ref['part'])
                        if body.get('body') is not None or time.monotonic()>deadline:break
                        time.sleep(.05)
                    if body.get('state')=='TRUNCATED':
                        check('native_long_context_reports_projection_limit',any(c['reason']==
                            ('PYTHON_PROJECTION_LIMIT' if python else 'BYTE_LIMIT') for c in body['changes']))
                        truncated_request=identity
                if truncated_request:break
            check('actual_prepared_request_truncation_observed',truncated_request is not None)
        child.terminate(); child.wait(timeout=15)
        _, _ = start()
        os.environ['FH_SNAPSHOT_VIEW_OWNERS'] = owner_ref
        counts = (len(fixture.models), len(fixture.reads), len(fixture.writes))
        for identity, call, part in snapshots: check('restart_read_'+str(len(checks)), store.snapshot(owner, identity, call, part).get('body') is not None)
        check('history_zero_http', counts == (len(fixture.models), len(fixture.reads), len(fixture.writes)))
        check('zero_business_writes', not fixture.writes)
        report = dict(mode='CONTROLLED_HTTP_AND_MODEL', realModel='NOT_RUN', checks=checks,
            candidateLockSha256=hashlib.sha256((candidate/'service-lock.json').read_bytes()).hexdigest(),
            snapshots=len(snapshots), bytes=sum(p.stat().st_size for p in store.directory(owner).rglob('*') if p.is_file()),
            truncationRequestId=truncated_request,
            browser='NOT_RUN', overhead='NOT_MEASURED')
        (output/'report.json').write_text(json.dumps(report, indent=2))
        print(json.dumps(report))
    finally:
        if previous is None: os.environ.pop('FH_SNAPSHOT_VIEW_OWNERS', None)
        else: os.environ['FH_SNAPSHOT_VIEW_OWNERS'] = previous
        for child in children:
            if child.poll() is None: child.terminate()
            try: child.wait(timeout=15)
            except subprocess.TimeoutExpired: child.kill(); child.wait()
        for log in logs: log.close()
        fixture.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('candidate', type=Path)
    parser.add_argument('--harness', type=Path, required=True); parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--long-context',action='store_true')
    args = parser.parse_args(); verify(args.candidate, args.harness, args.output,args.long_context)
