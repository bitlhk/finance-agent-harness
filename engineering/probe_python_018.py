"""Isolated SDK boundary probe, not a Python Finance Harness integration.

Run in a venv containing openjiuwen==0.1.18. All HTTP is loopback and synthetic.
Only allowlisted callback fields are captured; no authorization headers/reasoning.
"""
import argparse
import asyncio
import hashlib
import importlib.metadata as metadata
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import platform
import threading


def plain(value):
    if hasattr(value, 'model_dump'):
        return plain(value.model_dump())
    if isinstance(value, dict):
        return {k:plain(v) for k,v in value.items()
                if k not in {'reasoning_content', 'api_key', 'model_client_config'}}
    if isinstance(value, (list, tuple)):
        return [plain(v) for v in value]
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return type(value).__name__


async def probe(output):
    assert metadata.version('openjiuwen') == '0.1.18'
    from openjiuwen.core.foundation.llm import Model, ModelClientConfig, ModelRequestConfig
    from openjiuwen.core.foundation.llm.call_scope import get_current_llm_call_id
    from openjiuwen.core.runner import Runner
    from openjiuwen.core.runner.callback.events import LLMCallEvents as E

    output.mkdir(parents=True, exist_ok=False)
    events, received, checks = [], [], []
    mode = {'value':'normal'}
    authorized = {'value':True}
    def check(name, valid):
        assert valid, name
        checks.append(name)

    class Endpoint(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_POST(self):
            request = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            received.append({'path':self.path, 'body':request})
            if mode['value'] == 'failure':
                self.send_response(500); self.send_header('Content-Type','application/json')
                self.end_headers(); self.wfile.write(b'{"error":{"message":"synthetic failure"}}')
                return
            self.send_response(200)
            if request.get('stream'):
                self.send_header('Content-Type','text/event-stream'); self.end_headers()
                for content in ('synthetic ', 'stream'):
                    chunk={'id':'stream-probe','object':'chat.completion.chunk','created':1,
                           'model':'synthetic-model','choices':[{'index':0,'delta':{'content':content},'finish_reason':None}]}
                    self.wfile.write(('data: '+json.dumps(chunk)+'\n\n').encode()); self.wfile.flush()
                self.wfile.write(b'data: [DONE]\n\n'); self.wfile.flush()
                return
            first = not any(m.get('role') == 'tool' for m in request['messages'])
            message = {'role':'assistant','content':'synthetic 12.34' if not first else None}
            if first:
                message['tool_calls']=[{'id':'tool-1','type':'function','function':{'name':'synthetic_balance','arguments':'{}'}}]
            response={'id':'invoke-probe','object':'chat.completion','created':1,'model':'synthetic-model',
                      'choices':[{'index':0,'message':message,'finish_reason':'tool_calls' if first else 'stop'}],
                      'usage':{'prompt_tokens':5,'completion_tokens':3,'total_tokens':8}}
            data=json.dumps(response).encode()
            self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(data)))
            self.end_headers(); self.wfile.write(data)

    server=ThreadingHTTPServer(('127.0.0.1',0),Endpoint)
    thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
    observed=('LLM_INVOKE_INPUT','LLM_INPUT','LLM_OUTPUT','LLM_INVOKE_OUTPUT',
              'LLM_STREAM_INPUT','LLM_STREAM_OUTPUT','LLM_STREAM_COMPLETED',
              'LLM_RESPONSE_RECEIVED','LLM_CALL_ERROR')
    for name in observed:
        def listener(name):
            async def capture(*args, **kwargs):
                allowed=('messages','tools','temperature','max_tokens','response','result','tool_calls','is_stream')
                data={k:plain(kwargs[k]) for k in allowed if k in kwargs}
                if 'error' in kwargs:
                    data['errorType']=type(kwargs['error']).__name__
                events.append({'event':name,'callId':get_current_llm_call_id(),'data':data})
            return capture
        Runner.callback_framework.on(getattr(E,name))(listener(name))

    @Runner.callback_framework.on_transform(E.LLM_INVOKE_INPUT, priority=100)
    async def grant_check(*args, session=None, **kwargs):
        if not authorized['value']:
            raise PermissionError('SYNTHETIC_GRANT_REVOKED')
        return args, kwargs

    model=Model(ModelClientConfig(client_provider='OpenAI',api_base=f'http://127.0.0.1:{server.server_port}/v1',
                                  api_key='synthetic-not-a-secret',max_retries=0,timeout=5,
                                  use_shared_llm_http_client=False),
                ModelRequestConfig(model='synthetic-model',temperature=0,max_tokens=80))
    tools=[{'type':'function','function':{'name':'synthetic_balance','description':'Synthetic only',
                                        'parameters':{'type':'object','properties':{},'additionalProperties':False}}}]
    messages=[{'role':'user','content':'synthetic balance probe'}]
    try:
        first=await model.invoke(messages,tools=tools)
        check('invoke_tool_call_parsed',bool(first.tool_calls) and first.tool_calls[0].name=='synthetic_balance')
        messages += [{'role':'assistant','content':None,'tool_calls':[{'id':'tool-1','type':'function',
                        'function':{'name':'synthetic_balance','arguments':'{}'}}]},
                     {'role':'tool','tool_call_id':'tool-1','content':'{"amount":"12.34","currency":"CNY"}'}]
        second=await model.invoke(messages,tools=tools)
        check('model_tool_model_manual_loop',second.content=='synthetic 12.34' and len(received)==2)
        inputs=[e for e in events if e['event']=='LLM_INPUT']
        check('prepared_messages_match_endpoint',inputs[-1]['data']['messages']==received[-1]['body']['messages'])
        check('prepared_tools_match_endpoint',inputs[-1]['data']['tools']==received[-1]['body']['tools'])
        check('second_call_has_tool_fact','12.34' in json.dumps(inputs[-1]))
        check('invoke_call_ids_distinct',len({e['callId'] for e in inputs})==2 and all(e['callId'] for e in inputs))
        chunks=[plain(chunk) async for chunk in model.stream([{'role':'user','content':'stream probe'}])]
        check('stream_chunks_received',len(chunks)>=2 and 'synthetic ' in json.dumps(chunks))
        check('stream_completion_observed',any(e['event']=='LLM_STREAM_COMPLETED' for e in events))
        streamids={e['callId'] for e in events if e['event'] in ('LLM_STREAM_INPUT','LLM_STREAM_OUTPUT','LLM_STREAM_COMPLETED')}
        check('stream_call_id_consistent',len(streamids)==1 and all(streamids))
        count=len(received); authorized['value']=False
        try:
            await model.invoke(messages,tools=tools)
        except PermissionError:
            check('transform_authorization_blocks_http',len(received)==count)
        else:
            raise AssertionError('authorization hook did not block')
        authorized['value']=True; mode['value']='failure'; count=len(received)
        try:
            await model.invoke(messages,tools=tools)
        except Exception as error:
            check('http_failure_reported',type(error).__name__!='AssertionError')
        else:
            raise AssertionError('HTTP failure disappeared')
        check('max_retries_zero_one_http_attempt',len(received)==count+1)
        check('error_callback_observed',any(e['event']=='LLM_CALL_ERROR' for e in events))
        count=len(received)
        completed=sum(e['event']=='LLM_STREAM_COMPLETED' for e in events)
        errors=sum(e['event']=='LLM_CALL_ERROR' for e in events)
        try:
            async for _ in model.stream([{'role':'user','content':'stream failure probe'}]):
                pass
        except Exception:
            check('stream_failure_one_http_attempt',len(received)==count+1)
        else:
            raise AssertionError('stream failure disappeared')
        check('stream_failure_error_not_completion',
              sum(e['event']=='LLM_CALL_ERROR' for e in events)>errors and
              sum(e['event']=='LLM_STREAM_COMPLETED' for e in events)==completed)
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=3)
    dist=metadata.distribution('openjiuwen')
    files={str(f):hashlib.sha256(Path(dist.locate_file(f)).read_bytes()).hexdigest()
           for f in dist.files or [] if str(f).endswith('.py') and Path(dist.locate_file(f)).is_file()}
    dependencies=sorted({d.metadata['Name']:d.version for d in metadata.distributions()}.items())
    (output/'dependencies.json').write_text(json.dumps(dict(dependencies),indent=2))
    (output/'sdk-source-hashes.json').write_text(json.dumps(files,indent=2))
    (output/'events.json').write_text(json.dumps(events,indent=2))
    (output/'endpoint-requests.json').write_text(json.dumps(received,indent=2))
    report={'sdk':'openjiuwen==0.1.18','python':platform.python_version(),'checks':checks,
            'httpRequests':len(received),'events':len(events),'sdkSourceFiles':len(files),
            'boundary':'Public Model invoke/stream + callbacks, manual synthetic tool loop; not Agent/Builder integration',
            'limits':['Callback input matches tested fields, not a full wire snapshot',
                      'Error callback records exception type, not raw HTTP response',
                      'Authorization transform is tested for invoke only, not a financial host authorization adapter',
                      'No real model, no bank data, no Python Finance Core or dashboard implemented']}
    (output/'report.json').write_text(json.dumps(report,indent=2)); print(json.dumps(report))


if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--output',type=Path,required=True)
    asyncio.run(probe(parser.parse_args().output))
