"""Bound snapshot/forecast tools for Java. This module never starts an Agent or LLM."""
from __future__ import annotations

from collections import OrderedDict
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import copy
import hmac
import json
import threading
import time
from typing import Annotated, Literal

from pydantic import AwareDatetime, Field, StrictInt, ValidationError, model_validator

from .contracts import BusinessError, Frozen, Id, canonical, digest
from .source_port import SourceBinding, read_current
from .forecast.service import predict, checked_receipt


class ToolRequest(Frozen):
    schema_version: Literal['credit-model-tools.v1']
    request_id: Id
    operation_key: Id
    session_ref: Id
    binding: SourceBinding
    observed_at: AwareDatetime
    deadline_at: AwareDatetime
    remaining_ms: Annotated[StrictInt, Field(ge=1, le=180000)]
    model_version: str | None = Field(default=None, pattern=r'^[a-f0-9]{64}$')
    model_digest: str | None = Field(default=None, pattern=r'^[a-f0-9]{64}$')
    method: Literal['model', 'baseline'] | None = None

    @model_validator(mode='after')
    def consistent_time(self):
        if self.observed_at != self.binding.observed_at:
            raise ValueError('observation mismatch')
        return self


class ModelTools:
    """The host must supply current authority; transport authentication is independent."""

    def __init__(self, source, *, authorize, predictor=None, cache_size=256):
        if not callable(authorize) or not 1 <= cache_size <= 4096:
            raise ValueError('explicit authority and bounded cache required')
        self.source, self.authorize, self.predictor = source, authorize, predictor
        self.cache_size = cache_size
        self._cache = OrderedDict()
        self._lock = threading.RLock()

    def execute(self, operation, request):
        started = time.monotonic()
        request = ToolRequest.model_validate(request)
        if operation not in ('snapshot', 'forecast'):
            raise BusinessError('INVALID_INPUT')
        remaining = min(request.remaining_ms / 1000,
                        (request.deadline_at - datetime.now(timezone.utc)).total_seconds())
        deadline = started + remaining

        def check():
            if time.monotonic() >= deadline:
                raise BusinessError('DEADLINE_EXCEEDED')
            if self.authorize(request.binding, request.session_ref) is not True:
                raise BusinessError('ACCESS_DENIED')

        def current():
            check()
            # RuntimeView includes account balances even when there are no ledger facts.
            # This v1 endpoint returns a complete view, never a silently rehashed projection.
            if 'ledger' not in request.binding.visible_source_ids:
                raise BusinessError('ACCESS_DENIED')
            try:
                view = read_current(self.source, request.binding)
            except BusinessError as exc:
                code = {'source-snapshot-changed': 'SOURCE_CHANGED',
                        'source-binding-mismatch': 'ACCESS_DENIED',
                        'scope-denied': 'ACCESS_DENIED',
                        'not-found': 'SOURCE_MISSING'}.get(exc.code, 'UPSTREAM_FAILURE')
                raise BusinessError(code) from None
            if 'ledger' not in view.case.visible_sources:
                raise BusinessError('ACCESS_DENIED')
            check()
            return view

        # Do not extend the budget while waiting for another operation.
        if not self._lock.acquire(timeout=max(0, remaining)):
            raise BusinessError('DEADLINE_EXCEEDED')
        try:
            view = current()
            if operation == 'snapshot':
                if any(x is not None for x in (request.method, request.model_version, request.model_digest)):
                    raise BusinessError('INVALID_INPUT')
            elif self.predictor is None:
                raise BusinessError('MODEL_UNBOUND')
            elif (request.method is None or request.model_version != self.predictor.version
                  or request.model_digest != self.predictor.version):
                raise BusinessError('MODEL_UNBOUND')
            namespace = (request.binding.tenant_id, request.binding.principal_id,
                         request.session_ref, request.operation_key)
            fingerprint = digest(dict(operation=operation, binding=request.binding.model_dump(mode='json'),
                                      method=request.method, model_version=request.model_version,
                                      model_digest=request.model_digest))
            cached = self._cache.get(namespace)
            if cached:
                if cached[0] != fingerprint:
                    raise BusinessError('IDEMPOTENCY_CONFLICT')
                current()  # Revocation and source changes must apply to cache hits too.
                result = copy.deepcopy(cached[1])
                result['request_id'] = request.request_id
                result['elapsed_ms'] = (time.monotonic() - started) * 1000
                return result
            if operation == 'snapshot':
                value = view.model_dump(mode='json')
            else:
                receipt = checked_receipt(predict(view, self.predictor, method=request.method,
                                                   business_now=request.observed_at))
                value = receipt.model_dump(mode='json')
            current()  # A result is released only against the still-current binding.
            result = dict(schema_version='credit-model-tools.v1', request_id=request.request_id,
                          status='complete', code='', observed_at=request.observed_at.isoformat(),
                          snapshot_digest=request.binding.snapshot_digest, result=value,
                          elapsed_ms=(time.monotonic() - started) * 1000)
            if operation == 'forecast':
                result.update(model_version=self.predictor.version, model_digest=self.predictor.version)
                if any(x['status'] != 'available' for x in value['results']):
                    result.update(status='unavailable', code='FORECAST_UNAVAILABLE')
            if result['status'] == 'complete':
                self._cache[namespace] = fingerprint, copy.deepcopy(result)
                while len(self._cache) > self.cache_size:
                    self._cache.popitem(last=False)
            return result
        finally:
            self._lock.release()


