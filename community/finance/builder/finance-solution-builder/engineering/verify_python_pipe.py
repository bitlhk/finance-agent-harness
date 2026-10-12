import argparse
import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import uuid

parser=argparse.ArgumentParser();parser.add_argument('--candidate',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args();candidate=args.candidate.resolve();output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
sys.path.insert(0,str(Path(__file__).resolve().parent))
sys.path.insert(0,str(candidate))
from verify_local_service import Endpoints,expiry
from finance_python.bridge import Bridge,BridgeError,AuthorizationRejected
from finance_python.agent import Conversation,initialize_sdk

fixture=Endpoints();policy=output/'policy.json'
def authorize(scopes=None):
    policy.write_text(json.dumps({'schemaVersion':'local-simulator-access.v0.1','bankUrl':fixture.base,
        'subjects':{fixture.customer:{'enabled':True,'expiresAt':expiry(),'scopes':scopes or ['account:read']}}}))
authorize()
os.environ.update(FH_BANK_URL=fixture.base,FH_BANK_LOGIN='test',FH_BANK_SECRET='test',FH_ACCESS_POLICY=str(policy),
                  FH_HISTORY_DIR=str(output/'history'),FH_HISTORY_OWNER='controlled-owner')
checks=[]
def check(name,value):
    checks.append({'name':name,'passed':bool(value)});(output/'checks.json').write_text(json.dumps(checks,indent=2));assert value,name

async def native():
    await initialize_sdk()
    from openjiuwen.core.runner import Runner
    from openjiuwen.core.single_agent.rail.base import AgentCallbackEvent as E
    bridge=Bridge(candidate)
    try:
        opened=await asyncio.to_thread(bridge.call,'open')
        conversation=Conversation(bridge,opened['session'],{'key':'synthetic-key','url':fixture.base+'/v1','name':'controlled','ref':'controlled-pipe','version':'1'},candidate,opened)
        await conversation.configure()
        async def finish(context): context.request_force_finish({'output':'CONTROLLED_FORCE_FINISH'})
        await conversation.agent.register_callback(E.BEFORE_MODEL_CALL,finish)
        before=len(fixture.models)
        result=await conversation.chat('force finish without dispatch')
        check('native-force-finish-zero-model-http',len(fixture.models)==before)
        installed=list(conversation.tool_ids)
        await conversation.close()
        check('native-tool-unregistered',bool(installed) and all(Runner.resource_mgr.get_tool(tool_id=tid) is None for tid in installed))
        opened=await asyncio.to_thread(bridge.call,'open')
        before=len(fixture.reads)
        try: await asyncio.to_thread(bridge.call,'query',opened['session'],{'principalId':'other'})
        except BridgeError: pass
        else: raise AssertionError('identity accepted')
        check('pipe-model-identity-fields-rejected',len(fixture.reads)==before)
        for parameters in (
            {'capabilityId':'wealth.facts.describe','arguments':{}},
            {'capabilityId':'transfer.internal.create','arguments':{}},
            {'capabilityId':'unknown.query','arguments':{}},
            {'capabilityId':'account.balance.query','arguments':{'accountId':None}},
            {'capabilityId':'account.balance.query','arguments':{'accountId':123}},
            {'capabilityId':'account.balance.query','arguments':{'principalId':'other'}}):
            try: bridge.call('query',opened['session'],parameters)
            except BridgeError: pass
            else: raise AssertionError('invalid or uninstalled capability accepted')
        check('uninstalled-task-malformed-zero-bank-reads',len(fixture.reads)==before)
        bridge.child.kill();bridge.child.wait(5)
        try: bridge.call('authorize',opened['session'])
        except BridgeError: pass
        else: raise AssertionError('dead bridge reused')
        check('bridge-death-no-restart',bridge.child.poll() is not None)
    finally:
        bridge.close();await Runner.stop()

def timeout_probe():
    bridge=Bridge(candidate)
    try:
        import signal
        bridge.timeout=1
        os.kill(bridge.child.pid, signal.SIGSTOP)
        try: bridge.call('health')
        except BridgeError: pass
        else: raise AssertionError('hung bridge accepted')
        check('hung-child-timeout-reaped-no-restart',bridge.failed and bridge.child.poll() is not None)
    finally: bridge.close()

def frames():
    jar=next((candidate/'target').glob('*.jar'))
    command=['java','-Xmx256m','-cp',str(jar)+os.pathsep+str(candidate/'target/dependency/*'),
             'io.github.bitlhk.finance.builder.local.PythonFinanceBridge',str(candidate)]
    def child():
        generation=str(uuid.uuid4())
        return generation,subprocess.Popen(command,env=dict(os.environ,FH_BRIDGE_GENERATION=generation),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    generation,process=child()
    try:
        request=dict(schemaVersion='finance-local-pipe.v0.1',generation='wrong-generation',requestId=str(uuid.uuid4()),operation='open',session=None,parameters={})
        process.stdin.write((json.dumps(request)+'\n').encode());process.stdin.flush()
        response=json.loads(process.stdout.readline())
        check('stale-generation-rejected',response['ok'] is False)
    finally: process.stdin.close();process.wait(15)
    for name,payload in [('half-frame',b'{"schemaVersion":'),('malformed-json',b'not-json\n'),('oversized-frame',b'a'*65538+b'\n')]:
        _,process=child()
        data,_=process.communicate(payload,timeout=20)
        check(name+'-closed-without-response',not data and process.poll() is not None)

try:
    asyncio.run(native());frames();timeout_probe();check('zero-bank-business-writes',not fixture.writes)
finally: fixture.close()
print(json.dumps({'checks':len(checks),'passed':all(c['passed'] for c in checks)}))
