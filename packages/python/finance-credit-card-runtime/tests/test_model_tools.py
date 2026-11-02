"""Real HTTP contract probes with only synthetic, current runtime views."""
from datetime import datetime, timedelta, timezone
import json
import subprocess
import sys
import threading
from urllib.request import Request, urlopen
from urllib.error import HTTPError

import pytest
from pydantic import ValidationError

from finance_credit_card_runtime.contracts import BusinessError, digest
from finance_credit_card_runtime.model_tools import ModelTools, ToolRequest, make_server
from finance_credit_card_runtime.source_port import SourceBinding
from finance_credit_card_runtime.forecast.models import ForecastProtocol, HorizonForecast
from finance_credit_card_runtime.forecast.service import predict
from test_credit_contracts import snapshot


def fixture():
    view = snapshot()
    fact = dict(record_id='history-start', customer_id='customer-1', account_id='account-1',
                group_id='group-1', source_id='ledger', kind='balance',
                event_at=view.case.observed_at-timedelta(days=35),
                known_at=view.case.observed_at-timedelta(days=35), version='v1', amount_minor=0)
    raw = view.model_dump();raw['facts'] = [fact]
    view = type(view).model_validate(raw)
    binding = SourceBinding(tenant_id='tenant-1', principal_id='manager-1',
                            customer_id='customer-1', case_id='case-1',
                            observed_at=view.case.observed_at,
                            visible_source_ids=('ledger', 'benefits'), snapshot_digest=digest(view))

    class Source:
        reads = 0
        def read(self, binding):
            self.reads += 1
            return view

    class Predictor:
        version = 'b'*64
        calls = 0
        protocol = ForecastProtocol()
        manifest = dict(dataset_version=view.dataset_version, protocol_digest=digest(protocol),
                        protocol=protocol.model_dump())
        def predict(self, features, method):
            self.calls += 1
            return tuple(HorizonForecast(horizon_days=h,status='available',reason='synthetic',
                                         p10_minor=h*100,p50_minor=h*200,p90_minor=h*300,
                                         shortage_probability=.1) for h in (1,3,7))
    request = dict(schema_version='credit-model-tools.v1',request_id='request-1',
                   operation_key='operation-1',session_ref='session-1',
                   binding=binding.model_dump(mode='json'),
                   observed_at=binding.observed_at.isoformat(),remaining_ms=10000,
                   deadline_at=(datetime.now(timezone.utc)+timedelta(seconds=10)).isoformat())
    return view, Source(), Predictor(), request


def test_same_frozen_predictor_values_cache_and_current_authority():
    view, source, predictor, request = fixture()
    granted = [True]
    app = ModelTools(source,authorize=lambda *_: granted[0],predictor=predictor)
    request.update(model_version=predictor.version,model_digest=predictor.version,method='model')
    expected = predict(view,predictor,method='model',business_now=view.case.observed_at)
    predictor.calls = 0
    result = app.execute('forecast',request)
    assert result['status'] == 'complete'
    assert result['result'] == expected.model_dump(mode='json')
    assert predictor.calls == 1
    assert app.execute('forecast',request | {'request_id':'retry'})['request_id'] == 'retry'
    assert predictor.calls == 1
    with pytest.raises(BusinessError,match='IDEMPOTENCY_CONFLICT'):
        app.execute('forecast',request | {'method':'baseline'})
    granted[0] = False
    with pytest.raises(BusinessError,match='ACCESS_DENIED'):
        app.execute('forecast',request)
    assert predictor.calls == 1


def test_same_principal_narrow_sources_and_post_read_revocation():
    _, source, predictor, request = fixture()
    app = ModelTools(source,authorize=lambda *_: True,predictor=predictor)
    narrow = request | {'binding':request['binding'] | {'visible_source_ids':['ledger']}}
    with pytest.raises(BusinessError,match='ACCESS_DENIED'):
        app.execute('snapshot',narrow)
    checks = []
    def authorize(*_):
        checks.append(1)
        return len(checks) == 1
    app = ModelTools(source,authorize=authorize)
    with pytest.raises(BusinessError,match='ACCESS_DENIED'):
        app.execute('snapshot',request)


def test_benefits_only_legal_view_cannot_expose_account_balances():
    view, source, _, request = fixture()
    raw = view.model_dump()
    raw['case']['visible_sources'] = ('benefits',)
    raw['facts'] = []
    narrow_view = type(view).model_validate(raw)
    source.read = lambda _: narrow_view
    request['binding'].update(visible_source_ids=['benefits'],snapshot_digest=digest(narrow_view))
    app = ModelTools(source,authorize=lambda *_: True)
    for operation in ('snapshot','forecast'):
        with pytest.raises(BusinessError,match='ACCESS_DENIED'):
            app.execute(operation,request)


def test_deadline_unbound_and_forbidden_inputs():
    _, source, predictor, request = fixture()
    app = ModelTools(source,authorize=lambda *_: True)
    with pytest.raises(BusinessError,match='DEADLINE_EXCEEDED'):
        app.execute('snapshot',request | {'deadline_at':'2000-01-01T00:00:00Z'})
    assert source.reads == 0
    with pytest.raises(BusinessError,match='MODEL_UNBOUND'):
        app.execute('forecast',request)
    for key in ('features','labels','api_key','model_path'):
        with pytest.raises(ValidationError):
            ToolRequest.model_validate(request | {key:'untrusted'})
    with pytest.raises(ValidationError):
        ToolRequest.model_validate(request | {'remaining_ms':True})


def test_source_change_during_prediction_cannot_release_results():
    view, source, predictor, request = fixture()
    original = predictor.predict
    def changing(features, method):
        result = original(features,method)
        source.read = lambda _: view.model_copy(update={'revision':1})
        return result
    predictor.predict = changing
    request.update(model_version=predictor.version,model_digest=predictor.version,method='model')
    with pytest.raises(BusinessError,match='SOURCE_CHANGED'):
        ModelTools(source,authorize=lambda *_: True,predictor=predictor).execute('forecast',request)


def test_actual_http_authentication_strict_json_and_no_agent_route():
    _, source, _, request = fixture()
    app = ModelTools(source,authorize=lambda *_: True)
    server = make_server(app,token='synthetic-tools-token')
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    url='http://127.0.0.1:'+str(server.server_port)
    def call(path, body, credential='synthetic-tools-token'):
        r=Request(url+path,data=body,headers={'Authorization':'Bearer '+credential,
                                            'Content-Type':'application/json'})
        try:
            with urlopen(r,timeout=3) as response:
                return response.status,json.load(response)
        except HTTPError as exc:
            return exc.code,json.load(exc)
    try:
        body=json.dumps(request).encode()
        code,result=call('/v1/credit-tools/snapshot',body)
        assert code == 200 and result['result']['customer']['customer_id']=='customer-1'
        before=source.reads
        assert call('/v1/credit-tools/snapshot',body,'wrong')[0] == 403
        assert call('/v1/query',body)[0] == 400
        assert call('/v1/credit-tools/snapshot',b'{"binding":{},"binding":{}}')[0] == 400
        assert source.reads == before
    finally:
        server.shutdown();server.server_close();thread.join(timeout=2)


def test_tool_import_does_not_import_agent_or_llm_dependencies():
    code = ('import sys; import finance_credit_card_runtime.model_tools; '
            'assert not any(x.startswith(("openjiuwen", "openai")) for x in sys.modules)')
    subprocess.run([sys.executable,'-c',code],check=True,timeout=10)
