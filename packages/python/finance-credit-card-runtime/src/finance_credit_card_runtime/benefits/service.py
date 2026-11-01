"""推荐请求与异步执行；模型不得拥有业务状态写入口。"""

import asyncio
import time
from concurrent.futures import ThreadPoolExecutor

from ..contracts import BusinessError, digest
from .context import build_context
from .dataset import observed_history
from .runtime import FlashRanker, RecommendationWorkflow
from .store import RecommendationStore


class RecommendationService:
    def __init__(self, *, root, get_view, config, model_config, ports=None, runtime=None):
        self.get_view, self.config = get_view, config
        self.store = RecommendationStore(root)
        self.store.recover()
        self.runtime = runtime or RecommendationWorkflow()
        self.pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="benefit-run")
        if ports is None:
            from .local import WorkerRanker

            ports = {"llm":FlashRanker(model_config), "onerec":WorkerRanker(config)}
            from .marketing import FlashCopywriter

            ports["marketing"] = FlashCopywriter(model_config)
            from .dialogue import FlashDialoguePlanner

            ports["dialogue"] = FlashDialoguePlanner(model_config)
        self.ports = ports

    def context(self, journey, request):
        view = self.get_view(journey)
        context = build_context(view,request)
        if self.config.dataset_root:
            context = observed_history(self.config.dataset_root,context)
        if request.conversation:
            history = self._history(journey,request.parent_run_id,current=view)
            context = context.model_copy(update={"comparison_fingerprint":digest({
                "input":context.comparison_fingerprint,"history":history})})
        return context

    def _history(self, journey, parent, *, current=None):
        current = current if current is not None else self.get_view(journey)
        rows, seen = [], set()
        while parent and len(rows) < 19:
            if parent in seen:
                raise BusinessError('conversation-history-invalid')
            seen.add(parent)
            row = self.store.get(journey,parent)
            if row['status'] in ('queued','running'):
                raise BusinessError('conversation-parent-running')
            request = row['payload']['request']
            valid = ('benefits' in current.case.visible_sources
                     and row['payload']['context']['snapshot_digest'] == digest(current))
            result = row['result'] if valid else {}
            prior = result.get('dialogue',{})
            rankings = [{"engine":engine,"items":[{
                "benefit_id":item['benefit']['benefit_id'],"title":item['benefit']['title'],
                "benefit":item['benefit'],"reference":item['reference']}
                for item in ranking.get('items',[])[:5]]}
                for engine,ranking in result.get('results',{}).items()
                if ranking.get('status') == 'complete']
            rows.append({"message":request['goal'],"question":prior.get('question',''),
                         "resolved_goal":prior.get('goal',''),"recommendations":rankings,
                         "excluded_benefit_ids":prior.get('excluded_benefit_ids',[]),
                         "previous_result_available":valid})
            parent = request.get('parent_run_id')
        if parent:
            raise BusinessError('conversation-history-limit')
        return list(reversed(rows))

    def submit(self, journey, request, *, seconds=None):
        deadline = time.monotonic() + min(self.config.seconds,
            self.config.seconds if seconds is None else seconds)
        context = self.context(journey,request)
        run_id, created = self.store.create(journey,request,context)
        if created:
            self.pool.submit(self._execute,journey,run_id,request,context,deadline)
        return self.show(journey,run_id)

    def _execute(self, journey, run_id, request, context, deadline):
        if not self.store.transition(journey,run_id,"queued","running"):
            return
        try:
            options = {}
            if request.conversation:
                options.update(dialogue_history=self._history(journey,request.parent_run_id),
                               dialogue_observer=lambda value:self.store.audit_dialogue(run_id,value))
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise BusinessError('recommendation-timeout')
            result = asyncio.run(self.runtime.run(context,request.engines,self.ports,
                seconds=remaining,
                cancelled=lambda:self.store.get(journey,run_id)["status"] == "cancelled",
                observer=lambda value:self.store.audit(run_id,value),
                include_marketing=request.marketing_copy,
                source_check=lambda:self._check_current(journey,context),
                marketing_observer=lambda value:self.store.audit_marketing(run_id,value), **options))
            states = [row['status'] for row in result['results'].values()]
            status = ("complete" if result.get('dialogue',{}).get('action') == 'clarify'
                else "no-candidates" if states and all(x == "no-candidates" for x in states)
                else "complete" if all(x == "complete" for x in states)
                else "partial" if any(x == "complete" for x in states) else "failed")
            if request.marketing_copy and status == "complete" and result.get('dialogue',{}).get('action') != 'clarify' and result.get("marketing",{}).get("status") != "complete":
                status = "partial"
            if result.get("termination",{}).get("status") == "partial":
                status = "partial"
            self.store.transition(journey,run_id,"running",status,result)
        except Exception as exc:
            code = exc.code if isinstance(exc,BusinessError) else "recommendation-runtime-failed"
            self.store.transition(journey,run_id,"running","failed",{"code":code})

    def _check_current(self, journey, context):
        current = self.get_view(journey)
        if "benefits" not in current.case.visible_sources or digest(current) != context.snapshot_digest:
            raise BusinessError("recommendation-source-changed")
        if self.config.dataset_root:
            from .models import RecommendationRequest
            fresh = observed_history(self.config.dataset_root,build_context(current,RecommendationRequest(
                operation_key='history-recheck',goal=context.goal,top_k=context.top_k)))
            if fresh.history != context.history:
                raise BusinessError('preference-history-changed')

    def show(self, journey, run_id):
        current = self.get_view(journey)
        stored = self.store.get(journey,run_id)
        context = stored['payload']['context']
        stale = context['snapshot_digest'] != digest(current)
        if not stale and self.config.dataset_root:
            from .models import RecommendationContext
            try:
                self._check_current(journey,RecommendationContext.model_validate(context))
            except BusinessError:
                stale = True
        visible = "benefits" in current.case.visible_sources
        return {key:stored[key] for key in ('run_id','journey_id','status','created_at')} | {
            "customer_id":context['customer_id'], "observed_at":context['observed_at'],
            "observed_history_count":len(context.get('history',[])) if visible and not stale else None,
            "comparison_fingerprint":stored['result'].get('comparison_fingerprint',context['comparison_fingerprint']),
            "stale":stale, "code":"source-not-visible" if not visible else "source-changed-recheck" if stale else "",
            "marketing_requested": bool(stored['payload']['request'].get('marketing_copy',False)),
            "result":stored['result'] if visible and not stale else {},
            "engine_receipts":stored['engine_receipts'] if visible else [],
            "request":stored['payload']['request'],
            "conversation_turns":self._turns(journey,run_id,current) if stored['payload']['request'].get('conversation') else [],
        }

    def _turns(self, journey, run_id, current):
        turns, seen = [], set()
        while run_id and len(turns) < 20:
            if run_id in seen:
                raise BusinessError('conversation-history-invalid')
            seen.add(run_id)
            row = self.store.get(journey,run_id)
            request = row['payload']['request']
            stale = row['payload']['context']['snapshot_digest'] != digest(current)
            if not stale and self.config.dataset_root:
                from .models import RecommendationContext
                try:
                    self._check_current(journey,RecommendationContext.model_validate(row['payload']['context']))
                except BusinessError:
                    stale = True
            visible = 'benefits' in current.case.visible_sources
            turns.append({'run_id':run_id,'message':request['goal'],'status':row['status'],
                          'stale':stale or not visible,
                          'result':row['result'] if visible and not stale else {}})
            run_id = request.get('parent_run_id')
        return list(reversed(turns))

    def cancel(self, journey, run_id):
        self.get_view(journey)
        self.store.cancel(journey,run_id)
        return self.show(journey,run_id)

    def close(self):
        self.pool.shutdown(wait=False,cancel_futures=True)
