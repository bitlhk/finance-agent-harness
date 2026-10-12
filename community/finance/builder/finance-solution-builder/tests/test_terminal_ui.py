import json
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import pytest
from finance_solution_builder.terminal_ui import InterfaceConfig, presentation_config
from finance_solution_builder.workbench_contracts import parse_selection, ApiError
from finance_solution_builder.experience import Experience
from finance_solution_builder.service import digest
from test_template_modules import selection


PROJECT = Path(__file__).resolve().parents[1]


def test_studio_brand_uses_full_and_collapsed_assets_without_inline_fallback():
    source = (PROJECT / "browser/workbench.js").read_text(encoding="utf-8")
    css = (PROJECT / "browser/studio-navigation.css").read_text(encoding="utf-8")
    html = (PROJECT / "examples/local-workbench/index.html").read_text(encoding="utf-8")
    assert 'class="brand-logo-full" src="/finance-harness-logo.png"' in source
    assert 'class="brand-logo-mark" src="/finance-harness-mark.png"' in source
    assert ".sidebar-collapsed .sidebar .brand-logo-full{display:none}" in css
    assert ".sidebar-collapsed .sidebar .brand-logo-mark{display:block}" in css
    assert 'href="/finance-harness-favicon.png"' in html


def test_new_drafts_prefer_configured_deepseek_v4_flash():
    source = (PROJECT / "browser/workbench.js").read_text(encoding="utf-8")
    assert 'preferredDefaultModelRef = "model-ea-002"' in source
    assert 'configured.find((m) => m.modelRef === preferredDefaultModelRef)' in source


def test_task_blueprint_switches_the_legacy_scenario_template():
    """A task blueprint must submit its own declared template, not the mobile default."""
    source = (PROJECT / "browser/workbench.js").read_text(encoding="utf-8")
    start = source.index("  function applyTaskBlueprintDefaults(blueprint) {")
    end = source.index("\n  function selectCustomComposition()", start)
    assert "state.scenario = blueprint.legacyTemplateId;" in source[start:end]


def test_internal_control_host_uses_the_catalog_runtime_source():
    support = json.loads((PROJECT / "templates/local-readonly-service/internal-control-host-support.v0.1.json")
                         .read_text(encoding="utf-8"))
    assert support["runtime"]["integrationRef"] == "repo://integrations/openjiuwen-java"


@pytest.mark.parametrize("terminal", ["MOBILE_WEB", "DESKTOP_WEB", "ATM_WEB"])
def test_terminal_contract_keeps_modules_and_identity(harness, terminal):
    body = selection(harness, schema_version="0.6", presentation=terminal)
    parsed = parse_selection(body)
    value = presentation_config(parsed.model_dump())
    assert value["terminal"] == terminal
    assert parsed.capabilityIds == body["capabilityIds"]
    assert parsed.selectedModelRefs == body["selectedModelRefs"]
    assert value["sessionPolicy"]["absoluteSeconds"] == (600 if terminal == "ATM_WEB" else 3600)


@pytest.mark.parametrize("change", [{"theme":"EVIL"}, {"assistantName":"<script>"},
    {"shortcuts":["BALANCE","BALANCE"]}, {"balanceCard":"https://evil"},
    {"principalId":"other"}, {"fontSize":100}, {"voiceInput":"true"}])
def test_untrusted_ui_configuration_rejected(change):
    with pytest.raises(ValueError):
        InterfaceConfig.model_validate(change)


def test_atm_microphone_rejected_and_responsive_mapped_explicitly():
    with pytest.raises(ValueError, match="ATM_VOICE"):
        presentation_config({"presentation":"ATM_WEB", "interfaceConfig":{"voiceInput":True}})
    assert presentation_config({"presentation":"RESPONSIVE_WEB"})["terminal"] == "MOBILE_WEB"


def test_legacy_responsive_start_view_does_not_mutate_record(harness):
    from finance_solution_builder.workbench_contracts import parse_saved_selection
    old=selection(harness,presentation="RESPONSIVE_WEB")
    assert parse_saved_selection(old).presentation=="MOBILE_WEB"
    assert old["presentation"]=="RESPONSIVE_WEB"
    with pytest.raises(ApiError):
        parse_selection(old)


