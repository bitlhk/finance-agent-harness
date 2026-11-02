import asyncio
import json

import pytest

from finance_credit_card_runtime.benefits.context import build_context
from finance_credit_card_runtime.benefits.models import BenefitsConfig, RecommendationRequest
from finance_credit_card_runtime.benefits.runtime import RecommendationWorkflow
from finance_credit_card_runtime.benefits.service import RecommendationService
from finance_credit_card_runtime.contracts import BusinessError, digest
from finance_credit_card_runtime.source_port import ServiceConfig


async def ranking(context):
    item = context.candidates[0]
    return {"items":[{"benefit_id":item.benefit.benefit_id,"reason":"与本轮需求匹配",
                       "evidence_refs":[item.reference.ref_id]}]}, {"model":"probe-ranker"}, []


def test_language_model_history_uses_exact_yuan_without_mutating_source():
    from finance_credit_card_runtime.benefits.context import model_history
    source = [{'record_id':'record-1','amount_minor':1461073},
        {'record_id':'record-2','amount_minor':None},{'record_id':'record-3','value':1}]
    actual = model_history(source)
    assert actual[0]['amount_cny']=='14610.73' and actual[0]['amount_unit']=='CNY yuan'
    assert actual[1]['amount_cny'] is None
    assert 'amount_minor' not in actual[0] and 'amount_cny' not in actual[2]
    assert source[0]['amount_minor']==1461073
    with pytest.raises(BusinessError,match='invalid-history-amount'):
        model_history([{'amount_minor':True}])


def test_native_dialogue_clarifies_without_ranking(view):
    context = build_context(view, RecommendationRequest(operation_key='chat-1'))
    calls = []

    async def planner(ctx, history):
        calls.append(history)
        return {"action":"clarify","question":"这次想了解出行还是餐饮权益？"}, {"model":"probe"}, []

    async def forbidden(*_):
        raise AssertionError('clarification must not rank or compose')

    result = asyncio.run(RecommendationWorkflow().run(context,('llm',),
        {'dialogue':planner,'llm':forbidden,'marketing':forbidden}, dialogue_history=[]))
    assert result['dialogue']['action'] == 'clarify'
    assert result['results'] == {}
    assert result['nodes'] == ['authorize','understand','rank','validate','record']
    assert calls == [[]]


def test_native_dialogue_uses_model_resolved_goal_and_selected_engine(view):
    context = build_context(view, RecommendationRequest(operation_key='chat-2'))
    goals = []

    async def planner(ctx, history):
        assert history[0]['message'] == '不考虑机场'
        return {"action":"recommend","goal":"优先餐饮，排除机场相关权益"}, {}, []

    async def port(ctx):
        goals.append(ctx.goal)
        return await ranking(ctx)

    result = asyncio.run(RecommendationWorkflow().run(context,('llm',),
        {'dialogue':planner,'llm':port}, dialogue_history=[{'message':'不考虑机场'}]))
    assert goals == ['优先餐饮，排除机场相关权益']
    assert result['results']['llm']['status'] == 'complete'
    assert result['comparison_fingerprint'] != context.comparison_fingerprint
    assert 'onerec' not in result['results']


def test_dialogue_receipt_survives_bad_planner(view):
    context = build_context(view, RecommendationRequest(operation_key='chat-3'))
    receipts = []

    async def planner(*_):
        error = BusinessError('dialogue-model-unavailable')
        error.calls = [{'kind':'model','status':'failed'}]
        raise error

    with pytest.raises(Exception):
        asyncio.run(RecommendationWorkflow().run(context,('llm',),{'dialogue':planner},
            dialogue_history=[],dialogue_observer=receipts.append))
    assert receipts[0]['calls'][0]['status'] == 'failed'


