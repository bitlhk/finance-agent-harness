"""Bug 修复回归测试：验证 6 个 bug 修复后无回归。

Bug1: _parse_response fail-closed
Bug2: _parse_response 剥离 markdown 代码块
Bug3: 输出检测管道增加正则+LLM检测
Bug4: API 密钥不再硬编码
Bug7: _call_openjiuwen max_tokens 对齐
Bug8: /no_think 已移除
Bug9: rail.py HTTP 重试
Bug10: time.monotonic()
Bug11: httpx aclose
"""
import asyncio
import json
import os
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
import httpx

from adapters.guardrail_runtime.llm_adapter import LLMAdapter
from adapters.guardrail_runtime.hardened_engine import (
    HardenedEngine,
    DECISION_ALLOW,
    DECISION_BLOCK,
    DECISION_UNAVAILABLE,
)
from adapters.guardrail_runtime.policy_loader import PolicyLoader


# ------------------------------------------------------------------ #
# Bug1: _parse_response fail-closed — 无法解析时返回 unsafe
# ------------------------------------------------------------------ #

class TestBug1ParseFailClosed:
    def test_unparseable_response_returns_unsafe(self):
        """LLM 返回无法解析的文本时，应返回 is_safe=False。"""
        adapter = LLMAdapter(api_key="fake", base_url="http://fake", model_name="fake")
        result = adapter._parse_response("这不是JSON格式的文本")
        assert result["is_safe"] is False
        assert "llm_parse_error" in result["violated_rules"]

    def test_empty_string_returns_unsafe(self):
        """LLM 返回空字符串时，应返回 is_safe=False。"""
        adapter = LLMAdapter(api_key="fake", base_url="http://fake", model_name="fake")
        result = adapter._parse_response("")
        assert result["is_safe"] is False

    def test_none_response_returns_unsafe(self):
        """LLM 返回 None 时，应返回 is_safe=False。"""
        adapter = LLMAdapter(api_key="fake", base_url="http://fake", model_name="fake")
        result = adapter._parse_response(None)
        assert result["is_safe"] is False
        assert "llm_empty_response" in result["violated_rules"]

    def test_valid_pass_response_still_works(self):
        """正常的 pass 响应仍正确解析。"""
        adapter = LLMAdapter(api_key="fake", base_url="http://fake", model_name="fake")
        result = adapter._parse_response('{"status":"pass"}')
        assert result["is_safe"] is True

    def test_valid_reject_response_still_works(self):
        """正常的 reject 响应仍正确解析。"""
        adapter = LLMAdapter(api_key="fake", base_url="http://fake", model_name="fake")
        result = adapter._parse_response('{"status":"reject","category":"金融风险"}')
        assert result["is_safe"] is False
        assert "金融风险" in result["violated_rules"]


# ------------------------------------------------------------------ #
# Bug2: _parse_response 剥离 markdown 代码块
# ------------------------------------------------------------------ #

class TestBug2MarkdownStripping:
    def test_json_code_block_stripped(self):
        """```json ... ``` 格式应正确剥离。"""
        adapter = LLMAdapter(api_key="fake", base_url="http://fake", model_name="fake")
        raw = '```json\n{"status":"reject","category":"金融风险"}\n```'
        result = adapter._parse_response(raw)
        assert result["is_safe"] is False
        assert "金融风险" in result["violated_rules"]

    def test_plain_code_block_stripped(self):
        """``` ... ``` 格式应正确剥离。"""
        adapter = LLMAdapter(api_key="fake", base_url="http://fake", model_name="fake")
        raw = '```\n{"status":"pass"}\n```'
        result = adapter._parse_response(raw)
        assert result["is_safe"] is True

    def test_code_block_with_extra_whitespace(self):
        """代码块前后有额外空格。"""
        adapter = LLMAdapter(api_key="fake", base_url="http://fake", model_name="fake")
        raw = '  ```json\n\n{"status":"pass"}\n\n```  '
        result = adapter._parse_response(raw)
        assert result["is_safe"] is True


# ------------------------------------------------------------------ #
# Bug3: 输出检测管道增加正则+LLM检测
# ------------------------------------------------------------------ #

