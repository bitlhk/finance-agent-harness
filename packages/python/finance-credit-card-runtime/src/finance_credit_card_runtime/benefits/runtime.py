"""原生 WorkflowAgent 拥有推荐节点执行顺序，不另写 Agent 调度循环。"""

import asyncio
import json
import time
import uuid

from ..contracts import BusinessError, digest
from .models import EngineResult, ModelChoices, Ranking
from .validation import bind_choices, validate_ranking


class RecommendationWorkflow:
    async def run(self, context, engines, ports, *, seconds=120, cancelled=lambda: False, observer=None,
                  include_marketing=False, marketing_observer=None, source_check=None,
                  dialogue_history=None, dialogue_observer=None):
        from openjiuwen.core.application.workflow_agent import WorkflowAgent, WorkflowAgentConfig
        from openjiuwen.core.runner import Runner
        from openjiuwen.core.workflow import Workflow, WorkflowCard, WorkflowComponent

        identifier = "benefits-" + uuid.uuid4().hex
        trace, results = [], {}
        marketing = {}
        dialogue = {}
        deadline = time.monotonic() + seconds

        def check():
            if cancelled():
                raise BusinessError("recommendation-cancelled")
            if time.monotonic() >= deadline:
                raise BusinessError("recommendation-timeout")
            if source_check:
                source_check()
            if time.monotonic() >= deadline:
                raise BusinessError("recommendation-timeout")

        async def authorize():
            check()
            if any(x.reference.customer_id != context.customer_id for x in context.candidates):
                raise BusinessError("recommendation-subject-mismatch")

        async def understand():
            nonlocal context
            check()
            if not context.candidates:
                dialogue.update(action="recommend", goal=context.goal)
                return
            planner = ports.get("dialogue")
            if planner is None:
                raise BusinessError("dialogue-model-not-configured")
            value = {"status":"failed", "calls":[]}
            started = time.monotonic()
            try:
                decision, info, calls = await asyncio.wait_for(
                    planner(context, dialogue_history), max(.01, deadline-time.monotonic()))
                from .dialogue import DialogueDecision

                decision = DialogueDecision.model_validate(decision)
                value.update(status="complete", provenance=info, calls=calls)
                check()
                if decision.action == "recommend":
                    excluded = set(decision.excluded_benefit_ids)
                    authorized = {x.benefit.benefit_id for x in context.candidates}
                    if not excluded <= authorized:
                        raise BusinessError("dialogue-exclusion-invalid")
                    context = context.model_copy(update={"goal":decision.goal,
                        "candidates":tuple(x for x in context.candidates
                                           if x.benefit.benefit_id not in excluded),
                        "comparison_fingerprint":digest({"request":context.comparison_fingerprint,
                            "resolved_goal":decision.goal,
                            "excluded_benefit_ids":sorted(excluded)})})
                dialogue.update(decision.model_dump(mode="json"))
            except BaseException as exc:
                value['status'] = 'cancelled' if cancelled() else 'failed'
                value["calls"] = getattr(exc, "calls", value['calls']) or [{
                    "kind":"engine-attempt", "stage":"dialogue", "status":value['status'],
                    "elapsed_ms":(time.monotonic()-started)*1000}]
                raise
            finally:
                if dialogue_observer:
                    dialogue_observer(value)

        async def rank():
            check()
            if dialogue.get("action") == "clarify":
                return

            async def invoke(engine):
                started = time.monotonic()
                calls, provenance = [], {"framework": "openjiuwen.WorkflowAgent"}
                if not context.candidates:
                    results[engine] = EngineResult(engine=engine, status="no-candidates",
                        elapsed_ms=0, provenance=provenance)
                    return
                try:
                    port = ports.get(engine)
                    if port is None:
                        raise BusinessError("recommendation-engine-not-configured")
                    raw, info, calls = await asyncio.wait_for(
                        port(context), timeout=max(0.01, deadline-time.monotonic()))
                    provenance.update(info)
                    check()
                    ranking = Ranking.model_validate(raw)
                    verified = validate_ranking(context, ranking, engine=engine,
                        elapsed_ms=(time.monotonic()-started)*1000, provenance=provenance,
                        calls=tuple(calls))
                    check()
                    results[engine] = verified
                except asyncio.CancelledError:
                    raise
                except Exception as exc:
                    calls = getattr(exc, "calls", calls)
                    code = exc.code if isinstance(exc, BusinessError) else (
                        "recommendation-timeout" if isinstance(exc, TimeoutError) else "invalid-model-response")
                    results[engine] = EngineResult(engine=engine, status="failed", code=code,
                        elapsed_ms=(time.monotonic()-started)*1000, provenance=provenance,
                        calls=tuple(calls or [{"kind":"engine-attempt","name":engine,"status":"failed","code":code}]))
                finally:
                    if observer and engine in results:
                        observer(results[engine])
            await asyncio.gather(*(invoke(engine) for engine in engines))

        async def validate():
            check()
            if dialogue.get("action") == "clarify":
                return
            if set(results) != set(engines):
                raise BusinessError("incomplete-engine-receipts")

        async def compose():
            check()
            if dialogue.get("action") == "clarify":
                return
            from .marketing import bind_marketing

            ranked = results.get("onerec")
            if ranked is None or ranked.status != "complete" or not ranked.items:
                marketing.update(status="blocked", code="onerec-ranking-unavailable", calls=[])
                if marketing_observer:
                    marketing_observer(marketing)
                return
            item = ranked.items[0]
            calls, info = [], {}
            started = time.monotonic()
            attempted = False
            try:
                writer = ports.get("marketing")
                if writer is None:
                    raise BusinessError("marketing-model-not-configured")
                attempted = True
                raw, info, calls = await asyncio.wait_for(writer(context, item),
                    timeout=max(.01, deadline-time.monotonic()))
                check()
                verified_copy = bind_marketing(context, item, raw, provenance=info, calls=calls)
                check()
                marketing.update(verified_copy)
            except asyncio.CancelledError:
                interrupted = "cancelled" if cancelled() else "timeout"
                marketing.update(status="cancelled" if cancelled() else "failed", code="marketing-"+interrupted, calls=calls or ([
                    {"kind":"engine-attempt", "stage":"marketing", "status":interrupted,
                     "elapsed_ms":(time.monotonic()-started)*1000}] if attempted else []))
                raise
            except Exception as error:
                calls = getattr(error, "calls", calls)
                code = error.code if isinstance(error, BusinessError) else "marketing-generation-failed"
                if not calls and attempted:
                    calls = [{"kind":"engine-attempt", "stage":"marketing", "status":"failed",
                              "code":code, "elapsed_ms":(time.monotonic()-started)*1000}]
                marketing.update(status="failed", code=code, benefit_id=item.benefit.benefit_id,
                                 rank=1, calls=calls, provenance=info, review_required=True, sent=False)
                if code in ("recommendation-cancelled", "recommendation-timeout"):
                    raise
            finally:
                if marketing_observer:
                    marketing_observer(marketing)

        async def finish():
            check()

        class Node(WorkflowComponent):
            def __init__(self, name, action):
                super().__init__()
                self.name, self.action = name, action

            async def invoke(self, inputs, session, model_context):
                await self.action()
                trace.append(self.name)
                return {"node": self.name, "status": "complete"}

        workflow = Workflow(card=WorkflowCard(id=identifier, name="benefits_recommendation", version="1"))
        workflow.set_start_comp("authorize", Node("authorize", authorize))
        workflow.add_workflow_comp("rank", Node("rank", rank))
        workflow.add_workflow_comp("validate", Node("validate", validate))
        workflow.set_end_comp("record", Node("record", finish))
        if dialogue_history is not None:
            workflow.add_workflow_comp("understand", Node("understand", understand))
            workflow.add_connection("authorize", "understand")
            workflow.add_connection("understand", "rank")
        else:
            workflow.add_connection("authorize", "rank")
        workflow.add_connection("rank", "validate")
        if include_marketing:
            workflow.add_workflow_comp("compose", Node("compose", compose))
            workflow.add_connection("validate", "compose")
            workflow.add_connection("compose", "record")
        else:
            workflow.add_connection("validate", "record")
        agent = WorkflowAgent(WorkflowAgentConfig(id=identifier, version="1", description="合成权益推荐工作流"))
        agent.add_workflows([workflow])
        from openjiuwen.core.context_engine import TokenCounter
        from openjiuwen.core.session.agent import create_agent_session
        from openjiuwen.core.single_agent.schema.agent_card import AgentCard

        class LocalCounter(TokenCounter):
            def count(self, text, **kwargs):
                return len(text.encode("utf-8"))

            def count_messages(self, messages, **kwargs):
                return sum(self.count(m.model_dump_json()) for m in messages)

            def count_tools(self, tool_infos, **kwargs):
                return sum(self.count(t.model_dump_json()) for t in tool_infos)

        session = create_agent_session(session_id=identifier, card=AgentCard(id=identifier))

        def output():
            return {"framework":"openjiuwen.WorkflowAgent", "nodes":list(trace),
                    "results":{key:value.model_dump(mode="json") for key,value in results.items()},
                    **({"dialogue":dict(dialogue), "comparison_fingerprint":context.comparison_fingerprint}
                       if dialogue_history is not None else {}),
                    **({"marketing":dict(marketing)} if include_marketing else {})}

        try:
            # Seed the public context pool with a local counter. Legacy WorkflowAgent
            # owns its invocation session; passing an external session unwraps it
            # incorrectly in SDK 0.1.16. Its same-ID session retrieves this context.
            await agent.context_engine.create_context(session=session, token_counter=LocalCounter())
            await asyncio.wait_for(agent.invoke({"query":"执行当前授权推荐", "conversation_id":identifier}),
                                   timeout=max(.01, deadline-time.monotonic()))
            expected = ["authorize"]+(["understand"] if dialogue_history is not None else [])+["rank", "validate"]+(["compose"] if include_marketing else [])+["record"]
            if trace != expected:
                raise BusinessError("native-workflow-incomplete")
            return output()
        except Exception:
            ranked = results.get("onerec")
            if include_marketing and not cancelled() and time.monotonic() >= deadline and ranked is not None and ranked.status == "complete" and ranked.items:
                # 只保留deadline前已核验的排名；原生执行被中止，不伪造compose/record已完成。
                for engine in engines:
                    if engine not in results:
                        results[engine] = EngineResult(engine=engine,status="failed",code="recommendation-timeout",
                            elapsed_ms=(time.monotonic()-deadline+seconds)*1000,
                            provenance={"framework":"openjiuwen.WorkflowAgent","not_completed_before_deadline":True})
                        if observer:
                            observer(results[engine])
                prior_calls = marketing.get("calls", [])
                marketing.clear()
                marketing.update(status="failed",code="marketing-timeout",rank=1,
                                 benefit_id=ranked.items[0].benefit.benefit_id,calls=prior_calls,
                                 review_required=True,sent=False)
                if marketing_observer:
                    marketing_observer(marketing)
                return {**output(), "termination":{"status":"partial","code":"deadline-after-verified-ranking",
                                                    "native_interrupted":True}}
            raise
        finally:
            Runner.resource_mgr.remove_workflow(identifier + "_1")
            from openjiuwen.core.session.checkpointer import CheckpointerFactory

            await CheckpointerFactory.get_checkpointer().release(identifier, identifier)


