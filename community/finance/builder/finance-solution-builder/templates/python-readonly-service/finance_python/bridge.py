"""One owned Java child, bounded private protocol, no caller-defined commands."""
import json
import os
from pathlib import Path
import queue
import subprocess
import threading
import uuid


class BridgeError(RuntimeError):
    pass


class AuthorizationRejected(BridgeError):
    pass


class Bridge:
    def __init__(self, root, timeout=20):
        self.root=Path(root).resolve()
        self.generation=str(uuid.uuid4())
        self.timeout=timeout
        self.lock=threading.Lock()
        self.responses=queue.Queue(maxsize=1)
        self.failed=False
        jars=list((self.root/'target').glob('*.jar'))
        if len(jars)!=1:
            raise BridgeError('BRIDGE_ARTIFACT_MISSING')
        environment={k:v for k,v in os.environ.items() if k in
                     {'PATH','HOME','JAVA_HOME','LANG','FH_BANK_URL','FH_BANK_LOGIN','FH_BANK_SECRET',
                      'FH_ACCESS_POLICY','FH_HISTORY_DIR','FH_HISTORY_OWNER','FH_HISTORY_ENABLED',
                      'FH_HISTORY_REQUEST_BYTES','FH_HISTORY_TOTAL_BYTES','FH_HISTORY_MANIFEST_BYTES','FH_SNAPSHOTS_ENABLED',
                      'FH_OBSERVATION_QUALIFICATION_FILE','FH_OBSERVATION_QUALIFICATION_SHA256',
                      'FH_SNAPSHOT_CAPTURE_OWNERS','FH_SNAPSHOT_MAX_FILES','FH_SNAPSHOT_MAX_BYTES',
                      'FH_SNAPSHOT_RETENTION_SECONDS','FH_SNAPSHOT_FLUSH_MILLIS','FH_OTHER_MODEL_BODY_COLLECTOR'}}
        environment['FH_BRIDGE_GENERATION']=self.generation
        self.child=subprocess.Popen(['java','-Xmx256m','-cp',str(jars[0])+os.pathsep+str(self.root/'target/dependency/*'),
            'io.github.bitlhk.finance.builder.local.PythonFinanceBridge',str(self.root)],
            cwd=self.root,env=environment,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
        self.reader=threading.Thread(target=self._read,daemon=True)
        self.reader.start()
        try:
            self.description=self.call('health')
        except Exception:
            self.close(); raise

    def _read(self):
        try:
            while True:
                line=self.child.stdout.readline(262146)
                if not line or len(line)>262145 or not line.endswith(b'\n'):
                    raise BridgeError('BRIDGE_FRAME_UNAVAILABLE')
                self.responses.put(json.loads(line),timeout=self.timeout)
        except Exception:
            try: self.responses.put_nowait(None)
            except queue.Full: pass

    def call(self, operation, session=None, parameters=None):
        if operation not in {'health','open','authorize','close','begin','finish','stage-start','stage-end','query','snapshot'}:
            raise BridgeError('BRIDGE_OPERATION_UNSUPPORTED')
        if not self.lock.acquire(timeout=self.timeout):
            self.close()
            raise BridgeError('BRIDGE_BUSY_TIMEOUT')
        try:
            if self.failed or self.child.poll() is not None:
                raise BridgeError('BRIDGE_UNAVAILABLE')
            request_id=str(uuid.uuid4())
            message={'schemaVersion':'finance-local-pipe.v0.1','generation':self.generation,
                     'requestId':request_id,'operation':operation,'session':session,'parameters':parameters or {}}
            data=(json.dumps(message,ensure_ascii=False)+'\n').encode()
            if len(data)>65536:
                raise BridgeError('BRIDGE_INPUT_TOO_LARGE')
            try:
                self.child.stdin.write(data); self.child.stdin.flush()
                response=self.responses.get(timeout=self.timeout)
                if not response or response.get('generation')!=self.generation or response.get('requestId')!=request_id:
                    raise BridgeError('BRIDGE_RESPONSE_BINDING_REJECTED')
            except Exception:
                self.failed=True
                self.close()
                raise BridgeError('BRIDGE_UNAVAILABLE') from None
            if not response.get('ok'):
                if response.get('error')=='AUTHORIZATION_REJECTED':
                    raise AuthorizationRejected('AUTHORIZATION_REJECTED')
                raise BridgeError('BRIDGE_OPERATION_FAILED')
            return response['result']
        finally:
            self.lock.release()

    def close(self):
        self.failed=True
        if self.child.poll() is None:
            # EOF lets Java flush its bounded optional writer; force only on timeout.
            try: self.child.stdin.close()
            except OSError:pass
            try: self.child.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.child.kill(); self.child.wait(timeout=3)
        for stream in (self.child.stdin,self.child.stdout):
            if stream:
                try: stream.close()
                except OSError: pass
