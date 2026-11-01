import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from pydantic import ValidationError
from finance_credit_card_runtime.contracts import RuntimeView, digest, BusinessError
from finance_credit_card_runtime.source_port import SourceBinding, read_current
from finance_credit_card_runtime.benefits.context import build_context
from finance_credit_card_runtime.benefits.models import RecommendationRequest
from finance_credit_card_runtime.forecast.service import predict, checked_receipt


def test_workbench_conversations_are_independent_not_partitioned_by_customer(tmp_path):
    from finance_credit_card_runtime.server import WorkbenchConversations
    store = WorkbenchConversations(tmp_path, owner='manager-a', scene='benefits')
    first, second = store.create('customer-a'), store.create('customer-a')
    third = store.create('customer-b')
    assert len(store.list()) == 3
    assert len({first['thread'],second['thread'],third['thread']}) == 3
    first['messages'] = [{'role':'user','text':'不要沙拉'}]
    saved = store.save(first)
    assert store.load(second['id'])['messages'] == []
    restarted = WorkbenchConversations(tmp_path, owner='manager-a', scene='benefits')
    assert restarted.load(first['id']) == saved
    with pytest.raises(BusinessError, match='conversation-changed'):
        restarted.save(first)
    for owner,scene in [('manager-b','benefits'),('manager-a','limit-risk')]:
        other = WorkbenchConversations(tmp_path, owner=owner, scene=scene)
        assert other.list() == []
        with pytest.raises(BusinessError, match='conversation-unavailable'):
            other.load(first['id'])


def snapshot():
    now = datetime(2026, 6, 12, tzinfo=timezone.utc)
    return RuntimeView.model_validate(
        dict(
            dataset_version="a" * 64,
            case=dict(
                case_id="case-1",
                customer_id="customer-1",
                account_id="account-1",
                group_id="group-1",
                observed_at=now,
            ),
            customer=dict(
                customer_id="customer-1", group_id="group-1", name="合成客户", tier=4
            ),
            account=dict(
                account_id="account-1",
                customer_id="customer-1",
                card_id="card-1",
                group_id="group-1",
                available_minor=6000,
                credit_limit_minor=10000,
                used_limit_minor=4000,
            ),
            facts=[],
            benefits=[
                dict(
                    benefit_id="benefit-1",
                    title="合成餐饮权益",
                    known_at=now,
                    valid_from=now - timedelta(days=1),
                    valid_until=now + timedelta(days=7),
                    minimum_tier=4,
                    remaining=2,
                )
            ],
        )
    )


def test_no_builder_or_private_labels_in_runtime():
    root = Path(__file__).parents[1] / "src"
    for p in root.rglob("*.py"):
        text = p.read_text(encoding="utf-8")
        assert "finance_builder_base" not in text
        assert "credit_card_service_builder" not in text
        assert "class GenerationSettings" not in text
        assert "def train(" not in text


def test_observed_history_is_case_snapshot_and_time_bound(tmp_path):
    import hashlib
    from finance_credit_card_runtime.benefits.dataset import observed_history
    ctx = build_context(snapshot(), RecommendationRequest(operation_key='history-check'))
    row = dict(record_id='past-1',benefit_id='benefit-1',source_id='benefits',
        event_at='2026-06-11T00:00:00Z',known_at='2026-06-11T00:00:00Z',value=1)
    def publish(samples):
        data = ('\n'.join(json.dumps(x) for x in samples)+'\n').encode()
        (tmp_path/'samples.jsonl').write_bytes(data)
        (tmp_path/'manifest.json').write_text(json.dumps({'samples_sha256':hashlib.sha256(data).hexdigest()}))
    stored = ctx.model_dump(mode='json')
    stored['history'] = [row, row | {'record_id':'future','known_at':'2026-06-13T00:00:00Z'}]
    publish([{'context':stored | {'case_id':'other-case'}},{'context':stored}])
    history = observed_history(tmp_path,ctx).history
    assert len(history) == 1 and history[0]['record_id'] == 'past-1'
    publish([{'context':stored | {'snapshot_digest':'b'*64}}])
    with pytest.raises(BusinessError,match='preference-dataset-mismatch'):
        observed_history(tmp_path,ctx)
    publish([{'context':stored | {'case_id':'other-case'}}])
    assert observed_history(tmp_path,ctx).history == ()


