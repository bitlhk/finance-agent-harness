from __future__ import annotations

import argparse
import json
import re
import sys
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse


PROJECT_ROOT = Path(__file__).resolve().parents[2]
WEB_ROOT = Path(__file__).resolve().parent

DEMOS = {
    "wealth-manager-previsit-v1.1": {
        "label": "财富经理访前助手",
        "solution": PROJECT_ROOT / "examples/wealth-manager-previsit/solution-build-input.v1.json",
        "build": PROJECT_ROOT / "build/wealth-manager-previsit-v1.1",
        "story": {
            "audience": "财富经理（员工辅助）",
            "scenario": "客户拜访前准备",
            "purpose": "在拜访前汇总客户事实、识别信息缺口，形成关键问题与面谈目标。",
            "capabilityId": "wealth.previsit-context.query",
            "capabilityLabel": "查询客户访前上下文",
            "systems": "Employee Agent · CRM · 持仓 · 产品",
            "providerLabel": "员工辅助系统适配器",
            "evaluationFocus": "事实与缺口、关键问题、面谈目标",
            "inputExample": "客户引用、财富经理身份、拜访目的",
            "outputExample": "访前事实、缺失项、来源证据、面谈目标",
            "evaluationPreview": {
                "sampleCount": 20,
                "passedCount": 18,
                "accuracyPercent": 90.0,
                "accuracyThresholdPercent": 85.0,
                "p95LatencyMs": 9200,
                "p95LatencyThresholdMs": 10000,
                "badCaseSummary": "2 条：关键信息缺失、面谈问题依据不足",
            },
        },
    },
    "wealth-customer-diagnosis-v1.1": {
        "label": "客户财富体检助手",
        "solution": PROJECT_ROOT / "examples/wealth-customer-diagnosis/solution-build-input.v1.json",
        "build": PROJECT_ROOT / "build/wealth-customer-diagnosis-v1.1",
        "story": {
            "audience": "手机银行客户（客户自助）",
            "scenario": "个人资产结构体检",
            "purpose": "分析资产结构、集中度和流动性，提示近期财富事件并生成关键问题。",
            "capabilityId": "wealth.customer-context.query",
            "capabilityLabel": "查询客户财富上下文",
            "systems": "非 AI 银行接口 · 账户 · 持仓 · 产品",
            "providerLabel": "客户自助银行适配器",
            "evaluationFocus": "资产结构、集中度、流动性、关键问题",
            "inputExample": "客户身份、体检范围、查询上下文",
            "outputExample": "资产结构、集中度提示、流动性提示、待确认问题",
            "evaluationPreview": {
                "sampleCount": 20,
                "passedCount": 17,
                "accuracyPercent": 85.0,
                "accuracyThresholdPercent": 85.0,
                "p95LatencyMs": 9500,
                "p95LatencyThresholdMs": 10000,
                "badCaseSummary": "3 条：集中度提示不足、流动性依据缺失",
            },
        },
    },
}

STAGE_META = {
    "validate_input": (
        "校验 Solution",
        "确认应用、使用者、Runtime、Pack、Provider 与 Eval 引用满足输入契约。",
        "solution-build-input.v1.json",
        "标准化 Solution",
    ),
    "resolve_assets": (
        "解析并锁定资产",
        "从 Harness 和固定提交解析全部资产，计算摘要，拒绝缺失或漂移的引用。",
        "Profile / Domain Pack / Provider / Runtime / Eval",
        "solution-lock.json",
    ),
    "assemble_candidate": (
        "装配 Candidate",
        "以 OpenJiuwen Java 模板为宿主，安装金融 Pack、Skill、Provider 接线与 Integration。",
        "已锁定资产闭包",
        "candidate/ Java 工程",
    ),
    "harness_tests": (
        "验证 Harness 合同",
        "运行 Finance Harness 自动测试，确认金融能力合同和安装机制没有回退。",
        "Finance Harness",
        "Harness 测试证据",
    ),
    "harness_install": (
        "安装 Harness 依赖",
        "将本次验证过的 Harness 构件安装到本地 Maven 依赖环境。",
        "Harness Maven 工程",
        "可供 Candidate 编译的依赖",
    ),
    "candidate_compile": (
        "编译 Candidate",
        "编译装配后的 OpenJiuwen Java 多模块工程，生成可运行 JAR。",
        "candidate/ + locked assets",
        "Candidate JAR",
    ),
    "candidate_asset_tests": (
        "验证装配资产",
        "验证声明的 Profile、Pack、Provider、能力卡和 Host Wiring 确实进入 Candidate。",
        "Candidate 资产清单",
        "资产与契约测试结果",
    ),
    "candidate_start_health": (
        "启动与健康检查",
        "启动 Candidate 进程并访问健康端点，验证产物不仅可编译而且可运行。",
        "Candidate JAR",
        "健康检查证据",
    ),
    "evaluation_dependencies": (
        "准备 Evaluation",
        "安装锁定 Eval Pack 所需的本地依赖，同时保持 Candidate 资产版本不变。",
        "Evaluation Pack",
        "可执行评测环境",
    ),
    "evaluation_frozen_replay": (
        "回放冻结评测",
        "对锁定的历史证据执行 replay，验证评测契约；不冒充当前 Candidate 在线业务效果。",
        "冻结 Eval Pack 与证据",
        "evaluation/ + 回放结果",
    ),
}