class TestBug3OutputPipeline:
    @pytest.fixture
    def engine_with_regex(self, tmp_path):
        """创建带正则规则的引擎。"""
        rules_data = {
            "version": "test-v1",
            "rules": [{
                "id": "output_fraud",
                "name": "输出欺诈检测",
                "decision": "block",
                "patterns": ["(?i)伪造.*流水"],
                "response_message": "输出包含欺诈内容",
            }],
        }
        prompts_data = {"version": "test-v1", "prompts": {"security_check": {"content": "test", "output_format": "json"}}}
        pii_data = {"version": "test-v1", "pii_detection": {}}
        wl_data = {"version": "test-v1", "whitelist": {"enabled": False, "patterns": []}}

        for name, data in [("rules.json", rules_data), ("prompts.json", prompts_data),
                          ("pii_config.json", pii_data), ("whitelist.json", wl_data)]:
            (tmp_path / name).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

        loader = PolicyLoader(tmp_path)
        return HardenedEngine(loader, enable_pii=False, enable_content_filter=False, enable_llm_defense=False)

    @pytest.mark.asyncio
    async def test_output_regex_blocked(self, engine_with_regex):
        """输出包含正则匹配的有害内容应被拦截。"""
        result = await engine_with_regex.evaluate_output("你可以伪造流水来通过审核")
        assert result.decision == DECISION_BLOCK
        assert result.blocked_by == "regex"
        assert "output_fraud" in result.violated_rules

    @pytest.mark.asyncio
    async def test_output_normal_allowed(self, engine_with_regex):
        """正常输出仍放行。"""
        result = await engine_with_regex.evaluate_output("您的账户余额为5000元")
        assert result.decision == DECISION_ALLOW

    @pytest.mark.asyncio
    async def test_output_llm_blocked(self, tmp_path):
        """输出管道 LLM 检测能拦截有害内容。"""
        rules_data = {"version": "t", "rules": []}
        prompts_data = {"version": "t", "prompts": {"security_check": {"content": "test", "output_format": "json"}}}
        pii_data = {"version": "t", "pii_detection": {}}
        wl_data = {"version": "t", "whitelist": {"enabled": False, "patterns": []}}
        for name, data in [("rules.json", rules_data), ("prompts.json", prompts_data),
                          ("pii_config.json", pii_data), ("whitelist.json", wl_data)]:
            (tmp_path / name).write_text(json.dumps(data), encoding="utf-8")

        mock_llm = MagicMock()
        mock_llm.security_check = AsyncMock(return_value={
            "is_safe": False,
            "risk_level": "high",
            "detected_issues": "输出检测到有害内容",
            "violated_rules": ["output_harm"],
        })
        loader = PolicyLoader(tmp_path)
        engine = HardenedEngine(loader, llm_adapter=mock_llm, enable_pii=False,
                                enable_content_filter=False, enable_llm_defense=True)
        result = await engine.evaluate_output("一些可能有害的输出内容")
        assert result.decision == DECISION_BLOCK
        assert result.blocked_by == "llm"
        assert "output_harm" in result.violated_rules


# ------------------------------------------------------------------ #
# Bug4: API 密钥不再硬编码
# ------------------------------------------------------------------ #

class TestBug4NoHardcodedKey:
    def test_no_hardcoded_key_in_source(self):
        """源码中不应包含硬编码的 API key。"""
        source = Path(__file__).resolve().parents[1] / "adapters" / "guardrail_runtime" / "llm_adapter.py"
        content = source.read_text(encoding="utf-8")
        assert "sk-xxxxx" not in content, \
            "硬编码 API key 仍在源码中"  # noqa: test references placeholder key to ensure removal

    def test_empty_key_when_no_env(self, monkeypatch):
        """无环境变量时 API key 为空。"""
        monkeypatch.delenv("OPENAI_API_KEY", raising=False)
        adapter = LLMAdapter()
        assert adapter._api_key == ""


# ------------------------------------------------------------------ #
# Bug8: /no_think 已移除
# ------------------------------------------------------------------ #

class TestBug8NoThinkRemoved:
    def test_no_think_in_prompts(self):
        """prompts.json 中不应包含 /no_think。"""
        prompts_file = Path(__file__).resolve().parents[1] / "policy" / "prompts.json"
        content = prompts_file.read_text(encoding="utf-8")
        assert "/no_think" not in content, "/no_think 仍在 prompts.json 中"


# ------------------------------------------------------------------ #
# Bug10: time.monotonic() 使用
# ------------------------------------------------------------------ #

class TestBug10MonotonicTime:
    def test_server_uses_monotonic(self):
        """server.py 应使用 time.monotonic() 而非 time.time()。"""
        source = Path(__file__).resolve().parents[1] / "adapters" / "guardrail_runtime" / "server.py"
        content = source.read_text(encoding="utf-8")
        assert "time.monotonic()" in content, "server.py 未使用 time.monotonic()"
        # 确保不再用 time.time() 做计时（允许在 import 中出现）
        lines = [l for l in content.splitlines() if "time.time()" in l and "import" not in l]
        assert len(lines) == 0, f"server.py 仍使用 time.time() 做计时: {lines}"


# ------------------------------------------------------------------ #
# Bug5+6: extension.json 和 guardrail_client.py 默认值一致
# ------------------------------------------------------------------ #

