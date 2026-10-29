"""
具有专业 GuardRails AI 安全保护的 LangChainAgent。

本模块实现了一个基于 LangChain 的代理，使用 GuardRails AI、Presido、大模型提示词防御等进行专业输入验证，
用于检测提示注入攻击。
"""
import logging
import asyncio
import uuid
import json
import re
from typing import Dict, Any, List, Optional, AsyncIterator
from dataclasses import dataclass, field

from langchain_openai import ChatOpenAI
from langchain_core.messages import HumanMessage, AIMessage, SystemMessage
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_core.runnables import RunnableConfig
from langgraph.graph import StateGraph, END
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

# GuardRails AI 导入
try:
    from guardrails import Guard, OnFailAction
    GUARDRAILS_AVAILABLE = True
except Exception as e:
    print(f"警告：GuardRails AI 不可用：{e}")
    GUARDRAILS_AVAILABLE = False
    Guard = None
    OnFailAction = None

# 导入自定义正则验证器（内部已处理 GuardRails 可选依赖）
from src.validators.regex_validator import (
    RegexBlacklistValidator,
    GUARDRAILS_AVAILABLE as REGEX_VALIDATOR_AVAILABLE,
)
if not REGEX_VALIDATOR_AVAILABLE:
    RegexBlacklistValidator = None  # type: ignore

from config.config import (
    OPENAI_API_KEY,
    OPENAI_BASE_URL,
    OPENAI_MODEL,
    SYSTEM_PROMPT,
    GUARDRAILS_CONFIG,
    GUARDRAILS_DEFAULT_RULES,
    PII_DETECTION_CONFIG,
    get_guardrail_config,
    LOG_STR_MAX
)

# PII检测导入
try:
    from src.pii_detection import get_pii_service, PIIDetectionService
    PII_DETECTION_AVAILABLE = True
except ImportError:
    PII_DETECTION_AVAILABLE = False
    get_pii_service = None
    PIIDetectionService = None

# 内容过滤导入
try:
    from src.classifier.service import get_classifier_service, ContentFilterService
    CLASSIFIER_AVAILABLE = True
except ImportError:
    CLASSIFIER_AVAILABLE = False
    get_classifier_service = None
    ContentFilterService = None

# 设置日志
logger = logging.getLogger("agent_service")

@dataclass
class AgentState:
    """LangGraph 代理的状态。"""
    messages: List[Dict[str, Any]] = field(default_factory=list)
    current_input: str = ""
    processed_input: str = ""
    response: str = ""
    is_blocked: bool = False
    block_reason: str = ""
    blocked_by: str = ""
    violated_rules: List[str] = field(default_factory=list)
    response_message: str | None = None
    is_white_listed: bool = False
    # 敏感信息检测字段
    pii_input_detected: bool = False
    pii_input_entities: List[str] = field(default_factory=list)
    pii_output_detected: bool = False
    pii_output_entities: List[str] = field(default_factory=list)
    # 内容过滤字段
    content_filter_triggered: bool = False
    content_filter_categories: List[str] = field(default_factory=list)
    content_filter_scores: Dict[str, float] = field(default_factory=dict)