STAGE_EXPLANATION = {
    "validate_input": (
        "A · 看懂需求",
        "配置字段完整、格式正确，并且明确了要构建的 Agent、使用者和验收标准。",
        "业务目标被转换成 Builder 能稳定处理的标准输入。",
    ),
    "resolve_assets": (
        "A · 看懂需求",
        "所有引用都能找到固定版本和内容摘要；任何缺失或版本漂移都会停止构建。",
        "本轮使用的 Runtime、Pack、Provider 和 Eval 均已冻结版本。",
    ),
    "assemble_candidate": (
        "B · 完成装配",
        "声明的业务能力、系统接入和运行接线全部出现在 Candidate 中。",
        "生成可继续编译的 OpenJiuwen Java Agent 工程。",
    ),
    "harness_tests": (
        "C · 工程验证",
        "Finance Harness 的能力合同、安装机制和自动测试全部通过。",
        "确认 Builder 消费的金融能力底座没有回退。",
    ),
    "harness_install": (
        "C · 工程验证",
        "验证过的 Harness 构件能够被 Candidate 的 Maven 工程解析。",
        "准备好 Candidate 编译所需的本地依赖。",
    ),
    "candidate_compile": (
        "C · 工程验证",
        "装配后的工程编译成功，并生成可识别摘要的 Candidate JAR。",
        "得到经过编译的可运行 Agent 程序。",
    ),
    "candidate_asset_tests": (
        "C · 工程验证",
        "Profile、Pack、Provider、能力卡和 Host Wiring 与 Solution 声明一致。",
        "证明应装入的资产确实进入 Candidate，且没有装错组合。",
    ),
    "candidate_start_health": (
        "C · 工程验证",
        "Candidate 能成功启动，且健康检查端点在限定时间内返回正常。",
        "证明 Agent 不仅能编译，也能作为服务运行。",
    ),
    "evaluation_dependencies": (
        "D · 效果验收",
        "锁定的 Eval Pack 可以在独立评测环境中执行。",
        "准备好与本轮资产版本对应的验收工具。",
    ),
    "evaluation_frozen_replay": (
        "D · 效果验收",
        "冻结证据全部完成回放；它只证明评测契约可用，不替代在线业务验收。",
        "留下可复查的评测结果，并明确尚未完成的真实业务门禁。",
    ),
}


def _load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def configure_data_root(data_root: Path) -> None:
    """Point the read-only visualizer at an existing Builder workspace."""
    demo_paths = {
        "wealth-manager-previsit-v1.1": (
            "examples/wealth-manager-previsit/solution-build-input.v1.json",
            "build/wealth-manager-previsit-v1.1",
        ),
        "wealth-customer-diagnosis-v1.1": (
            "examples/wealth-customer-diagnosis/solution-build-input.v1.json",
            "build/wealth-customer-diagnosis-v1.1",
        ),
    }
    for demo_id, (solution_path, build_path) in demo_paths.items():
        DEMOS[demo_id]["solution"] = data_root / solution_path
        DEMOS[demo_id]["build"] = data_root / build_path