def test_multi_turn_persistence_foreign_parent_and_source_redaction(view,tmp_path):
    current = [view]
    histories = []

    async def planner(context, history):
        histories.append(history)
        if not history:
            return {'action':'clarify','question':'请问这次是出行还是餐饮？'}, {}, []
        return {'action':'recommend','goal':'按本轮出行需求选择权益'}, {}, []

    service = RecommendationService(root=tmp_path/'chat.sqlite',get_view=lambda _:current[0],
        config=BenefitsConfig(enabled=True),model_config=ServiceConfig(),
        ports={'dialogue':planner,'llm':ranking})
    try:
        first = service.submit('journey-1',RecommendationRequest(operation_key='chat-first',
            goal='你好',conversation=True))
        service.pool.submit(lambda:None).result(timeout=10)
        first = service.show('journey-1',first['run_id'])
        assert first['result']['dialogue']['action'] == 'clarify'
        request = RecommendationRequest(operation_key='chat-next',goal='出行',conversation=True,
                                        parent_run_id=first['run_id'])
        with pytest.raises(BusinessError,match='not-found'):
            service.submit('another-journey',request)
        second = service.submit('journey-1',request)
        service.pool.submit(lambda:None).result(timeout=10)
        second = service.show('journey-1',second['run_id'])
        assert len(second['conversation_turns']) == 2
        assert histories[-1][0]['question'] == '请问这次是出行还是餐饮？'
        assert second['result']['results']['llm']['status'] == 'complete'
        assert service.submit('journey-1',request)['run_id'] == second['run_id']
        current[0] = view.model_copy(update={'revision':view.revision+1})
        redacted = service.show('journey-1',second['run_id'])
        assert redacted['result'] == {}
        assert all(row['result'] == {} and row['stale'] for row in redacted['conversation_turns'])
    finally:
        service.pool.shutdown(wait=True)


def test_legacy_operation_fingerprint_unchanged(view,tmp_path):
    from finance_credit_card_runtime.benefits.store import RecommendationStore

    store=RecommendationStore(tmp_path/'legacy.sqlite')
    request=RecommendationRequest(operation_key='old-operation')
    context=build_context(view,request)
    run,_=store.create('journey-1',request,context)
    old={'request':{'operation_key':'old-operation','engines':['llm'],'top_k':3,
                    'goal':request.goal},'context':context.model_dump(mode='json')}
    row=store.get('journey-1',run)
    assert digest(row['payload']) == digest(old)
    assert 'conversation' not in json.dumps(row['payload']['request'])


def test_inflight_parent_rejected_and_cancelled_chain_remains_bound(view,tmp_path):
    from finance_credit_card_runtime.benefits.store import RecommendationStore

    store=RecommendationStore(tmp_path/'runs.sqlite')
    request=RecommendationRequest(operation_key='parent',conversation=True)
    context=build_context(view,request)
    run,_=store.create('journey-1',request,context)
    service=RecommendationService(root=tmp_path/'other.sqlite',get_view=lambda _:view,
        config=BenefitsConfig(enabled=True),model_config=ServiceConfig(),ports={})
    service.store=store
    try:
        with pytest.raises(BusinessError,match='parent-running'):
            service.context('journey-1',request.model_copy(update={'parent_run_id':run}))
        store.cancel('journey-1',run)
        assert service._history('journey-1',run)[0]['message'] == request.goal
    finally:
        service.close()


def test_persisted_recommendation_identity_is_available_for_followup_only_while_current(view,tmp_path):
    from finance_credit_card_runtime.benefits.models import Ranking
    from finance_credit_card_runtime.benefits.validation import validate_ranking

    current = [view]
    service = RecommendationService(root=tmp_path/'reference-history.sqlite',get_view=lambda _:current[0],
        config=BenefitsConfig(enabled=True),model_config=ServiceConfig(),ports={})
    try:
        request = RecommendationRequest(operation_key='history-first',conversation=True,goal='推荐合适权益')
        context = service.context('journey',request)
        candidate = context.candidates[0]
        ranking = validate_ranking(context,Ranking(items=[{
            'benefit_id':candidate.benefit.benefit_id,'reason':'probe',
            'evidence_refs':[candidate.reference.ref_id]}]),engine='llm',elapsed_ms=1,provenance={})
        parent,_ = service.store.create('journey',request,context)
        assert service.store.transition('journey',parent,'queued','running')
        assert service.store.transition('journey',parent,'running','complete',{
            'dialogue':{'action':'recommend','goal':'优先餐饮且排除机场',
                        'excluded_benefit_ids':['benefit-excluded']},
            'results':{'llm':ranking.model_dump(mode='json')}})
        first = service._history('journey',parent)[0]
        assert first['previous_result_available']
        assert first['resolved_goal'] == '优先餐饮且排除机场'
        assert first['excluded_benefit_ids'] == ['benefit-excluded']
        assert first['recommendations'][0]['items'][0] == {
            'benefit_id':candidate.benefit.benefit_id,'title':candidate.benefit.title,
            'benefit':candidate.benefit.model_dump(mode='json'),
            'reference':candidate.reference.model_dump(mode='json')}
        # "第一项换一个" can now be resolved from persisted, ordered identities.
        followup = RecommendationRequest(operation_key='history-next',conversation=True,
            parent_run_id=parent,goal='第一项换一个')
        service.context('journey',followup)
        for changed in (
            view.model_copy(update={'revision':view.revision+1}),
            view.model_copy(update={'case':view.case.model_copy(update={'visible_sources':('ledger',)})}),
        ):
            current[0] = changed
            redacted = service._history('journey',parent)[0]
            assert not redacted['previous_result_available']
            assert redacted['recommendations'] == [] and redacted['resolved_goal'] == ''
            assert redacted['excluded_benefit_ids'] == []
            assert redacted['message'] == request.goal
    finally:
        service.close()

