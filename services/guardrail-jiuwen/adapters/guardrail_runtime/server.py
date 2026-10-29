"""FastAPI 独立服务：guardrail 安全检测 HTTP 端点。

端点：
  POST /v1/rails/input   - 输入安全检测
  POST /v1/rails/output  - 输出安全检测
  POST /v1/policy/reload - 手动触发策略重载
  GET  /v1/policy/status - 策略版本和规则数量
  GET  /v1/health        - 健康检查
"""
from __future__ import annotations

import logging
import os
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from adapters.guardrail_runtime.hardened_engine import HardenedEngine, DetectionResult
from adapters.guardrail_runtime.llm_adapter import LLMAdapter
from adapters.guardrail_runtime.policy_loader import PolicyLoader

logger = logging.getLogger("guardrail.server")

# ------------------------------------------------------------------ #
# 请求/响应模型
# ------------------------------------------------------------------ #


class RailRequest(BaseModel):
    """Rail 检测请求。"""
    text: str
    request_id: Optional[str] = None
    conversation_id: Optional[str] = None
    history: Optional[List[Dict[str, Any]]] = None


class RailResponse(BaseModel):
    """Rail 检测响应。"""
    decision: str  # allow / review / block / unavailable
    reason_code: Optional[str] = None
    reason_message: str = ""
    violated_rules: List[str] = []
    blocked_by: str = ""
    response_message: str = ""
    processed_text: Optional[str] = None  # 脱敏后的文本（如做了脱敏）
    pii_detected: bool = False
    pii_entities: List[str] = []
    policy_version: str = ""
    duration_ms: float = 0.0


class ReloadResponse(BaseModel):
    """策略重载响应。"""
    version: str
    rules_count: int
    prompts: List[str]
    status: str = "reloaded"


# ------------------------------------------------------------------ #
# 服务初始化
# ------------------------------------------------------------------ #

POLICY_DIR = Path(os.environ.get("GUARDRAIL_POLICY_DIR", "policy"))

policy_loader = PolicyLoader(POLICY_DIR)

llm_adapter = LLMAdapter()

engine = HardenedEngine(
    policy_loader=policy_loader,
    llm_adapter=llm_adapter,
    enable_pii=os.environ.get("GUARDRAIL_ENABLE_PII", "true").lower() == "true",
    enable_content_filter=os.environ.get("GUARDRAIL_ENABLE_CONTENT_FILTER", "true").lower() == "true",
    enable_llm_defense=os.environ.get("GUARDRAIL_ENABLE_LLM_DEFENSE", "true").lower() == "true",
    enable_whitelist=True,
)

# 启动 watchdog 自动热重载
if os.environ.get("GUARDRAIL_AUTO_RELOAD", "true").lower() == "true":
    policy_loader.start_watchdog()

app = FastAPI(title="guardrail-jiuwen", version="0.1.0")


@app.on_event("shutdown")
async def shutdown():
    """服务关闭时清理资源。"""
    await llm_adapter.aclose()
    # 关闭内容过滤的 httpx 连接池
    cf = engine._content_filter
    if cf and cf.get("_httpx_client") and not cf["_httpx_client"].is_closed:
        await cf["_httpx_client"].aclose()
    # 停止 watchdog
    policy_loader.stop_watchdog()


# ------------------------------------------------------------------ #
# 端点
# ------------------------------------------------------------------ #


@app.get("/v1/health")
async def health():
    return {"status": "ok", "service": "guardrail-jiuwen"}


@app.get("/v1/policy/status")
async def policy_status():
    return policy_loader.get_status()


@app.post("/v1/policy/reload", response_model=ReloadResponse)
async def reload_policy():
    status = policy_loader.reload()
    return ReloadResponse(
        version=status["version"],
        rules_count=status["rules_count"],
        prompts=status["prompts"],
    )


@app.post("/v1/rails/input", response_model=RailResponse)
async def evaluate_input(request: RailRequest):
    """输入安全检测。"""
    import time
    t0 = time.monotonic()

    result: DetectionResult = await engine.evaluate_input(
        text=request.text,
        history=request.history,
    )

    elapsed_ms = (time.monotonic() - t0) * 1000
    return RailResponse(
        decision=result.decision,
        reason_code=result.reason_code,
        reason_message=result.reason_message,
        violated_rules=result.violated_rules,
        blocked_by=result.blocked_by,
        response_message=result.response_message,
        processed_text=result.processed_text if result.processed_text != request.text else None,
        pii_detected=result.pii_detected,
        pii_entities=result.pii_entities,
        policy_version=policy_loader.get_version(),
        duration_ms=round(elapsed_ms, 2),
    )


@app.post("/v1/rails/output", response_model=RailResponse)
async def evaluate_output(request: RailRequest):
    """输出安全检测。"""
    import time
    t0 = time.monotonic()

    result: DetectionResult = await engine.evaluate_output(text=request.text)

    elapsed_ms = (time.monotonic() - t0) * 1000
    return RailResponse(
        decision=result.decision,
        reason_code=result.reason_code,
        reason_message=result.reason_message,
        violated_rules=result.violated_rules,
        blocked_by=result.blocked_by,
        response_message=result.response_message,
        processed_text=result.processed_text if result.processed_text != request.text else None,
        pii_detected=result.pii_detected,
        pii_entities=result.pii_entities,
        policy_version=policy_loader.get_version(),
        duration_ms=round(elapsed_ms, 2),
    )


if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("GUARDRAIL_PORT", "9876"))
    uvicorn.run(app, host="0.0.0.0", port=port)
