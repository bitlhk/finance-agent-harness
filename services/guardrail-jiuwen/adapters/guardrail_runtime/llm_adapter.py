"""LLM 适配器：封装 LLM 安全检测调用，替代 LangChain ChatOpenAI。

优先使用 OpenJiuwen Model，若不可用则降级为 httpx 直接调用 OpenAI 兼容 API。
后续对接其他 Agent 框架时，只需替换此文件。
"""
from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any, Dict, Optional

import httpx

# 加载 .env 文件中的环境变量
try:
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).resolve().parents[2] / ".env")
except ImportError:
    pass

logger = logging.getLogger("guardrail.llm")

# 尝试导入 OpenJiuwen Model
try:
    from openjiuwen.core.foundation.llm import Model, ModelClientConfig, ModelRequestConfig
    OPENJIUWEN_AVAILABLE = True
except ImportError:
    OPENJIUWEN_AVAILABLE = False
    Model = None
    ModelClientConfig = None
    ModelRequestConfig = None


class LLMAdapter:
    """LLM 安全检测适配器。

    封装 LLM 调用逻辑，提供统一的 `security_check` 接口。
    优先使用 OpenJiuwen Model，降级为 httpx 直连。
    """

    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model_name: Optional[str] = None,
        timeout: float = 30.0,
    ):
        # 所有 LLM 配置统一从 .env → os.environ 读取，不再硬编码默认值
        self._api_key = api_key or os.environ.get("OPENAI_API_KEY", "")
        self._base_url = base_url or os.environ.get("OPENAI_BASE_URL", "")
        self._model_name = model_name or os.environ.get("OPENAI_MODEL", "")
        self._timeout = timeout
        self._openjiuwen_model: Optional[Any] = None
        self._httpx_client: Optional[httpx.AsyncClient] = None

        if OPENJIUWEN_AVAILABLE and self._api_key and self._model_name:
            try:
                client_config = ModelClientConfig(
                    api_key=self._api_key,
                    base_url=self._base_url,
                )
                self._openjiuwen_model = Model(
                    model_name=self._model_name,
                    client_config=client_config,
                )
                logger.info(f"LLMAdapter: OpenJiuwen Model initialized ({self._model_name})")
            except Exception as e:
                logger.warning(f"LLMAdapter: OpenJiuwen Model init failed: {e}, falling back to httpx")
                self._openjiuwen_model = None
        else:
            logger.info(f"LLMAdapter: using httpx direct API (openjiuwen={OPENJIUWEN_AVAILABLE})")

    async def security_check(self, system_prompt: str, user_input: str) -> Dict[str, Any]:
        """调用 LLM 进行安全检测，返回解析后的结果。

        Args:
            system_prompt: 安全检测系统提示词（从 prompts.json 加载）
            user_input: 待检测的用户输入

        Returns:
            {"is_safe": bool, "risk_level": str, "detected_issues": str, "violated_rules": list}
        """
        if not self._api_key or not self._model_name:
            logger.warning("LLM security check unavailable: no API key or model configured")
            return {
                "is_safe": False,
                "risk_level": "unavailable",
                "detected_issues": "LLM 安全检测服务未配置",
                "violated_rules": ["llm_not_configured"],
            }

        try:
            if self._openjiuwen_model is not None:
                raw_response = await self._call_openjiuwen(system_prompt, user_input)
            else:
                raw_response = await self._call_httpx(system_prompt, user_input)

            return self._parse_response(raw_response)

        except Exception as e:
            logger.error(f"LLM security check error: {e}", exc_info=True)
            # 检测器异常时返回 unavailable（fail-closed）
            return {
                "is_safe": False,
                "risk_level": "unavailable",
                "detected_issues": f"LLM service error: {e}",
                "violated_rules": ["llm_unavailable"],
            }

    async def _call_openjiuwen(self, system_prompt: str, user_input: str) -> str:
        """通过 OpenJiuwen Model 调用 LLM。"""
        messages = [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_input},
        ]
        request_config = ModelRequestConfig(temperature=0.0, max_tokens=100)
        response = await self._openjiuwen_model.ainvoke(messages, request_config=request_config)
        # OpenJiuwen Model 返回格式适配
        if hasattr(response, "content"):
            return response.content
        if isinstance(response, dict):
            return response.get("content", str(response))
        return str(response)

    async def _call_httpx(self, system_prompt: str, user_input: str) -> str:
        """通过 httpx 直接调用 OpenAI 兼容 API。"""
        url = f"{self._base_url}/chat/completions"
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        payload = {
            "model": self._model_name,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_input},
            ],
            "temperature": 0.0,
            "max_tokens": 100,
            "enable_thinking": False,
        }

        if self._httpx_client is None:
            self._httpx_client = httpx.AsyncClient(timeout=self._timeout)
        try:
            resp = await self._httpx_client.post(url, json=payload, headers=headers)
        except RuntimeError:
            # event loop 可能已关闭，重建 client
            if self._httpx_client and not self._httpx_client.is_closed:
                await self._httpx_client.aclose()
            self._httpx_client = httpx.AsyncClient(timeout=self._timeout)
            resp = await self._httpx_client.post(url, json=payload, headers=headers)
        resp.raise_for_status()
        data = resp.json()
        message = data["choices"][0]["message"]
        # 推理模型（如 Qwen3.5）将思考放在 reasoning_content，最终回复放在 content
        content = message.get("content")
        if not content and message.get("reasoning_content"):
            content = message["reasoning_content"]
        return content

    async def aclose(self) -> None:
        """关闭 httpx 连接池。"""
        if self._httpx_client and not self._httpx_client.is_closed:
            await self._httpx_client.aclose()

    def _parse_response(self, response_text: str) -> Dict[str, Any]:
        """解析 LLM 安全检测响应。

        预期格式: {"status": "pass"} 或 {"status": "reject", "category": "具体名称"}
        """
        import re as _re
        # LLM 可能返回 None（如推理模型关闭思考后无内容）
        if not response_text:
            logger.warning("LLM returned empty response, defaulting to unsafe")
            return {
                "is_safe": False,
                "risk_level": "high",
                "detected_issues": "LLM 返回空响应",
                "violated_rules": ["llm_empty_response"],
            }
        text = response_text.strip()

        # 剥离 markdown 代码块（```json ... ``` 或 ``` ... ```）
        code_block_match = _re.match(r"^```(?:json)?\s*\n?(.*?)\n?```\s*$", text, _re.DOTALL)
        if code_block_match:
            text = code_block_match.group(1).strip()

        # 尝试 JSON 解析
        try:
            result = json.loads(text)
            status = result.get("status", "reject")
            category = result.get("category", "")

            if status == "pass":
                # 只有明确 pass 才放行
                return {
                    "is_safe": True,
                    "risk_level": "low",
                    "detected_issues": "",
                    "violated_rules": [],
                }

            # reject / unknown / null / 任何非 pass 值 → 拦截
            return {
                "is_safe": False,
                "risk_level": "high",
                "detected_issues": f"检测到安全风险: {category}" if category else "LLM 判定为不安全",
                "violated_rules": [category] if category else ["llm_security_check"],
            }

        except json.JSONDecodeError:
            # 降级：关键词匹配
            if '"status":"reject"' in text or '"status": "reject"' in text:
                return {
                    "is_safe": False,
                    "risk_level": "high",
                    "detected_issues": "检测到安全风险",
                    "violated_rules": ["llm_security_check"],
                }
            elif '"status":"pass"' in text or '"status": "pass"' in text:
                return {
                    "is_safe": True,
                    "risk_level": "low",
                    "detected_issues": "",
                    "violated_rules": [],
                }
            else:
                # 无法解析 LLM 响应，fail-closed（拒绝而非放行）
                logger.warning(f"Cannot parse LLM security response, defaulting to unsafe (len={len(text)})")
                return {
                    "is_safe": False,
                    "risk_level": "high",
                    "detected_issues": "LLM 响应解析失败",
                    "violated_rules": ["llm_parse_error"],
                }
