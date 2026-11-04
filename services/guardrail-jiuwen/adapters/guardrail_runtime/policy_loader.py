"""JSON 策略加载器 + 热重载机制。

从 policy/ 目录加载 rules.json / prompts.json / pii_config.json / whitelist.json，
支持 watchdog 自动监听文件变更和 API 手动触发重载。
"""
from __future__ import annotations

import json
import logging
import re
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

from adapters.guardrail_runtime.policy_crypto import decrypt_config, read_key, PolicyCryptoError

logger = logging.getLogger("guardrail.policy")


class PolicyLoadError(Exception):
    """策略加载错误。"""


class PolicyLoader:
    """从 JSON 文件加载安全策略，支持热重载。

    用法:
        loader = PolicyLoader("policy/")
        rules = loader.get_rules()
        prompt = loader.get_prompt("security_check")
        loader.reload()  # 手动重载
    """

    def __init__(self, policy_dir: str | Path):
        self._policy_dir = Path(policy_dir)
        self._lock = threading.RLock()
        self._rules: List[Dict[str, Any]] = []
        self._prompts: Dict[str, Dict[str, Any]] = {}
        self._pii_config: Dict[str, Any] = {}
        self._whitelist: Dict[str, Any] = {}
        self._response_templates: Dict[str, Any] = {}
        self._content_filter_config: Dict[str, Any] = {}
        self._version: str = "unknown"
        self._watchdog_observer: Optional[Any] = None
        self._load_all()

    # ------------------------------------------------------------------ #
    # 公开接口
    # ------------------------------------------------------------------ #

    def get_rules(self) -> List[Dict[str, Any]]:
        """返回已编译的正则规则列表。每条规则含 id/name/patterns/compiled_patterns/decision/response_message。"""
        with self._lock:
            return list(self._rules)

    def get_prompt(self, name: str) -> str:
        """返回指定名称的提示词内容。"""
        with self._lock:
            prompt_data = self._prompts.get(name)
            if prompt_data is None:
                raise KeyError(f"Prompt '{name}' not found in policy")
            content = prompt_data.get("content", "")
            if isinstance(content, list):
                content = "\n".join(content)
            return content

    def get_prompt_meta(self, name: str) -> Dict[str, Any]:
        """返回提示词完整元数据。"""
        with self._lock:
            prompt_data = self._prompts.get(name)
            if prompt_data is None:
                raise KeyError(f"Prompt '{name}' not found in policy")
            return dict(prompt_data)

    def get_pii_config(self) -> Dict[str, Any]:
        """返回 PII 检测配置。"""
        with self._lock:
            return dict(self._pii_config)

    def get_whitelist(self) -> Dict[str, Any]:
        """返回白名单配置。"""
        with self._lock:
            return dict(self._whitelist)

    def get_response_templates(self) -> Dict[str, Any]:
        """返回标准拦截话术模板。"""
        with self._lock:
            return dict(self._response_templates)

    def get_content_filter_config(self) -> Dict[str, Any]:
        """返回内容过滤配置（锚点、阈值、开关）。"""
        with self._lock:
            return dict(self._content_filter_config)

    def get_response_message(
        self,
        stage: str,
        rule_id: Optional[str] = None,
        category: Optional[str] = None,
    ) -> str:
        """从话术模板中提取对应拦截话术。

        Args:
            stage: 检测阶段 (regex / llm / pii_input / pii_output / content_filter / unavailable / unknown)
            rule_id: 正则规则 ID（stage=regex 时使用）
            category: LLM 检测到的风险类别（stage=llm 时使用）

        Returns:
            对应的标准话术字符串
        """
        with self._lock:
            templates = self._response_templates.get("templates", {})
            if stage == "regex":
                regex_map = templates.get("regex", {})
                if rule_id and rule_id in regex_map:
                    return regex_map[rule_id]
                return templates.get("unknown", "安全警告：检测到安全风险，请求已被拦截。")
            elif stage == "llm":
                llm_cfg = templates.get("llm", {})
                fmt = llm_cfg.get("format", "安全风险提示：有{category}风险，请换一个话题。")
                cat = category or llm_cfg.get("default_category", "安全")
                return fmt.replace("{category}", cat)
            else:
                return templates.get(stage, templates.get("unknown", "安全警告：检测到安全风险，请求已被拦截。"))

    def get_version(self) -> str:
        """返回当前策略版本号。"""
        with self._lock:
            return self._version

    def get_status(self) -> Dict[str, Any]:
        """返回策略加载状态摘要。"""
        with self._lock:
            return {
                "version": self._version,
                "rules_count": len(self._rules),
                "prompts": list(self._prompts.keys()),
                "pii_config_loaded": bool(self._pii_config),
                "whitelist_loaded": bool(self._whitelist),
                "policy_dir": str(self._policy_dir),
            }

    def reload(self) -> Dict[str, Any]:
        """手动触发策略重载。返回加载状态摘要。"""
        logger.info("Manual policy reload triggered")
        self._load_all()
        return self.get_status()

    def start_watchdog(self) -> None:
        """启动 watchdog 自动监听 policy/ 目录文件变更。

        文件变更时自动重载策略。需要安装 watchdog 包。
        """
        try:
            from watchdog.observers import Observer
            from watchdog.events import FileSystemEventHandler
        except ImportError:
            logger.warning("watchdog not installed, auto-reload disabled")
            return

        if self._watchdog_observer is not None:
            logger.info("Watchdog already running")
            return

        class _Handler(FileSystemEventHandler):
            def __init__(self, loader: PolicyLoader):
                self._loader = loader

            def on_modified(self, event):
                if event.is_directory:
                    return
                if not event.src_path.endswith(".json"):
                    return
                logger.info(f"Policy file changed: {event.src_path}, reloading...")
                try:
                    self._loader._load_all()
                except PolicyLoadError:
                    logger.error("Encrypted policy reload rejected; previous configuration retained")

            def on_created(self, event):
                self.on_modified(event)

            def on_moved(self, event):
                from types import SimpleNamespace
                self.on_modified(SimpleNamespace(is_directory=event.is_directory, src_path=event.dest_path))

        observer = Observer()
        observer.schedule(_Handler(self), str(self._policy_dir), recursive=False)
        observer.daemon = True
        observer.start()
        self._watchdog_observer = observer
        logger.info(f"Watchdog started on {self._policy_dir}")

    def stop_watchdog(self) -> None:
        """停止 watchdog 监听。"""
        if self._watchdog_observer is not None:
            self._watchdog_observer.stop()
            self._watchdog_observer.join(timeout=5)
            self._watchdog_observer = None
            logger.info("Watchdog stopped")

    # ------------------------------------------------------------------ #
    # 内部加载逻辑
    # ------------------------------------------------------------------ #

    def _load_all(self) -> None:
        """加载所有策略 JSON 文件（原子切换：加载失败保留旧配置）。"""
        with self._lock:
            # 先加载到临时变量，全部成功后才提交
            version = "unknown"
            rules: List[Dict[str, Any]] = []
            prompts: Dict[str, Dict[str, Any]] = {}
            pii_config: Dict[str, Any] = {}
            whitelist: Dict[str, Any] = {}
            response_templates: Dict[str, Any] = {}
            content_filter_config: Dict[str, Any] = {}

            load_errors = []

            # 加载 rules.json
            rules_data = self._load_json("rules.json")
            if rules_data:
                version = rules_data.get("version", version)
                rules = self._compile_rules(rules_data.get("rules", []))
            else:
                load_errors.append("rules.json")

            # 加载 prompts.json
            prompts_data = self._load_json("prompts.json")
            if prompts_data:
                version = prompts_data.get("version", version)
                prompts = prompts_data.get("prompts", {})
            else:
                load_errors.append("prompts.json")

            # 加载 pii_config.json
            pii_data = self._load_json("pii_config.json")
            if pii_data:
                version = pii_data.get("version", version)
                pii_config = pii_data.get("pii_detection", {})

            # 加载 whitelist.json
            wl_data = self._load_json("whitelist.json")
            if wl_data:
                version = wl_data.get("version", version)
                whitelist = wl_data.get("whitelist", {})

            # 加载 response_templates.json
            rt_data = self._load_json("response_templates.json")
            if rt_data:
                response_templates = rt_data.get("templates", {})
                response_templates["_version"] = rt_data.get("version", "unknown")

            # 加载 content_filter_config.json
            cf_path = self._policy_dir / "content_filter_config.json"
            cf_data = None
            if cf_path.exists():
                try:
                    cf_data = json.loads(decrypt_config(cf_path.read_bytes(), read_key()))
                except (OSError, PolicyCryptoError):
                    # Do not publish partial policy, silently disable filtering, or log content.
                    raise PolicyLoadError("Cannot load encrypted content-filter policy") from None
            elif self._content_filter_config:
                raise PolicyLoadError("Encrypted content-filter policy was removed")
            if cf_data:
                content_filter_config = cf_data

            # 原子切换：如果关键文件（rules/prompts）加载失败且已有旧配置，保留旧配置
            if load_errors:
                if self._rules or self._prompts:
                    logger.warning(
                        f"Policy reload failed for {load_errors}, keeping previous configuration "
                        f"(rules={len(self._rules)}, prompts={len(self._prompts)})"
                    )
                    return
                else:
                    # 首次加载，没有旧配置可保留，继续提交（可能为空）
                    logger.warning(
                        f"Policy initial load with missing files: {load_errors}, "
                        f"proceeding with empty config"
                    )

            # 全部成功，原子提交
            self._rules = rules
            self._prompts = prompts
            self._pii_config = pii_config
            self._whitelist = whitelist
            self._response_templates = {"templates": response_templates}
            self._content_filter_config = content_filter_config
            self._version = version

            cf_categories = len(content_filter_config.get("categories", {})) if content_filter_config else 0
            logger.info(
                f"Policy loaded: version={version}, rules={len(rules)}, "
                f"prompts={list(prompts.keys())}, pii_loaded={bool(pii_config)}, "
                f"whitelist_loaded={bool(whitelist)}, "
                f"response_templates_loaded={bool(response_templates)}, "
                f"content_filter_categories={cf_categories}"
            )

    def _load_json(self, filename: str) -> Optional[Dict[str, Any]]:
        """加载单个 JSON 文件，失败时返回 None 并记录告警。"""
        filepath = self._policy_dir / filename
        if not filepath.exists():
            logger.warning(f"Policy file not found: {filepath}")
            return None
        try:
            with open(filepath, "r", encoding="utf-8") as f:
                return json.load(f)
        except json.JSONDecodeError as e:
            logger.error(f"JSON parse error in {filepath}: {e}")
            return None
        except Exception as e:
            logger.error(f"Failed to load {filepath}: {e}")
            return None

    def _compile_rules(self, raw_rules: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """编译正则规则，跳过编译失败的规则。"""
        compiled_rules: List[Dict[str, Any]] = []
        skipped = 0

        for rule in raw_rules:
            rule_id = rule.get("id", rule.get("name", "unknown"))
            patterns = rule.get("patterns", [])
            if not patterns:
                logger.warning(f"Rule '{rule_id}' has no patterns, skipping")
                skipped += 1
                continue

            compiled_patterns: List[re.Pattern] = []
            for pattern in patterns:
                try:
                    compiled = re.compile(pattern)
                    compiled_patterns.append(compiled)
                except re.error as e:
                    logger.warning(
                        f"Regex compile failed in rule '{rule_id}': pattern='{pattern}', error={e}"
                    )

            if not compiled_patterns:
                logger.warning(f"Rule '{rule_id}' has no valid patterns after compilation, skipping")
                skipped += 1
                continue

            compiled_rules.append(
                {
                    "id": rule_id,
                    "name": rule.get("name", rule_id),
                    "description": rule.get("description", ""),
                    "decision": rule.get("decision", "block"),
                    "output_enabled": rule.get("output_enabled", True),
                    "patterns": patterns,
                    "compiled_patterns": compiled_patterns,
                    "response_message": rule.get("response_message", ""),
                }
            )

        if skipped > 0:
            logger.warning(f"Total rules skipped: {skipped}")

        return compiled_rules
