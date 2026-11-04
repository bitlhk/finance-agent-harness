"""热重载集成测试：修改 policy JSON → reload → 新规则立即生效。"""
import json
import time
from pathlib import Path

import pytest

from adapters.guardrail_runtime.hardened_engine import HardenedEngine, DECISION_ALLOW, DECISION_BLOCK
from adapters.guardrail_runtime.policy_loader import PolicyLoader


@pytest.fixture
def policy_dir(tmp_path):
    rules = {
        "version": "v1",
        "rules": [
            {
                "id": "initial_rule",
                "name": "初始规则",
                "decision": "block",
                "patterns": ["initial_keyword"],
                "response_message": "",
            }
        ],
    }
    prompts = {"version": "v1", "prompts": {"security_check": {"content": "test"}}}
    pii = {"version": "v1", "pii_detection": {}}
    wl = {"version": "v1", "whitelist": {"enabled": False, "patterns": []}}

    for name, data in [("rules.json", rules), ("prompts.json", prompts), ("pii_config.json", pii), ("whitelist.json", wl)]:
        (tmp_path / name).write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    return tmp_path


@pytest.mark.asyncio
async def test_add_rule_via_reload(policy_dir):
    """添加新规则后 reload，新规则立即生效。"""
    loader = PolicyLoader(policy_dir)
    engine = HardenedEngine(loader, enable_pii=False, enable_content_filter=False, enable_llm_defense=False)

    # 初始：新关键词不触发
    result = await engine.evaluate_input("new_keyword_test")
    assert result.decision == DECISION_ALLOW

    # 添加新规则
    rules_data = json.loads((policy_dir / "rules.json").read_text(encoding="utf-8"))
    rules_data["rules"].append({
        "id": "new_rule",
        "name": "新规则",
        "decision": "block",
        "patterns": ["new_keyword_test"],
        "response_message": "",
    })
    (policy_dir / "rules.json").write_text(json.dumps(rules_data), encoding="utf-8")

    # reload
    loader.reload()

    # 新规则生效
    result = await engine.evaluate_input("new_keyword_test")
    assert result.decision == DECISION_BLOCK
    assert "new_rule" in result.violated_rules


@pytest.mark.asyncio
async def test_remove_rule_via_reload(policy_dir):
    """删除规则后 reload，该规则不再触发。"""
    loader = PolicyLoader(policy_dir)
    engine = HardenedEngine(loader, enable_pii=False, enable_content_filter=False, enable_llm_defense=False)

    # 初始：initial_keyword 被拦截
    result = await engine.evaluate_input("initial_keyword")
    assert result.decision == DECISION_BLOCK

    # 删除所有规则
    rules_data = json.loads((policy_dir / "rules.json").read_text(encoding="utf-8"))
    rules_data["rules"] = []
    (policy_dir / "rules.json").write_text(json.dumps(rules_data), encoding="utf-8")

    # reload
    loader.reload()

    # 规则已删除，不再拦截
    result = await engine.evaluate_input("initial_keyword")
    assert result.decision == DECISION_ALLOW


@pytest.mark.asyncio
async def test_modify_rule_pattern_via_reload(policy_dir):
    """修改规则 pattern 后 reload，新 pattern 生效。"""
    loader = PolicyLoader(policy_dir)
    engine = HardenedEngine(loader, enable_pii=False, enable_content_filter=False, enable_llm_defense=False)

    # 修改 pattern
    rules_data = json.loads((policy_dir / "rules.json").read_text(encoding="utf-8"))
    rules_data["rules"][0]["patterns"] = ["modified_keyword"]
    (policy_dir / "rules.json").write_text(json.dumps(rules_data), encoding="utf-8")

    loader.reload()

    # 旧 keyword 不再触发
    result = await engine.evaluate_input("initial_keyword")
    assert result.decision == DECISION_ALLOW

    # 新 keyword 触发
    result = await engine.evaluate_input("modified_keyword")
    assert result.decision == DECISION_BLOCK


@pytest.mark.asyncio
async def test_version_update_via_reload(policy_dir):
    """reload 后版本号更新。"""
    loader = PolicyLoader(policy_dir)
    assert loader.get_version() == "v1"

    # 更新所有 JSON 文件的版本号
    for fname in ["rules.json", "prompts.json", "pii_config.json", "whitelist.json"]:
        fpath = policy_dir / fname
        if fpath.exists():
            data = json.loads(fpath.read_text(encoding="utf-8"))
            data["version"] = "v2"
            fpath.write_text(json.dumps(data), encoding="utf-8")

    loader.reload()
    assert loader.get_version() == "v2"


@pytest.mark.asyncio
async def test_corrupted_json_does_not_crash(policy_dir):
    """JSON 文件损坏时 reload 不崩溃，保留旧规则（原子切换策略）。"""
    loader = PolicyLoader(policy_dir)
    assert len(loader.get_rules()) == 1

    # 写入损坏的 JSON
    (policy_dir / "rules.json").write_text("{ broken", encoding="utf-8")

    # reload 不崩溃
    loader.reload()

    # 旧规则保留（原子切换：加载失败不提交空配置）
    assert len(loader.get_rules()) == 1
    assert loader.get_rules()[0]["id"] == "initial_rule"
