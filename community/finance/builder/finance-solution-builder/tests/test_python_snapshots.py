import asyncio
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

path=Path(__file__).resolve().parents[1]/'templates/python-readonly-service/finance_python/snapshots.py'
spec=importlib.util.spec_from_file_location('python_snapshots',path)
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_detached_filtered_input_preserves_tool_schema_and_results():
    value={'model':'test','extra_headers':{'Authorization':'Bearer private'},'messages':[
        {'role':'assistant','content':'safe','reasoning_content':'HIDDEN'},
        {'role':'tool','tool_call_id':'c1','content':'amount=10; token=private'}],
        'tools':[{'type':'function','function':{'name':'query','parameters':{'type':'object',
        'properties':{'period':{'type':'string'}},'required':['period']}}}]}
    frozen,state=module.freeze(value,True,['private'])
    assert state=='FILTERED'
    assert frozen['tools']==value['tools']
    assert 'private' not in json.dumps(frozen) and 'HIDDEN' not in json.dumps(frozen)
    value['messages'][0]['content']='mutated'
    assert frozen['messages'][0]['content']=='safe'


def test_response_excludes_hidden_reasoning_and_preserves_tool_call():
    value={'role':'assistant','content':'answer','reasoning_content':'hidden',
        'tool_calls':[{'id':'x','type':'function','function':{'name':'query','arguments':'{}'}}]}
    body,state=module.freeze(value,False,[])
    assert body['tool_calls']==value['tool_calls'] and state=='FILTERED'
    assert 'hidden' not in json.dumps(body)


def test_projection_limits_are_explicit_and_do_not_modify_actual_request():
    for value in ({'messages':[{'role':'user','content':'x'}]*129},
                  {'messages':[{'role':'user','content':'x'*50000}]},
                  {'messages':[{'role':'user','content':'汉'*20000}]}):
        original=json.dumps(value)
        body,state=module.freeze(value,True,[])
        assert state=='TRUNCATED' and len(json.dumps(body).encode())<65536
        assert json.dumps(value)==original


def test_optional_capture_failure_does_not_escape():
    class Bridge:
        description={'snapshotCapture':'ENABLED'}
        def call(self,*args):raise OSError('synthetic private error')
    conversation=SimpleNamespace(bridge=Bridge(),model={'key':'private'},handle='session')
    capture=module.ClientSnapshots(conversation)
    asyncio.run(capture.capture('call','span','CLIENT_REQUEST_PREPARED','CAPTURED',{'messages':[]}))
    assert capture.failed
    asyncio.run(capture.capture('call','span','PARSED_MODEL_RESPONSE','CAPTURED',None))
    assert capture.failed


def test_disabled_capture_does_not_send_body():
    class Bridge:
        description={'snapshotCapture':'DISABLED'}
        def call(self,*args):raise AssertionError('must not call')
    capture=module.ClientSnapshots(SimpleNamespace(bridge=Bridge()))
    asyncio.run(capture.capture('call','span','CLIENT_REQUEST_PREPARED','CAPTURED',{'messages':[]}))
    assert not capture.failed
