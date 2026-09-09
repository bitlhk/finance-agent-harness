"""guardrail HTTP 客户端：供非 JiuwenSwarm 的 Agent 框架调用。

所有需要调用 guardrail 服务的代码都通过此客户端，
后续对接其他 Agent 框架时只需修改此文件或创建新的 adapter。
"""
from __future__ import annotations

import logging
import os
from typing import Any, Dict, List, Optional

import httpx

logger = logging.getLogger("guardrail.client")

_DEFAULT_URL = os.environ.get("GUARDRAIL_SERVICE_URL", "http://localhost:9876")
_DEFAULT_TIMEOUT = float(os.environ.get("GUARDRAIL_TIMEOUT", "35.0"))


class GuardrailClient:
    """guardrail 安全检测 HTTP 客户端。

    用法:
        client = GuardrailClient()
        result = await client.check_input("用户输入文本")
        if result["decision"] == "block":
            # 拦截
    """

    def __init__(
        self,
        base_url: str = _DEFAULT_URL,
        timeout: float = _DEFAULT_TIMEOUT,
    ):
        self._base_url = base_url
        self._timeout = timeout
        self._client: Optional[httpx.AsyncClient] = None

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=self._base_url,
                timeout=self._timeout,
            )
        return self._client

    async def check_input(
        self,
        text: str,
        request_id: Optional[str] = None,
        conversation_id: Optional[str] = None,
        history: Optional[List[Dict[str, Any]]] = None,
    ) -> Dict[str, Any]:
        """输入安全检测。

        Returns:
            {"decision": "allow|block|review|unavailable", "reason_code": ..., ...}
        """
        client = await self._get_client()
        resp = await client.post(
            "/v1/rails/input",
            json={
                "text": text,
                "request_id": request_id,
                "conversation_id": conversation_id,
                "history": history,
            },
        )
        resp.raise_for_status()
        return resp.json()

    async def check_output(self, text: str) -> Dict[str, Any]:
        """输出安全检测。"""
        client = await self._get_client()
        resp = await client.post(
            "/v1/rails/output",
            json={"text": text},
        )
        resp.raise_for_status()
        return resp.json()

    async def reload_policy(self) -> Dict[str, Any]:
        """手动触发策略热重载。"""
        client = await self._get_client()
        resp = await client.post("/v1/policy/reload")
        resp.raise_for_status()
        return resp.json()

    async def get_policy_status(self) -> Dict[str, Any]:
        """获取策略版本和规则数量。"""
        client = await self._get_client()
        resp = await client.get("/v1/policy/status")
        resp.raise_for_status()
        return resp.json()

    async def health_check(self) -> bool:
        """健康检查。"""
        try:
            client = await self._get_client()
            resp = await client.get("/v1/health")
            resp.raise_for_status()
            data = resp.json()
            return data.get("status") == "ok"
        except Exception:
            return False

    async def close(self) -> None:
        """关闭 HTTP 客户端。"""
        if self._client and not self._client.is_closed:
            await self._client.aclose()