class FlashRanker:
    def __init__(self, config):
        self.config = config

    async def __call__(self, context):
        from openai import AsyncOpenAI
        from .context import model_history

        config = self.config
        payload = context.model_dump(mode='json')
        payload['history'] = model_history(context.history)
        started = time.monotonic()
        async with AsyncOpenAI(api_key=config.credential(), base_url=config.api_base,
                               max_retries=0, timeout=config.model_seconds) as client:
            result = await client.chat.completions.create(model=config.model,
                messages=[{"role":"system","content":"你是客户经理的合成权益推荐组件。只对输入候选排序，不新增权益、办理或客户授权。每项只输出候选 benefit_id 和 reason，最多 top_k 项。不要输出或重写证据定位，服务端会从当前授权候选绑定来源。匹配理由只能依据本轮明确目标、候选用途及输入history中的实际记录；history未提供某种行为时，不宣称客户近期或过去发生过该行为、具有该习惯。历史为空时明确依据本轮目标初筛，不编造消费次数、日期或偏好。理由不复述账务金额、受阻概率或风险等级；这些由服务端事实区展示。未提供权益价格时，不声称费用低、无额外支出或低消费门槛。仅输出JSON，结构为" + json.dumps(ModelChoices.model_json_schema(),ensure_ascii=False)},
                          {"role":"user","content":json.dumps(payload,ensure_ascii=False)}],
                response_format={"type":"json_object"}, max_tokens=config.max_tokens,
                extra_body={"thinking":{"type":"disabled"}})
        content = result.choices[0].message.content
        call = {"kind":"model","provider":config.provider,"model":config.model,"status":"complete",
                "elapsed_ms":(time.monotonic()-started)*1000,
                "usage":result.usage.model_dump(mode="json") if result.usage else None}
        try:
            ranking = bind_choices(context,ModelChoices.model_validate_json(content))
        except Exception:
            error = BusinessError('invalid-model-response')
            error.calls = [call]
            raise error from None
        return ranking.model_dump(mode="json"), {"provider":config.provider,"model":config.model,
            "response_model":result.model,"thinking":"disabled"}, [call]
