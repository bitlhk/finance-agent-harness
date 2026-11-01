"""Private loopback transport for host-authorized, read-only credit queries.

The host injects the source authority and model ports. The transport bearer is
not a customer identity; SnapshotSource must authorize the supplied binding.
"""

import hmac
import asyncio
import json
import threading
import time
import sqlite3
import uuid
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from datetime import datetime, timezone
from typing import Annotated, Literal

from pydantic import AwareDatetime, Field, StrictInt, ValidationError

from .benefits.models import RecommendationRequest
from .benefits.service import RecommendationService
from .contracts import BusinessError, Frozen, Id, digest
from .forecast.service import predict
from .source_port import SourceBinding, read_current


class Query(Frozen):
    capability_id: Literal["credit.card.limit-risk.query", "credit.card.benefits.query"]
    binding: SourceBinding
    session_ref: Id
    operation_key: Id
    goal: str = Field(min_length=1, max_length=1000)
    remaining_ms: Annotated[StrictInt, Field(ge=1, le=180000)]
    deadline_at: AwareDatetime | None = None
    parent_run_id: Id | None = None
    top_k: Annotated[StrictInt, Field(ge=1, le=5)] | None = None
    engines: tuple[Literal['llm','onerec'], ...] | None = None


class WorkbenchConversations:
    """Owner-scoped conversation directory for both credit-card scene hosts.

    A conversation, not a customer, owns its independent runtime thread.
    Source grants must still be checked by the host before reading messages.
    """

    def __init__(self, root, *, owner, scene):
        root = Path(root)
        root.mkdir(parents=True, exist_ok=True)
        self.path = root / 'workbench-conversations.sqlite3'
        self.owner, self.scene = owner, scene
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE IF NOT EXISTS conversations ('
                       'id TEXT PRIMARY KEY, owner TEXT NOT NULL, scene TEXT NOT NULL, '
                       'revision INTEGER NOT NULL, updated_at TEXT NOT NULL, body TEXT NOT NULL)')

    def create(self, customer_ref):
        value = dict(id='conversation-'+uuid.uuid4().hex, revision=0, title='新对话',
                     customerRef=customer_ref, thread='session-'+uuid.uuid4().hex,
                     parent=None, messages=[], updatedAt=datetime.now(timezone.utc).isoformat())
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('INSERT INTO conversations VALUES (?,?,?,?,?,?)',
                       (value['id'], self.owner, self.scene, 0, value['updatedAt'], json.dumps(value)))
        return value

    def load(self, conversation_id):
        with closing(sqlite3.connect(self.path)) as db:
            row = db.execute('SELECT body FROM conversations WHERE id=? AND owner=? AND scene=?',
                             (conversation_id, self.owner, self.scene)).fetchone()
        if row is None:
            raise BusinessError('conversation-unavailable')
        return json.loads(row[0])

    def list(self):
        with closing(sqlite3.connect(self.path)) as db:
            rows = db.execute('SELECT body FROM conversations WHERE owner=? AND scene=? '
                              'ORDER BY updated_at DESC, id DESC LIMIT 100',
                              (self.owner, self.scene)).fetchall()
        return [{key: value[key] for key in ('id','title','customerRef','updatedAt')}
                for (raw,) in rows for value in [json.loads(raw)]]

    def save(self, value):
        # Compare-and-swap prevents a stale tab from replacing newer history.
        updated = dict(value, revision=value['revision']+1,
                       updatedAt=datetime.now(timezone.utc).isoformat())
        with closing(sqlite3.connect(self.path)) as db, db:
            changed = db.execute('UPDATE conversations SET revision=?,updated_at=?,body=? '
                                 'WHERE id=? AND owner=? AND scene=? AND revision=?',
                                 (updated['revision'],updated['updatedAt'],json.dumps(updated),
                                  value['id'],self.owner,self.scene,value['revision'])).rowcount
        if changed != 1:
            raise BusinessError('conversation-changed')
        return updated


