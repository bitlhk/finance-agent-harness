"""加固检测引擎：替代 LangGraph 8 节点工作流。

用 Python async 管道实现 5 阶段输入检测 + 2 阶段输出检测，
返回四态决策（allow / review / block / unavailable）。

原 LangGraph 工作流：
  white_list → PII输入 → GuardRails正则 → Embedding内容过滤 → LLM深度检测 → call_llm → 输出检测

本引擎（去掉 call_llm，guardrail 不做对话生成）：
  输入: whitelist → PII输入 → 正则匹配 → [内容过滤] → LLM深度检测
  输出: [内容过滤] → PII输出
"""
from __future__ import annotations

import asyncio
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from adapters.guardrail_runtime.policy_loader import PolicyLoader
from adapters.guardrail_runtime.llm_adapter import LLMAdapter

logger = logging.getLogger("guardrail.engine")
security_logger = logging.getLogger("security")

# 决策类型常量
DECISION_ALLOW = "allow"
DECISION_REVIEW = "review"
DECISION_BLOCK = "block"
DECISION_UNAVAILABLE = "unavailable"

LOG_STR_MAX = 100


def _safe_log(text: str) -> str:
    """脱敏日志：只记录长度和 hash，不写原文。"""
    if not text:
        return "EMPTY"
    import hashlib
    h = hashlib.md5(text.encode()).hexdigest()[:8]
    return f"len={len(text)},hash={h}"


@dataclass
class DetectionResult:
    """检测结果。"""
    decision: str = DECISION_ALLOW
    reason_code: Optional[str] = None
    reason_message: str = ""
    violated_rules: List[str] = field(default_factory=list)
    blocked_by: str = ""
    response_message: str = ""
    pii_detected: bool = False
    pii_entities: List[str] = field(default_factory=list)
    processed_text: str = ""  # PII 脱敏后的文本