class DataPreflightError(RuntimeError):
    """Raised when the WebUI cannot load every configured demo safely."""


def validate_demo_data() -> None:
    """Validate all configured demos before the HTTP port is opened."""
    problems: list[str] = []
    for demo_id, demo in DEMOS.items():
        build_dir = demo["build"]
        required_files = (
            demo["solution"],
            build_dir / "solution-lock.json",
            build_dir / "build-report.json",
        )
        missing = [path for path in required_files if not path.is_file()]
        if missing:
            formatted = ", ".join(_safe_text(str(path)) for path in missing)
            problems.append(f"{demo_id}: missing {formatted}")
            continue
        try:
            build_demo_payload(demo_id)
        except (OSError, ValueError, KeyError, TypeError) as exc:
            problems.append(f"{demo_id}: invalid build data ({_safe_text(str(exc))})")
    if problems:
        details = "\n".join(f"- {problem}" for problem in problems)
        raise DataPreflightError(details)


def _safe_text(value: str) -> str:
    value = value.replace(str(PROJECT_ROOT), "$BUILDER_ROOT")
    value = re.sub(r"[A-Za-z]:\\Users\\[^\\]+", "%USERPROFILE%", value)
    return value


def _sanitize(value: Any) -> Any:
    if isinstance(value, str):
        return _safe_text(value)
    if isinstance(value, list):
        return [_sanitize(item) for item in value]
    if isinstance(value, dict):
        return {key: _sanitize(item) for key, item in value.items()}
    return value


def _stage_payload(stage: dict[str, Any], index: int) -> dict[str, Any]:
    title, summary, consumes, produces = STAGE_META.get(
        stage["name"],
        (stage["name"], "执行构建阶段。", "上一步输出", "阶段证据"),
    )
    group, pass_criteria, outcome = STAGE_EXPLANATION.get(
        stage["name"],
        ("构建执行", "阶段命令成功并留下可复核证据。", "生成阶段输出。"),
    )
    return {
        **_sanitize(stage),
        "index": index + 1,
        "title": title,
        "summary": summary,
        "consumes": consumes,
        "produces": produces,
        "group": group,
        "passCriteria": pass_criteria,
        "outcome": outcome,
    }


def _candidate_summary(candidate_dir: Path) -> dict[str, Any]:
    interesting = [
        "solution-lock.json",
        "build-report.json",
        "agents/domain-runtime/pom.xml",
        "agents/domain-runtime/target/domain-runtime-0.1.0-SNAPSHOT.jar",
        "knowledge-pack/snapshot-manifest.yaml",
    ]
    files = [item for item in interesting if (candidate_dir / item).exists()]
    total_files = sum(1 for path in candidate_dir.rglob("*") if path.is_file()) if candidate_dir.exists() else 0
    return {"totalFiles": total_files, "keyFiles": files}


