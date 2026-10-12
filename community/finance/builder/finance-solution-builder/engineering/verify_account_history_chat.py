"""Isolated real Host/native Runtime, controlled model and synthetic bank HTTP."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from urllib.parse import urlsplit, parse_qs
from verify_local_service import Endpoints, expiry, request

candidate = Path(sys.argv[1]).resolve()
fixture = Endpoints()
original = fixture.server.RequestHandlerClass
class HistoryHandler(original):
    def do_GET(self):
        parsed = urlsplit(self.path)
        if parsed.path not in ('/api/v1/accounts/synthetic-1/statement', '/api/v1/accounts/synthetic-1/transaction-page'):
            return super().do_GET()
        customer = fixture.sessions.get(self.headers.get('X-Session-Id'))
        if not customer: return self.reply(401, {'status':'FAILED'})
        fixture.reads.append((self.path, customer))
        query = parse_qs(parsed.query)
        if parsed.path.endswith('/statement'):
            data = dict(accountId='synthetic-1',period=query['period'][0],currency='CNY',openingBalance='100.00',totalIn='20.30',totalOut='5.00',closingBalance='115.30')
        else:
            data = dict(items=[dict(accountId='synthetic-1',transactionId='syn-txn-1',bookedAt='2026-08-05T00:00:00Z',amount='20.30',currency='CNY',direction='IN',summary='Synthetic record',status='BOOKED')],hasMore=True,nextCursor='bmV4dA')
        return self.reply(200, dict(status='COMPLETED',data=data,sourceSystem='account-core-simulator',asOf='2026-09-01T00:00:00Z'))
fixture.server.RequestHandlerClass = HistoryHandler
def respond(body):
    if body['messages'][-1]['role'] == 'tool':
        message, reason = {'role':'assistant','content':'CONTROLLED_TEST_COMPLETION'}, 'stop'
    else:
        query = next(str(m.get('content','')) for m in reversed(body['messages']) if m['role']=='user')
        statement = 'statement-probe' in query
        arguments = dict(accountId='synthetic-1',period='2026-08') if statement else dict(accountId='synthetic-1',dateFrom='2026-08-01',dateTo='2026-08-31')
        if 'invalid-probe' in query: arguments.pop('accountId')
        message = dict(role='assistant',content=None,tool_calls=[dict(id='call-controlled',type='function',function=dict(name='account_statement_query' if statement else 'account_transaction_query',arguments=json.dumps(arguments)))])
        reason = 'tool_calls'
    return 200, dict(id='controlled',object='chat.completion',model='explicit-boundary-test',choices=[dict(index=0,message=message,finish_reason=reason)],usage=dict(prompt_tokens=1,completion_tokens=1,total_tokens=2))
fixture.model_responder = respond
policy = candidate / 'history-test-policy.json'
policy.write_text(json.dumps(dict(schemaVersion='local-simulator-access.v0.1',bankUrl=fixture.base,subjects={fixture.customer:dict(enabled=True,expiresAt=expiry(),scopes=['account:read'])})))
policy.chmod(0o600)
token=secrets.token_urlsafe(40)
env=dict(os.environ,FH_BANK_URL=fixture.base,FH_BANK_LOGIN='demo',FH_BANK_SECRET='synthetic',FH_ACCESS_POLICY=str(policy),FH_HOST_TOKEN=token,FH_MODEL_URL=fixture.base+'/v1',FH_MODEL_KEY='fixture',FH_MODEL_NAME='explicit-boundary-test',FH_MODEL_MODE='CONTROLLED_TEST',FH_PORT='0')
info=candidate/'instance.json';info.unlink(missing_ok=True)
jar=next((candidate/'target').glob('*.jar'))
evidence=dict(model='CONTROLLED_TEST',bank='SYNTHETIC_HTTP',checks=[],realModel='NOT_RUN',turns=[])
child=None
try:
    with (candidate/'history-test-host.log').open('wb') as log:
        child=subprocess.Popen(['java','-Xmx512m','-cp',str(jar)+os.pathsep+str(candidate/'target/dependency/*'),'io.github.bitlhk.finance.builder.local.LocalAgentHost',str(candidate)],cwd=candidate,env=env,stdout=log,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+30
        while not info.exists():
            if child.poll() is not None or time.monotonic()>deadline: raise AssertionError('HOST_START_FAILED')
            time.sleep(.1)
        base='http://127.0.0.1:'+str(json.loads(info.read_text())['port'])
        code,opened=request(base,token,'/sessions',{})
        assert code==200, (code,opened)
        sid=opened['sessionId']
        for question,capability in [('statement-probe','account.statement.query'),('transaction-probe','account.transaction.query')]:
            before=len(fixture.reads)
            code,reply=request(base,token,f'/sessions/{sid}/chat',dict(query=question))
            assert code==200 and reply.get('toolResults'), (code,reply)
            result=next(r for r in reply['toolResults'] if r['data'].get('capabilityId')==capability)
            assert result['data']['resultStatus']=='COMPLETED', result
            assert len(fixture.reads)==before+1
            cards=reply['display']['cards'];assert len(cards)==1 and cards[0]['type']=='records',cards
            assert 'synthetic-1' not in json.dumps(cards)
            if capability == 'account.transaction.query': assert cards[0]['context']['hasMore'] is True
            evidence['turns'].append(dict(capabilityId=capability,toolResults=reply['toolResults'],display=reply['display']))
            evidence['checks'].append(capability+' native tool, source route, exact facts and pack display')
        before=len(fixture.reads)
        request(base,token,f'/sessions/{sid}/chat',dict(query='statement-probe invalid-probe'))
        assert len(fixture.reads)==before
        assert not fixture.writes
        evidence['checks'] += ['missing account causes zero bank reads','no business writes','no automatic pagination']
        evidence['status']='PASS'
finally:
    if child is not None:
        child.terminate()
        try: child.wait(timeout=10)
        except subprocess.TimeoutExpired: child.kill();child.wait()
    fixture.close()
    (candidate.parent/'history-chat-evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(dict(status=evidence.get('status','FAIL'),checks=len(evidence['checks']))))
