# Builder 生成干净 Runtime 只读候选：本轮验证

日期：2026-09-06。状态：**Controlled Generated Candidate Verified / Live Host Integration Pending**。
不是完整手机银行交付，不是实授权限、真实银行替换、Builder WebUI 或人工独立接入验收。

## 固定版本

- Harness：`a515f7de6053d2dbf1cc633feecd685d64066c31`，分支 `feat/cross-repo-readonly-v01`。
- Builder：`8229b3c1dff86281720922c79e4408753445fbdd`，分支 `feat/harness-clean-runtime-readonly-v01`。
- Builder 上游 MR #118：`66d2767557cf4fd4890d55fe8729aaeafd30de97`；本批为独立本地补丁，未修改同事 MR/主线。
- 验证开始及结束两个工作区均干净；后续本文件为报告补充，不改所测代码。
- 原生 SDK `agent-core-java:0.1.13`，Harness `0.1.0-SNAPSHOT`。
  六个 Harness 运行 JAR 摘要逐一匹配本次构建；完整 99 个依赖 JAR 摘要在 Builder Lock。

主线仍为 `647b41c`。Harness 开发分支包含尚未合并的 PR #6；本轮不合并、不推送、不部署。
手机银行、模拟器和 EA 工作区均未修改，EA 原有未跟踪客户文件保留。

## 本轮实际结果

环境：Linux x86_64、JDK 21.0.12、Maven 3.8.7、Python 3.12.3。未在本轮另跑 Python 3.10 或 Windows。

| 验证 | 结果与边界 |
| --- | --- |
| Builder 原有基线 | 29 项通过 |
| Builder 修改后 | 43 项通过，含 14 项新增输入/路径/产物/错误/模板回归 |
| Builder + Base | 83 通过、2 跳过；跳过为可选 Intent 消费者及 Windows 扩展路径，不改 Base API |
| Harness Maven install | 13 模块成功；169 项中 162 通过、7 个显式真实 Smoke 跳过，0 失败/错误 |
| Harness Schema/Fixture | 9 个 Schema、54 项探针通过 |
| Harness Python | 21 项通过，含十案例清单与空/缩减矩阵拒绝 |
| Builder 实际生成和执行 | 六个命令阶段全部通过；十案例执行且验证，不只是编译/健康检查 |
| 原五配置 CLI 回归 | 重跑及验证通过，3 COMPLETED / 2 REJECTED，拒绝读取为 0 |
| 仓库外目录复跑 | 搬到 `/tmp/fh-clean-runtime-6aYxpA/candidate`，直接 Java 启动并验证十个新结果；不使用原源码工作目录 |
| Git 检查 | 两仓 diff --check 通过；无遗留本轮 Agent 进程 |

十个受控结果：5 COMPLETED / 5 REJECTED。无权限、过期、错源客户、模型选择器四类拒绝的
业务读取均为 0；坏响应在两次读取之后拒绝，不将已发生读取伪装成 0。所有案例写入尝试为 0。
缺测评/口径的描述为 PARTIAL，明确零资产仍输出 0；不生成适当性结论。

原生模型是脚本响应，本地 HTTP 服务是严格 Fixture，不是完整 non-ai-banking 模拟器。
未消耗真实模型 API，不执行真实或模拟银行转账，不改数据库。

## 可核验产物

Builder 子项目相对路径：

- `build/clean-readonly-final-v01/build-report.json`
  SHA-256 `dc01aebf2d69ca70f93e76218c7c3d1d32c1aa11f6524c36fa63f4a687ffdfd4`。
- `build/clean-readonly-final-v01/solution-lock.json`：构建源、声明 Runtime、模板/资产/验证器和实际 JAR 摘要。
- `build/clean-readonly-final-v01/logs/`：六阶段日志及报告中对应摘要。
- `build/clean-readonly-final-v01/candidate/target/runs/`：每案例 Native Event、投影结果、Trace 及 Assembler Lock。
- 外移后生成的新证据：`/tmp/fh-clean-runtime-6aYxpA/candidate/target/portable-runs/`。
  此目录是同机 AI 自测，不能称为非原作者或跨机器验收。
- Harness 中 `target/builder-handoff/0001-feat-finance-builder-generate-controlled-agents-on-s.patch`
  是可交给 Builder Owner 评审的 Git 补丁；未冒充已被上游接纳。

## 接入工作量与失败记录

当前生成产物只有一个 Java 启动类。专属工作为选 recipe、提供两仓路径、安装依赖并执行一条 Builder 命令；
授权边界、事实映射、缺数处理、原生安装和证据均复用 Harness，不复制 Java 公共实现。
新来源仍须写/配置 Provider 映射及可信宿主绑定，不能靠 Builder 或 Prompt 生成授权。

最终一次自动命令共 **30.799 秒**：Harness Java 测试/安装 24.037 秒，Candidate 打包 3.593 秒，
十案例脚本模型运行 2.427 秒，其余为 Schema/Python/报告检查。该环境已有缓存，且不含初次虚拟环境
安装。不是人工接入耗时、真实 LLM 时延或提效比例。

保留两次失败，不合并进正式通过数：

1. `clean-readonly-trial-01`：编译成功，启动时 Jackson YAML/core 不同版本产生 NoSuchMethodError。
   修复为模板显式导入 Harness Jackson BOM 2.20.1，并加入模板回归；未改变 SDK 或业务规则。
2. `clean-readonly-trial-02`：十案例已执行，验证器误把手机源的“缺语义依据”当作“缺币种”。
   手机 Fixture 本身提供 CNY，正确行为是保留金额/币种、输出未证实范围与风险体系的缺口。
   修正的是错误预期，不修改源数据或 Mapper 来迎合测试。

`clean-readonly-trial-03` 为 dirty 工作区试跑通过；正式固定版本证据以 final-v01 为准。
SDK 仍输出多 SLF4J Provider/重复 logback 配置的启动警告；当前选中候选目录的 console-only WARN 配置，
未改 SDK，也不把此受控模板认定为生产日志配置验收。

## 待完成

Builder Owner 接纳与 WebUI 接线、真实宿主的会话/可撤销授权、完整模拟器联调、办理确认 UI、
反诈转账与 UNKNOWN 查证、真实同条件 Provider 替换、业务 Owner 口径确认和非原作者独立接入，
分别待验收。下一步优先真实宿主只读及完整模拟器，再按相同准则逐项开放手机银行能力，
不继续扩展金融本体或建设第二 Runtime。

设计与使用入口见 [交付边界](../integration-acceptance/clean-runtime-builder-v01.md)。