class CreditQueryStore:
    """Durable idempotency and manager messages for a single active runtime."""

    def __init__(self, root):
        root = Path(root)
        root.mkdir(parents=True, exist_ok=True)
        self.path = root/'credit-query.sqlite3'
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE IF NOT EXISTS credit_queries('
                'sequence INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, operation TEXT NOT NULL,'
                'fingerprint TEXT NOT NULL, goal TEXT NOT NULL, run_id TEXT NOT NULL,'
                'result TEXT NOT NULL, pending INTEGER NOT NULL, UNIQUE(scope,operation))')
            for sequence, raw in db.execute('SELECT sequence,result FROM credit_queries WHERE pending=1').fetchall():
                result = json.loads(raw) | {'status':'failed','code':'runtime-interrupted',
                    'manager_preparation':{'status':'failed','code':'runtime-interrupted',
                        'execution':{'agent_execution':'interrupted','audit_completeness':'UNKNOWN'}}}
                db.execute('UPDATE credit_queries SET pending=0,result=? WHERE sequence=?',
                           (json.dumps(result),sequence))

    def get(self, scope, operation, fingerprint):
        with closing(sqlite3.connect(self.path)) as db:
            row = db.execute('SELECT fingerprint,result FROM credit_queries WHERE scope=? AND operation=?',
                             (scope,operation)).fetchone()
        if row is None:
            return None
        if row[0] != fingerprint:
            raise BusinessError('operation-key-input-conflict')
        return json.loads(row[1])

    def begin(self, scope, query, fingerprint, run_id):
        with closing(sqlite3.connect(self.path)) as db, db:
            result = {'status':'running','run_id':run_id,'customer_id':query.binding.customer_id}
            db.execute('INSERT INTO credit_queries(scope,operation,fingerprint,goal,run_id,result,pending) '
                       'VALUES(?,?,?,?,?,?,1)',
                       (scope,query.operation_key,fingerprint,query.goal,run_id,json.dumps(result)))

    def history(self, scope):
        with closing(sqlite3.connect(self.path)) as db:
            rows = db.execute('SELECT goal,run_id,result FROM credit_queries WHERE scope=? ORDER BY sequence',
                              (scope,)).fetchall()
        if len(rows) >= 20:
            raise BusinessError('credit-conversation-limit')
        messages, intents = [], ()
        for goal, _, raw in rows:
            messages.append(goal)
            result = json.loads(raw)
            plan = result.get('manager_preparation',{}).get('plan')
            if plan:
                from .contracts import Intent
                intents = tuple(Intent.model_validate(value) for value in plan['intents'])
        return messages, intents, rows[-1][1] if rows else None

    def finish(self, scope, operation, result):
        with closing(sqlite3.connect(self.path)) as db, db:
            changed = db.execute('UPDATE credit_queries SET result=?,pending=0 '
                'WHERE scope=? AND operation=? AND pending=1',
                (json.dumps(result,ensure_ascii=False,allow_nan=False),scope,operation)).rowcount
            if changed != 1:
                raise BusinessError('credit-operation-no-longer-active')

    def cancelled(self, scope, operation):
        with closing(sqlite3.connect(self.path)) as db:
            row = db.execute('SELECT result FROM credit_queries WHERE scope=? AND operation=?',
                             (scope,operation)).fetchone()
        return row is not None and json.loads(row[0]).get('status') == 'cancelled'

    def cancel(self, scope, operation, fingerprint):
        current = self.get(scope,operation,fingerprint)
        if current is None:
            raise BusinessError('credit-run-not-found')
        if current['status'] == 'running':
            cancelled = {key:current[key] for key in ('run_id','customer_id')}
            cancelled.update(status='cancelled',code='user-cancelled',new_model_calls=0,
                manager_preparation={'status':'cancelled','execution':{'audit_completeness':'PENDING'}})
            with closing(sqlite3.connect(self.path)) as db, db:
                db.execute('UPDATE credit_queries SET result=?,pending=0 WHERE scope=? AND operation=? AND pending=1',
                    (json.dumps(cancelled),scope,operation))
        return self.get(scope,operation,fingerprint)

    def audit_cancelled(self, scope, operation, fingerprint, execution):
        current = self.get(scope,operation,fingerprint)
        if current is None or current.get('status') != 'cancelled':
            raise BusinessError('credit-operation-no-longer-active')
        current['manager_preparation'] = {'status':'cancelled','execution':execution}
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('UPDATE credit_queries SET result=? WHERE scope=? AND operation=? AND pending=0',
                (json.dumps(current),scope,operation))
        return current