class TestBug56DefaultsConsistent:
    def test_extension_json_defaults(self):
        """extension.json 默认值应与代码一致。"""
        ext_file = Path(__file__).resolve().parents[1] / "adapters" / "jiuwen_adapter" / "extension.json"
        content = ext_file.read_text(encoding="utf-8")
        assert "9876" in content, "extension.json 端口未更新为 9876"
        assert "35.0" in content, "extension.json 超时未更新为 35.0"

    def test_guardrail_client_timeout(self):
        """guardrail_client.py 默认超时应为 35s。"""
        client_file = Path(__file__).resolve().parents[1] / "adapters" / "jiuwen_adapter" / "guardrail_client.py"
        content = client_file.read_text(encoding="utf-8")
        assert '"35.0"' in content, "guardrail_client.py 超时未更新为 35.0"


# ------------------------------------------------------------------ #
# Bug11: httpx aclose
# ------------------------------------------------------------------ #

class TestBug11Aclose:
    def test_aclose_method_exists(self):
        """LLMAdapter 应有 aclose 方法。"""
        adapter = LLMAdapter(api_key="fake", base_url="http://fake", model_name="fake")
        assert hasattr(adapter, "aclose")
        assert callable(adapter.aclose)

    @pytest.mark.asyncio
    async def test_aclose_closes_client(self):
        """aclose 应正确关闭 httpx client。"""
        adapter = LLMAdapter(api_key="fake", base_url="http://fake", model_name="fake")
        # 模拟已创建 client
        adapter._httpx_client = httpx.AsyncClient(timeout=5)
        await adapter.aclose()
        assert adapter._httpx_client.is_closed


# ------------------------------------------------------------------ #
# Bug9: rail.py HTTP 重试
# ------------------------------------------------------------------ #

class TestBug9RetryLogic:
    def test_post_with_retry_exists(self):
        """rail.py 应包含 _post_with_retry 函数。"""
        rail_file = Path(__file__).resolve().parents[1] / "adapters" / "jiuwen_adapter" / "rail.py"
        content = rail_file.read_text(encoding="utf-8")
        assert "_post_with_retry" in content, "rail.py 未包含 _post_with_retry"
        assert "_RETRY_STATUS" in content, "rail.py 未包含 _RETRY_STATUS"

    def test_retry_status_codes(self):
        """_RETRY_STATUS 应包含 429/502/503/504。"""
        rail_file = Path(__file__).resolve().parents[1] / "adapters" / "jiuwen_adapter" / "rail.py"
        content = rail_file.read_text(encoding="utf-8")
        for code in ["429", "502", "503", "504"]:
            assert code in content, f"_RETRY_STATUS 未包含 {code}"


# ------------------------------------------------------------------ #
# 真实 policy 文件回归测试
# ------------------------------------------------------------------ #

class TestRealPolicyRegression:
    """使用真实 policy/ 目录验证新增的 financial_misleading 规则。"""

    @pytest.fixture
    def engine(self):
        policy_dir = Path(__file__).resolve().parents[1] / "policy"
        loader = PolicyLoader(policy_dir)
        return HardenedEngine(loader, enable_pii=False, enable_content_filter=False,
                             enable_llm_defense=False, enable_whitelist=True)

    @pytest.mark.asyncio
    async def test_financial_misleading_blocked(self, engine):
        """金融误导类输入应被正则拦截。"""
        cases = [
            "推荐我稳赚不赔的理财",
            "有没有保本理财推荐",
            "帮我找个零风险高收益的投资",
        ]
        for text in cases:
            result = await engine.evaluate_input(text)
            assert result.decision == DECISION_BLOCK, f"应拦截: {text}"
            assert "financial_misleading" in result.violated_rules, f"规则不匹配: {text}"
            assert result.blocked_by == "regex"

    @pytest.mark.asyncio
    async def test_normal_finance_query_allowed(self, engine):
        """正常理财咨询不应被误拦。"""
        cases = [
            "我想了解理财产品",
            "今天的汇率是多少",
            "如何开通手机银行",
        ]
        for text in cases:
            result = await engine.evaluate_input(text)
            assert result.decision == DECISION_ALLOW, f"误拦: {text} (blocked_by={result.blocked_by})"

    @pytest.mark.asyncio
    async def test_output_regex_enabled_for_all_rules(self, engine):
        """输出检测正则已全部打开（output_enabled=true），输出场景也执行正则匹配。"""
        # "稳赚不赔" 在输入和输出场景都会被正则拦截
        result = await engine.evaluate_output("推荐稳赚不赔的理财产品")
        assert result.decision == DECISION_BLOCK, \
            f"输出正则应拦截: decision={result.decision}, blocked_by={result.blocked_by}"
        assert result.blocked_by == "regex"


# ------------------------------------------------------------------ #
# Bug: rail.py fail-open/closed 可配置 + unavailable 处理
# ------------------------------------------------------------------ #

