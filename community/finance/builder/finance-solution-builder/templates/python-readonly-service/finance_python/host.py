"""Loopback application host. Financial output is buffered, not token streamed."""
import asyncio
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import signal
import sys
import uuid
from datetime import datetime, timezone

from aiohttp import web
from .agent import Conversation, initialize_sdk
from .bridge import AuthorizationRejected, Bridge, BridgeError
from .settings import model_connection


class Host:
    def __init__(self, root):
        self.root = root.resolve()
        self.manifest = json.loads((root / 'service-lock.json').read_text())
        expected = os.environ.get('FH_EXPECTED_LOCK')
        if expected and hashlib.sha256((root / 'service-lock.json').read_bytes()).hexdigest() != expected:
            raise ValueError('BUILD_LOCK_MISMATCH')
        self.token = os.environ['FH_HOST_TOKEN']
        if len(self.token) < 32:
            raise ValueError('HOST_TOKEN_INVALID')
        self.model = model_connection(root, self.manifest)
        self.bridge = Bridge(root)
        self.instance = self.bridge.generation
        self.sessions = {}
        self.locks = {}
        self.installations = {}
        self.runs = {}

    async def close_session(self, sid):
        conversation = self.sessions.pop(sid, None)
        self.installations.pop(sid, None)
        self.locks.pop(sid, None)
        if conversation:
            await conversation.close()

    async def handle(self, request):
        if not hmac.compare_digest(request.headers.get('Authorization', ''), 'Bearer ' + self.token):
            return web.json_response({'error': 'HOST_AUTH_REQUIRED'}, status=401)
        if request.headers.get('Origin'):
            return web.json_response({'error': 'DIRECT_BROWSER_ACCESS_DENIED'}, status=403)
        sid = None
        try:
            if request.method == 'GET' and request.path == '/health':
                if self.bridge.child.poll() is not None:
                    raise BridgeError('BRIDGE_UNAVAILABLE')
                return web.json_response(dict(status='RUNNING', buildId=self.manifest['buildId'],
                    instanceId=self.instance, selectedCapabilities=self.manifest['capabilities'],
                    runtime='openjiuwen-python-0.1.18', artifactLock=self.manifest,
                    mode='EXPLICIT_TEST_MODEL' if os.environ.get('FH_MODEL_MODE') == 'CONTROLLED_TEST'
                    else 'CONFIGURED_MODEL_LOCAL_SIMULATOR', activeSessions=len(self.sessions)))
            if request.method != 'POST':
                return web.json_response({'error': 'ROUTE_NOT_FOUND'}, status=404)
            body = await request.json()
            if not isinstance(body, dict):
                raise ValueError('INVALID_INPUT')
            if request.path == '/sessions':
                if set(body) - {'modelRef', 'previousSessionId'} or any(not isinstance(v, str) for v in body.values()):
                    raise ValueError('HOST_OWNS_IDENTITY')
                # No implicit session-history transfer across model or identity changes.
                previous = body.get('previousSessionId')
                if previous:
                    if previous not in self.sessions:
                        raise AuthorizationRejected('SESSION_UNAVAILABLE')
                    await self.sessions[previous].authorize()
                    await self.close_session(previous)
                model = model_connection(self.root, self.manifest, body.get('modelRef'))
                opened = await asyncio.to_thread(self.bridge.call, 'open')
                sid = opened['session']
                self.sessions[sid] = Conversation(self.bridge, sid, model, self.root, opened)
                self.locks[sid] = asyncio.Lock()
                await self.sessions[sid].configure()
                installation = dict(javaBridge=opened['javaBridgeInstallation'],
                                    pythonTools=list(self.sessions[sid].registered),
                                    registrationStatus='RUNTIME_REGISTERED',
                                    hostMethods=opened['hostMethods'],
                                    runtime='openjiuwen-python-0.1.18')
                self.installations[sid] = installation
                evidence = self.root / 'runtime-evidence' / sid
                evidence.mkdir(parents=True)
                (evidence / 'runtime-lock.json').write_text(json.dumps(installation))
                return web.json_response(dict(sessionId=sid, buildId=self.manifest['buildId'],
                    instanceId=self.instance, installations=installation, authorization='LOCAL_SIMULATOR_POLICY',
                    modelRef=model['ref'], modelConfigVersion=model['version']))
            match = re.fullmatch(r'/sessions/([a-f0-9-]{36})/(chat|ag-ui|end|revoke|status|snapshot|activity|cancel)', request.path)
            if not match:
                return web.json_response({'error': 'ROUTE_NOT_FOUND'}, status=404)
            sid, action = match.groups()
            conversation = self.sessions.get(sid)
            if not conversation:
                raise AuthorizationRejected('SESSION_UNAVAILABLE')
            if action in ('end', 'revoke'):
                if body:
                    raise ValueError('INVALID_INPUT')
                # Invalidate before awaiting a busy pipe. In-flight tool results are not released.
                conversation.closed = True
                await self.close_session(sid)
                return web.json_response(dict(status='ENDED' if action == 'end' else 'REVOKED', clearConversation=True))
            if action == 'cancel':
                if set(body) != {'runId'}:
                    raise ValueError('INVALID_INPUT')
                conversation.closed = True
                await self.close_session(sid)
                return web.json_response(dict(status='CANCEL_REQUESTED', clearConversation=True))
            await conversation.authorize()
            if action in ('status', 'snapshot', 'activity'):
                if body:
                    raise ValueError('INVALID_INPUT')
                return web.json_response(dict(status='RUNNING' if sid in self.runs else 'IDLE',
                    messages=conversation.messages, clearConversation=False, sessionId=sid,
                    lifecycle=dict(status='RUNNING' if sid in self.runs else 'IDLE')))
            if action == 'ag-ui':
                self.validate_agui(body, sid)
                query, run = body['messages'][0]['content'], body['runId']
            else:
                if set(body) - {'query', 'runId'}:
                    raise ValueError('INVALID_INPUT')
                query, run = body.get('query'), body.get('runId', uuid.uuid4().hex)
            if not isinstance(query, str) or not query.strip() or len(query) > 4000 or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', run):
                raise ValueError('INVALID_CHAT_INPUT')
            lock = self.locks[sid]
            if lock.locked():
                return web.json_response({'error': 'SESSION_BUSY'}, status=409)
            async with lock:
                self.runs[sid] = run
                try:
                    conversation.model = model_connection(self.root, self.manifest, conversation.model['ref'])
                    try:
                        result = await asyncio.wait_for(conversation.chat(query, run), timeout=110)
                    except Exception:
                        result = await conversation.partial_after_model_failure(query, run)
                        if result is None:
                            raise
                    await conversation.authorize()
                    result.update(response=result['answer'], buildId=self.manifest['buildId'],
                                  instanceId=self.instance, sessionId=sid, runId=run)
                    message = 'message-' + uuid.uuid4().hex
                    display = dict(schemaVersion='finance-display.v0.1', runId=run, messageId=message,
                        cards=[dict(card, cardId=message+'-'+str(index)) for index, card in enumerate(result.pop('cards'))],
                        at=datetime.now(timezone.utc).isoformat(), modelName=conversation.model['name'])
                    result['display'] = display
                    conversation.messages.extend([dict(role='user', text=query, runId=run),
                        dict(role='assistant', text=result['answer'], display=display)])
                    if action == 'ag-ui':
                        return self.agui_result(sid, run, result)
                    return web.json_response(result)
                finally:
                    self.runs.pop(sid, None)
        except AuthorizationRejected:
            if sid: await self.close_session(sid)
            return web.json_response(dict(error='AUTHORIZATION_REJECTED', clearConversation=True), status=403)
        except (ValueError, KeyError, TypeError):
            return web.json_response(dict(error='CONFIGURATION_OR_INPUT_REJECTED'), status=400)
        except Exception:
            if sid: await self.close_session(sid)
            return web.json_response(dict(error='MODEL_OR_RUNTIME_FAILED', clearConversation=True), status=502)

    def validate_agui(self, body, sid):
        if (set(body) != {'threadId', 'runId', 'messages', 'tools', 'context', 'state', 'forwardedProps'}
                or body['threadId'] != sid or body['tools'] != [] or body['context'] != []
                or body['state'] not in (None, {}) or body['forwardedProps'] != {'instanceId': self.instance}
                or not isinstance(body['messages'], list) or len(body['messages']) != 1):
            raise ValueError('AG_UI_INPUT_REJECTED')
        message = body['messages'][0]
        if (not isinstance(message, dict) or set(message) != {'role', 'id', 'content'}
                or message['role'] != 'user' or not isinstance(message['id'], str)
                or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', message['id'])):
            raise ValueError('AG_UI_INPUT_REJECTED')

    def agui_result(self, sid, run, result):
        message = 'message-' + uuid.uuid4().hex
        events = [dict(type='RUN_STARTED', threadId=sid, runId=run),
            dict(type='TEXT_MESSAGE_START', messageId=message, role='assistant'),
            dict(type='TEXT_MESSAGE_CONTENT', messageId=message, delta=result['answer']),
            dict(type='TEXT_MESSAGE_END', messageId=message),
            dict(type='CUSTOM', name='finance.evidence', value=dict(buildId=self.manifest['buildId'],
                instanceId=self.instance, sessionId=sid, runId=run, result=result, clearConversation=False)),
            dict(type='RUN_FINISHED', threadId=sid, runId=run)]
        return web.Response(text=''.join('data: ' + json.dumps(e, ensure_ascii=False) + '\n\n' for e in events),
                            content_type='text/event-stream', headers={'Cache-Control': 'no-store'})


async def serve(root):
    await initialize_sdk()
    host = Host(root)
    app = web.Application(client_max_size=32768)
    app.router.add_route('*', '/{path:.*}', host.handle)
    runner = web.AppRunner(app, access_log=None, shutdown_timeout=5)
    await runner.setup()
    site = web.TCPSite(runner, '127.0.0.1', int(os.environ.get('FH_PORT', '0')))
    stopped = asyncio.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        asyncio.get_running_loop().add_signal_handler(sig, stopped.set)
    try:
        await site.start()
        port = runner.addresses[0][1]
        instance = dict(port=port, pid=os.getpid(), buildId=host.manifest['buildId'],
                        instanceId=host.instance, runtime='openjiuwen-python-0.1.18', bridgePid=host.bridge.child.pid)
        (root / 'instance.json.tmp').write_text(json.dumps(instance))
        (root / 'instance.json.tmp').replace(root / 'instance.json')
        await stopped.wait()
    finally:
        await runner.cleanup()
        host.bridge.close()
        from openjiuwen.core.runner import Runner
        await Runner.stop()


if __name__ == '__main__':
    try:
        asyncio.run(serve(Path(sys.argv[1]).resolve()))
    except Exception:
        print('PYTHON_FINANCE_HOST_START_OR_STOP_FAILED', file=sys.stderr)
        sys.exit(1)