def make_server(application: ModelTools, *, token: str, port=0):
    """Create an IPv4 loopback-only server with explicit, in-memory transport binding."""
    if not token or token != token.strip() or any(c.isspace() for c in token):
        raise ValueError('invalid transport credential')

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            self.connection.settimeout(5)
            request_id = None
            status = 200
            try:
                auth = self.headers.get_all('Authorization') or []
                if len(auth) != 1 or not hmac.compare_digest(auth[0], 'Bearer ' + token):
                    raise BusinessError('ACCESS_DENIED')
                if self.path not in ('/v1/credit-tools/snapshot', '/v1/credit-tools/forecast'):
                    raise BusinessError('INVALID_INPUT')
                lengths = self.headers.get_all('Content-Length') or []
                if (len(lengths) != 1 or not lengths[0].isdigit()
                        or not 0 < int(lengths[0]) <= 2 * 1024 * 1024
                        or self.headers.get('Transfer-Encoding')
                        or self.headers.get_content_type() != 'application/json'):
                    raise BusinessError('INVALID_INPUT')
                data = self.rfile.read(int(lengths[0]))
                if len(data) != int(lengths[0]):
                    raise BusinessError('INVALID_INPUT')

                def unique(pairs):
                    result = {}
                    for key, val in pairs:
                        if key in result:
                            raise ValueError('duplicate key')
                        result[key] = val
                    return result

                raw = json.loads(data, object_pairs_hook=unique,
                                 parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
                request = ToolRequest.model_validate(raw)
                request_id = request.request_id
                result = application.execute(self.path.rsplit('/', 1)[1], request)
            except (ValueError, ValidationError, TypeError, UnicodeError):
                status, result = 400, dict(status='rejected', code='INVALID_INPUT')
            except BusinessError as exc:
                status = {'ACCESS_DENIED': 403, 'DEADLINE_EXCEEDED': 408,
                          'SOURCE_CHANGED': 409, 'IDEMPOTENCY_CONFLICT': 409,
                          'INVALID_INPUT': 400, 'SOURCE_MISSING': 404}.get(exc.code, 503)
                result = dict(status='rejected' if status < 500 else 'unavailable', code=exc.code)
            except Exception:
                status, result = 503, dict(status='failed', code='UPSTREAM_FAILURE')
            result.setdefault('schema_version', 'credit-model-tools.v1')
            result.setdefault('request_id', request_id)
            encoded = canonical(result)
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(encoded)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            try:
                self.wfile.write(encoded)
            except (BrokenPipeError, ConnectionResetError):
                pass

    return ThreadingHTTPServer(('127.0.0.1', port), Handler)
