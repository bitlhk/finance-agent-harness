"""Native Tool invocation proves omission is not reformatted to null before the pipe."""
import argparse,asyncio,json,sys,uuid
from pathlib import Path
parser=argparse.ArgumentParser();parser.add_argument('--candidate',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--template',type=Path,help='Optional source-template probe; not generated-artifact acceptance')
args=parser.parse_args();root=args.candidate.resolve();sys.path.insert(0,str(args.template.resolve() if args.template else root))
from finance_python.agent import Conversation,initialize_sdk
plan=json.loads((root/'installation-plan.json').read_text())
class Pipe:
    def __init__(self): self.queries=[]
    def call(self,operation,session=None,parameters=None):
        if operation=='query':
            self.queries.append(parameters)
            return {'execution':{'status':'COMPLETED','data':{'capabilityId':parameters['capabilityId'],
                'traceId':'internal','requestId':'internal','harness':{'receiptId':'internal'}},
                'evidence':[{'private':'not-model-context'}],'checkpoint':{},'taskId':'internal'},'cards':[]}
        return {}
async def run():
    await initialize_sdk()
    from openjiuwen.core.runner import Runner
    pipe=Pipe();c=Conversation(pipe,str(uuid.uuid4()),{'key':'synthetic','url':'http://127.0.0.1:1','name':'not-called'},root,
        {'javaBridgeInstallation':plan,'hostMethods':[]})
    checks=[]
    try:
        await c.configure()
        for tid,item in zip(c.tool_ids,plan['capabilities']):
            tool=Runner.resource_mgr.get_tool(tool_id=tid)
            cashflow='period' in item['definition']['inputSchema'].get('required',[])
            valid={'period':'2026-08'} if cashflow else {}
            result=await tool.invoke(valid)
            assert pipe.queries[-1]=={'capabilityId':item['definition']['capabilityId'],'arguments':valid}
            assert not {'evidence','checkpoint','taskId','cards'}&set(result)
            assert not {'harness','traceId','requestId'}&set(result['data'])
            checks.append({'name':item['definition']['capabilityId']+'-raw-input-preserved','passed':True})
            before=len(pipe.queries)
            bad_values=[{'principalId':'other'},{'accountId':None},{'accountId':123},{'accountId':'../bad'}]
            if cashflow:bad_values += [{},{'period':None},{'period':'2026-13'},{'period':'2026-08','subject':'other'}]
            for bad in bad_values:
                try: await tool.invoke(bad)
                except ValueError: pass
                else: raise AssertionError('invalid native input accepted')
            assert len(pipe.queries)==before
            checks.append({'name':item['definition']['capabilityId']+'-invalid-zero-pipe-query','passed':True})
        ids=list(c.tool_ids);await c.close()
        assert all(Runner.resource_mgr.get_tool(tool_id=tid) is None for tid in ids)
        checks.append({'name':'all-native-tools-unregistered','passed':True})
    finally: await c.close();await Runner.stop()
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(checks,indent=2));print(json.dumps({'checks':len(checks)}))
asyncio.run(run())
