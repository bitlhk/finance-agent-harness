from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]


def _load_server_module():
    path = ROOT / "examples/build-visualizer/server.py"
    spec = importlib.util.spec_from_file_location("build_visualizer_server", path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _write_build_fixture(module, tmp_path: Path, demo_id: str, display_name: str) -> None:
    solution_path = tmp_path / f"{demo_id}-solution.json"
    build_path = tmp_path / demo_id
    evaluation_path = build_path / "evaluation"
    evaluation_path.mkdir(parents=True)
    solution_path.write_text(
        json.dumps({"displayName": display_name}, ensure_ascii=False), encoding="utf-8"
    )
    stages = [
        "validate_input",
        "resolve_assets",
        "assemble_candidate",
        "candidate_compile",
        "candidate_start_health",
        "evaluation_frozen_replay",
    ]
    (build_path / "build-report.json").write_text(
        json.dumps(
            {
                "status": "passed_with_risk",
                "stages": [
                    {"name": stage, "status": "passed", "durationMs": index + 1}
                    for index, stage in enumerate(stages)
                ],
                "verification": {
                    "candidateStarted": True,
                    "candidateAssetTestsExecuted": True,
                    "candidateOnlineEvaluationExecuted": False,
                },
            }
        ),
        encoding="utf-8",
    )
    resolved = {
        "version": "1.0.0",
        "source": r"C:\Users\demo\asset",
        "status": "resolved",
        "digest": "sha256:fixture",
    }
    (build_path / "solution-lock.json").write_text(
        json.dumps(
            {
                "runtimeTemplate": {"id": "runtime-template", **resolved},
                "runtimeIntegration": {"id": "runtime-integration", **resolved},
                "assets": [{"id": "domain-pack", **resolved}],
            }
        ),
        encoding="utf-8",
    )
    (evaluation_path / "report.json").write_text(
        json.dumps(
            {
                "cases": [{"caseId": "case-1"}, {"caseId": "case-2"}],
                "summary": {
                    "rightWins": 2,
                    "ties": 0,
                    "meanElapsedMs": {"right": 14780},
                },
            }
        ),
        encoding="utf-8",
    )
    module.DEMOS[demo_id] = {
        **module.DEMOS[demo_id],
        "solution": solution_path,
        "build": build_path,
    }


def test_demo_payload_uses_real_build_evidence(tmp_path: Path) -> None:
    module = _load_server_module()
    _write_build_fixture(module, tmp_path, "wealth-manager-previsit-v1.1", "财富经理访前助手")
    payload = module.build_demo_payload("wealth-manager-previsit-v1.1")

    assert payload["solution"]["displayName"] == "财富经理访前助手"
    assert payload["qualification"]["engineeringReady"] is True
    assert payload["qualification"]["onlineEvaluationExecuted"] is False
    assert payload["qualification"]["qualified"] is False
    assert payload["qualification"]["passedStages"] == payload["qualification"]["totalStages"]
    assert payload["evaluationSummary"] == {
        "frozenCaseCount": 2,
        "rightWins": 2,
        "ties": 0,
        "candidateMeanElapsedMs": 14780,
        "accuracyThreshold": None,
        "p95LatencyThresholdMs": None,
        "onlineExecuted": False,
    }
    assert {stage["name"] for stage in payload["stages"]} >= {
        "resolve_assets",
        "assemble_candidate",
        "candidate_compile",
        "candidate_start_health",
        "evaluation_frozen_replay",
    }


def test_demo_payload_exposes_locked_assets_without_user_home(tmp_path: Path) -> None:
    module = _load_server_module()
    _write_build_fixture(module, tmp_path, "wealth-manager-previsit-v1.1", "财富经理访前助手")
    payload = module.build_demo_payload("wealth-manager-previsit-v1.1")

    assert all(asset["status"] == "resolved" for asset in payload["assets"])
    serialized = json.dumps(payload, ensure_ascii=False)
    assert "C:\\\\Users\\\\" not in serialized
    assert "%USERPROFILE%" in serialized


def test_demo_payloads_expose_distinct_business_stories(tmp_path: Path) -> None:
    module = _load_server_module()
    _write_build_fixture(module, tmp_path, "wealth-manager-previsit-v1.1", "财富经理访前助手")
    _write_build_fixture(module, tmp_path, "wealth-customer-diagnosis-v1.1", "客户财富体检助手")
    previsit = module.build_demo_payload("wealth-manager-previsit-v1.1")
    diagnosis = module.build_demo_payload("wealth-customer-diagnosis-v1.1")

    assert previsit["story"]["audience"] == "财富经理（员工辅助）"
    assert previsit["story"]["capabilityId"] == "wealth.previsit-context.query"
    assert diagnosis["story"]["audience"] == "手机银行客户（客户自助）"
    assert diagnosis["story"]["capabilityId"] == "wealth.customer-context.query"
    assert previsit["story"] != diagnosis["story"]
    assert previsit["availableDemos"] == diagnosis["availableDemos"]
    assert previsit["story"]["evaluationPreview"]["sampleCount"] == 20
    assert previsit["story"]["evaluationPreview"]["accuracyPercent"] == 90.0
    assert diagnosis["story"]["evaluationPreview"]["accuracyPercent"] == 85.0


def test_visualizer_handles_stale_api_payload_without_blank_page() -> None:
    html = (ROOT / "examples/build-visualizer/index.html").read_text(encoding="utf-8")

    assert "function legacyStory(demoId)" in html
    assert "payload.story||legacyStory(selectedId)" in html
    assert "if(version!==loadVersion)return" in html
    assert "演示数据暂时无法加载" in html
    assert "重新加载" in html
    assert "正在读取构建记录" in html
    assert "function setControlsEnabled(enabled)" in html
    assert "if(!data)return" in html


def test_default_page_is_an_agent_constructor_workbench() -> None:
    html = (ROOT / "examples/build-visualizer/workbench.html").read_text(encoding="utf-8")
    server = (ROOT / "examples/build-visualizer/server.py").read_text(encoding="utf-8")

    assert "Agent 构造工作台" in html
    assert "Agent 构建 Storyline" in html
    assert "一步一步完成构建" in html
    assert "完成本步并继续" in html
    assert 'parsed.path in {"/", "/workbench.html"}' in server
    assert 'parsed.path in {"/guide", "/guide.html", "/index.html"}' in server


def test_constructor_workbench_matches_finance_studio_visual_language() -> None:
    html = (ROOT / "examples/build-visualizer/workbench.html").read_text(encoding="utf-8")

    assert "--paper: #f6f6f4" in html
    assert "--green: #b8202b" in html
    assert "--success: #397755" in html
    assert "operational Finance Studio" in html
    assert '<link rel="icon" href="data:,">' in html
    assert ".story-step.current { border-color: var(--green); background: var(--mint)" in html
    assert ".story-step.complete i { color: #fff; background: var(--success); }" in html
    assert ".stage-page { max-width: 1500px; }" in html
    assert "background: #d9d9d7" in html
    assert "color: #262625" in html


def test_workbench_replays_build_steps_and_keeps_evidence_honest() -> None:
    html = (ROOT / "examples/build-visualizer/workbench.html").read_text(encoding="utf-8")

    assert "明确构建目标" in html
    assert "选择并锁定组件" in html
    assert "装配 Candidate" in html
    assert "构建并启动" in html
    assert "执行真实业务 Evaluation" in html
    assert "形成交付裁决" in html
    assert "这一页要做什么" in html
    assert "完成本步前确认" in html
    assert "function advance()" in html
    assert "index > maxVisited" in html
    assert "showStep(activeStep - 1)" in html
    assert "开发侧 Eval Pack 回放" in html
    assert "不计作当前 Candidate 在线效果" in html
    assert "等待银行测试环境" in html
    assert "本轮暂不可交付" in html
    assert "展开完整 Builder 活动与证据" in html


def test_workbench_exposes_assets_interfaces_and_controlled_optimization() -> None:
    html = (ROOT / "examples/build-visualizer/workbench.html").read_text(encoding="utf-8")

    assert "本轮需要装入的 7 类组件" in html
    assert "<strong>是什么：</strong>" in html
    assert "示例：${esc(item.example)}" in html
    assert "OpenJiuwen Java Runtime" in html
    assert "Profile" in html
    assert "Domain Pack" in html
    assert "金融能力合同" in html
    assert "Provider Adapter" in html
    assert "Runtime Integration" in html
    assert "Evaluation Pack" in html
    assert "CapabilityRequestV1 ↔ CapabilityResultV1" in html
    assert "assembly-board" in html
    assert "animateMotion" in html
    assert "共享组件" in html
    assert "多个金融场景可以直接复用" in html
    assert "场景组件" in html
    assert "随业务对象、流程或银行接入方式发生变化" in html
    assert "由什么组成" in html
    assert "怎样接入" in html
    assert "锁定的版本 / 来源" in html
    assert "点击一段连接，查看双方交接了什么" in html
    assert "一句话理解：Solution 说明“要装什么”" in html
    assert "上半部分：构建时，把组件装进 Agent" in html
    assert "下半部分：运行时，Agent 调用银行能力" in html
    assert "为什么需要这一步" in html
    assert "具体怎样执行" in html
    assert "通过什么接口或方式" in html
    assert "字段和结构逐项解释" in html
    assert "完整脱敏示例" in html
    assert "同一个分支或版本名的内容以后可能变化" in html
    assert "V1 表示第一版稳定合同" in html
    assert "Provider Adapter 是“翻译器”" in html
    assert "java -jar ${jarPath}" in html
    assert "renderPage(true)" in html
    assert "preserveScroll ? previousScroll : 0" in html
    assert "Builder 的自动构建与优化循环" in html
    assert 'id="builderOptimizationLoop"' in html
    assert "animateMotion" in html
    assert "ORCHESTRATOR" in html
    assert "调整结构或参数" in html
    assert "EvaluationReport" in html
    assert "达到业务门槛 → 进入交付" in html
    assert "次数用完 → 停止并保留未解决问题" in html
    assert "本页动画说明控制逻辑，不代表在线业务评测已经执行" in html
    assert "用授权客户和银行测试系统检验 Agent 是否真的可用" in html
    assert "不计入真实业务验收" in html
    assert "工程门禁" in html


def test_visualizer_can_read_build_data_from_a_separate_workspace(tmp_path: Path) -> None:
    module = _load_server_module()
    module.configure_data_root(tmp_path)

    assert module.DEMOS["wealth-manager-previsit-v1.1"]["solution"] == (
        tmp_path / "examples/wealth-manager-previsit/solution-build-input.v1.json"
    )
    assert module.DEMOS["wealth-customer-diagnosis-v1.1"]["build"] == (
        tmp_path / "build/wealth-customer-diagnosis-v1.1"
    )


def test_visualizer_preflight_accepts_complete_demo_data(tmp_path: Path) -> None:
    module = _load_server_module()
    _write_build_fixture(module, tmp_path, "wealth-manager-previsit-v1.1", "财富经理访前助手")
    _write_build_fixture(module, tmp_path, "wealth-customer-diagnosis-v1.1", "客户财富体检助手")

    module.validate_demo_data()


def test_visualizer_preflight_reports_every_missing_demo(tmp_path: Path) -> None:
    module = _load_server_module()
    module.configure_data_root(tmp_path)

    with pytest.raises(module.DataPreflightError) as exc_info:
        module.validate_demo_data()

    message = str(exc_info.value)
    assert "wealth-manager-previsit-v1.1" in message
    assert "wealth-customer-diagnosis-v1.1" in message
    assert "solution-lock.json" in message
    assert "build-report.json" in message


def test_visualizer_main_does_not_open_port_when_data_is_missing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    module = _load_server_module()
    opened = False

    def fail_if_server_opens(*args, **kwargs):
        nonlocal opened
        opened = True
        raise AssertionError("HTTP server must not start before data preflight passes")

    monkeypatch.setattr(module, "ThreadingHTTPServer", fail_if_server_opens)
    monkeypatch.setattr(
        module.sys,
        "argv",
        ["server.py", "--data-root", str(tmp_path), "--port", "18117"],
    )

    with pytest.raises(SystemExit) as exc_info:
        module.main()

    assert exc_info.value.code == 2
    assert opened is False


def test_storyline_opens_seven_distinct_full_width_pages() -> None:
    html = (ROOT / "examples/build-visualizer/index.html").read_text(encoding="utf-8")

    assert html.count("data-story-target=") == 7
    assert "等待在线业务验收" not in html.split('<nav class="flow"', 1)[0]
    assert "▶ 从头讲解一次" not in html
    assert 'id="buildStatus"' not in html
    assert 'id="replay"' not in html
    assert "function replay()" not in html
    assert 'data-story-target="solution"' in html
    assert 'data-story-target="needs"' in html
    assert html.count('class="phase" data-flow-layer="solution"') == 2
    assert '<section class="page" id="page"' in html
    assert 'class="workspace"' not in html
    assert "function renderGoal()" in html
    assert "function renderSolution()" in html
    assert "function renderNeeds()" in html
    assert "function renderAssemble()" in html
    assert "function renderRuntime()" in html
    assert "function renderEvaluation()" in html
    assert "function renderQualification()" in html
    assert 'data-flow-layer="business"' in html
    assert 'data-flow-layer="solution"' in html
    assert html.count('class="phase" data-flow-layer="builder"') == 4
    assert "业务需求怎样成为可交付 Agent" in html
    assert "定义目标 Agent 的 Solution" in html
    assert "选择 Solution 的资产与连接" in html
    assert "Builder 按 Solution 完成装配" in html
    assert "Builder 汇总证据并作出交付裁决" in html
    assert "业务需求 · ${data.story.scenario}" in html
    assert "SOLUTION 定义什么" in html
    assert "本轮 Agent 的方案边界" in html
    assert "Solution 承接业务需求" in html
    assert "下一步根据这份说明选择专业包" in html
    assert "这条链路的作用" in html
    assert "Solution 先确定目标 Agent 的方案边界" in html
    assert "业务场景变化时可以调整 Solution" in html
    assert "装配、验证、评估并裁决交付" in html
    assert "ONLINE EVALUATION PREVIEW · 演示推演" in html
    assert "当前没有调用银行系统" in html
    assert "不会把本栏写入交付裁决" in html


def test_asset_page_explains_finance_contract_and_module_interfaces() -> None:
    html = (ROOT / "examples/build-visualizer/index.html").read_text(encoding="utf-8")

    assert "Builder 要装哪些东西" in html
    assert "Agent 怎样取得银行数据和能力" in html
    assert "OpenJiuwen Agent Candidate" in html
    assert "Finance Harness / 金融能力合同" in html
    assert "DEVELOPMENT / 开发态" in html
    assert "<b>Solution Builder</b>" in html
    assert "金融能力合同" in html
    assert "wealth.previsit-context.query" in html
    assert "系统识别码：${esc(t.capabilityId)}" in html
    assert "供程序稳定识别这项能力" in html
    assert "为什么需要系统识别码" in html
    assert "04 · 金融能力合同 · ${t.capabilityLabel}" in html
    assert "对应下方 04 号组件" in html
    assert "对应 04 · 金融能力合同" in html
    assert "Builder 使用底座和 8 类组件拼出当前 Solution" in html
    assert "共享资产" in html
    assert "场景资产" in html
    assert "01–08 与下方组件卡完全对应" in html
    assert "01、02、07 进入 Agent Candidate" in html
    assert "03、04、06 进入 Finance Harness 接线" in html
    assert "05 连接银行系统" in html
    assert "08 对 Candidate 执行统一验收" in html
    assert "切换左侧场景时，只刷新这些高亮资产" not in html
    assert "function renderScenarioComparison()" not in html
    assert "Provider SPI" in html
    assert "Builder 负责" in html
    assert "银行项目团队负责" in html
    assert "这样做的价值" in html
    assert "Agent 提出统一请求 → Harness 校验并找到适配器" in html
    assert "Provider SPI 是什么" in html
    assert "本轮组建 Agent 所需的 8 类组件" in html
    assert "对应上图：" in html
    assert "architecture-support" in html
    assert "<dt>是什么</dt>" in html
    assert "<dt>组成</dt>" in html
    assert "<dt>架构对应</dt>" in html
    assert "<dt>接口</dt>" in html


def test_delivery_qualification_only_renders_on_final_story_page() -> None:
    html = (ROOT / "examples/build-visualizer/index.html").read_text(encoding="utf-8")

    assert "qualification:{index:'07',title:'Builder 汇总证据并作出交付裁决'}" in html
    assert "function renderSolution()" in html
    assert html.count("最终交付包") == 1
    assert "qualification:renderQualification" in html


def test_assembly_page_exposes_interactive_components_and_interfaces() -> None:
    html = (ROOT / "examples/build-visualizer/index.html").read_text(encoding="utf-8")

    assert "图中使用的组件" in html
    assert "组件之间的接口链" in html
    assert "data-assembly-key=" in html
    assert "CapabilityRequestV1" in html
    assert "CapabilityResultV1" in html
    assert "Builder 怎样根据验收结果继续优化" in html
    assert "function bindAssemblyExplorer()" in html
    assert "一轮装配与优化是怎样循环的" in html
    assert "assemblyCyclePath" in html
    assert "animateMotion" in html
    assert "data-assembly-key=" in html
    assert "renderAssemblyCycle()" in html


def test_runtime_evaluation_and_delivery_share_online_gates() -> None:
    html = (ROOT / "examples/build-visualizer/index.html").read_text(encoding="utf-8")

    assert "Builder 能独立处理本地部署" in html
    assert "CRM、持仓和产品接口对授权请求返回正常" in html
    assert "在线业务准确率门槛" in html
    assert "在线 P95 时延门槛" in html
    assert 'data-jump-story="runtime"' in html
    assert 'data-jump-story="evaluation"' in html