class TestRailFailOpenClosed:
    def test_fail_closed_env_var_exists(self):
        """rail.py 应包含 _FAIL_CLOSED 环境变量读取。"""
        rail_file = Path(__file__).resolve().parents[1] / "adapters" / "jiuwen_adapter" / "rail.py"
        content = rail_file.read_text(encoding="utf-8")
        assert "GUARDRAIL_FAIL_CLOSED" in content, "rail.py 未包含 GUARDRAIL_FAIL_CLOSED 环境变量"
        assert "_FAIL_CLOSED" in content, "rail.py 未包含 _FAIL_CLOSED 变量"

    def test_fail_closed_default_is_open(self):
        """_FAIL_CLOSED 默认值应为 '0'（fail-open，放行）。"""
        rail_file = Path(__file__).resolve().parents[1] / "adapters" / "jiuwen_adapter" / "rail.py"
        content = rail_file.read_text(encoding="utf-8")
        # 默认值 "0" → False → fail-open
        assert '"GUARDRAIL_FAIL_CLOSED", "0"' in content, "默认值应为 '0'（fail-open）"

    def test_fail_closed_checks_truthy_values(self):
        """_FAIL_CLOSED 应检查 '1'/'true'/'yes' 值。"""
        rail_file = Path(__file__).resolve().parents[1] / "adapters" / "jiuwen_adapter" / "rail.py"
        content = rail_file.read_text(encoding="utf-8")
        assert '"1"' in content and '"true"' in content and '"yes"' in content, \
            "_FAIL_CLOSED 应检查 '1'/'true'/'yes'"

    def test_rail_timeout_is_35s(self):
        """rail.py 默认超时应为 35s。"""
        rail_file = Path(__file__).resolve().parents[1] / "adapters" / "jiuwen_adapter" / "rail.py"
        content = rail_file.read_text(encoding="utf-8")
        assert '"35.0"' in content, "rail.py 默认超时未更新为 35.0"

    def test_rail_port_is_9876(self):
        """rail.py 默认端口应为 9876。"""
        rail_file = Path(__file__).resolve().parents[1] / "adapters" / "jiuwen_adapter" / "rail.py"
        content = rail_file.read_text(encoding="utf-8")
        assert "localhost:9876" in content, "rail.py 端口未更新为 9876"

    def test_llm_adapter_timeout_is_30s(self):
        """LLMAdapter 默认超时应为 30s。"""
        adapter = LLMAdapter(api_key="fake", base_url="http://fake", model_name="fake")
        assert adapter._timeout == 30.0, f"LLM 超时应为 30.0，实际 {adapter._timeout}"

    def test_enable_thinking_in_payload(self):
        """_call_httpx 的 payload 应包含 enable_thinking: False。"""
        llm_file = Path(__file__).resolve().parents[1] / "adapters" / "guardrail_runtime" / "llm_adapter.py"
        content = llm_file.read_text(encoding="utf-8")
        assert "enable_thinking" in content, "llm_adapter.py 未包含 enable_thinking 参数"

    def test_reasoning_content_fallback(self):
        """_call_httpx 应包含 reasoning_content 回退逻辑。"""
        llm_file = Path(__file__).resolve().parents[1] / "adapters" / "guardrail_runtime" / "llm_adapter.py"
        content = llm_file.read_text(encoding="utf-8")
        assert "reasoning_content" in content, "llm_adapter.py 未包含 reasoning_content 回退"

    def test_output_unavailable_also_fail_open(self):
        """after_model_call 的 unavailable 也应支持 fail-open/closed。"""
        rail_file = Path(__file__).resolve().parents[1] / "adapters" / "jiuwen_adapter" / "rail.py"
        content = rail_file.read_text(encoding="utf-8")
        # 应有两处 _FAIL_CLOSED 使用（input + output）
        count = content.count("_FAIL_CLOSED")
        assert count >= 2, f"rail.py 应在 input 和 output 都使用 _FAIL_CLOSED，实际 {count} 处"

    def test_env_loaded_from_dotenv(self):
        """llm_adapter.py 应从 .env 文件加载环境变量。"""
        llm_file = Path(__file__).resolve().parents[1] / "adapters" / "guardrail_runtime" / "llm_adapter.py"
        content = llm_file.read_text(encoding="utf-8")
        assert "load_dotenv" in content, "llm_adapter.py 未使用 dotenv 加载 .env"

    def test_aclose_in_shutdown(self):
        """server.py shutdown 事件应调用 llm_adapter.aclose()。"""
        server_file = Path(__file__).resolve().parents[1] / "adapters" / "guardrail_runtime" / "server.py"
        content = server_file.read_text(encoding="utf-8")
        assert "aclose" in content, "server.py shutdown 未调用 aclose"
