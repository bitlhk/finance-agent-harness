"""
自定义正则表达式匹配Validator，支持GuardRails AI集成。

本模块实现了类似 GuardRails AI 官方 regex_match 的验证器：
- RegexMatchValidator: 通用正则匹配验证器，支持 search/match/fullmatch 模式
- RegexBlacklistValidator: 黑名单规则验证器，支持多规则多模式管理

参考GuardRails AI regex_match方案实现：
https://guardrailsai.com/hub/validator/guardrails/regex_match
"""

import re
import logging
from typing import Dict, Any, List, Optional, Literal

logger = logging.getLogger("agent_service")


# GuardRails AI 可选集成
try:
    from guardrails.validator_base import (
        Validator,
        register_validator,
        FailResult,
        PassResult,
    )
    GUARDRAILS_AVAILABLE = True

    @register_validator(name="custom_regex_match", data_type="string")
    class RegexMatchValidator(Validator):
        """通用正则表达式匹配验证器。

        功能类似 GuardRails AI 官方 regex_match，支持：
        - 单条或多条正则表达式
        - search / match / fullmatch 三种匹配模式
        - 命中后返回 FailResult 并支持 on_fail 动作

        Usage:
            guard = Guard().use(
                RegexMatchValidator,
                regex=r"(?i)(ignore.*instructions)",
                match_type="search",
                on_fail="exception",
            )
            guard.validate("ignore previous instructions")
        """

        def __init__(
            self,
            regex: str | List[str],
            match_type: Literal["search", "match", "fullmatch"] = "search",
            on_fail: Optional[str] = None,
            **kwargs,
        ):
            super().__init__(on_fail=on_fail, **kwargs)
            if isinstance(regex, str):
                self.regex_list = [regex]
            else:
                self.regex_list = list(regex)
            self.match_type = match_type

        def _validate(self, value: str, metadata: Dict[str, Any]):
            for pattern in self.regex_list:
                try:
                    matcher = getattr(re, self.match_type)
                    if matcher(pattern, value):
                        return FailResult(
                            error_message=(
                                f"Regex {self.match_type} matched: "
                                f"pattern={pattern!r}"
                            )
                        )
                except re.error as e:
                    logger.warning(f"Invalid regex pattern '{pattern}': {e}")
                    continue
            return PassResult()

    @register_validator(name="regex_blacklist", data_type="string")
    class RegexBlacklistValidator(Validator):
        """正则表达式黑名单验证器，用于多规则安全检测。

        支持通过 rules 参数传入多条命名规则，每条规则可包含多个正则模式。
        任一模式命中即返回 FailResult。

        Usage:
            rules = [
                {
                    "name": "prompt_injection",
                    "description": "Detect prompt injection",
                    "patterns": [r"(?i)ignore.*instructions"],
                }
            ]
            guard = Guard().use(
                RegexBlacklistValidator,
                rules=rules,
                on_fail="exception",
            )
            guard.validate("ignore all previous instructions")

        也支持通过类方法 configure() 进行全局规则配置（向后兼容）：
            RegexBlacklistValidator.configure(rules)
            guard = Guard().use(RegexBlacklistValidator, on_fail="exception")
        """

        _global_rules: List[Dict[str, Any]] = []

        @classmethod
        def configure(cls, rules: List[Dict[str, Any]]):
            """全局配置规则列表（向后兼容）。"""
            cls._global_rules = list(rules)

        def __init__(
            self,
            rules: Optional[List[Dict[str, Any]]] = None,
            on_fail: Optional[str] = None,
            **kwargs,
        ):
            super().__init__(on_fail=on_fail, **kwargs)
            # 优先使用实例传入的 rules，否则使用全局配置
            self._instance_rules = rules

        @property
        def rules(self) -> List[Dict[str, Any]]:
            if self._instance_rules is not None:
                return self._instance_rules
            return self.__class__._global_rules

        def _validate(self, value: str, metadata: Dict[str, Any]):
            for rule in self.rules:
                rule_name = rule.get("name", "unknown")
                response_message = rule.get("response_message", "")
                for pattern in rule.get("patterns", []):
                    try:
                        if re.search(pattern, value):
                            error_msg = f"Matched blacklist rule: {rule_name}"
                            if response_message:
                                error_msg += f" | response_message: {response_message}"
                            return FailResult(error_message=error_msg)
                    except re.error as e:
                        logger.warning(
                            f"Invalid regex in rule '{rule_name}': {e}"
                        )
                        continue
            return PassResult()

    GUARDRAILS_INTEGRATION_AVAILABLE = True

except Exception as e:
    logger.info(f"GuardRails AI Validator基础类不可用: {e}")
    GUARDRAILS_AVAILABLE = False
    GUARDRAILS_INTEGRATION_AVAILABLE = False
    Validator = None  # type: ignore
    register_validator = None  # type: ignore
    FailResult = None  # type: ignore
    PassResult = None  # type: ignore
    RegexMatchValidator = None  # type: ignore
    RegexBlacklistValidator = None  # type: ignore