def test_past_absolute_deadline_prevents_source_access(tmp_path):
    from finance_credit_card_runtime.server import Query, RuntimeApplication
    from finance_credit_card_runtime.source_port import ServiceConfig
    from finance_credit_card_runtime.benefits.models import BenefitsConfig
    view = snapshot()
    class Source:
        def read(self, binding):
            raise AssertionError('expired request must not access sources')
    app = RuntimeApplication(source=Source(),state_root=tmp_path,model_config=ServiceConfig(),
        benefits_config=BenefitsConfig(),ports={})
    query = Query(capability_id='credit.card.limit-risk.query',binding=SourceBinding(
        tenant_id='tenant-1',principal_id='manager-1',customer_id='customer-1',case_id='case-1',
        observed_at=view.case.observed_at,visible_source_ids=('ledger','benefits'),snapshot_digest=digest(view)),
        session_ref='expired-session',operation_key='expired-op',goal='核查额度',remaining_ms=1000,
        deadline_at=datetime.now(timezone.utc)-timedelta(seconds=1))
    try:
        with pytest.raises(BusinessError,match='runtime-deadline-exceeded'):
            app.query(query)
        with pytest.raises(BusinessError,match='recommendation-disabled'):
            app.query(query.model_copy(update={'capability_id':'credit.card.benefits.query',
                'deadline_at':datetime.now(timezone.utc)+timedelta(seconds=10)}))
    finally:
        app.close()


def test_roundtrip_and_current_benefit_reference():
    view = snapshot()
    assert RuntimeView.model_validate_json(view.model_dump_json()) == view
    ctx = build_context(view, RecommendationRequest(operation_key="test-1"))
    assert ctx.candidates[0].reference.snapshot_digest == digest(view)
    assert ctx.candidates[0].reference.customer_id == view.customer.customer_id


@pytest.mark.parametrize(
    "patch",
    [
        {"available_minor": False},
        {"available_minor": -1},
        {"available_minor": 6001},
        {"unexpected": "value"},
    ],
)
def test_credit_account_rejects_invalid_or_inconsistent_values(patch):
    raw = snapshot().model_dump(mode="json")
    raw["account"].update(patch)
    with pytest.raises(ValidationError):
        RuntimeView.model_validate(raw)


def test_source_port_checks_subject_visibility_and_version():
    view = snapshot()

    class ExplicitSource:
        def read(self, binding):
            return view

    binding = SourceBinding(
        tenant_id="tenant-1",
        principal_id="manager-1",
        customer_id="customer-1",
        case_id="case-1",
        observed_at=view.case.observed_at,
        visible_source_ids=("ledger", "benefits"),
        snapshot_digest=digest(view),
    )
    assert read_current(ExplicitSource(), binding) == view
    for change in [
        {"customer_id": "other"},
        {"visible_source_ids": ("ledger",)},
        {"snapshot_digest": "b" * 64},
    ]:
        with pytest.raises(BusinessError):
            read_current(ExplicitSource(), binding.model_copy(update=change))


def test_eligibility_and_missing_model_do_not_fabricate_values():
    view = snapshot()
    benefit = view.benefits[0]
    for change in [
        {"minimum_tier": 5},
        {"remaining": 0},
        {"valid_until": view.case.observed_at},
    ]:
        current = view.model_copy(
            update={"benefits": (benefit.model_copy(update=change),)}
        )
        assert (
            build_context(
                current, RecommendationRequest(operation_key="eligibility")
            ).candidates
            == ()
        )
    receipt = predict(view, None)
    assert all(x.status == "untrained" and x.p50_minor is None for x in receipt.results)
    assert checked_receipt(receipt) == receipt
    with pytest.raises(BusinessError):
        checked_receipt(receipt.model_copy(update={"receipt_id": "0" * 64}))


