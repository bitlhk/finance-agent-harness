"""PolicyLoader 单元测试。"""
import json
import os
import tempfile
import time
from pathlib import Path

import pytest

from adapters.guardrail_runtime.policy_loader import PolicyLoader


@pytest.fixture
def policy_dir(tmp_path):
    """创建临时策略目录，写入测试 JSON 文件。"""
    rules_data = {
        "version": "test-v1",
        "rules": [
            {
                "id": "test_fraud",
                "name": "测试欺诈",
                "description": "test",
                "decision": "block",
                "patterns": ["(?i)(fake|伪造).*(document|流水)"],
                "response_message": "blocked",
            },
            {
                "id": "test_injection",
                "name": "注入攻击",
                "decision": "block",
                "patterns": ["(?i)ignore.*previous.*instruction"],
                "response_message": "",
            },
        ],
    }
    prompts_data = {
        "version": "test-v1",
        "prompts": {
            "security_check": {
                "name": "安全检测",
                "content": "You are a security expert. Return JSON.",
                "output_format": "json",
            }
        },
    }
    pii_data = {
        "version": "test-v1",
        "pii_detection": {
            "input_enabled": True,
            "input_entities": ["cn_pii"],
        },
    }
    wl_data = {
        "version": "test-v1",
        "whitelist": {"enabled": False, "patterns": []},
    }

    (tmp_path / "rules.json").write_text(json.dumps(rules_data, ensure_ascii=False), encoding="utf-8")
    (tmp_path / "prompts.json").write_text(json.dumps(prompts_data, ensure_ascii=False), encoding="utf-8")
    (tmp_path / "pii_config.json").write_text(json.dumps(pii_data, ensure_ascii=False), encoding="utf-8")
    (tmp_path / "whitelist.json").write_text(json.dumps(wl_data, ensure_ascii=False), encoding="utf-8")

    return tmp_path


def test_load_rules(policy_dir):
    """测试正常加载规则。"""
    loader = PolicyLoader(policy_dir)
    rules = loader.get_rules()
    assert len(rules) == 2
    assert rules[0]["id"] == "test_fraud"
    assert len(rules[0]["compiled_patterns"]) == 1
    assert rules[0]["compiled_patterns"][0].pattern == "(?i)(fake|伪造).*(document|流水)"


def test_load_prompts(policy_dir):
    """测试加载提示词。"""
    loader = PolicyLoader(policy_dir)
    prompt = loader.get_prompt("security_check")
    assert "security expert" in prompt


def test_load_pii_config(policy_dir):
    """测试加载 PII 配置。"""
    loader = PolicyLoader(policy_dir)
    pii = loader.get_pii_config()
    assert pii["input_enabled"] is True
    assert "cn_pii" in pii["input_entities"]


def test_load_whitelist(policy_dir):
    """测试加载白名单。"""
    loader = PolicyLoader(policy_dir)
    wl = loader.get_whitelist()
    assert wl["enabled"] is False


def test_version(policy_dir):
    """测试版本号。"""
    loader = PolicyLoader(policy_dir)
    assert loader.get_version() == "test-v1"


def test_status(policy_dir):
    """测试状态摘要。"""
    loader = PolicyLoader(policy_dir)
    status = loader.get_status()
    assert status["rules_count"] == 2
    assert "security_check" in status["prompts"]
    assert status["pii_config_loaded"] is True
    assert status["whitelist_loaded"] is True


def test_manual_reload(policy_dir):
    """测试手动重载：修改 JSON 后 reload 生效。"""
    loader = PolicyLoader(policy_dir)
    assert len(loader.get_rules()) == 2

    # 修改 rules.json，增加一条规则
    rules_data = json.loads((policy_dir / "rules.json").read_text(encoding="utf-8"))
    rules_data["rules"].append(
        {
            "id": "new_rule",
            "name": "新规则",
            "decision": "block",
            "patterns": ["test_pattern"],
            "response_message": "",
        }
    )
    (policy_dir / "rules.json").write_text(json.dumps(rules_data, ensure_ascii=False), encoding="utf-8")

    # 重载
    status = loader.reload()
    assert status["rules_count"] == 3
    assert len(loader.get_rules()) == 3


def test_bad_regex_skipped(policy_dir):
    """测试正则编译失败的规则被跳过。"""
    rules_data = json.loads((policy_dir / "rules.json").read_text(encoding="utf-8"))
    rules_data["rules"].append(
        {
            "id": "bad_regex",
            "name": "坏正则",
            "decision": "block",
            "patterns": ["[invalid regex ("],  # 故意写坏
            "response_message": "",
        }
    )
    (policy_dir / "rules.json").write_text(json.dumps(rules_data, ensure_ascii=False), encoding="utf-8")

    loader = PolicyLoader(policy_dir)
    rules = loader.get_rules()
    assert len(rules) == 2  # bad_regex 被跳过


def test_json_error_no_crash(policy_dir):
    """测试 JSON 格式错误时不崩溃。"""
    # 写入损坏的 JSON
    (policy_dir / "rules.json").write_text("{ invalid json }", encoding="utf-8")

    loader = PolicyLoader(policy_dir)
    # rules 加载失败，返回空列表
    assert loader.get_rules() == []
    # 其他文件仍正常加载
    assert loader.get_prompt("security_check") is not None


def test_missing_file_no_crash(tmp_path):
    """测试缺少文件时不崩溃。"""
    loader = PolicyLoader(tmp_path)
    assert loader.get_rules() == []
    assert loader.get_version() == "unknown"


def test_get_prompt_not_found(policy_dir):
    """测试获取不存在的提示词时抛 KeyError。"""
    loader = PolicyLoader(policy_dir)
    with pytest.raises(KeyError):
        loader.get_prompt("nonexistent")


def test_empty_patterns_skipped(policy_dir):
    """测试空 patterns 的规则被跳过。"""
    rules_data = json.loads((policy_dir / "rules.json").read_text(encoding="utf-8"))
    rules_data["rules"].append(
        {"id": "empty", "name": "空规则", "decision": "block", "patterns": [], "response_message": ""}
    )
    (policy_dir / "rules.json").write_text(json.dumps(rules_data, ensure_ascii=False), encoding="utf-8")

    loader = PolicyLoader(policy_dir)
    assert len(loader.get_rules()) == 2  # empty 被跳过