def build_demo_payload(demo_id: str) -> dict[str, Any]:
    selected = DEMOS.get(demo_id, DEMOS["wealth-manager-previsit-v1.1"])
    build_dir = selected["build"]
    solution = _load_json(selected["solution"])
    report = _load_json(build_dir / "build-report.json")
    lock = _load_json(build_dir / "solution-lock.json")
    evaluation_path = build_dir / "evaluation" / "report.json"
    evaluation = _load_json(evaluation_path) if evaluation_path.exists() else {}
    stages = [_stage_payload(stage, index) for index, stage in enumerate(report["stages"])]
    assets = [lock["runtimeTemplate"], lock["runtimeIntegration"], *lock["assets"]]
    passed = sum(stage["status"] == "passed" for stage in stages)
    online = bool(report["verification"]["candidateOnlineEvaluationExecuted"])
    engineering_ready = (
        passed == len(stages)
        and bool(report["verification"]["candidateStarted"])
        and bool(report["verification"]["candidateAssetTestsExecuted"])
    )
    return {
        "demoId": demo_id,
        "label": selected["label"],
        "story": selected["story"],
        "availableDemos": [
            {
                "id": key,
                "label": value["label"],
                "audience": value["story"]["audience"],
                "capabilityId": value["story"]["capabilityId"],
            }
            for key, value in DEMOS.items()
        ],
        "solution": _sanitize(solution),
        "lock": _sanitize(lock),
        "report": _sanitize(report),
        "stages": stages,
        "assets": _sanitize(assets),
        "candidate": _candidate_summary(build_dir / "candidate"),
        "evaluationSummary": {
            "frozenCaseCount": len(evaluation.get("cases", [])),
            "rightWins": evaluation.get("summary", {}).get("rightWins", 0),
            "ties": evaluation.get("summary", {}).get("ties", 0),
            "candidateMeanElapsedMs": evaluation.get("summary", {}).get("meanElapsedMs", {}).get("right"),
            "accuracyThreshold": None,
            "p95LatencyThresholdMs": None,
            "onlineExecuted": online,
        },
        "qualification": {
            "engineeringReady": engineering_ready,
            "onlineEvaluationExecuted": online,
            "qualified": engineering_ready and online and report["status"] == "passed",
            "passedStages": passed,
            "totalStages": len(stages),
            "status": "合格 Agent" if engineering_ready and online else "工程门禁通过 · 在线业务验收待完成",
        },
        "optimization": [
            {
                "step": "观察",
                "state": "done",
                "text": "构建、启动和冻结评测通过，但在线业务评测未执行。",
            },
            {
                "step": "归因",
                "state": "done",
                "text": "缺口定位为部署身份与授权测试客户，责任对象是运行环境配置。",
            },
            {
                "step": "修正对象",
                "state": "active",
                "text": "补充 EA 部署身份、一次性 Token 配置与授权客户引用。",
            },
            {
                "step": "重验",
                "state": "pending",
                "text": "对同一 Lock 运行 Candidate 在线业务 Evaluation；通过后才升级为合格。",
            },
        ],
    }


class Handler(BaseHTTPRequestHandler):
    def _send(self, body: bytes, content_type: str, status: HTTPStatus = HTTPStatus.OK) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/api/demo":
            demo_id = parse_qs(parsed.query).get("id", ["wealth-manager-previsit-v1.1"])[0]
            try:
                body = json.dumps(build_demo_payload(demo_id), ensure_ascii=False).encode("utf-8")
            except (OSError, ValueError, KeyError) as exc:
                body = json.dumps({"error": str(exc)}, ensure_ascii=False).encode("utf-8")
                self._send(body, "application/json; charset=utf-8", HTTPStatus.SERVICE_UNAVAILABLE)
                return
            self._send(body, "application/json; charset=utf-8")
            return
        if parsed.path in {"/", "/workbench.html"}:
            self._send((WEB_ROOT / "workbench.html").read_bytes(), "text/html; charset=utf-8")
            return
        if parsed.path in {"/guide", "/guide.html", "/index.html"}:
            self._send((WEB_ROOT / "index.html").read_bytes(), "text/html; charset=utf-8")
            return
        self._send(b"not found", "text/plain; charset=utf-8", HTTPStatus.NOT_FOUND)

    def log_message(self, format: str, *args: object) -> None:
        return


def main() -> None:
    parser = argparse.ArgumentParser(description="Finance Solution Builder build visualizer")
    parser.add_argument("--port", type=int, default=18116)
    parser.add_argument(
        "--data-root",
        type=Path,
        default=PROJECT_ROOT,
        help="Builder project whose examples/ and build/ directories provide display data",
    )
    parser.add_argument(
        "--check-data",
        action="store_true",
        help="validate every configured demo and exit without opening an HTTP port",
    )
    args = parser.parse_args()
    configure_data_root(args.data_root.resolve())
    try:
        validate_demo_data()
    except DataPreflightError as exc:
        print("Finance Solution Builder WebUI data preflight failed:", file=sys.stderr)
        print(exc, file=sys.stderr)
        print(
            "Generate the missing build outputs or pass the correct --data-root. "
            "The HTTP service was not started.",
            file=sys.stderr,
        )
        raise SystemExit(2) from exc
    if args.check_data:
        print("Finance Solution Builder WebUI data preflight passed.", flush=True)
        return
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"Finance Solution Builder WebUI: http://127.0.0.1:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