class RuntimeApplication:
    """One active application owns recommendation state for this state directory."""

    def __init__(self, *, source, state_root, model_config, benefits_config,
                 predictor=None, ports=None, engines=("llm",), max_sessions=64,
                 credit_model=None, service_catalog=(), allowed_capabilities=None):
        if not engines or not set(engines) <= {"llm", "onerec"}:
            raise ValueError("invalid engine binding")
        self.source, self.predictor = source, predictor
        self.root = Path(state_root).resolve()
        self.model_config, self.config = model_config, benefits_config
        self.ports, self.engines = ports, tuple(engines)
        self.max_sessions = max_sessions
        self.credit_model, self.service_catalog = credit_model, tuple(service_catalog)
        known = {'credit.card.limit-risk.query','credit.card.benefits.query'}
        self.allowed_capabilities = frozenset(known if allowed_capabilities is None else allowed_capabilities)
        if not self.allowed_capabilities or not self.allowed_capabilities <= known:
            raise ValueError('invalid capability installation')
        self._services = {}
        self._history_tenant = None
        self._lock = threading.Lock()
        self._credit_lock = threading.Lock()
        self._credit_store = CreditQueryStore(self.root)

    def _scope(self, query):
        # Actor, tenant and host session remain separate even for the same subject.
        return digest({"tenant": query.binding.tenant_id,
                       "principal": query.binding.principal_id,
                       "customer": query.binding.customer_id,
                       "case": query.binding.case_id, "session": query.session_ref})

    def _service(self, query):
        scope = self._scope(query)
        with self._lock:
            if self.config.dataset_root:
                if self._history_tenant is not None and self._history_tenant != query.binding.tenant_id:
                    raise BusinessError('preference-history-tenant-mismatch')
                self._history_tenant = query.binding.tenant_id
            if scope not in self._services:
                if len(self._services) >= self.max_sessions:
                    raise BusinessError("runtime-session-capacity")
                # The binding is immutable: a changed snapshot requires a new host
                # session. It cannot silently revive an old recommendation history.
                binding = query.binding
                service = RecommendationService(
                    root=self.root / (scope + '.sqlite3'), get_view=lambda _: read_current(self.source, binding),
                    config=self.config, model_config=self.model_config, ports=self.ports)
                self._services[scope] = (binding, service)
            binding, service = self._services[scope]
            if binding != query.binding:
                raise BusinessError("runtime-session-binding-changed")
            return scope, service

    def query(self, query):
        started = time.monotonic()
        if query.deadline_at is not None:
            remaining = min(query.remaining_ms, int((query.deadline_at-datetime.now(timezone.utc)).total_seconds()*1000))
            if remaining < 1:
                raise BusinessError('runtime-deadline-exceeded')
            query = query.model_copy(update={'remaining_ms':remaining})
        if query.capability_id not in self.allowed_capabilities:
            raise BusinessError('capability-not-installed')
        if query.capability_id == 'credit.card.benefits.query' and not self.config.enabled:
            raise BusinessError('recommendation-disabled')
        view = read_current(self.source, query.binding)
        if query.capability_id == "credit.card.limit-risk.query":
            if query.top_k is not None or query.engines is not None:
                raise BusinessError('credit-query-option-not-supported')
            if "ledger" not in view.case.visible_sources:
                raise BusinessError("ledger-source-not-visible")
            return self._credit_query(query, view, started)
        scope, service = self._service(query)
        remaining = query.remaining_ms / 1000 - (time.monotonic() - started)
        if remaining <= 0:
            raise BusinessError("runtime-deadline-exceeded")
        if (query.engines is not None and (not query.engines or len(set(query.engines)) != len(query.engines)
                or not set(query.engines) <= set(self.engines))):
            raise BusinessError('recommendation-engine-not-installed')
        request = RecommendationRequest(
            operation_key=query.operation_key, goal=query.goal, engines=query.engines or self.engines,
            top_k=query.top_k or 3,
            conversation=True, parent_run_id=query.parent_run_id)
        return service.submit(scope, request, seconds=remaining)

    def _credit_identity(self, query):
        scope = self._scope(query)
        model = self.predictor.version if self.predictor is not None else None
        fingerprint = digest({'input':query.model_dump(mode='json',exclude={'remaining_ms','deadline_at'}),
            'model':model,'analysis':self.model_config.model_dump(mode='json')})
        return scope, fingerprint, 'credit-'+digest({'scope':scope,'operation':query.operation_key})[:32]

    def _credit_query(self, query, view, started):
        scope, fingerprint, run_id = self._credit_identity(query)
        def check(*, cancel=True):
            if cancel and self._credit_store.cancelled(scope,query.operation_key):
                raise BusinessError('credit-query-cancelled')
            read_current(self.source, query.binding)
            if (time.monotonic()-started)*1000 >= query.remaining_ms:
                raise BusinessError('runtime-deadline-exceeded')
        check(cancel=False)
        cached = self._credit_store.get(scope,query.operation_key,fingerprint)
        if cached is not None:
            check(cancel=False)
            return cached | {'cache_hit':True,'new_model_calls':0}
        if not self._credit_lock.acquire(blocking=False):
            raise BusinessError('credit-runtime-busy')
        begun = False
        preparation = {}
        try:
            cached = self._credit_store.get(scope,query.operation_key,fingerprint)
            if cached is not None:
                check(cancel=False)
                return cached | {'cache_hit':True,'new_model_calls':0}
            messages, intents, parent = self._credit_store.history(scope)
            if query.parent_run_id is not None and query.parent_run_id != parent:
                raise BusinessError('credit-parent-not-current')
            check()
            self._credit_store.begin(scope,query,fingerprint,run_id)
            begun = True
            receipt = predict(view,self.predictor,method='model',business_now=query.binding.observed_at)
            preparation = {'status':'unavailable','code':'forecast-model-not-bound',
                           'execution':{'agent_execution':'NOT_RUN'}}
            if self.predictor is not None:
                from .credit import prepare_credit
                remaining = query.remaining_ms/1000-(time.monotonic()-started)
                preparation = asyncio.run(prepare_credit(
                    get_current=lambda:read_current(self.source,query.binding),messages=messages+[query.goal],
                    intents=intents,config=self.model_config,receipt=receipt,model=self.credit_model,
                    catalog=self.service_catalog,seconds=remaining,
                    cancelled=lambda:self._credit_store.cancelled(scope,query.operation_key)))
            check()
            available = all(r.status == 'available' for r in receipt.results)
            result = {'status':'complete' if available and preparation['status']=='complete'
                      else 'partial' if available else 'unavailable', 'run_id':run_id,
                'customer_id':view.customer.customer_id,'manager_preparation':preparation,
                'account':view.account.model_dump(mode='json'),'receipt':receipt.model_dump(mode='json')}
            result.update(cache_hit=False,new_model_calls=sum(
                call.get('kind')=='model' for call in preparation.get('execution',{}).get('calls',[])))
            self._credit_store.finish(scope,query.operation_key,result)
            return result
        except Exception as exc:
            if begun:
                if self._credit_store.cancelled(scope,query.operation_key):
                    return self._credit_store.audit_cancelled(scope,query.operation_key,fingerprint,
                        preparation.get('execution',{'agent_execution':'NOT_RUN'}))
                code = exc.code if isinstance(exc,BusinessError) else 'credit-runtime-failed'
                self._credit_store.finish(scope,query.operation_key,{'status':'failed','code':code,
                    'customer_id':query.binding.customer_id,'run_id':run_id,
                    'manager_preparation':{'status':'failed','code':code,
                        'execution':preparation.get('execution',{'agent_execution':'NOT_RUN'})}})
            raise
        finally:
            self._credit_lock.release()

    def show(self, query, run_id):
        if query.capability_id == 'credit.card.limit-risk.query' and query.capability_id in self.allowed_capabilities:
            view = read_current(self.source,query.binding)
            if 'ledger' not in view.case.visible_sources:
                raise BusinessError('ledger-source-not-visible')
            scope, fingerprint, expected = self._credit_identity(query)
            if run_id != expected:
                raise BusinessError('credit-run-not-found')
            result = self._credit_store.get(scope,query.operation_key,fingerprint)
            if result is None:
                raise BusinessError('credit-run-not-found')
            read_current(self.source,query.binding)
            return result
        if (query.capability_id != 'credit.card.benefits.query'
                or query.capability_id not in self.allowed_capabilities):
            raise BusinessError('capability-not-installed')
        read_current(self.source, query.binding)
        scope, service = self._service(query)
        return service.show(scope, run_id)

    def cancel(self, query, run_id=None):
        if query.capability_id not in self.allowed_capabilities:
            raise BusinessError('capability-not-installed')
        view = read_current(self.source,query.binding)
        if query.capability_id == 'credit.card.limit-risk.query':
            if 'ledger' not in view.case.visible_sources:
                raise BusinessError('ledger-source-not-visible')
            scope, fingerprint, expected = self._credit_identity(query)
            if run_id is not None and run_id != expected:
                raise BusinessError('credit-run-not-found')
            return self._credit_store.cancel(scope,query.operation_key,fingerprint)
        if not run_id:
            raise BusinessError('recommendation-run-required')
        scope, service = self._service(query)
        return service.cancel(scope,run_id)

    def close(self):
        with self._lock:
            for _, service in self._services.values():
                service.close()


