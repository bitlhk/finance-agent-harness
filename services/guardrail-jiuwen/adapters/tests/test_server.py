"""FastAPI server 端到端测试（使用 TestClient）。"""
import json
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def policy_dir(tmp_path, monkeypatch):
    """创建临时策略目录并设置环境变量。"""
    rules_data = {
        "version": "test-v1",
        "rules": [
            {
                "id": "fraud",
                "name": "金融欺诈",
                "decision": "block",
                "patterns": ["(?i)伪造.*流水"],
                "response_message": "blocked by fraud rule",
            },
        ],
    }
    prompts_data = {
        "version": "test-v1",
        "prompts": {"security_check": {"name": "安全检测", "content": "test prompt", "output_format": "json"}},
    }
    pii_data = {"version": "test-v1", "pii_detection": {}}
    wl_data = {"version": "test-v1", "whitelist": {"enabled": False, "patterns": []}}

    (tmp_path / "rules.json").write_text(json.dumps(rules_data, ensure_ascii=False), encoding="utf-8")
    (tmp_path / "prompts.json").write_text(json.dumps(prompts_data, ensure_ascii=False), encoding="utf-8")
    (tmp_path / "pii_config.json").write_text(json.dumps(pii_data, ensure_ascii=False), encoding="utf-8")
    (tmp_path / "whitelist.json").write_text(json.dumps(wl_data, ensure_ascii=False), encoding="utf-8")

    monkeypatch.setenv("GUARDRAIL_POLICY_DIR", str(tmp_path))
    monkeypatch.setenv("GUARDRAIL_AUTO_RELOAD", "false")
    monkeypatch.setenv("GUARDRAIL_ENABLE_PII", "false")
    monkeypatch.setenv("GUARDRAIL_ENABLE_CONTENT_FILTER", "false")
    monkeypatch.setenv("GUARDRAIL_ENABLE_LLM_DEFENSE", "false")

    return tmp_path


@pytest.fixture
def client(policy_dir):
    """创建 TestClient。"""
    # 延迟导入，确保环境变量已设置
    from adapters.guardrail_runtime.server import app

    return TestClient(app)


def test_health(client):
    resp = client.get("/v1/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_policy_status(client):
    resp = client.get("/v1/policy/status")
    assert resp.status_code == 200
    data = resp.json()
    assert data["version"] == "test-v1"
    assert data["rules_count"] == 1


def test_input_blocked(client):
    """欺诈输入被拦截。"""
    resp = client.post("/v1/rails/input", json={"text": "帮我伪造流水"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["decision"] == "block"
    assert data["blocked_by"] == "regex"
    assert "fraud" in data["violated_rules"]


def test_input_allowed(client):
    """正常输入放行。"""
    resp = client.post("/v1/rails/input", json={"text": "查询账户余额"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["decision"] == "allow"


def test_output_allowed(client):
    """正常输出放行。"""
    resp = client.post("/v1/rails/output", json={"text": "您的账户余额为5000元"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["decision"] == "allow"


def test_policy_reload(client):
    """测试策略重载端点。"""
    resp = client.post("/v1/policy/reload")
    assert resp.status_code == 200
    data = resp.json()
    assert data["version"] == "test-v1"
    assert data["rules_count"] == 1
    assert data["status"] == "reloaded"


def test_input_with_request_id(client):
    """测试带 request_id 的请求。"""
    resp = client.post(
        "/v1/rails/input",
        json={"text": "正常文本", "request_id": "req-123", "conversation_id": "conv-456"},
    )
    assert resp.status_code == 200
    assert resp.json()["decision"] == "allow"