def test_private_http_authority_binding_and_missing_model(tmp_path):
    import threading
    import urllib.error
    import urllib.request
    from finance_credit_card_runtime.server import RuntimeApplication, make_server
    from finance_credit_card_runtime.source_port import ServiceConfig
    from finance_credit_card_runtime.benefits.models import BenefitsConfig

    view = snapshot()
    calls = []

    class Source:
        def read(self, binding):
            calls.append(binding)
            if binding.principal_id != "manager-1" or binding.tenant_id != "tenant-1":
                raise BusinessError("source-access-denied")
            return view

    application = RuntimeApplication(
        source=Source(),
        state_root=tmp_path,
        model_config=ServiceConfig(),
        benefits_config=BenefitsConfig(),
        ports={},
    )
    server = make_server(application, token="synthetic-private-transport-token")
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    binding = dict(
        tenant_id="tenant-1",
        principal_id="manager-1",
        customer_id="customer-1",
        case_id="case-1",
        observed_at=view.case.observed_at.isoformat(),
        visible_source_ids=["ledger", "benefits"],
        snapshot_digest=digest(view),
    )
    body = dict(
        capability_id="credit.card.limit-risk.query",
        binding=binding,
        session_ref="host-session-1",
        operation_key="operation-1",
        goal="检查额度风险",
        remaining_ms=3000,
    )

    def post(payload, token="synthetic-private-transport-token", path="/v1/query"):
        request = urllib.request.Request(
            f"http://127.0.0.1:{server.server_port}{path}",
            data=json.dumps(payload).encode(),
            headers={
                "Authorization": "Bearer " + token,
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as exc:
            return exc.code, json.load(exc)

    try:
        assert post(body, token="wrong")[0] == 403
        assert calls == []
        code, result = post(body)
        assert code == 200 and result["status"] == "unavailable"
        assert all(r["status"] == "untrained" for r in result["receipt"]["results"])
        for change in (
            {"principal_id": "other"},
            {"tenant_id": "other"},
            {"customer_id": "other"},
            {"snapshot_digest": "b" * 64},
            {"visible_source_ids": ["ledger"]},
        ):
            assert post(dict(body, binding=binding | change))[0] == 409
        assert post(body | {"remaining_ms": 0})[0] == 422
        assert post(body | {"capability_id": "credit.card.approve"})[0] == 422
        assert post(body, path="/v1/result")[0] == 422
        assert post(body, path="/v1/execute")[0] == 404
    finally:
        server.shutdown()
        server.server_close()
        application.close()
        thread.join(timeout=5)


def test_expired_queue_budget_never_calls_model(tmp_path):
    import time
    from concurrent.futures import ThreadPoolExecutor
    from finance_credit_card_runtime.benefits.models import BenefitsConfig
    from finance_credit_card_runtime.benefits.service import RecommendationService
    from finance_credit_card_runtime.source_port import ServiceConfig
    from threading import Event

    gate = Event()
    service = RecommendationService(
        root=tmp_path / "runs.sqlite3",
        get_view=lambda _: snapshot(),
        config=BenefitsConfig(),
        model_config=ServiceConfig(),
        ports={},
    )
    service.pool.shutdown()
    service.pool = ThreadPoolExecutor(max_workers=1)
    blocked = service.pool.submit(gate.wait, 2)
    try:
        row = service.submit(
            "journey-1", RecommendationRequest(operation_key="queued-1"), seconds=0.01
        )
        time.sleep(0.03)
        gate.set()
        blocked.result(timeout=2)
        for _ in range(100):
            row = service.show("journey-1", row["run_id"])
            if row["status"] == "failed":
                break
            time.sleep(0.01)
        assert row["status"] == "failed"
        assert row["result"]["code"] == "recommendation-timeout"
        assert row["engine_receipts"] == []
    finally:
        gate.set()
        service.pool.shutdown(wait=True)


def test_benefits_only_identity_cannot_query_account_even_without_model(
    tmp_path, monkeypatch
):
    from finance_credit_card_runtime.server import Query, RuntimeApplication
    from finance_credit_card_runtime.source_port import ServiceConfig
    from finance_credit_card_runtime.benefits.models import BenefitsConfig

    view = snapshot()
    view = view.model_copy(
        update={"case": view.case.model_copy(update={"visible_sources": ("benefits",)})}
    )

    class Source:
        def read(self, binding):
            return view

    def forbidden(*args, **kwargs):
        raise AssertionError("predictor must not run before ledger authorization")

    monkeypatch.setattr("finance_credit_card_runtime.server.predict", forbidden)
    application = RuntimeApplication(
        source=Source(),
        state_root=tmp_path,
        model_config=ServiceConfig(),
        benefits_config=BenefitsConfig(),
        ports={},
    )
    binding = SourceBinding(
        tenant_id="tenant-1",
        principal_id="manager-1",
        customer_id="customer-1",
        case_id="case-1",
        observed_at=view.case.observed_at,
        visible_source_ids=("benefits",),
        snapshot_digest=digest(view),
    )
    query = Query(
        capability_id="credit.card.limit-risk.query",
        binding=binding,
        session_ref="session-1",
        operation_key="operation-1",
        goal="查询额度",
        remaining_ms=1000,
    )
    try:
        with pytest.raises(BusinessError, match="ledger-source-not-visible"):
            application.query(query)
    finally:
        application.close()


def test_history_read_consumes_deadline_before_workflow(tmp_path):
    import time
    from finance_credit_card_runtime.benefits.models import BenefitsConfig
    from finance_credit_card_runtime.benefits.service import RecommendationService
    from finance_credit_card_runtime.source_port import ServiceConfig

    class ForbiddenRuntime:
        async def run(self, *args, **kwargs):
            raise AssertionError(
                "workflow must not start after synchronous history used deadline"
            )

    service = RecommendationService(
        root=tmp_path / "history.sqlite3",
        get_view=lambda _: snapshot(),
        config=BenefitsConfig(),
        model_config=ServiceConfig(),
        ports={},
        runtime=ForbiddenRuntime(),
    )
    original = service._history

    def slow_history(*args, **kwargs):
        time.sleep(0.04)
        return original(*args, **kwargs)

    service._history = slow_history
    try:
        row = service.submit(
            "journey-1",
            RecommendationRequest(operation_key="history-budget", conversation=True),
            seconds=0.01,
        )
        service.pool.shutdown(wait=True)
        row = service.show("journey-1", row["run_id"])
        assert row["status"] == "failed"
        assert row["result"]["code"] == "recommendation-timeout"
    finally:
        service.close()


@pytest.mark.parametrize('repair',[False,True])
def test_native_credit_react_owns_loop_and_missing_forecast_is_degraded(repair):
    import asyncio
    from finance_credit_card_runtime.credit import prepare_credit
    from finance_credit_card_runtime.source_port import ServiceConfig

    class Model:
        def __init__(self):
            self.calls = 0
            self.tools = []

        async def invoke(self, messages, *, tools=None, **kwargs):
            from openjiuwen.core.foundation.llm import AssistantMessage
            from openjiuwen.core.foundation.llm.schema.tool_call import ToolCall

            self.calls += 1
            if tools:
                self.tools = [
                    t.name if hasattr(t, "name") else t["function"]["name"]
                    for t in tools
                ]
            if self.calls < 3:
                name = "customer_context" if self.calls == 1 else "forecast_receipt"
                return AssistantMessage(
                    content="",
                    tool_calls=[
                        ToolCall(
                            id="call-" + str(self.calls),
                            type="function",
                            name=name,
                            arguments="{}",
                        )
                    ],
                )
            return AssistantMessage(
                content=json.dumps(
                    {
                        "status": "degraded",
                        "intent_changes": [
                            {
                                "topic": "forecast",
                                "state": "confirmed",
                                "origin": "manager-request",
                                "quote": "未出现的客户原话" if repair and self.calls==3 else "检查未来7天额度风险",
                                "message_index": 0,
                            }
                        ],
                    }
                )
            )

    model = Model()
    result = asyncio.run(
        prepare_credit(
            get_current=snapshot,
            messages=["检查未来7天额度风险"],
            config=ServiceConfig(),
            receipt=predict(snapshot(), None),
            model=model,
        )
    )
    assert result["status"] == "degraded"
    assert result["plan"]["gaps"][0]["topic"] == "forecast"
    assert result["plan"]["customer_authorization_status"] == "not-collected"
    assert result["execution"]["framework"] == "openjiuwen.ReActAgent"
    assert result["execution"]["budget"]["agent_execution"] == "executed"
    assert [c["kind"] for c in result["execution"]["calls"]] == [
        "model",
        "tool",
        "model",
        "tool",
        "model",
    ] + (['model'] if repair else [])
    assert model.calls == 3 + int(repair)
    assert set(model.tools) == {
        "customer_context",
        "available_benefits",
        "service_options",
        "forecast_receipt",
    }


def test_credit_durable_idempotency_parent_and_actor_isolation(tmp_path, monkeypatch):
    from finance_credit_card_runtime.server import Query, RuntimeApplication
    from finance_credit_card_runtime.source_port import ServiceConfig
    from finance_credit_card_runtime.benefits.models import BenefitsConfig
    import finance_credit_card_runtime.server as server_module

    view = snapshot()
    current = [view]
    calls = []
    original = server_module.predict

    def counted(*args, **kwargs):
        calls.append(1)
        return original(*args, **kwargs)

    monkeypatch.setattr(server_module, "predict", counted)

    class Source:
        def read(self, binding):
            return current[0]

    binding = SourceBinding(
        tenant_id="tenant-1",
        principal_id="manager-1",
        customer_id="customer-1",
        case_id="case-1",
        observed_at=view.case.observed_at,
        visible_source_ids=("ledger", "benefits"),
        snapshot_digest=digest(view),
    )
    query = Query(
        capability_id="credit.card.limit-risk.query",
        binding=binding,
        session_ref="session-1",
        operation_key="op-1",
        goal="检查额度",
        remaining_ms=3000,
    )

    def application():
        return RuntimeApplication(
            source=Source(),
            state_root=tmp_path,
            model_config=ServiceConfig(),
            benefits_config=BenefitsConfig(),
            ports={},
        )

    app = application()
    first = app.query(query)
    replay = app.query(query.model_copy(update={"remaining_ms": 2500}))
    assert replay == first | {"cache_hit": True, "new_model_calls": 0}
    assert len(calls) == 1
    app.close()
    app = application()
    try:
        assert app.query(query) == first | {"cache_hit": True, "new_model_calls": 0}
        assert len(calls) == 1
        interrupted = query.model_copy(
            update={
                "operation_key": "interrupted-operation",
                "session_ref": "interrupted-session",
            }
        )
        scope, fingerprint, run_id = app._credit_identity(interrupted)
        app._credit_store.begin(scope, interrupted, fingerprint, run_id)
        app.close()
        app = application()
        recovered = app.query(interrupted)
        assert (
            recovered["status"] == "failed"
            and recovered["code"] == "runtime-interrupted"
        )
        assert (
            recovered["customer_id"] == "customer-1" and recovered["run_id"] == run_id
        )
        assert (
            recovered["manager_preparation"]["execution"]["audit_completeness"]
            == "UNKNOWN"
        )
        with pytest.raises(BusinessError, match="operation-key-input-conflict"):
            app.query(query.model_copy(update={"goal": "另一个问题"}))
        second = app.query(
            query.model_copy(
                update={"operation_key": "op-2", "parent_run_id": first["run_id"]}
            )
        )
        assert second["run_id"] != first["run_id"] and len(calls) == 2
        with pytest.raises(BusinessError, match="credit-parent-not-current"):
            app.query(
                query.model_copy(
                    update={"operation_key": "op-3", "parent_run_id": first["run_id"]}
                )
            )
        other = query.model_copy(
            update={"binding": binding.model_copy(update={"principal_id": "manager-2"})}
        )
        other_result = app.query(other)
        assert other_result["run_id"] != first["run_id"] and len(calls) == 3
        with pytest.raises(BusinessError, match="credit-run-not-found"):
            app.show(other, first["run_id"])
        current[0] = view.model_copy(update={"revision": 1})
        with pytest.raises(BusinessError, match="source-snapshot-changed"):
            app.query(query)
    finally:
        app.close()


def test_uninstalled_capability_is_denied_before_reading_source(tmp_path):
    from finance_credit_card_runtime.server import Query, RuntimeApplication
    from finance_credit_card_runtime.source_port import ServiceConfig
    from finance_credit_card_runtime.benefits.models import BenefitsConfig

    class Source:
        def read(self, binding):
            raise AssertionError("uninstalled capability must make zero source calls")

    view = snapshot()
    binding = SourceBinding(
        tenant_id="tenant-1",
        principal_id="manager-1",
        customer_id="customer-1",
        case_id="case-1",
        observed_at=view.case.observed_at,
        visible_source_ids=("ledger", "benefits"),
        snapshot_digest=digest(view),
    )
    query = Query(
        capability_id="credit.card.limit-risk.query",
        binding=binding,
        session_ref="session-1",
        operation_key="op-1",
        goal="检查额度",
        remaining_ms=3000,
    )
    app = RuntimeApplication(
        source=Source(),
        state_root=tmp_path,
        model_config=ServiceConfig(),
        benefits_config=BenefitsConfig(),
        ports={},
        allowed_capabilities={"credit.card.benefits.query"},
    )
    try:
        with pytest.raises(BusinessError, match="capability-not-installed"):
            app.query(query)
        with pytest.raises(BusinessError, match="capability-not-installed"):
            app.show(query, "arbitrary-run")
    finally:
        app.close()


def test_credit_cancelled_terminal_cannot_be_overwritten_by_late_prediction(
    tmp_path, monkeypatch
):
    from concurrent.futures import ThreadPoolExecutor
    from threading import Event
    import finance_credit_card_runtime.server as server_module
    from finance_credit_card_runtime.server import Query, RuntimeApplication
    from finance_credit_card_runtime.source_port import ServiceConfig
    from finance_credit_card_runtime.benefits.models import BenefitsConfig

    entered, release = Event(), Event()
    original = server_module.predict

    def slow_prediction(*args, **kwargs):
        entered.set()
        assert release.wait(3)
        return original(*args, **kwargs)

    monkeypatch.setattr(server_module, "predict", slow_prediction)
    view = snapshot()

    class Source:
        def read(self, binding):
            return view

    binding = SourceBinding(
        tenant_id="tenant-1",
        principal_id="manager-1",
        customer_id="customer-1",
        case_id="case-1",
        observed_at=view.case.observed_at,
        visible_source_ids=("ledger", "benefits"),
        snapshot_digest=digest(view),
    )
    query = Query(
        capability_id="credit.card.limit-risk.query",
        binding=binding,
        session_ref="session-1",
        operation_key="cancel-op",
        goal="检查额度",
        remaining_ms=3000,
    )
    app = RuntimeApplication(
        source=Source(),
        state_root=tmp_path,
        model_config=ServiceConfig(),
        benefits_config=BenefitsConfig(),
        ports={},
    )
    try:
        with ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(app.query, query)
            assert entered.wait(2)
            cancelled = app.cancel(query)
            assert cancelled["status"] == "cancelled"
            release.set()
            late = future.result(timeout=3)
            assert late["status"] == "cancelled"
            assert "account" not in late and "receipt" not in late
            assert app.query(query)["status"] == "cancelled"
            assert app.show(query, cancelled["run_id"])["status"] == "cancelled"
    finally:
        release.set()
        app.close()