class Host:
    def __init__(self, root):
        self.lock = threading.RLock()
        self.root = root
        self.instance = "instance-a"
        self.jobs = {"build-a":{"status":"RUNNING","instanceId":self.instance}}
        self.requests = []
        self.block = None
        candidate = root / "candidate"
        candidate.mkdir()
        config = presentation_config({})
        (candidate / "ui-config.json").write_text(json.dumps(config))
        (candidate / "service-lock.json").write_text(json.dumps({"capabilities":["account.balance.query"],
            "interface":{"configuration":config,"sha256":digest(candidate / "ui-config.json")}}))
    def owned(self, build, owner):
        if owner != "owner":
            raise ApiError("OWNER_REJECTED", 403)
        return self.jobs[build]
    def path(self, build): return self.root
    def _capture(self, build): return self.instance
    def _same_instance(self, build, target): return target == self.instance
    def _request(self, build, route, body, instance):
        self.requests.append((instance, route, body))
        if route == "/sessions":
            if self.block:
                self.block[0].set()
                self.block[1].wait(5)
            return 200, {"sessionId":"session-a"}
        return 200, {"status":"AUTHORIZED"}


def ticket(host):
    experience = Experience(host)
    token = experience.issue("build-a", "owner")["url"].split("#")[1]
    return experience, token


def test_ticket_bound_to_instance_and_no_identity_fields(tmp_path):
    host = Host(tmp_path)
    api, token = ticket(host)
    with pytest.raises(ApiError):
        api.call("build-a", token, "start", {"customer":"other"})
    assert not host.requests
    assert api.call("build-a", token, "start", {})["sessionId"] == "session-a"
    host.instance = "instance-b"
    with pytest.raises(ApiError, match="INSTANCE_CHANGED"):
        api.call("build-a", token, "status", {})


def test_end_during_start_discards_late_session(tmp_path):
    host = Host(tmp_path)
    api, token = ticket(host)
    host.block = (threading.Event(), threading.Event())
    with ThreadPoolExecutor() as pool:
        pending = pool.submit(api.call, "build-a", token, "start", {})
        assert host.block[0].wait(2)
        api.call("build-a", token, "reset", {})
        host.block[1].set()
        with pytest.raises(ApiError, match="INSTANCE_CHANGED"):
            pending.result(3)
    assert api.current("build-a", token)["session"] is None
    assert host.requests[-1][1] == "/sessions/session-a/end"


def test_network_failure_drops_session_without_stopping_instance(tmp_path):
    host = Host(tmp_path)
    api, token = ticket(host)
    api.call("build-a", token, "start", {})
    def failed(*a, **kw): raise ConnectionError()
    host._request = failed
    with pytest.raises(ApiError, match="SERVICE_UNAVAILABLE"):
        api.call("build-a", token, "status", {})
    assert api.current("build-a", token)["session"] is None
    assert host.jobs["build-a"]["status"] == "RUNNING"


def test_partial_business_result_status_and_failure_code_are_preserved(tmp_path):
    host = Host(tmp_path)
    api, token = ticket(host)
    api.call("build-a", token, "start", {})

    def partial(_build, route, _body, instance):
        assert instance == host.instance
        if route.endswith("/chat"):
            return 200, {"status":"PARTIAL", "failureCode":"MODEL_RESPONSE_UNAVAILABLE_AFTER_TOOL_SUCCESS",
                "response":"已保留业务结果。", "display":{"messageId":"message-a", "cards":[]}}
        return 200, {"status":"AUTHORIZED"}

    host._request = partial
    result = api.call("build-a", token, "chat", {"query":"查询余额", "runId":"run-a"})
    assert result["status"] == "PARTIAL"
    assert result["failureCode"] == "MODEL_RESPONSE_UNAVAILABLE_AFTER_TOOL_SUCCESS"
    assert api.current("build-a", token)["session"] == "session-a"


def test_atm_cannot_use_speech(tmp_path):
    host = Host(tmp_path)
    api, token = ticket(host)
    with pytest.raises(ApiError, match="ASR_DISABLED"):
        api.audio("build-a", token, b"audio", "audio/wav")
    assert not host.requests