class LangChainAgent:
    """安全护栏的主要代理类。"""

    def __init__(
        self,
        custom_guardrail_rules: List[Dict] = None,
        prompt_defense_config: Dict[str, Any] = None,
        checkpointer: AsyncSqliteSaver | None = None
    ):
        """使用专业安全组件初始化代理。

        Args:
            custom_guardrail_rules: 自定义的GuardRails规则列表，格式同GUARDRAILS_DEFAULT_RULES
            prompt_defense_config: 提示词防御配置，从数据库加载，未传入时从config.py读取默认值
            checkpointer: LangGraph AsyncSqliteSaver 持久化 checkpointer，为 None 时使用内存存储
        """
        self.logger = logging.getLogger("agent_service.agent")
        self.security_logger = logging.getLogger("security")

        # 存储自定义规则
        self.custom_rules = custom_guardrail_rules or []

        # 初始化 LLM
        self.llm = ChatOpenAI(
            api_key=OPENAI_API_KEY,
            base_url=OPENAI_BASE_URL,
            model=OPENAI_MODEL,
            temperature=0.7,
            streaming=True
        )

        # 存储 guardrails 配置
        self.rejection_message = GUARDRAILS_CONFIG.get(
            "rejection_message",
            "安全警告：检测到非法输入，请求已拦截。请遵守使用规范。"
        )
        self.enable_guardrails = GUARDRAILS_CONFIG.get("enable_guardrails", True)

        # 初始化 GuardRails AI
        if GUARDRAILS_AVAILABLE and self.enable_guardrails:
            self.guard = self._initialize_guardrails(self.custom_rules)
        else:
            self.guard = None
            self.logger.warning("GuardRails AI 不可用或已禁用，使用备用安全机制")

        # 初始化PII检测服务（默认不启用，等待数据库配置加载）
        self.enable_pii_detection = False
        self.pii_service = None
        if PII_DETECTION_AVAILABLE:
            self.pii_service = get_pii_service()
            self.logger.info("PII检测服务已创建（默认禁用，等待配置加载）")
        else:
            self.logger.warning("PII检测模块不可用")

        # 白名单规则（从数据库加载，由 server 调用 reload_white_list_rules 填充）
        self.white_list_patterns = []

        # 初始化内容过滤服务（默认启用，等待数据库配置加载）
        self.content_filter_service: ContentFilterService | None = None
        self.enable_content_filter = False
        if CLASSIFIER_AVAILABLE:
            self.content_filter_service = get_classifier_service()
            self.enable_content_filter = True
            self.logger.info("内容过滤服务已创建（等待数据库配置加载）")
        else:
            self.logger.warning("内容过滤模块不可用")

        # 初始化提示词防御配置（优先从数据库加载，未传入时从config.py读取默认值）
        from config.config import SECURITY_CHECK_PROMPT
        if prompt_defense_config:
            self.enable_prompt_defense = prompt_defense_config.get("enabled", True)
            self.security_check_prompt = prompt_defense_config.get("prompt_content", SECURITY_CHECK_PROMPT)
            self.logger.info("提示词防御配置已从数据库加载到Agent")
        else:
            self.enable_prompt_defense = True
            self.security_check_prompt = SECURITY_CHECK_PROMPT
            self.logger.info("提示词防御配置使用config.py默认值（数据库尚未初始化）")

        # 创建提示模板
        self.prompt = ChatPromptTemplate.from_messages([
            ("system", SYSTEM_PROMPT),
            MessagesPlaceholder(variable_name="messages"),
            ("human", "{input}")
        ])

        # 构建链
        self.chain = self.prompt | self.llm | StrOutputParser()

        # 初始化对话历史记忆（优先使用外部传入的持久化 checkpointer）
        if checkpointer is not None:
            self.memory = checkpointer
        else:
            # 测试/开发回退：使用内存存储（数据不持久化）
            from langgraph.checkpoint.memory import MemorySaver
            self.memory = MemorySaver()

        # 构建 LangGraph 工作流
        self.workflow = self._build_workflow()

        self.logger.info("LangChainAgent 初始化成功")

    def _initialize_guardrails(self, custom_rules: List[Dict] = None):
        """使用专业安全规则初始化 GuardRails AI。"""
        try:
            # 合并默认规则和自定义规则
            all_rules = list(GUARDRAILS_DEFAULT_RULES)
            if custom_rules:
                for custom_rule in custom_rules:
                    existing_idx = next(
                        (i for i, r in enumerate(all_rules)
                         if r["name"] == custom_rule["name"]), None
                    )
                    if existing_idx is not None:
                        all_rules[existing_idx] = custom_rule
                    else:
                        all_rules.append(custom_rule)

            # 配置自定义 GuardRails Validator
            if RegexBlacklistValidator is not None:
                RegexBlacklistValidator.configure(all_rules)

            # 使用新版 GuardRails API：Guard().use(validator)
            guard = Guard().use(RegexBlacklistValidator(on_fail=OnFailAction.EXCEPTION))
            self.logger.info(f"GuardRails AI 初始化成功，加载了 {len(all_rules)} 个规则")
            return guard

        except Exception as error:
            self.logger.error(f"GuardRails AI 初始化失败：{error}", exc_info=True)
            return None

    def _check_input_with_guardrails(self, input_text: str) -> Dict[str, Any]:
        """使用 GuardRails AI 检查输入。"""
        # 检查运行时配置：黑名单过滤是否启用
        runtime_config = get_guardrail_config()
        if not runtime_config.get("black_list", {}).get("enabled", True):
            return {
                "is_safe": True,
                "risk_level": "low",
                "detected_issues": "",
                "violated_rules": []
            }

        if not self.guard:
            return self._fallback_security_check(input_text)

        try:
            self.guard.validate(input_text)
            input_text_print = f"{input_text[:LOG_STR_MAX]}..." if len(input_text) > LOG_STR_MAX else input_text
            return {
                "is_safe": True,
                "risk_level": "low",
                "detected_issues": "",
                "violated_rules": []
            }

        except Exception as e:
            error_msg = str(e)
            # on_fail="exception" 时命中规则会抛异常，从异常信息中提取具体规则名和应答话术
            if "Matched blacklist rule" in error_msg:
                rule_part = error_msg.split("Matched blacklist rule: ")[-1].strip()
                rule_name = rule_part
                response_message = None
                if " | response_message: " in rule_part:
                    rule_name = rule_part.split(" | response_message: ")[0].strip()
                    response_message = rule_part.split(" | response_message: ")[1].strip()
                    if response_message == "None" or response_message == "":
                        response_message = None
                self.security_logger.warning(f"GuardRails AI检测到安全风险：{rule_name}")
                return {
                    "is_safe": False,
                    "risk_level": "high",
                    "detected_issues": rule_name,
                    "violated_rules": [rule_name] if rule_name else ["guardrails_pattern_match"],
                    "response_message": response_message,
                }

            self.logger.error(f"GuardRails AI验证出错：{e}")
            return self._fallback_security_check(input_text)

    def _fallback_security_check(self, input_text: str) -> Dict[str, Any]:
        """当 GuardRails 不可用时的备用安全检查。"""
        input_lower = input_text.lower()

        # 基本安全规则（备用）
        security_rules = {
            "prompt_injection": [
                "ignore previous", "ignore all", "forget", "disregard",
                "忽略之前", "忽略所有", "无视", "忘记"
            ],
            "role_play_bypass": [
                "role play", "act as", "pretend to be", "you are now",
                "角色扮演", "扮演", "假装", "从现在开始你是"
            ],
            "financial_fraud": [
                "fake id", "forged document", "false income", "money laundering",
                "伪造", "假造", "虚假收入", "洗钱"
            ],
            "malicious_code": [
                "delete file", "rm -rf", "format system", "virus", "malware",
                "删除文件", "格式化系统", "病毒", "恶意代码"
            ]
        }

        violated_rules = []
        for rule_name, keywords in security_rules.items():
            for keyword in keywords:
                if keyword in input_lower:
                    violated_rules.append(rule_name)
                    break
        
        input_text_print = f"{input_text[:LOG_STR_MAX]}..." if len(input_text) > LOG_STR_MAX else input_text
        if violated_rules:
            self.security_logger.warning(f"备用安全违规 - 规则：{violated_rules}, 输入：{input_text_print}")
            return {
                "is_safe": False,
                "risk_level": "medium",
                "detected_issues": f"检测到安全违规：{', '.join(violated_rules)}",
                "violated_rules": violated_rules,
                "response_message": None,
            }

        self.security_logger.info(f"备用安全检查通过：{input_text_print}")
        return {
            "is_safe": True,
            "risk_level": "low",
            "detected_issues": "",
            "violated_rules": [],
            "response_message": None,
        }

    def _check_white_list(self, input_text: str) -> bool:
        """检查输入是否匹配白名单放行规则。

        Returns:
            bool: True 表示匹配白名单，应跳过后续检测
        """
        runtime_config = get_guardrail_config()
        white_list_config = runtime_config.get("white_list", {})

        if not white_list_config.get("enabled", False):
            return False

        patterns = self.white_list_patterns
        if not patterns:
            return False

        input_text_print = f"{input_text[:LOG_STR_MAX]}..." if len(input_text) > LOG_STR_MAX else input_text
        for pattern in patterns:
            if not pattern:
                continue
            try:
                if re.search(pattern, input_text):
                    self.security_logger.info(f"白名单放行匹配: pattern={pattern}, input={input_text_print}")
                    return True
            except re.error:
                self.logger.warning(f"白名单正则表达式无效: {pattern}")
                continue

        return False

    def reload_white_list_rules(self, patterns: List[str]) -> bool:
        """重新加载白名单规则。

        Args:
            patterns: 白名单正则表达式模式列表

        Returns:
            bool: 重新加载是否成功
        """
        try:
            self.white_list_patterns = [p for p in patterns if p]
            self.logger.info(
                f"白名单规则已重新加载: 共 {len(self.white_list_patterns)} 条规则"
            )
            return True
        except Exception as e:
            self.logger.error(f"白名单规则重新加载失败: {e}", exc_info=True)
            return False

    def _build_workflow(self) -> StateGraph:
        """构建代理的LangGraph工作流。

        同时构建两个工作流：
        - self.workflow: 完整8节点工作流（含输出检测）
        - self.input_check_workflow: 仅输入检测5节点工作流（到llm_defense_check后END）

        完整工作流节点顺序：
        white_list_check → pii_input_check → guardrails_check → content_filter_check → llm_defense_check → call_llm → content_filter_output_check → pii_output_check → END
        """

        async def white_list_check(state: AgentState, config: RunnableConfig) -> Dict[str, Any]:
            """白名单放行检查：匹配则跳过后续所有检测。"""
            input_text = state.current_input
            if self._check_white_list(input_text):
                input_text_print = f"{input_text[:LOG_STR_MAX]}..." if len(input_text) > LOG_STR_MAX else input_text
                self.security_logger.info(f"白名单放行：{input_text_print}")
                return {
                    "is_white_listed": True,
                    "processed_input": input_text,
                    "messages": state.messages
                }
            return {
                "is_white_listed": False,
                "processed_input": input_text,
                "messages": state.messages
            }

        async def pii_input_check(state: AgentState, config: RunnableConfig) -> Dict[str, Any]:
            """PII输入检测：检测并脱敏，或检测并拦截。"""
            if not self.pii_service or not self.enable_pii_detection:
                return {
                    "processed_input": state.processed_input or state.current_input,
                    "messages": state.messages
                }

            input_text = state.processed_input or state.current_input
            input_text_print = input_text[:LOG_STR_MAX] if len(input_text) > LOG_STR_MAX else input_text
            pii_input_info = self.pii_service.check_input(input_text)

            if pii_input_info["has_pii"]:
                pii_types = [e["type"] for e in pii_input_info["summary"]["entities"]]
                if pii_input_info.get("should_block", False):
                    self.security_logger.warning(f"输入PII拦截: {pii_types}, 输入: {input_text_print}")
                    return {
                        "is_blocked": True,
                        "block_reason": f"PII detected: {', '.join(pii_types)}",
                        "blocked_by": "pii",
                        "response": "安全警告：检测到输入包含个人敏感信息，请求已被拦截。请避免在对话中泄露身份证号、银行卡号等敏感信息。",
                        "pii_input_detected": True,
                        "pii_input_entities": pii_types,
                        "violated_rules": pii_types,
                        "messages": state.messages
                    }
                self.security_logger.info(f"输入PII已脱敏: {pii_types}")
                return {
                    "processed_input": pii_input_info["anonymized_text"],
                    "pii_input_detected": True,
                    "pii_input_entities": pii_types,
                    "messages": state.messages
                }

            return {
                "processed_input": input_text,
                "messages": state.messages
            }

        async def guardrails_check(state: AgentState, config: RunnableConfig) -> Dict[str, Any]:
            """GuardRails AI 关键字/模式匹配检测。"""
            input_text = state.processed_input or state.current_input
            input_text_print = f"{input_text[:LOG_STR_MAX]}..." if len(input_text) > LOG_STR_MAX else input_text
            guardrails_result = self._check_input_with_guardrails(input_text)

            if not guardrails_result["is_safe"]:
                violated_rules = guardrails_result.get("violated_rules", [])
                detected_issues = guardrails_result.get("detected_issues", "检测到安全风险")
                risk_level = guardrails_result.get("risk_level", "high")
                response_message = guardrails_result.get("response_message")

                if response_message:
                    rejection_message = response_message
                else:
                    rejection_message = f"{self.rejection_message} [风险级别：{risk_level}，问题：{detected_issues}]"

                self.security_logger.warning(
                    f"GuardRails 拦截 - 风险：{risk_level}, "
                    f"规则：{violated_rules}, 输入：{input_text_print}"
                )

                return {
                    "is_blocked": True,
                    "block_reason": detected_issues,
                    "blocked_by": "guardrails",
                    "violated_rules": violated_rules,
                    "response": rejection_message,
                    "response_message": response_message,
                    "messages": state.messages
                }

            return {"messages": state.messages}

        async def content_filter_check(state: AgentState, config: RunnableConfig) -> Dict[str, Any]:
            """Embedding 内容过滤检测。"""
            if not self.content_filter_service or not self.enable_content_filter:
                return {"messages": state.messages}

            input_text = state.processed_input or state.current_input
            input_text_print = f"{input_text[:LOG_STR_MAX]}..." if len(input_text) > LOG_STR_MAX else input_text

            try:
                cf_result = await self.content_filter_service.classify(input_text, is_output=False)

                if cf_result.is_blocked:
                    triggered = cf_result.triggered_categories
                    scores = cf_result.category_scores
                    self.security_logger.warning(
                        f"内容过滤拦截 - 类别：{triggered}, "
                        f"分数：{scores}, 输入：{input_text_print}"
                    )

                    return {
                        "is_blocked": True,
                        "block_reason": f"内容过滤触发: {', '.join(triggered)}",
                        "blocked_by": "content_filter",
                        "violated_rules": triggered,
                        "response": f"安全警告：检测到有害内容（{', '.join(triggered)}），请求已被拦截。",
                        "content_filter_triggered": True,
                        "content_filter_categories": triggered,
                        "content_filter_scores": scores,
                        "messages": state.messages
                    }

                # 记录检测分数（未拦截时）
                if cf_result.max_score > 0.5:
                    self.logger.info(
                        f"内容过滤检测通过（高分关注）- max_score={cf_result.max_score:.4f}, "
                        f"category={cf_result.max_category}, 输入：{input_text_print}"
                    )

                # 检测命中但未拦截（detect 模式）
                # 仅当实际触发类别时才设置检测字段
                if cf_result.triggered_categories:
                    return {
                        "content_filter_triggered": True,
                        "content_filter_categories": cf_result.triggered_categories,
                        "content_filter_scores": cf_result.category_scores,
                        "messages": state.messages
                    }
                return {"messages": state.messages}

            except Exception as e:
                self.logger.error(f"内容过滤检测出错：{e}", exc_info=True)
                # 出错时默认放行
                return {"messages": state.messages}

        async def llm_defense_check(state: AgentState, config: RunnableConfig) -> Dict[str, Any]:
            """LLM 提示词防御深度检测。"""
            input_text = state.processed_input or state.current_input
            input_text_print = f"{input_text[:LOG_STR_MAX]}..." if len(input_text) > LOG_STR_MAX else input_text
            llm_result = await self._check_with_llm_security(input_text)

            if not llm_result["is_safe"]:
                detected_issues = llm_result.get("detected_issues", "检测到安全风险")
                risk_level = llm_result.get("risk_level", "high")
                violated_rules = llm_result.get("violated_rules", [])

                rejection_message = f"{self.rejection_message} [风险级别：{risk_level}，问题：{detected_issues}]"

                self.security_logger.warning(
                    f"LLM 安全检测拦截 - 风险：{risk_level}, "
                    f"问题：\"{detected_issues}\", 规则：{violated_rules}, 输入：{input_text_print}"
                )

                return {
                    "is_blocked": True,
                    "block_reason": detected_issues,
                    "blocked_by": "llm",
                    "violated_rules": violated_rules,
                    "response": rejection_message,
                    "messages": state.messages
                }

            self.security_logger.info(f"安全检查全部通过：{input_text_print}")
            return {"messages": state.messages}

        async def call_llm(state: AgentState, config: RunnableConfig) -> Dict[str, Any]:
            """使用当前输入调用 LLM。"""
            if state.is_blocked:
                return {
                    "response": state.response,
                    "messages": state.messages
                }

            current_input = state.processed_input or state.current_input

            try:
                langchain_messages = []
                for msg in state.messages:
                    if msg["role"] == "user":
                        langchain_messages.append(HumanMessage(content=msg["content"]))
                    elif msg["role"] == "assistant":
                        langchain_messages.append(AIMessage(content=msg["content"]))

                langchain_messages.append(HumanMessage(content=current_input))

                full_response = await self.chain.ainvoke(
                    {"messages": langchain_messages, "input": current_input},
                    config
                )

                # 处理 LangChain 消息对象 (AIMessage/TextAccessor等)，提取纯文本
                if hasattr(full_response, 'content'):
                    full_response = full_response.content
                if not isinstance(full_response, str):
                    full_response = str(full_response)

                new_messages = state.messages + [
                    {"role": "user", "content": current_input},
                    {"role": "assistant", "content": full_response}
                ]

                res_print = f"{full_response[:LOG_STR_MAX]}..." if len(full_response) > LOG_STR_MAX else full_response
                self.logger.info(f"已为输入生成 LLM 响应：{res_print}")

                return {
                    "response": full_response,
                    "messages": new_messages
                }

            except Exception as e:
                self.logger.error(f"调用 LLM 出错：{str(e)}", exc_info=True)
                return {
                    "response": "抱歉，处理请求时出现错误。请稍后再试。",
                    "messages": state.messages
                }

        async def content_filter_output_check(state: AgentState, config: RunnableConfig) -> Dict[str, Any]:
            """输出阶段 Embedding 内容过滤检测。"""
            if state.is_blocked or not state.response:
                return {"messages": state.messages}

            if not self.content_filter_service or not self.enable_content_filter:
                return {"messages": state.messages}

            response_text = state.response
            while hasattr(response_text, 'content'):
                response_text = response_text.content
            if not isinstance(response_text, str):
                response_text = str(response_text)

            response_print = f"{response_text[:LOG_STR_MAX]}..." if len(response_text) > LOG_STR_MAX else response_text

            try:
                cf_result = await self.content_filter_service.classify(response_text, is_output=True)

                if cf_result.is_blocked:
                    triggered = cf_result.triggered_categories
                    scores = cf_result.category_scores
                    self.security_logger.warning(
                        f"输出内容过滤拦截 - 类别：{triggered}, "
                        f"分数：{scores}, 输出：{response_print}"
                    )

                    return {
                        "is_blocked": True,
                        "block_reason": f"输出内容过滤触发: {', '.join(triggered)}",
                        "blocked_by": "content_filter",
                        "response": f"安全警告：检测到输出包含有害内容（{', '.join(triggered)}），响应已被拦截。",
                        "content_filter_triggered": True,
                        "content_filter_categories": triggered,
                        "content_filter_scores": scores,
                        "messages": state.messages
                    }

                if cf_result.max_score > 0.5:
                    self.logger.info(
                        f"输出内容过滤检测通过（高分关注）- max_score={cf_result.max_score:.4f}, "
                        f"category={cf_result.max_category}, 输出：{response_print}"
                    )

                # 检测命中但未拦截（detect 模式）
                # 仅当输出阶段实际触发类别时才覆盖检测字段，避免清空输入阶段的检测结果
                if cf_result.triggered_categories:
                    return {
                        "content_filter_triggered": True,
                        "content_filter_categories": cf_result.triggered_categories,
                        "content_filter_scores": cf_result.category_scores,
                        "messages": state.messages
                    }
                return {"messages": state.messages}

            except Exception as e:
                self.logger.error(f"输出内容过滤检测出错：{e}", exc_info=True)
                return {"messages": state.messages}

        async def pii_output_check(state: AgentState, config: RunnableConfig) -> Dict[str, Any]:
            """PII输出检测：对LLM响应进行脱敏或拦截。"""
            if state.is_blocked or not state.response:
                return {"messages": state.messages}

            if not self.pii_service or not self.enable_pii_detection:
                return {"messages": state.messages}

            # 提取纯文本（处理 LangChain TextAccessor/AIMessage 等对象）
            response_text = state.response
            while hasattr(response_text, 'content'):
                response_text = response_text.content
            if not isinstance(response_text, str):
                response_text = str(response_text)

            pii_output_info = self.pii_service.check_output(response_text)
            if pii_output_info["has_pii"]:
                pii_types = [e["type"] for e in pii_output_info["summary"]["entities"]]
                if pii_output_info.get("should_block", False):
                    self.security_logger.warning(
                        f"输出PII拦截: {pii_types}"
                    )
                    return {
                        "is_blocked": True,
                        "block_reason": f"PII detected in output: {', '.join(pii_types)}",
                        "blocked_by": "pii",
                        "response": "安全警告：输出包含个人敏感信息，已被拦截。",
                        "pii_output_detected": True,
                        "pii_output_entities": pii_types,
                        "violated_rules": pii_types,
                        "messages": state.messages
                    }
                self.security_logger.warning(
                    f"输出PII已脱敏: {pii_types}"
                )
                return {
                    "response": pii_output_info["anonymized_text"],
                    "pii_output_detected": True,
                    "pii_output_entities": pii_types,
                    "messages": state.messages
                }

            return {"messages": state.messages}

        # 条件边路由函数
        def route_white_list(state: AgentState) -> str:
            return "call_llm" if state.is_white_listed else "pii_input_check"

        def route_pii_input(state: AgentState) -> str:
            return END if state.is_blocked else "guardrails_check"

        def route_guardrails(state: AgentState) -> str:
            return END if state.is_blocked else "content_filter_check"

        def route_content_filter(state: AgentState) -> str:
            return END if state.is_blocked else "llm_defense_check"

        def route_llm_defense(state: AgentState) -> str:
            return END if state.is_blocked else "call_llm"

        def route_content_filter_output(state: AgentState) -> str:
            return END if state.is_blocked else "pii_output_check"

        # 构建图
        workflow = StateGraph(AgentState)

        # 添加8个节点
        workflow.add_node("white_list_check", white_list_check)
        workflow.add_node("pii_input_check", pii_input_check)
        workflow.add_node("guardrails_check", guardrails_check)
        workflow.add_node("content_filter_check", content_filter_check)
        workflow.add_node("llm_defense_check", llm_defense_check)
        workflow.add_node("call_llm", call_llm)
        workflow.add_node("content_filter_output_check", content_filter_output_check)
        workflow.add_node("pii_output_check", pii_output_check)

        # 设置入口点
        workflow.set_entry_point("white_list_check")

        # 定义条件边
        workflow.add_conditional_edges(
            "white_list_check",
            route_white_list,
            {"call_llm": "call_llm", "pii_input_check": "pii_input_check"}
        )
        workflow.add_conditional_edges(
            "pii_input_check",
            route_pii_input,
            {END: END, "guardrails_check": "guardrails_check"}
        )
        workflow.add_conditional_edges(
            "guardrails_check",
            route_guardrails,
            {END: END, "content_filter_check": "content_filter_check"}
        )
        workflow.add_conditional_edges(
            "content_filter_check",
            route_content_filter,
            {END: END, "llm_defense_check": "llm_defense_check"}
        )
        workflow.add_conditional_edges(
            "llm_defense_check",
            route_llm_defense,
            {END: END, "call_llm": "call_llm"}
        )
        workflow.add_edge("call_llm", "content_filter_output_check")
        workflow.add_conditional_edges(
            "content_filter_output_check",
            route_content_filter_output,
            {END: END, "pii_output_check": "pii_output_check"}
        )
        workflow.add_edge("pii_output_check", END)

        # 构建输入检测工作流（不含 call_llm 和输出检测节点）
        input_check_workflow = StateGraph(AgentState)
        input_check_workflow.add_node("white_list_check", white_list_check)
        input_check_workflow.add_node("pii_input_check", pii_input_check)
        input_check_workflow.add_node("guardrails_check", guardrails_check)
        input_check_workflow.add_node("content_filter_check", content_filter_check)
        input_check_workflow.add_node("llm_defense_check", llm_defense_check)

        input_check_workflow.set_entry_point("white_list_check")
        input_check_workflow.add_conditional_edges(
            "white_list_check",
            route_white_list,
            {"call_llm": END, "pii_input_check": "pii_input_check"}
        )
        input_check_workflow.add_conditional_edges(
            "pii_input_check",
            route_pii_input,
            {END: END, "guardrails_check": "guardrails_check"}
        )
        input_check_workflow.add_conditional_edges(
            "guardrails_check",
            route_guardrails,
            {END: END, "content_filter_check": "content_filter_check"}
        )
        input_check_workflow.add_conditional_edges(
            "content_filter_check",
            route_content_filter,
            {END: END, "llm_defense_check": "llm_defense_check"}
        )
        input_check_workflow.add_conditional_edges(
            "llm_defense_check",
            route_llm_defense,
            {END: END, "call_llm": END}
        )
        self.input_check_workflow = input_check_workflow.compile(checkpointer=self.memory)

        # 编译完整工作流
        return workflow.compile(checkpointer=self.memory)

    async def process_message(self, message: str, conversation_id: str = "default") -> Dict[str, Any]:
        """通过代理8节点工作流处理单条消息。"""
        try:
            config = {
                "configurable": {
                    "thread_id": conversation_id,
                    "checkpoint_ns": ""
                }
            }
            message_print = f"{message[:LOG_STR_MAX]}..." if len(message) > LOG_STR_MAX else message

            initial_state = {
                "current_input": message,
                "response": "",
                "is_blocked": False,
                "block_reason": ""
            }

            result = await self.workflow.ainvoke(initial_state, config)

            if result.get("is_blocked", False):
                self.security_logger.warning(
                    f"消息已拦截 - 对话：{conversation_id}, "
                    f"原因：{result.get('block_reason', '未知')}, "
                    f"输入：{message_print}"
                )
            else:
                self.logger.info(f"消息已处理 - 对话：{conversation_id}, 输入：{message_print}")

            blocked_by = result.get("blocked_by")
            if blocked_by is None and result.get("is_blocked", False):
                block_reason = result.get("block_reason", "").lower()
                if "guardrails" in block_reason:
                    blocked_by = "guardrails"
                elif "llm" in block_reason or "大模型" in block_reason:
                    blocked_by = "llm"
                elif "pii" in block_reason:
                    blocked_by = "pii"
                elif "content_filter" in block_reason or "内容过滤" in block_reason:
                    blocked_by = "content_filter"
                elif result.get("response", "").startswith(self.rejection_message) or "安全警告" in result.get("response", ""):
                    blocked_by = "guardrails"

            # 构建检测汇总（用于检测模式下的日志记录）
            detection_summary = self._build_detection_summary(result)

            return {
                "response": result.get("response", ""),
                "is_blocked": result.get("is_blocked", False),
                "block_reason": result.get("block_reason", ""),
                "blocked_by": blocked_by,
                "violated_rules": result.get("violated_rules", []),
                "response_message": result.get("response_message"),
                "conversation_id": conversation_id,
                "detection_summary": detection_summary,
            }

        except Exception as e:
            self.logger.error(f"处理消息出错：{str(e)}", exc_info=True)
            return {
                "response": "处理请求时出现错误。请稍后再试。",
                "is_blocked": False,
                "error": str(e),
                "conversation_id": conversation_id
            }

    def _build_detection_summary(self, result: Dict[str, Any]) -> Dict[str, Any] | None:
        """从工作流最终状态构建检测汇总信息。

        按节点执行顺序（后优先）检查检测字段，返回最后一次匹配的节点信息。
        """
        # 优先级 1: PII output check (最后执行)
        if result.get("pii_output_detected"):
            entities = result.get("pii_output_entities", [])
            return {
                "last_detected_node": "pii_output_check",
                "detect_reason": f"PII detected in output: {', '.join(entities)}",
                "detected_by": "pii",
                "violated_rules": entities,
                "extra": {"pii_output_entities": entities},
            }

        # 优先级 2: Content filter output check
        # 注意：content_filter_triggered 不区分 input/output，
        # 如果 response 被修改过，可能是 output check 触发的
        if result.get("content_filter_triggered"):
            categories = result.get("content_filter_categories", [])
            scores = result.get("content_filter_scores", {})
            # 通过检查是否有 response 修改来判断是否是 output check
            has_response = bool(result.get("response"))
            node_name = "content_filter_output_check" if has_response else "content_filter_check"
            reason_prefix = "输出内容过滤触发" if has_response else "内容过滤触发"
            return {
                "last_detected_node": node_name,
                "detect_reason": f"{reason_prefix}: {', '.join(categories)}",
                "detected_by": "content_filter",
                "violated_rules": categories,
                "extra": {"content_filter_categories": categories, "content_filter_scores": scores},
            }

        # 优先级 3: LLM defense check (检测模式下无字段，拦截模式下已处理)
        # 优先级 4: Guardrails check (检测模式下无字段，拦截模式下已处理)

        # 优先级 5: PII input check
        if result.get("pii_input_detected"):
            entities = result.get("pii_input_entities", [])
            return {
                "last_detected_node": "pii_input_check",
                "detect_reason": f"PII detected: {', '.join(entities)}",
                "detected_by": "pii",
                "violated_rules": entities,
                "extra": {"pii_input_entities": entities},
            }

        return None

    async def check_message_safety(self, message: str) -> Dict[str, Any]:
        """检查消息安全性（快速返回，非流式）。

        执行两阶段安全检查：
        1. GuardRails 关键字/模式匹配检查
        2. 大模型深度安全检测

        Args:
            message: 要检查的消息

        Returns:
            包含安全检查结果的字典：
            - is_safe: bool - 是否安全
            - risk_level: str - 风险等级
            - detected_issues: str - 检测到的问题
            - blocked_by: str - 拦截来源 (None, "guardrails" 或 "llm")
        """
        # 第一阶段：使用 GuardRails 检查安全性（关键字匹配）
        security_result = self._check_input_with_guardrails(message)

        if not security_result["is_safe"]:
            return {
                **security_result,
                "blocked_by": "guardrails"
            }

        # 第二阶段：使用大模型进行深度安全检测
        llm_security_result = await self._check_with_llm_security(message)

        if not llm_security_result["is_safe"]:
            return {
                **llm_security_result,
                "blocked_by": "llm"
            }

        # 所有检查通过
        return {
            "is_safe": True,
            "risk_level": "low",
            "detected_issues": "",
            "violated_rules": [],
            "blocked_by": None
        }

    async def _check_with_llm_security(self, message: str) -> Dict[str, Any]:
        """使用大模型进行深度安全检测。"""
        # 检查提示词防御是否启用
        if not self.enable_prompt_defense:
            return {
                "is_safe": True,
                "risk_level": "low",
                "detected_issues": "",
                "violated_rules": []
            }

        try:
            # 使用安全检测提示词调用 LLM
            security_messages = [
                SystemMessage(content=self.security_check_prompt),
                HumanMessage(content=message)
            ]

            # 调用 LLM 进行安全检测（非流式）
            response = await self.llm.ainvoke(security_messages)
            response_text = response.content.strip()

            response_print = f"{response_text[:LOG_STR_MAX]}..." if len(response_text) > LOG_STR_MAX else response_text
            self.logger.info(f"安全检测 LLM 响应: {response_print}")

            # 解析响应
            try:
                # 尝试解析 JSON 响应
                result = json.loads(response_text)
                status = result.get("status", "pass")
                category = result.get("category", "")

                if status == "reject":
                    return {
                        "is_safe": False,
                        "risk_level": "high",
                        "detected_issues": f"检测到安全风险: {category}",
                        "violated_rules": [category] if category else ["llm_security_check"]
                    }

                # 状态为 pass
                return {
                    "is_safe": True,
                    "risk_level": "low",
                    "detected_issues": "",
                    "violated_rules": []
                }

            except json.JSONDecodeError:
                # 如果无法解析 JSON，检查是否包含 pass 或 reject 关键词
                if '"status":"reject"' in response_text or '"status": "reject"' in response_text:
                    return {
                        "is_safe": False,
                        "risk_level": "high",
                        "detected_issues": "检测到安全风险",
                        "violated_rules": ["llm_security_check"]
                    }
                elif '"status":"pass"' in response_text or '"status": "pass"' in response_text:
                    return {
                        "is_safe": True,
                        "risk_level": "low",
                        "detected_issues": "",
                        "violated_rules": []
                    }
                else:
                    # 无法确定，默认放行（保守处理）
                    self.logger.warning(f"无法解析安全检测响应，默认放行: {response_text}")
                    return {
                        "is_safe": True,
                        "risk_level": "low",
                        "detected_issues": "",
                        "violated_rules": []
                    }

        except Exception as e:
            self.logger.error(f"LLM 安全检测出错: {str(e)}", exc_info=True)
            # 出错时默认放行，由对话阶段处理
            return {
                "is_safe": True,
                "risk_level": "low",
                "detected_issues": "",
                "violated_rules": []
            }

    def _need_output_check(self) -> bool:
        """判断是否需要输出阶段检测。

        仅依据内容过滤的输出检测开关判断：
        - 开启时：需要等 LLM 全部输出完成后检测，走伪流式
        - 关闭时：直接走真正流式（边输出边返回）
        """
        return (
            self.content_filter_service is not None
            and self.enable_content_filter
            and self.content_filter_service.output_enabled
        )

    async def _stream_with_output_check(
        self, message: str, conversation_id: str
    ) -> AsyncIterator[str]:
        """伪流式：走完整工作流，等全部节点完成后分段输出。

        适用于内容过滤输出检测开启的场景。
        """
        config = {
            "configurable": {
                "thread_id": conversation_id,
                "checkpoint_ns": ""
            }
        }

        initial_state = {
            "current_input": message,
            "response": "",
            "is_blocked": False,
            "block_reason": ""
        }

        full_response = ""
        final_messages = []
        # 收集检测信息（用于检测模式下的日志记录）
        stream_detection_nodes: List[Dict[str, Any]] = []

        async for chunk in self.workflow.astream(
            initial_state, config, stream_mode="updates"
        ):
            # 检查所有可能拦截的节点
            for node_name in ["white_list_check", "pii_input_check", "guardrails_check", "content_filter_check", "llm_defense_check", "content_filter_output_check", "pii_output_check"]:
                if node_name in chunk:
                    output = chunk[node_name]
                    if output.get("is_blocked"):
                        blocked_by = output.get("blocked_by", "unknown")
                        block_reason = output.get("block_reason", "检测到安全风险")
                        risk_level = output.get("risk_level", "high")
                        violated_rules = output.get("violated_rules", [])
                        response_message = output.get("response_message")

                        reject_data = {
                            "status": "reject",
                            "blocked_by": blocked_by,
                            "risk_level": risk_level,
                            "detected_issues": block_reason,
                            "violated_rules": violated_rules,
                            "response_message": response_message,
                        }
                        yield f"data: {json.dumps(reject_data, ensure_ascii=False)}\n\n"
                        return
                    # 收集检测信息（检测模式下）
                    if node_name == "pii_input_check" and output.get("pii_input_detected"):
                        stream_detection_nodes.append({
                            "node": "pii_input_check",
                            "detect_reason": f"PII detected: {', '.join(output.get('pii_input_entities', []))}",
                            "detected_by": "pii",
                            "violated_rules": output.get("pii_input_entities", []),
                        })
                    elif node_name == "content_filter_check" and output.get("content_filter_triggered"):
                        cats = output.get("content_filter_categories", [])
                        stream_detection_nodes.append({
                            "node": "content_filter_check",
                            "detect_reason": f"内容过滤触发: {', '.join(cats)}",
                            "detected_by": "content_filter",
                            "violated_rules": cats,
                        })
                    elif node_name == "content_filter_output_check" and output.get("content_filter_triggered"):
                        cats = output.get("content_filter_categories", [])
                        stream_detection_nodes.append({
                            "node": "content_filter_output_check",
                            "detect_reason": f"输出内容过滤触发: {', '.join(cats)}",
                            "detected_by": "content_filter",
                            "violated_rules": cats,
                        })
                    elif node_name == "pii_output_check" and output.get("pii_output_detected"):
                        stream_detection_nodes.append({
                            "node": "pii_output_check",
                            "detect_reason": f"PII detected in output: {', '.join(output.get('pii_output_entities', []))}",
                            "detected_by": "pii",
                            "violated_rules": output.get("pii_output_entities", []),
                        })

            # 收集 call_llm 的最终消息和响应
            if "call_llm" in chunk:
                output = chunk["call_llm"]
                final_messages = output.get("messages", [])
                if output.get("response"):
                    full_response = output["response"]

            # 捕获 pii_output_check 的脱敏结果（覆盖原始响应）
            if "pii_output_check" in chunk:
                output = chunk["pii_output_check"]
                if output.get("response"):
                    full_response = output["response"]

        # 输出检测汇总（检测模式下，在 DONE 之前）
        if stream_detection_nodes:
            last_detection = stream_detection_nodes[-1]
            detection_summary = {
                "last_detected_node": last_detection["node"],
                "detect_reason": last_detection["detect_reason"],
                "detected_by": last_detection["detected_by"],
                "violated_rules": last_detection["violated_rules"],
                "all_detections": stream_detection_nodes,
            }
            yield f"data: [DETECTION_SUMMARY]{json.dumps(detection_summary, ensure_ascii=False)}\n\n"

        # 分段发送最终响应（支持 PII 脱敏后的内容）
        if full_response:
            for i in range(0, len(full_response), 2):
                yield f"data: {full_response[i:i+2]}\n\n"
                await asyncio.sleep(0.025)

        yield "data: [DONE]\n\n"

    async def _stream_without_output_check(
        self, message: str, conversation_id: str
    ) -> AsyncIterator[str]:
        """真正流式：输入检测走工作流，通过后直接调用 LLM 的 astream 边生成边返回。

        仅当内容过滤输出检测关闭时走此分支，输入检测复用 self.input_check_workflow。
        """
        config = {
            "configurable": {
                "thread_id": conversation_id,
                "checkpoint_ns": ""
            }
        }
        initial_state = {
            "current_input": message,
            "response": "",
            "is_blocked": False,
            "block_reason": ""
        }

        # ---------- 输入检测：复用工作流 ----------
        result = await self.input_check_workflow.ainvoke(initial_state, config)

        if result.get("is_blocked"):
            blocked_by = result.get("blocked_by", "unknown")
            block_reason = result.get("block_reason", "检测到安全风险")
            risk_level = "high"
            violated_rules = result.get("violated_rules", [])
            response_message = result.get("response_message")

            reject_data = {
                "status": "reject",
                "blocked_by": blocked_by,
                "risk_level": risk_level,
                "detected_issues": block_reason,
                "violated_rules": violated_rules,
                "response_message": response_message,
            }
            yield f"data: {json.dumps(reject_data, ensure_ascii=False)}\n\n"
            return

        messages = result.get("messages", [])
        processed_input = result.get("processed_input") or message

        # ---------- 流式调用 LLM ----------
        langchain_messages = []
        for msg in messages:
            if msg["role"] == "user":
                langchain_messages.append(HumanMessage(content=msg["content"]))
            elif msg["role"] == "assistant":
                langchain_messages.append(AIMessage(content=msg["content"]))
        langchain_messages.append(HumanMessage(content=processed_input))

        full_response = ""
        first_token_yielded = False
        try:
            async for token in self.chain.astream(
                {"messages": langchain_messages, "input": processed_input},
                config,
            ):
                # 处理 LangChain 消息对象，提取纯文本
                if hasattr(token, "content"):
                    token = token.content
                if not isinstance(token, str):
                    token = str(token)
                if not token:
                    continue
                # 过滤开头的纯空白 token，避免返回空内容
                if not first_token_yielded and not token.strip():
                    continue
                first_token_yielded = True
                full_response += token
                yield f"data: {token}\n\n"
        except Exception as e:
            self.logger.error(f"流式调用 LLM 出错：{str(e)}", exc_info=True)
            yield f"data: 抱歉，流式响应出现错误。\n\n"
            yield "data: [DONE]\n\n"
            return

        yield "data: [DONE]\n\n"

    async def process_message_stream_graph(
        self, message: str, conversation_id: str = "default"
    ) -> AsyncIterator[str]:
        """通过工作流处理消息并以流式响应返回。

        根据输出检测开关状态自动选择策略：
        - 输出检测开启时：走完整工作流，等全部节点完成后分段输出（伪流式）
        - 输出检测关闭时：直接调用 LLM astream，边生成边返回（真正流式）
        """
        message_print = f"{message[:LOG_STR_MAX]}..." if len(message) > LOG_STR_MAX else message
        try:
            if self._need_output_check():
                self.logger.info(f"流式处理（伪流式，输出检测开启）：{message_print}")
                async for chunk in self._stream_with_output_check(message, conversation_id):
                    yield chunk
            else:
                self.logger.info(f"流式处理（真正流式，输出检测关闭）：{message_print}")
                async for chunk in self._stream_without_output_check(message, conversation_id):
                    yield chunk
        except Exception as e:
            self.logger.error(f"流式图处理出错：{str(e)}", exc_info=True)
            yield f"data: 抱歉，流式响应出现错误。\n\n"

    def reload_guardrails(self, update_custom_rules: List[Dict] = None) -> bool:
        """重新加载GuardRails规则。

        Args:
            update_custom_rules: 更新后的自定义规则列表

        Returns:
            bool: 重新加载是否成功
        """
        if not GUARDRAILS_AVAILABLE or not self.enable_guardrails:
            self.logger.warning("GuardRails AI 不可用或已禁用，无法重新加载规则")
            return False

        try:
            # 更新自定义规则
            if update_custom_rules is not None:
                self.custom_rules = update_custom_rules

            # 重新初始化 GuardRails
            old_guard = self.guard
            self.guard = self._initialize_guardrails(self.custom_rules)

            if self.guard:
                self.logger.info(f"GuardRails AI 重新加载成功，加载了 {len(self.custom_rules)} 个规则")
                return True
            else:
                # 如果重新加载失败，回退到旧版本
                self.guard = old_guard
                self.logger.error("GuardRails AI 重新加载失败")
                return False

        except Exception as e:
            self.logger.error(f"GuardRails AI 重新加载失败：{e}", exc_info=True)
            return False

    def reload_pii_config(self, pii_config: Dict[str, Any]) -> bool:
        """从数据库配置重新加载PII检测服务。

        Args:
            pii_config: 数据库PII配置字典，包含 input_enabled, output_enabled, input_entities, output_entities 等

        Returns:
            bool: 重新加载是否成功
        """
        try:
            input_enabled = pii_config.get("input_enabled", False)
            output_enabled = pii_config.get("output_enabled", False)
            input_entities = pii_config.get("input_entities", [])
            output_entities = pii_config.get("output_entities", [])
            use_presidio = pii_config.get("use_presidio", True)
            threshold = pii_config.get("threshold", 0.0)
            anonymize_input = pii_config.get("anonymize_input", True)
            anonymize_output = pii_config.get("anonymize_output", True)
            input_action_mode = pii_config.get("input_action_mode", "detect")
            output_action_mode = pii_config.get("output_action_mode", "detect")

            self.enable_pii_detection = input_enabled or output_enabled

            if not PII_DETECTION_AVAILABLE:
                self.logger.warning("PII检测模块不可用，无法加载配置")
                return False

            if self.pii_service is None:
                self.pii_service = get_pii_service()

            self.pii_service.update_config(
                input_entities=input_entities,
                output_entities=output_entities,
                use_presidio=use_presidio,
                threshold=threshold,
                anonymize_input=anonymize_input,
                anonymize_output=anonymize_output,
                input_action_mode=input_action_mode,
                output_action_mode=output_action_mode,
                input_enabled=input_enabled,
                output_enabled=output_enabled,
            )

            self.logger.info(
                f"PII检测服务已从数据库重新加载: "
                f"input_enabled={input_enabled}, output_enabled={output_enabled}, "
                f"input_action_mode={input_action_mode}, output_action_mode={output_action_mode}, "
                f"input_entities={input_entities}, output_entities={output_entities}"
            )
            return True

        except (AttributeError, TypeError) as e:
            self.logger.error(f"PII配置格式错误: {e}", exc_info=True)
            return False
        except Exception as e:
            self.logger.error(f"PII配置重新加载失败: {e}", exc_info=True)
            return False

    def reload_prompt_defense_config(self, prompt_config: Dict[str, Any]) -> bool:
        """从数据库配置重新加载提示词防御配置。

        Args:
            prompt_config: 数据库提示词防御配置字典，包含 enabled, prompt_content

        Returns:
            bool: 重新加载是否成功
        """
        try:
            enabled = prompt_config.get("enabled", True)
            prompt_content = prompt_config.get("prompt_content", "")

            self.enable_prompt_defense = enabled
            if prompt_content:
                self.security_check_prompt = prompt_content

            self.logger.info(
                f"提示词防御配置已从数据库重新加载: enabled={enabled}, "
                f"prompt_length={len(self.security_check_prompt)}"
            )
            return True

        except (AttributeError, TypeError) as e:
            self.logger.error(f"提示词防御配置格式错误: {e}", exc_info=True)
            return False
        except Exception as e:
            self.logger.error(f"提示词防御配置重新加载失败: {e}", exc_info=True)
            return False

    def reload_content_filter_config(self, cf_config: Dict[str, Any]) -> bool:
        """从数据库配置重新加载内容过滤服务。

        Args:
            cf_config: 数据库内容过滤配置字典，包含 input_enabled, output_enabled, action_mode

        Returns:
            bool: 重新加载是否成功
        """
        try:
            input_enabled = cf_config.get("input_enabled", True)
            output_enabled = cf_config.get("output_enabled", True)
            action_mode = cf_config.get("action_mode", "block")

            self.enable_content_filter = input_enabled or output_enabled

            if not CLASSIFIER_AVAILABLE:
                self.logger.warning("内容过滤模块不可用，无法加载配置")
                return False

            if self.content_filter_service is None:
                self.content_filter_service = get_classifier_service()

            self.content_filter_service.update_config(
                input_enabled=input_enabled,
                output_enabled=output_enabled,
                action_mode=action_mode,
            )

            self.logger.info(
                f"内容过滤配置已从数据库重新加载: input_enabled={input_enabled}, "
                f"output_enabled={output_enabled}, action_mode={action_mode}"
            )
            return True

        except (AttributeError, TypeError) as e:
            self.logger.error(f"内容过滤配置格式错误: {e}", exc_info=True)
            return False
        except Exception as e:
            self.logger.error(f"内容过滤配置重新加载失败: {e}", exc_info=True)
            return False

    def reload_runtime_config(self) -> bool:
        """重新加载运行时配置并更新PII检测服务。

        Returns:
            bool: 重新加载是否成功
        """
        try:
            runtime_config = get_guardrail_config()

            # 更新GuardRails启用状态
            black_list_config = runtime_config.get("black_list", {})
            new_guardrails_enabled = black_list_config.get("enabled", True)

            # 如果GuardRails从禁用变为启用，需要重新初始化
            if new_guardrails_enabled and not self.enable_guardrails and GUARDRAILS_AVAILABLE:
                self.guard = self._initialize_guardrails(self.custom_rules)
                self.logger.info("GuardRails AI 已从禁用状态重新初始化")

            self.enable_guardrails = new_guardrails_enabled

            self.logger.info(
                f"运行时配置已重新加载: white_list={runtime_config.get('white_list', {}).get('enabled', False)}, "
                f"black_list={self.enable_guardrails}, "
                f"prompt_defense={runtime_config.get('prompt_defense', {}).get('enabled', True)}"
            )
            return True

        except (AttributeError, TypeError) as e:
            self.logger.error(f"运行时配置格式错误: {e}", exc_info=True)
            return False
        except Exception as e:
            self.logger.error(f"运行时配置重新加载失败: {e}", exc_info=True)
            return False


# 单例实例
_agent_instance = None


def get_agent(
    custom_guardrail_rules: List[Dict] = None,
    prompt_defense_config: Dict[str, Any] = None,
    checkpointer: AsyncSqliteSaver | None = None
) -> LangChainAgent:
    """获取或创建单例代理实例。

    Args:
        custom_guardrail_rules: 自定义的GuardRails规则列表
        prompt_defense_config: 提示词防御配置，从数据库加载
        checkpointer: LangGraph AsyncSqliteSaver 持久化 checkpointer
    """
    global _agent_instance
    if _agent_instance is None:
        _agent_instance = LangChainAgent(
            custom_guardrail_rules=custom_guardrail_rules,
            prompt_defense_config=prompt_defense_config,
            checkpointer=checkpointer
        )
    else:
        if custom_guardrail_rules is not None and _agent_instance.custom_rules != custom_guardrail_rules:
            # 如果实例已存在但有新的规则，重新加载
            _agent_instance.reload_guardrails(custom_guardrail_rules)
        if prompt_defense_config is not None:
            _agent_instance.reload_prompt_defense_config(prompt_defense_config)
    return _agent_instance