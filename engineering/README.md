# Engineering Plane

Engineering Plane 是长期构建期能力，不进入生产请求热路径。

当前已有仓内 `solution.yaml`、`OpenJiuwenSolutionAssembler` 与 `solution-lock.json` 薄装配器：检查引用摘要和能力绑定，安装原生工具并记录本次内容。它不是大型 Builder、自动优化平台或远端服务证明。

外部 Builder 历史联调记录仍保留在 docs/builder-handoff-*；本轮复用现有装配路径，不开发第二套 Builder。后续通用 Composition 仍由真实第二接入需求决定。Builder 使用 Python 不要求 Runtime 与其同语言。

## 本轮离线门禁

1. `python engineering/verify_contracts.py`：实际 JSON Schema 2020-12、日期格式、共享正反例和所有声明资产。缺依赖或空测试不会当成成功。
2. `mvn -B -ntp verify`：全部 Java 模块，包括确认/恢复/授权边界、共同财富语义、原生 AgentLoop。真实 Smoke 需显式启用，普通 PR 不访问真实业务。
3. `bash examples/solutions/wealth-facts-kit/run-controlled.sh`：可交接的 CLI 路径，实际 HTTP Adapter + 脚本模型，默认无生产 Key。
4. `python engineering/verify_wealth_kit.py`：核验五份运行报告的证据摘要、业务结果和拒绝计数。

安装 Python 依赖见 `requirements-conformance.txt`。CI 同时执行以上入口并保存 XML 与受控报告。依赖下载需要网络，测试业务调用只使用 loopback。详见 [快速接入](../examples/solutions/wealth-facts-kit/README.md)。

2026-09-06 边界回归纳入原门禁，无新增线上任务：保险可信身份、Checkpoint 精度与空值补参、
共享合同覆盖缺口、Runtime 实测版本、受控端点请求白名单及固定业务时钟。
Schema 和 Java 同时消费 `valid/coverage-gap.json`：格式合法不代表事实齐全，消费者必须返回 PARTIAL。
CLI 报告检查实际 Runtime 制品摘要、源请求尝试记录、业务时钟与真实证据时间的区分。
结果与兼容影响见 [主线边界修复验证](../docs/verification/2026-09-06-mainline-boundary-hardening.md)。
