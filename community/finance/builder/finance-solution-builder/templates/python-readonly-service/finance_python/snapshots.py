"""Bounded client-boundary projection; no wire interception or hidden reasoning capture."""
import asyncio
import json
import os
import re
import time
import uuid

REQUEST = {'model','messages','tools','temperature','top_p','max_tokens','max_completion_tokens',
           'stream','stop','frequency_penalty','presence_penalty','seed','tool_choice','parallel_tool_calls'}
MESSAGE = {'role','content','tool_calls','tool_call_id','name'}
HIDDEN = {'reasoning_content','reasoning','thinking','analysis','thoughts','reasoning_details',
          'authorization','api_key','apiKey','password','secret','headers','extra_headers'}


def freeze(value, request, secrets):
    """Detach and redact synchronously before yielding or handing data to the bridge."""
    changed=False; nodes=0
    def clean(text):
        nonlocal changed
        old=text
        for secret in secrets:
            if secret:text=text.replace(secret,'[REDACTED]')
        text=re.sub(r'(?i)(bearer\s+)[A-Za-z0-9._~-]+',r'\1[REDACTED]',text)
        text=re.sub(r'(?i)(api[_-]?key|password|secret|token)\s*[:=]\s*[^\s,;]+',r'\1=[REDACTED]',text)
        text=re.sub(r'(?<![0-9])[0-9]{11,19}(?![0-9])','[REDACTED_NUMBER]',text)
        changed |= old!=text
        return text
    def visit(obj,depth=0):
        nonlocal nodes,changed
        nodes+=1
        if depth>16 or nodes>4096:raise OverflowError
        if isinstance(obj,str):
            if len(obj)>48000:raise OverflowError
            return clean(obj)
        if obj is None or isinstance(obj,(bool,int,float)):return obj
        if isinstance(obj,list):
            if len(obj)>128:raise OverflowError
            return [visit(x,depth+1) for x in obj]
        if isinstance(obj,dict):
            if len(obj)>128:raise OverflowError
            result={}
            for key,item in obj.items():
                if key in HIDDEN:changed=True;continue
                result[clean(str(key))]=visit(item,depth+1)
            return result
        changed=True;return '[NOT_CAPTURED]'
    try:
        allowed=REQUEST if request else MESSAGE
        selected={k:v for k,v in value.items() if k in allowed}
        changed |= len(selected)!=len(value)
        if request and isinstance(selected.get('messages'),list):
            messages=[]
            for item in selected['messages']:
                if not isinstance(item,dict):raise OverflowError
                message={k:v for k,v in item.items() if k in MESSAGE}
                changed |= len(message)!=len(item)
                messages.append(message)
            selected['messages']=messages
        result=visit(selected)
        if len(json.dumps(result,ensure_ascii=False,allow_nan=False).encode())>48000:raise OverflowError
        return result,'FILTERED' if changed else 'CAPTURED'
    except (OverflowError,ValueError,TypeError):
        return {'messages':[]} if request else {'content':'[BODY_OMITTED_SIZE_LIMIT]'},'TRUNCATED'


class ClientSnapshots:
    def __init__(self,conversation):
        self.conversation=conversation
        self.enabled=conversation.bridge.description.get('snapshotCapture')=='ENABLED'
        self.failed=False
        self.calls={}

    async def capture(self,call,span,boundary,state,value):
        current=self.conversation
        if not self.enabled or not span:return
        try:
            started=time.perf_counter()
            body,projected=freeze(value,boundary=='CLIENT_REQUEST_PREPARED',
                [current.model['key'],current.handle,os.environ.get('FH_HOST_TOKEN',''),os.environ.get('FH_BANK_SECRET','')])
            await asyncio.to_thread(current.bridge.call,'snapshot',current.handle,
                {'callId':call,'spanId':span,'boundary':boundary,'state':projected if state=='CAPTURED' else state,
                 'value':body,'pythonProjectionNanos':int((time.perf_counter()-started)*1e9)})
        except Exception:
            # No fallback SDK/body log and no reinterpretation of a business outcome.
            self.failed=True

    def attach(self,kwargs):
        if not self.enabled:return kwargs
        from openjiuwen.core.foundation.llm.call_scope import get_current_llm_call_id
        native=get_current_llm_call_id()
        try:call=str(uuid.UUID(native))
        except (ValueError,TypeError):
            self.failed=True
            return kwargs
        span=self.conversation.stage
        self.calls[native]=(call,span)
        # The host owns this single diagnostic collector. Timing callbacks remain separate.
        async def receive(**value):
            if 'llm_params' in value:
                await self.capture(call,span,'CLIENT_REQUEST_PREPARED','CAPTURED',value['llm_params'])
            elif 'llm_response' in value:
                response=value['llm_response']
                if hasattr(response,'model_dump'):
                    response=response.model_dump(mode='json',include=MESSAGE)
                await self.capture(call,span,'PARSED_MODEL_RESPONSE','CAPTURED',response)
        kwargs=dict(kwargs);kwargs['tracer_record_data']=receive
        return kwargs

    async def error(self):
        from openjiuwen.core.foundation.llm.call_scope import get_current_llm_call_id
        pair=self.calls.pop(get_current_llm_call_id(),None)
        if pair:await self.capture(*pair,'PARSED_MODEL_RESPONSE','CLIENT_ERROR',{'content':'[MODEL_CLIENT_FAILED]'})
