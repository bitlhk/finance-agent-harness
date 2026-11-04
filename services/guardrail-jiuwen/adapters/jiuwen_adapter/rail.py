# Copyright (c) 2026. All rights reserved.

"""guardrail_jiuwen Rail - JiuwenSwarm 安全护栏 Rail 扩展.

通过 HTTP 调用 guardrail-jiuwen FastAPI 服务，实现输入/输出安全检测。
不修改 jiuwenswarm 代码，仅通过 Rail 扩展机制接入。

部署方式：
  将此文件夹复制到 jiuwenswarm 的 extensions 目录，或在 RailManager 中 import_extension。
"""

from __future__ import annotations

import logging
import os
import time
import traceback
import asyncio
from typing import Any, Dict, List, Optional

import httpx
from openjiuwen.core.single_agent.rail.base import AgentCallbackContext
from openjiuwen.harness.rails.base import DeepAgentRail
from openjiuwen.core.runner.callback.errors import AbortError

logger = logging.getLogger("guardrail_rail")

# 文件写入日志 — 不依赖 logging handler，最可靠地确认方法是否被调用
_DEBUG_LOG = os.path.join(os.path.dirname(__file__), "_rail_debug.log")

def _debug_log(msg: str) -> None:
    try:
        with open(_DEBUG_LOG, "a", encoding="utf-8") as f:
            f.write(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {msg}\n")
    except Exception:
        pass

_GUARDRAIL_URL = os.environ.get("GUARDRAIL_SERVICE_URL", "http://localhost:9876")
_GUARDRAIL_TIMEOUT = float(os.environ.get("GUARDRAIL_TIMEOUT", "35.0"))

# unavailable 时的策略：fail-open（默认放行）或 fail-closed（拦截）
# 设为 "1" / "true" / "yes" 时拦截，其他值放行
_FAIL_CLOSED = os.environ.get("GUARDRAIL_FAIL_CLOSED", "0").lower() in ("1", "true", "yes")

# 是否将 guardrail 标准话术推送给 JiuwenSwarm（作为 AbortError 的 reason）
# 设为 "0" / "false" / "no" 时关闭，使用通用话术
_PUSH_RESPONSE_MESSAGE = os.environ.get("GUARDRAIL_PUSH_RESPONSE_MESSAGE", "1").lower() in ("1", "true", "yes")

# HTTP 临时错误状态码，触发单次重试
_RETRY_STATUS = {429, 502, 503, 504}


def _safe_repr(text: str, max_len: int = 30) -> str:
    """脱敏日志：只记录长度和 hash，不写原文。"""
    if not text:
        return "EMPTY"
    import hashlib
    h = hashlib.md5(text.encode()).hexdigest()[:8]
    return f"len={len(text)},hash={h}"


async def _post_with_retry(client: httpx.AsyncClient, url: str, json_payload: dict) -> httpx.Response:
    """发送 POST 请求，遇到临时错误时重试一次。"""
    try:
        resp = await client.post(url, json=json_payload)
        if resp.status_code in _RETRY_STATUS:
            logger.warning(f"[guardrail_rail] Retryable status {resp.status_code}, retrying once...")
            await asyncio.sleep(0.5)
            resp = await client.post(url, json=json_payload)
        return resp
    except (httpx.ConnectError, httpx.ReadTimeout, httpx.WriteTimeout) as e:
        logger.warning(f"[guardrail_rail] Transient error {type(e).__name__}, retrying once...")
        await asyncio.sleep(0.5)
        return await client.post(url, json=json_payload)


class CustomRail(DeepAgentRail):
    """guardrail_jiuwen 安全护栏 Rail.

    在 before_model_call 阶段检测用户输入安全性。
    在 after_model_call 阶段检测 LLM 输出安全性。

    若检测结果为 block，抛出 RuntimeError 中止请求。
    若检测结果为 unavailable，记录日志但不中止（降级放行）。
    """

    priority: int = 90  # 高优先级，在多数 Rail 之前执行

    def __init__(self) -> None:
        super().__init__()
        self._client: Optional[httpx.AsyncClient] = None
        _debug_log("CustomRail.__init__ called")

    def init(self, agent) -> None:
        _debug_log(f"CustomRail.init called, agent={type(agent).__name__}")
        super().init(agent) if hasattr(super(), "init") else None

    def get_callbacks(self) -> Dict[Any, Any]:
        cbs = super().get_callbacks()
        _debug_log(f"CustomRail.get_callbacks called, returned keys={[str(k) for k in cbs.keys()]}")
        return cbs

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=_GUARDRAIL_URL,
                timeout=_GUARDRAIL_TIMEOUT,
            )
        return self._client

    async def before_model_call(self, ctx: AgentCallbackContext) -> None:
        """输入安全检测：在 LLM 调用前拦截有害输入。"""
        _debug_log("before_model_call ENTERED")
        user_input = self._extract_user_input(ctx)
        _debug_log(f"before_model_call: extracted user_input={_safe_repr(user_input)}")
        # DEBUG: 诊断 Rail 是否被调用
        logger.warning(f"[guardrail_rail] before_model_call called, user_input={_safe_repr(user_input)}")
        if not user_input:
            # 兜底：尝试从多种路径提取
            user_input = self._fallback_extract_input(ctx)
            logger.warning(f"[guardrail_rail] fallback extract result: {_safe_repr(user_input)}")
        if not user_input:
            return

        # 提取对话历史，传递给检测服务提供上下文（P1-2 改进）
        history = self._extract_history(ctx)

        try:
            client = await self._get_client()
            _debug_log(f"before_model_call: POST to {_GUARDRAIL_URL}/v1/rails/input, text={_safe_repr(user_input)}")
            resp = await _post_with_retry(
                client,
                "/v1/rails/input",
                {"text": user_input, "history": history},
            )
            resp.raise_for_status()
            data = resp.json()
            _debug_log(f"before_model_call: guardrail response decision={data.get('decision')}, reason={data.get('reason_code')}")

            # 严格决策解析：未知值默认为 unknown（不静默放行）
            decision = data.get("decision", "unknown")
            if decision == "block":
                msg = data.get("response_message", "") if _PUSH_RESPONSE_MESSAGE else "输入安全检测拦截"
                _debug_log(f"before_model_call: BLOCKING input, msg={msg}")
                logger.warning(
                    f"[guardrail_rail] Input blocked: {data.get('reason_code')}, "
                    f"rules={data.get('violated_rules')}, msg={msg}"
                )
                raise AbortError(f"[guardrail] {msg}")

            if decision == "unavailable":
                msg = data.get("reason_message", "安全服务暂时不可用")
                logger.warning(
                    f"[guardrail_rail] Input check unavailable, degrading: {msg}"
                )
                # fail-open（默认放行）或 fail-closed（拦截），通过环境变量配置
                if _FAIL_CLOSED:
                    raise AbortError(f"[guardrail] 安全服务暂时不可用，请稍后重试")

            elif decision == "review":
                # review 表示不确定，fail-open 放行但记录告警
                logger.warning(
                    f"[guardrail_rail] Input review (ambiguous): {data.get('reason_code')}"
                )
                if _FAIL_CLOSED:
                    raise AbortError(f"[guardrail] 输入安全检测未通过，请稍后重试")

            elif decision != "allow":
                # 未知决策值
                logger.warning(
                    f"[guardrail_rail] Unknown decision='{decision}' from guardrail service"
                )
                if _FAIL_CLOSED:
                    raise AbortError(f"[guardrail] 安全服务返回未知状态，请稍后重试")

            logger.debug(f"[guardrail_rail] Input check passed: {decision}")

            # 如果 guardrail 返回了脱敏文本，回写到 ctx 替换用户输入
            processed_text = data.get("processed_text")
            if processed_text and processed_text != user_input:
                self._rewrite_user_input(ctx, processed_text)
                logger.info(f"[guardrail_rail] Input text desensitized: {_safe_repr(user_input)} -> {_safe_repr(processed_text)}")

        except AbortError:
            raise
        except Exception as e:
            _debug_log(f"before_model_call: EXCEPTION {type(e).__name__}: {e}\n{traceback.format_exc()}")
            logger.error(f"[guardrail_rail] Input check error: {e}")
            # 网络异常/HTTP错误/JSON解析失败 遵循同一 fail-open/closed 策略
            if _FAIL_CLOSED:
                raise AbortError(f"[guardrail] 安全服务暂时不可用，请稍后重试")

    async def after_model_call(self, ctx: AgentCallbackContext) -> None:
        """输出安全检测：在 LLM 输出后拦截有害内容。"""
        output_text = self._extract_output_text(ctx)
        if not output_text:
            return

        try:
            client = await self._get_client()
            resp = await _post_with_retry(
                client,
                "/v1/rails/output",
                {"text": output_text},
            )
            resp.raise_for_status()
            data = resp.json()

            # 严格决策解析：未知值默认为 unknown（不静默放行）
            decision = data.get("decision", "unknown")
            if decision == "block":
                msg = data.get("response_message", "") if _PUSH_RESPONSE_MESSAGE else "输出安全检测拦截"
                logger.warning(
                    f"[guardrail_rail] Output blocked: {data.get('reason_code')}, "
                    f"rules={data.get('violated_rules')}, msg={msg}"
                )
                raise AbortError(f"[guardrail] {msg}")

            if decision == "unavailable":
                logger.warning(
                    f"[guardrail_rail] Output check unavailable, degrading: "
                    f"{data.get('reason_message')}"
                )
                # fail-open（默认放行）或 fail-closed（拦截），通过环境变量配置
                if _FAIL_CLOSED:
                    raise AbortError(f"[guardrail] 输出安全服务暂时不可用，请稍后重试")

            elif decision == "review":
                logger.warning(
                    f"[guardrail_rail] Output review (ambiguous): {data.get('reason_code')}"
                )
                if _FAIL_CLOSED:
                    raise AbortError(f"[guardrail] 输出安全检测未通过，请稍后重试")

            elif decision != "allow":
                logger.warning(
                    f"[guardrail_rail] Unknown output decision='{decision}' from guardrail service"
                )
                if _FAIL_CLOSED:
                    raise AbortError(f"[guardrail] 输出安全服务返回未知状态，请稍后重试")

            logger.debug(f"[guardrail_rail] Output check passed: {decision}")

            # detect 模式下 guardrail 返回脱敏文本，替换 LLM 输出
            processed_text = data.get("processed_text")
            if processed_text and processed_text != output_text:
                self._rewrite_output(ctx, processed_text)
                logger.info(f"[guardrail_rail] Output text desensitized: {_safe_repr(output_text)} -> {_safe_repr(processed_text)}")

        except AbortError:
            raise
        except Exception as e:
            logger.error(f"[guardrail_rail] Output check error: {e}")
            # 网络异常/HTTP错误/JSON解析失败 遵循同一 fail-open/closed 策略
            if _FAIL_CLOSED:
                raise AbortError(f"[guardrail] 输出安全服务暂时不可用，请稍后重试")

    def _extract_user_input(self, ctx: AgentCallbackContext) -> str:
        """从 AgentCallbackContext 提取用户输入文本。"""
        inputs = getattr(ctx, "inputs", None)
        if inputs is None:
            return ""
        messages = getattr(inputs, "messages", None) or []
        if not messages:
            return ""
        for msg in reversed(messages):
            role = getattr(msg, "role", None) or (msg.get("role") if isinstance(msg, dict) else None)
            if role == "user":
                content = getattr(msg, "content", None) or (msg.get("content") if isinstance(msg, dict) else "")
                return self._parse_content(content)
        return ""

    @staticmethod
    def _parse_content(content) -> str:
        """从消息 content 提取文本，支持 str / list[str] / list[{type:text,text:...}] 格式。"""
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            parts = []
            for item in content:
                if isinstance(item, str):
                    parts.append(item)
                elif isinstance(item, dict):
                    # 支持 {"type": "text", "text": "..."} 格式
                    if item.get("type") == "text" and item.get("text"):
                        parts.append(item["text"])
                    elif item.get("text"):
                        parts.append(item["text"])
            if parts:
                return " ".join(parts)
        return ""

    def _rewrite_user_input(self, ctx: AgentCallbackContext, processed_text: str) -> None:
        """将脱敏后的文本回写到 ctx 中的用户消息。"""
        inputs = getattr(ctx, "inputs", None)
        if inputs is None:
            return
        # 尝试设置 query 字段（部分 Agent 从 inputs.query 读取用户输入）
        if hasattr(inputs, "query"):
            try:
                inputs.query = processed_text
            except (AttributeError, TypeError):
                pass
        messages = getattr(inputs, "messages", None) or []
        for msg in reversed(messages):
            role = getattr(msg, "role", None) or (msg.get("role") if isinstance(msg, dict) else None)
            if role == "user":
                if isinstance(msg, dict):
                    msg["content"] = processed_text
                else:
                    msg.content = processed_text
                return

    def _rewrite_output(self, ctx: AgentCallbackContext, processed_text: str) -> None:
        """将脱敏后的文本回写到 ctx 中的 LLM 输出（detect 模式）。"""
        inputs = getattr(ctx, "inputs", None)
        if inputs is None:
            return
        response = getattr(inputs, "response", None)
        if response is not None:
            if isinstance(response, dict):
                response["content"] = processed_text
            else:
                response.content = processed_text
            return
        # fallback: 从 messages 中找最后一条 assistant 消息
        messages = getattr(inputs, "messages", None) or []
        for msg in reversed(messages):
            role = getattr(msg, "role", None) or (msg.get("role") if isinstance(msg, dict) else None)
            if role == "assistant":
                if isinstance(msg, dict):
                    msg["content"] = processed_text
                else:
                    msg.content = processed_text
                return

    def _fallback_extract_input(self, ctx: AgentCallbackContext) -> str:
        """兜底提取用户输入：尝试多种路径。"""
        # 1. ctx.inputs.query (InvokeInputs)
        inputs = getattr(ctx, "inputs", None)
        if inputs is not None:
            query = getattr(inputs, "query", None)
            if isinstance(query, str) and query.strip():
                return query
            # 2. ctx.inputs.messages (可能 inputs 是 dict)
            messages = getattr(inputs, "messages", None)
            if not messages and isinstance(inputs, dict):
                messages = inputs.get("messages", [])
            if messages:
                for msg in reversed(messages):
                    role = getattr(msg, "role", None) or (msg.get("role") if isinstance(msg, dict) else None)
                    if role == "user":
                        content = getattr(msg, "content", None) or (msg.get("content") if isinstance(msg, dict) else "")
                        if isinstance(content, str) and content.strip():
                            return content
        # 3. ctx.session 中的消息历史
        session = getattr(ctx, "session", None)
        if session is not None:
            messages = getattr(session, "messages", None) or []
            if messages:
                for msg in reversed(messages):
                    role = getattr(msg, "role", None) or (msg.get("role") if isinstance(msg, dict) else None)
                    if role == "user":
                        content = getattr(msg, "content", None) or (msg.get("content") if isinstance(msg, dict) else "")
                        if isinstance(content, str) and content.strip():
                            return content
        # 4. ctx.extra
        extra = getattr(ctx, "extra", None) or {}
        for key in ("query", "user_input", "text"):
            val = extra.get(key)
            if isinstance(val, str) and val.strip():
                return val
        return ""

    def _extract_output_text(self, ctx: AgentCallbackContext) -> str:
        """从 AgentCallbackContext 提取 LLM 输出文本。

        after_model_call 时 ctx.inputs 是 ModelCallInputs，
        其中 response 是 LLM 返回的 AssistantMessage。
        """
        inputs = getattr(ctx, "inputs", None)
        if inputs is None:
            return ""
        response = getattr(inputs, "response", None)
        if response is not None:
            content = getattr(response, "content", None)
            text = self._parse_content(content)
            if text:
                return text
        messages = getattr(inputs, "messages", None) or []
        if not messages:
            return ""
        for msg in reversed(messages):
            role = getattr(msg, "role", None) or (msg.get("role") if isinstance(msg, dict) else None)
            if role == "assistant":
                content = getattr(msg, "content", None) or (msg.get("content") if isinstance(msg, dict) else "")
                return self._parse_content(content)
        return ""

    def _extract_history(self, ctx: AgentCallbackContext) -> Optional[List[Dict[str, Any]]]:
        """提取对话历史，传递给检测服务提供上下文（P1-2 改进）。

        从 ctx.inputs.messages 或 ctx.session.messages 中提取最近几轮对话，
        仅提取 role 和 text content，不包含工具调用细节。
        """
        messages = None
        inputs = getattr(ctx, "inputs", None)
        if inputs is not None:
            messages = getattr(inputs, "messages", None)
            if not messages and isinstance(inputs, dict):
                messages = inputs.get("messages", [])
        if not messages:
            session = getattr(ctx, "session", None)
            if session is not None:
                messages = getattr(session, "messages", None) or []

        if not messages:
            return None

        history: List[Dict[str, Any]] = []
        # 最多取最近 10 条消息作为上下文
        recent = list(messages)[-10:] if len(messages) > 10 else list(messages)
        for msg in recent:
            role = getattr(msg, "role", None) or (msg.get("role") if isinstance(msg, dict) else None)
            if role not in ("user", "assistant"):
                continue
            content = getattr(msg, "content", None) or (msg.get("content") if isinstance(msg, dict) else "")
            text = self._parse_content(content)
            if text and text.strip():
                history.append({"role": role, "text": text})
        return history if history else None