@pytest.mark.parametrize('engine', ['onerec', 'llm'])
def test_native_dialogue_exclusion_filters_engine_input_and_returned_items(view, engine):
    """Explicit exclusions constrain even an engine that returns a forbidden ID."""
    excluded = view.benefits[0]
    other = excluded.model_copy(update={'benefit_id':'benefit-other','title':'其他餐饮权益'})
    view = view.model_copy(update={'benefits':(excluded,other)})
    context = build_context(view, RecommendationRequest(operation_key='exclude-input'))
    calls = []

    async def planner(ctx, history):
        return {'action':'recommend','goal':'排除轻食沙拉，推荐其他权益',
                'excluded_benefit_ids':[excluded.benefit_id]}, {}, []

    async def port(ctx):
        calls.append([x.benefit.benefit_id for x in ctx.candidates])
        return {'items':[
            {'benefit_id':excluded.benefit_id,'reason':'engine ignored exclusion',
             'evidence_refs':[context.candidates[0].reference.ref_id]},
            {'benefit_id':other.benefit_id,'reason':'合适替代项',
             'evidence_refs':[ctx.candidates[0].reference.ref_id]},
        ]}, {}, []

    result = asyncio.run(RecommendationWorkflow().run(context,(engine,),
        {'dialogue':planner,engine:port},dialogue_history=[]))
    assert calls == [[other.benefit_id]]
    assert [x['benefit']['benefit_id'] for x in result['results'][engine]['items']] == [other.benefit_id]
    assert result['results'][engine]['rejected'][0]['benefit_id'] == excluded.benefit_id
    assert result['dialogue']['excluded_benefit_ids'] == [excluded.benefit_id]
    assert result['comparison_fingerprint'] != context.comparison_fingerprint


def test_native_dialogue_excluding_all_candidates_skips_engine(view):
    context = build_context(view, RecommendationRequest(operation_key='exclude-all'))

    async def planner(*_):
        return {'action':'recommend','goal':'这些都不适合',
                'excluded_benefit_ids':[x.benefit.benefit_id for x in context.candidates]}, {}, []

    async def forbidden(*_):
        raise AssertionError('empty candidate pool must not call model')

    result = asyncio.run(RecommendationWorkflow().run(context,('onerec',),
        {'dialogue':planner,'onerec':forbidden},dialogue_history=[]))
    assert result['results']['onerec']['status'] == 'no-candidates'


def test_native_dialogue_unknown_exclusion_fails_before_engine(view):
    context = build_context(view, RecommendationRequest(operation_key='exclude-unknown'))
    called = []

    async def planner(*_):
        return {'action':'recommend','goal':'推荐其他权益','excluded_benefit_ids':['unknown']}, {}, []

    async def forbidden(*_):
        called.append(True)
        raise AssertionError('invalid exclusion must stop before ranking')

    with pytest.raises(Exception):
        asyncio.run(RecommendationWorkflow().run(context,('onerec',),
            {'dialogue':planner,'onerec':forbidden},dialogue_history=[]))
    assert not called


@pytest.fixture
def view():
    from datetime import datetime,timedelta,timezone
    from finance_credit_card_runtime.contracts import RuntimeView
    now=datetime(2026,4,1,tzinfo=timezone.utc)
    return RuntimeView.model_validate(dict(dataset_version='a'*64,case=dict(case_id='case-1',customer_id='customer-1',account_id='account-1',group_id='group-1',observed_at=now),customer=dict(customer_id='customer-1',group_id='group-1',name='合成客户',tier=6),account=dict(account_id='account-1',customer_id='customer-1',card_id='card-1',group_id='group-1',available_minor=10000),facts=[],benefits=[dict(benefit_id='benefit-1',title='合成权益',known_at=now,valid_from=now-timedelta(days=1),valid_until=now+timedelta(days=10),minimum_tier=5,remaining=2)]))