def make_server(application, *, token, port=0):
    """Create an unstarted IPv4 loopback server; caller owns its lifecycle."""
    if not token or len(token) < 24 or not token.isascii() or any(c.isspace() for c in token):
        raise ValueError("private transport credential is missing or invalid")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # No headers, payloads or credentials enter access logs.

        def reply(self, status, payload):
            data = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            ingress = time.monotonic()
            self.connection.settimeout(5)
            values = self.headers.get_all("Authorization", [])
            if len(values) != 1 or not hmac.compare_digest(values[0].encode('utf-8'), ("Bearer " + token).encode()):
                return self.reply(403, {"code": "transport-access-denied"})
            if self.path not in ("/v1/query", "/v1/result", "/v1/cancel"):
                return self.reply(404, {"code": "route-not-found"})
            try:
                lengths = self.headers.get_all("Content-Length", [])
                if len(lengths) != 1 or self.headers.get("Transfer-Encoding"):
                    raise ValueError("invalid length")
                length = int(lengths[0])
                if not 0 < length <= 65536:
                    raise ValueError("invalid length")
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ValueError("truncated input")
                body = json.loads(raw)
                run_id = body.pop("run_id", None) if self.path in ("/v1/result", "/v1/cancel") else None
                query = Query.model_validate(body)
                remaining = query.remaining_ms-int((time.monotonic()-ingress)*1000)
                if remaining < 1 and self.path == '/v1/query':
                    raise BusinessError('runtime-deadline-exceeded')
                query = query.model_copy(update={'remaining_ms':max(1,remaining)})
                if self.path == "/v1/result" and (not isinstance(run_id, str) or not run_id):
                    raise ValueError("missing run id")
                result = (application.cancel(query,run_id) if self.path == '/v1/cancel'
                          else application.show(query,run_id) if run_id else application.query(query))
                self.reply(200, result)
            except BusinessError as exc:
                self.reply(409, {"code": exc.code})
            except (ValueError, TypeError, ValidationError, AttributeError):
                self.reply(422, {"code": "invalid-query"})
            except Exception:
                self.reply(503, {"code": "runtime-unavailable"})

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)