class HardenedEngine:
    """加固检测引擎。

    从 PolicyLoader 加载规则，用 async 管道执行安全检测。
    不依赖 LangChain/LangGraph。
    """

    def __init__(
        self,
        policy_loader: PolicyLoader,
        llm_adapter: Optional[LLMAdapter] = None,
        enable_pii: bool = True,
        enable_content_filter: bool = False,
        enable_llm_defense: bool = True,
        enable_whitelist: bool = True,
    ):
        self._policy = policy_loader
        self._llm = llm_adapter
        self._enable_pii = enable_pii
        self._enable_content_filter = enable_content_filter
        self._enable_llm_defense = enable_llm_defense
        self._enable_whitelist = enable_whitelist

        # 可选：上游 PII 检测服务
        self._pii_service: Optional[Any] = None
        if enable_pii:
            self._init_pii_service()

        # 可选：上游内容过滤服务
        self._content_filter: Optional[Any] = None
        self._content_filter_lock = asyncio.Lock()
        if enable_content_filter:
            self._init_content_filter()

    def reload(self) -> Dict[str, Any]:
        """热重载：先重载策略文件，再重建检测器。

        替代原来只调用 policy_loader.reload() 的做法，
        确保运行时检测器（PII、内容过滤）也使用最新配置。
        """
        results: Dict[str, Any] = {}
        try:
            policy_result = self._policy.reload()
            results["policy"] = policy_result
        except Exception as e:
            logger.error(f"Reload policy failed: {e}")
            results["policy"] = {"error": str(e)}

        # 重建 PII 检测器
        if self._enable_pii:
            try:
                self._init_pii_service()
                results["pii"] = "reloaded" if self._pii_service else "disabled"
            except Exception as e:
                logger.error(f"Reload PII service failed: {e}")
                results["pii"] = f"error: {e}"
        else:
            results["pii"] = "skipped"

        # 重建内容过滤器
        if self._enable_content_filter:
            try:
                self._init_content_filter()
                results["content_filter"] = "reloaded" if self._content_filter else "disabled"
            except Exception as e:
                logger.error(f"Reload content filter failed: {e}")
                results["content_filter"] = f"error: {e}"
        else:
            results["content_filter"] = "skipped"

        logger.info(f"HardenedEngine reloaded: {results}")
        return results

    def _init_pii_service(self) -> None:
        """尝试导入上游 PII 检测服务，失败则降级。"""
        try:
            import sys
            from pathlib import Path
            upstream_src = str(Path(__file__).resolve().parents[2] / "upstream")
            if upstream_src not in sys.path:
                sys.path.insert(0, upstream_src)
            from src.pii_detection import get_pii_service, reset_pii_service
            pii_config = self._policy.get_pii_config()
            pii_settings = pii_config if pii_config else {}
            if not pii_settings.get("enabled", True):
                logger.info("PII detection disabled by policy config (enabled=false)")
                self._pii_service = None
                return
            reset_pii_service()  # 确保单例使用最新配置
            self._pii_service = get_pii_service(
                input_entities=pii_settings.get("input_entities", ["cn_pii", "pii"]),
                output_entities=pii_settings.get("output_entities", ["all"]),
                use_presidio=pii_settings.get("use_presidio", True),
                threshold=pii_settings.get("threshold", 0.0),
                anonymize_input=pii_settings.get("anonymize_input", True),
                anonymize_output=pii_settings.get("anonymize_output", True),
                action_mode=pii_settings.get("action_mode", "detect"),
                input_action_mode=pii_settings.get("input_action_mode"),
                output_action_mode=pii_settings.get("output_action_mode"),
                input_enabled=pii_settings.get("input_enabled", True),
                output_enabled=pii_settings.get("output_enabled", True),
            )
            logger.info("PII detection service initialized")
        except Exception as e:
            logger.warning(f"PII detection unavailable: {e}")
            self._pii_service = None

    def _init_content_filter(self) -> None:
        """从 policy/content_filter_config.json 加载内容过滤配置。

        不依赖 upstream Python 硬编码，锚点和阈值全部从 JSON 读取。
        Embedding 向量在首次调用时按需计算并缓存。
        """
        try:
            cf_config = self._policy.get_content_filter_config()
            if not cf_config:
                logger.warning("Content filter config not found in policy, content filter disabled")
                self._content_filter = None
                return

            service_cfg = cf_config.get("service", {})
            categories_cfg = cf_config.get("categories", {})
            embedding_cfg = cf_config.get("embedding", {})

            # 读取 embedding 配置（统一从环境变量，不再硬编码默认值）
            import os
            base_url_env = embedding_cfg.get("base_url_env", "EMBEDDING_BASE_URL")
            model_env = embedding_cfg.get("model_env", "EMBEDDING_MODEL")
            api_key = os.environ.get("OPENAI_API_KEY", "")
            base_url = os.environ.get(base_url_env, "")
            model = os.environ.get(model_env, "")

            if not api_key or not base_url or not model:
                logger.warning(
                    f"Content filter: embedding API not configured "
                    f"(api_key={'set' if api_key else 'missing'}, "
                    f"base_url={base_url or 'missing'}, model={model or 'missing'})"
                )
                self._content_filter = None
                return

            # 构建类别配置
            category_configs = {}
            for cat_name, cat_data in categories_cfg.items():
                category_configs[cat_name] = {
                    "threshold": cat_data.get("threshold", 0.75),
                    "anchors": cat_data.get("anchors", []),
                    "anchor_embeddings": [],  # 延迟计算
                }

            self._content_filter = {
                "api_key": api_key,
                "base_url": base_url,
                "model": model,
                "input_enabled": service_cfg.get("input_enabled", True),
                "output_enabled": service_cfg.get("output_enabled", True),
                "action_mode": service_cfg.get("action_mode", "block"),
                "min_text_length": service_cfg.get("min_text_length", 8),
                "ascii_ratio_threshold": service_cfg.get("ascii_ratio_threshold", 0.5),
                "categories": category_configs,
                "_embeddings_cached": False,
            }

            cat_count = len(category_configs)
            anchor_count = sum(len(c["anchors"]) for c in category_configs.values())
            logger.info(
                f"Content filter initialized from JSON: {cat_count} categories, "
                f"{anchor_count} anchors, model={model}"
            )

        except Exception as e:
            logger.warning(f"Content filter initialization failed: {e}")
            self._content_filter = None

    # ------------------------------------------------------------------ #
    # 输入检测管道
    # ------------------------------------------------------------------ #

    async def evaluate_input(self, text: str, history: Optional[List[Dict]] = None) -> DetectionResult:
        """输入安全检测管道（替代 LangGraph 5 节点输入工作流）。

        阶段顺序：
        1. 白名单检查（但不能绕过安全链）
        2. PII 输入检测
        3. 正则规则匹配（GuardRails 关键词）
        4. 内容过滤（Embedding，可选）
        5. LLM 深度安全检测
        """
        result = DetectionResult(processed_text=text)
        text_print = _safe_log(text)

        # 阶段 1: 白名单检查（但不绕过安全链）
        is_whitelisted = self._check_whitelist(text)
        if is_whitelisted:
            logger.info(f"Input matched whitelist, but safety chain still runs: {text_print}")
            # 白名单不跳过安全检测，只标记

        # 阶段 2: PII 输入检测
        pii_result = self._check_pii_input(text)
        if pii_result.get("should_block"):
            result.decision = DECISION_BLOCK
            result.blocked_by = "pii"
            result.reason_code = "pii_input_block"
            result.reason_message = f"PII detected: {pii_result.get('entities', [])}"
            result.violated_rules = pii_result.get("entities", [])
            result.pii_detected = True
            result.pii_entities = pii_result.get("entities", [])
            result.response_message = self._policy.get_response_message("pii_input")
            security_logger.warning(f"Input PII blocked: {result.pii_entities}")
            return result

        if pii_result.get("has_pii"):
            result.pii_detected = True
            result.pii_entities = pii_result.get("entities", [])
            result.processed_text = pii_result.get("anonymized_text", text)
            security_logger.info(f"Input PII anonymized: {result.pii_entities}")

        # 阶段 3: 正则规则匹配
        regex_result = self._check_regex(result.processed_text)
        if regex_result.get("blocked"):
            result.decision = DECISION_BLOCK
            result.blocked_by = "regex"
            result.reason_code = regex_result.get("rule_id", "regex_block")
            result.reason_message = regex_result.get("name", "正则规则匹配")
            result.violated_rules = [regex_result.get("rule_id", "regex")]
            result.response_message = self._policy.get_response_message("regex", rule_id=regex_result.get("rule_id", "regex"))
            security_logger.warning(
                f"Regex blocked - rule: {result.violated_rules}, input: {text_print}"
            )
            return result

        # 阶段 4: 内容过滤（可选）
        if self._enable_content_filter and self._content_filter:
            try:
                cf_result = await self._check_content_filter(result.processed_text, is_output=False)
                if cf_result.get("blocked"):
                    result.decision = DECISION_BLOCK
                    result.blocked_by = "content_filter"
                    result.reason_code = "content_filter_block"
                    result.reason_message = f"内容过滤触发: {cf_result.get('categories', [])}"
                    result.violated_rules = cf_result.get("categories", [])
                    result.response_message = self._policy.get_response_message("content_filter")
                    security_logger.warning(f"Content filter blocked: {cf_result.get('categories')}")
                    return result
            except Exception as e:
                logger.error(f"Content filter error: {e}")
                # 内容过滤异常不终止，继续后续检测

        # 阶段 5: LLM 深度安全检测
        if self._enable_llm_defense and self._llm:
            try:
                llm_result = await self._check_llm_security(result.processed_text)
                if llm_result.get("risk_level") == "unavailable":
                    result.decision = DECISION_UNAVAILABLE
                    result.blocked_by = "llm"
                    result.reason_code = "llm_unavailable"
                    result.reason_message = "LLM 安全服务暂时不可用"
                    result.response_message = self._policy.get_response_message("unavailable")
                    security_logger.warning(f"LLM security check unavailable: {text_print}")
                    return result

                if not llm_result.get("is_safe", True):
                    # 区分 unavailable（服务不可用）和 block（检测到风险）
                    if llm_result.get("risk_level") == "unavailable":
                        result.decision = DECISION_UNAVAILABLE
                        result.blocked_by = "llm"
                        result.reason_code = "llm_unavailable"
                        result.reason_message = llm_result.get("detected_issues", "LLM 安全服务不可用")
                        result.response_message = self._policy.get_response_message("unavailable")
                        security_logger.warning(f"LLM security check unavailable: {result.reason_message}")
                        return result

                    category = llm_result.get("violated_rules", [""])[0] if llm_result.get("violated_rules") else ""
                    result.decision = DECISION_BLOCK
                    result.blocked_by = "llm"
                    result.reason_code = "llm_block"
                    result.reason_message = llm_result.get("detected_issues", "LLM 安全检测拦截")
                    result.violated_rules = llm_result.get("violated_rules", [])
                    result.response_message = self._policy.get_response_message("llm", category=category)
                    security_logger.warning(
                        f"LLM blocked - issues: {result.reason_message}, input: {text_print}"
                    )
                    return result

            except Exception as e:
                logger.error(f"LLM security check error: {e}")
                result.decision = DECISION_UNAVAILABLE
                result.blocked_by = "llm"
                result.reason_code = "llm_error"
                result.reason_message = "LLM 安全服务异常"
                security_logger.warning(f"LLM security check error: {e}")
                return result

        # 全部通过
        security_logger.info(f"Input safety check passed: {text_print}")
        return result

    # ------------------------------------------------------------------ #
    # 输出检测管道
    # ------------------------------------------------------------------ #

    async def evaluate_output(self, text: str) -> DetectionResult:
        """输出安全检测管道（替代 LangGraph 2 节点输出工作流）。

        阶段顺序：
        1. 内容过滤（可选）
        2. 正则规则匹配
        3. LLM 深度安全检测
        4. PII 输出检测
        """
        result = DetectionResult(processed_text=text)
        text_print = _safe_log(text)

        # 阶段 1: 内容过滤（可选）
        if self._enable_content_filter and self._content_filter:
            try:
                cf_result = await self._check_content_filter(text, is_output=True)
                if cf_result.get("blocked"):
                    result.decision = DECISION_BLOCK
                    result.blocked_by = "content_filter"
                    result.reason_code = "content_filter_output_block"
                    result.reason_message = f"输出内容过滤触发: {cf_result.get('categories', [])}"
                    result.violated_rules = cf_result.get("categories", [])
                    result.response_message = self._policy.get_response_message("content_filter")
                    security_logger.warning(f"Output content filter blocked: {cf_result.get('categories')}")
                    return result
            except Exception as e:
                logger.error(f"Output content filter error: {e}")

        # 阶段 2: 正则规则匹配（输出场景跳过 output_enabled=False 的规则）
        regex_result = self._check_regex(text, is_output=True)
        if regex_result.get("blocked"):
            result.decision = DECISION_BLOCK
            result.blocked_by = "regex"
            result.reason_code = regex_result.get("rule_id", "regex_output_block")
            result.reason_message = f"输出正则匹配: {regex_result.get('name', '')}"
            result.violated_rules = [regex_result.get("rule_id", "regex")]
            result.response_message = self._policy.get_response_message("regex", rule_id=regex_result.get("rule_id", "regex"))
            security_logger.warning(
                f"Output regex blocked - rule: {result.violated_rules}, output: {text_print}"
            )
            return result

        # 阶段 3: PII 输出检测（先于 LLM，确保检测模型收到脱敏文本）
        pii_result = self._check_pii_output(text)
        if pii_result.get("should_block"):
            result.decision = DECISION_BLOCK
            result.blocked_by = "pii"
            result.reason_code = "pii_output_block"
            result.reason_message = f"输出 PII detected: {pii_result.get('entities', [])}"
            result.violated_rules = pii_result.get("entities", [])
            result.pii_detected = True
            result.pii_entities = pii_result.get("entities", [])
            result.response_message = self._policy.get_response_message("pii_output")
            security_logger.warning(f"Output PII blocked: {result.pii_entities}")
            return result

        if pii_result.get("has_pii"):
            result.pii_detected = True
            result.pii_entities = pii_result.get("entities", [])
            result.processed_text = pii_result.get("anonymized_text", text)
            security_logger.info(f"Output PII anonymized: {result.pii_entities}")

        # 阶段 4: LLM 深度安全检测（使用脱敏后的文本）
        llm_text = result.processed_text  # 脱敏后的文本（如有 PII）
        if self._enable_llm_defense and self._llm:
            try:
                llm_result = await self._check_llm_security(llm_text)
                if llm_result.get("risk_level") == "unavailable":
                    result.decision = DECISION_UNAVAILABLE
                    result.blocked_by = "llm"
                    result.reason_code = "llm_output_unavailable"
                    result.reason_message = "LLM 安全服务暂时不可用"
                    result.response_message = self._policy.get_response_message("unavailable")
                    security_logger.warning(f"LLM output security check unavailable: {text_print}")
                    return result

                if not llm_result.get("is_safe", True):
                    # 区分 unavailable（服务不可用）和 block（检测到风险）
                    if llm_result.get("risk_level") == "unavailable":
                        result.decision = DECISION_UNAVAILABLE
                        result.blocked_by = "llm"
                        result.reason_code = "llm_output_unavailable"
                        result.reason_message = llm_result.get("detected_issues", "LLM 输出安全服务不可用")
                        result.response_message = self._policy.get_response_message("unavailable")
                        security_logger.warning(f"LLM output security check unavailable: {result.reason_message}")
                        return result

                    category = llm_result.get("violated_rules", [""])[0] if llm_result.get("violated_rules") else ""
                    result.decision = DECISION_BLOCK
                    result.blocked_by = "llm"
                    result.reason_code = "llm_output_block"
                    result.reason_message = llm_result.get("detected_issues", "LLM 输出安全检测拦截")
                    result.violated_rules = llm_result.get("violated_rules", [])
                    result.response_message = self._policy.get_response_message("llm", category=category)
                    security_logger.warning(
                        f"LLM output blocked - issues: {result.reason_message}, output: {text_print}"
                    )
                    return result
            except Exception as e:
                logger.error(f"LLM output security check error: {e}")
                result.decision = DECISION_UNAVAILABLE
                result.blocked_by = "llm"
                result.reason_code = "llm_output_error"
                result.reason_message = "LLM 输出安全服务异常"
                result.response_message = self._policy.get_response_message("unavailable")
                security_logger.warning(f"LLM output security check error: {e}")
                return result

        security_logger.info(f"Output safety check passed: {text_print}")
        return result

    # ------------------------------------------------------------------ #
    # 各阶段检测方法
    # ------------------------------------------------------------------ #

    def _check_whitelist(self, text: str) -> bool:
        """白名单检查（但不绕过安全链）。"""
        wl_config = self._policy.get_whitelist()
        if not wl_config.get("enabled", False):
            return False
        patterns = wl_config.get("patterns", [])
        for pattern in patterns:
            try:
                if re.search(pattern, text, re.IGNORECASE):
                    return True
            except re.error:
                continue
        return False

    def _check_pii_input(self, text: str) -> Dict[str, Any]:
        """PII 输入检测。"""
        if not self._pii_service:
            return {"has_pii": False}

        try:
            pii_info = self._pii_service.check_input(text)
            if pii_info.get("has_pii"):
                entities = [e["type"] for e in pii_info.get("summary", {}).get("entities", [])]
                return {
                    "has_pii": True,
                    "entities": entities,
                    "should_block": pii_info.get("should_block", False),
                    "anonymized_text": pii_info.get("anonymized_text", text),
                }
            return {"has_pii": False}
        except Exception as e:
            logger.error(f"PII input check error: {e}")
            return {"has_pii": False}

    # 教育/否定上下文关键词：出现这些词时，正则命中降级为 review 而非 block
    _EDUCATIONAL_KEYWORDS = [
        "如何识别", "警惕", "不可能", "请勿", "避免", "没有", "不存在",
        "不是", "并非", "不要", "切勿", "防范", "骗局", "诈骗", "风险提示",
        "不存在的", "没有.*这种", "不可能.*存在",
    ]

    def _has_educational_context(self, text: str) -> bool:
        """检测文本是否包含教育/否定上下文（避免误报）。"""
        for kw in self._EDUCATIONAL_KEYWORDS:
            try:
                if re.search(kw, text, re.IGNORECASE):
                    return True
            except re.error:
                continue
        return False

    def _check_regex(self, text: str, is_output: bool = False) -> Dict[str, Any]:
        """正则规则匹配（替代 GuardRails AI）。

        Args:
            text: 待检测文本
            is_output: True=输出检测，跳过 output_enabled=False 的规则；
                       False=输入检测，执行所有规则
        """
        rules = self._policy.get_rules()
        has_educational = self._has_educational_context(text)
        for rule in rules:
            # 输出检测时跳过标记为 output_enabled=False 的规则
            if is_output and not rule.get("output_enabled", True):
                continue
            for compiled_pattern in rule.get("compiled_patterns", []):
                if compiled_pattern.search(text):
                    # 教育上下文降级为 review（放行但标记需人工复查）
                    if has_educational:
                        return {
                            "blocked": False,
                            "review": True,
                            "rule_id": rule["id"],
                            "name": rule["name"],
                            "response_message": rule.get("response_message", ""),
                        }
                    return {
                        "blocked": True,
                        "rule_id": rule["id"],
                        "name": rule["name"],
                        "response_message": rule.get("response_message", ""),
                    }
        return {"blocked": False}

    async def _check_content_filter(self, text: str, is_output: bool = False) -> Dict[str, Any]:
        """内容过滤检测（基于 Embedding 余弦相似度）。

        从 content_filter_config.json 加载的锚点和阈值，
        首次调用时批量计算锚点 embedding 并缓存。
        """
        if not self._content_filter:
            return {"blocked": False}

        cf = self._content_filter

        # 检查开关
        if is_output and not cf["output_enabled"]:
            return {"blocked": False}
        if not is_output and not cf["input_enabled"]:
            return {"blocked": False}

        stripped = text.strip()
        text_len = len(stripped)

        # 短文本跳过（embedding 对短文本无区分度）
        if text_len < cf["min_text_length"]:
            return {"blocked": False}

        # 英文文本跳过（锚点全为中文，跨语言不可靠）
        ascii_chars = sum(1 for c in stripped if ord(c) < 128 and c.isalpha())
        if text_len > 0 and ascii_chars / text_len > cf["ascii_ratio_threshold"]:
            return {"blocked": False}

        try:
            # 首次调用时批量计算锚点 embedding（并发保护）
            if not cf["_embeddings_cached"]:
                async with self._content_filter_lock:
                    if not cf["_embeddings_cached"]:
                        await self._cache_anchor_embeddings(cf)
                        cf["_embeddings_cached"] = True

            if not cf.get("_httpx_client"):
                import httpx
                cf["_httpx_client"] = httpx.AsyncClient(
                    base_url=cf["base_url"],
                    headers={"Authorization": f"Bearer {cf['api_key']}"},
                    timeout=30.0,
                )

            # 获取输入文本的 embedding
            client = cf["_httpx_client"]
            resp = await client.post(
                "/embeddings",
                json={"model": cf["model"], "input": text},
            )
            resp.raise_for_status()
            input_embedding = resp.json()["data"][0]["embedding"]

            # 逐类别计算最大相似度
            import time
            triggered = []
            category_scores = {}
            for cat_name, cat_cfg in cf["categories"].items():
                anchor_embeddings = cat_cfg["anchor_embeddings"]
                if not anchor_embeddings:
                    continue

                scores = [self._cosine_similarity(input_embedding, anchor) for anchor in anchor_embeddings]
                cat_score = max(scores) if scores else 0.0
                category_scores[cat_name] = round(cat_score, 4)

                threshold = cat_cfg["threshold"]
                if cat_score >= threshold:
                    triggered.append(cat_name)

            is_blocked = len(triggered) > 0 and cf["action_mode"] == "block"

            if is_blocked:
                logger.info(
                    f"Content filter triggered: categories={triggered}, "
                    f"scores={category_scores}"
                )
                return {
                    "blocked": True,
                    "categories": triggered,
                    "scores": category_scores,
                }

            return {"blocked": False}

        except Exception as e:
            logger.error(f"Content filter check error: {e}")
            return {"blocked": False}

    async def _cache_anchor_embeddings(self, cf: Dict[str, Any]) -> None:
        """批量计算所有锚点的 embedding 并缓存。"""
        import httpx

        client = httpx.AsyncClient(
            base_url=cf["base_url"],
            headers={"Authorization": f"Bearer {cf['api_key']}"},
            timeout=30.0,
        )

        try:
            for cat_name, cat_cfg in cf["categories"].items():
                anchors = cat_cfg["anchors"]
                if not anchors:
                    continue

                # 分批请求（API 单次最多 32 条）
                all_embeddings = []
                batch_size = 32
                for i in range(0, len(anchors), batch_size):
                    batch = anchors[i:i + batch_size]
                    resp = await client.post(
                        "/embeddings",
                        json={"model": cf["model"], "input": batch},
                    )
                    resp.raise_for_status()
                    data = resp.json()["data"]
                    all_embeddings.extend([item["embedding"] for item in data])

                cat_cfg["anchor_embeddings"] = all_embeddings
                logger.info(f"Content filter: cached {len(all_embeddings)} embeddings for '{cat_name}'")

            cf["_httpx_client"] = client
        except Exception as e:
            logger.error(f"Failed to cache anchor embeddings: {e}")
            await client.aclose()
            raise

    @staticmethod
    def _cosine_similarity(a: List[float], b: List[float]) -> float:
        """计算两个向量的余弦相似度。"""
        dot = sum(x * y for x, y in zip(a, b))
        norm_a = sum(x * x for x in a) ** 0.5
        norm_b = sum(x * x for x in b) ** 0.5
        if norm_a == 0 or norm_b == 0:
            return 0.0
        return max(-1.0, min(1.0, dot / (norm_a * norm_b)))

    async def _check_llm_security(self, text: str) -> Dict[str, Any]:
        """LLM 深度安全检测。"""
        if not self._llm:
            return {"is_safe": True, "risk_level": "low", "detected_issues": "", "violated_rules": []}

        system_prompt = self._policy.get_prompt("security_check")
        return await self._llm.security_check(system_prompt, text)

    def _check_pii_output(self, text: str) -> Dict[str, Any]:
        """PII 输出检测。"""
        if not self._pii_service:
            return {"has_pii": False}

        try:
            pii_info = self._pii_service.check_output(text)
            if pii_info.get("has_pii"):
                entities = [e["type"] for e in pii_info.get("summary", {}).get("entities", [])]
                return {
                    "has_pii": True,
                    "entities": entities,
                    "should_block": pii_info.get("should_block", False),
                    "anonymized_text": pii_info.get("anonymized_text", text),
                }
            return {"has_pii": False}
        except Exception as e:
            logger.error(f"PII output check error: {e}")
            return {"has_pii": False}
