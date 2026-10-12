# Finance Solution Builder

[开发一个 Harness 业务组件](docs/asset-development-workflow-v01.md)：从 Skill 定制或 Provider 扩展开始，
定位实际合同、公共授权与观测接入点，验证新增行为后准入与发布。

[业务资产组成与文件浏览 V0.1](docs/asset-contents-v01.md)：查看版本化 Skill、声明、合同与依赖，
区分包内文件、外部引用和具体 Agent 的安装记录；复用已有定制与发布流程，不提供通用代码编辑器。

[统一创建入口 V0.1](docs/unified-creation-v01.md)：模板预选、业务模块与包绑定使用同一目录投影；
新建走声明驱动的 `0.9` 选择，旧配置与制品保留。实现与发布状态以该记录为准；
下方各阶段首版范围为历史说明，不代表当前全部支持范围。

[组件声明装配候选 V0.1](docs/component-assembly-v01.md)：组件声明准入已接到目录、预检、
公共构建与实际装配核对；Provider工厂泛化及共享TASK尚未完成，不代表转账已开放或服务已发布。

[中文可执行 SOP 候选](docs/chinese-readonly-methods-v01.md)：余额、财富、收支的新建默认方法
采用版本化中文正文；旧发布方法与生成制品不自动修改。尚未部署。

[业务资产第一阶段](docs/business-assets-phase1.md)：三个业务单元共用创建目录，
私有 SOP 派生来源与不可变版本；七只读组合不变，方法受控试跑仍明确为 Java。

[业务组件打包与复用 V0.1](docs/business-component-publishing-v01.md)：在既有草稿和试跑生命周期上，
增加显式工具绑定确认、标准 ZIP 交付、跨环境安全重导入，以及带精确版本进入创建 Agent。

当前包版本为 `0.2.0`。本版本面向 Agent 上线前冷启动，统一 Candidate 构建与运行边界，提供受控构造、
Sandbox Evaluation、自优化、Harness 资产修订提案和过程展示。版本范围与发布状态见
[0.2.0 说明](version-scope/0.2.0/README.md)；代码合入不等同于版本已经发布。

**当前 A4 集成范围（2026-09-12）**：Java 0.1.15 / Python 0.1.18 均支持余额、财富事实、
收支三个只读单元的七种组合；Python 使用原生循环和 Harness 金融执行桥，不是纯 Python Harness SDK。
已合入当前 finance 的代码到集成分支，MR #154 的维护者接纳与展示发布分别记录。
[职责与复用边界](docs/python-studio-a4-ownership.md)、[当前使用指南](docs/public/python-runtime-v01.md)。
下文各首版的范围与数量为历史记录，不替代当前分项验收。

[原生 Python 金融 Candidate V0.1](docs/python-finance-candidate-v01.md)：OpenJiuwen Python
0.1.18 驱动模型与工具循环，复用 Harness 金融执行桥；首版仅余额只读。
[分项验收记录](docs/python-finance-candidate-verification-20260912.md)包含页面实际对话、
受控撤权时序、离线搬迁及 Java 七组合回归，不代表稳定站发布或真人独立接入。

[真实三端页面](docs/live-terminal-surfaces-v01.md)：新生成Agent的手机外框、全窗口电脑和ATM屏幕，
复用现有交互、授权及来源；[首批能力源码盘点](docs/capability-inventory-20260910.md)只列候选，不启用新业务工具。

[业界标准参考页](docs/industry-standards-v01.md)：四项有边界的官方参考及核实记录；
账户、转账、信用卡改用“服务”展示名，26模块ID、能力范围与构建配方不变。

[设备预览与卡片精修](docs/device-polish-v1.md)：Builder 手机、笔记本与 ATM 外壳预览、
放大查看、紧凑模块卡片及共享金额展示。已于 2026-09-10 部署 `283e6d52`，
见[发布与分项验证记录](docs/device-polish-release-20260910.md)；旧 Agent 不自动升级。

[多终端体验与界面定制 V1](docs/terminal-ui-v1.md)：手机、电脑、ATM 浏览器模拟终端，
受管理主题/卡片，版本化配置、独立用户体验、会话时限、取消、快照及服务端语音接线。
仅复用原有财富与余额只读能力，新建制品使用0.6选择，旧记录不自动升级。

[场景模板与业务模块候选 V0.1](docs/template-modules-v01.md)：新版创建页采用模板 -> 业务模块 -> 底层框架。
手机银行26模块有固定源码来源；Java 使用已接入的只读组合，Python A1 支持余额、财富及两者组合，采用 Python Agent + Harness 执行桥；其他模板待接入。见 [A1 接入与验证](docs/python-readonly-a1.md)。
以下使用对象/双入口说明保留为0.4历史兼容参考，不是新版首页。

[使用对象与能力目录 V0.1](docs/directory-and-granularity-v01.md)：面客／对内共用资产目录，
场景模板和能力组件进入同一预检与构建路径；附真实文件映射及新增余额模板练习。
当前只有客户只读三种组合可构建，员工接线待完成，研发工程后续开放。

[Studio 上游交接说明](docs/upstream-studio-handoff-v01.md)：本批为已有只读 Studio
原型的独立源码交付，保留 MR #118 的构造讲解页面及最新 Base 契约。尚未迁移到
标准 Solution Build/Dryrun 事件及新版 Candidate Backend，不能计为 0.2.0 标准特性完整交付；请先按交接说明评审。

[AG-UI V0.1](docs/ag-ui-v01.md) adds standard SSE transport to Studio and newly generated
portable services. Final text remains buffered until authorization succeeds; old JSON
endpoints remain compatible. [Server mode](docs/server-demo-v01.md) keeps public
authentication, private model configuration and deployment storage separate.

[Markdown rendering](docs/markdown-rendering-v01.md) formats assistant messages on
desktop/mobile independently of AG-UI, with GFM parsing and HTML sanitization.

[开发指导入口](docs/developer-guide-entry-v01.md) links the authenticated server
sidebar to a separately versioned public redacted guide (HTML/PDF). Full internal
documentation and Studio operations retain login; internal source references are
not published anonymously.

本地持续服务与真实构建页面入口见 [可视化装配台 V0.1](docs/local-workbench-v01.md)。
它与原有构建报告展示页、十案例受控 CLI 分开；支持有限的只读组合，不自动启用办理能力。
实际构建工作台、六步构建引导页和概念说明页的区别、依赖及完整启动命令，统一见
[Agent Builder WebUI 启动指南](docs/webui-startup-guide.md)。

新版 [Studio 模型多选与目录接线](docs/model-selection-ui-v02.md) 复用该入口；
支持按真实能力目录装配、EA 元数据发现、服务端独立模型绑定、冻结允许列表及切换新会话。
目录中已配置不等于模型已验证，旧单模型请求仍兼容。

[Studio 交互修订](docs/studio-usability-v02.md) 补充已有模型显式绑定、启动阻塞提示、
构建阶段展示、能力复选与连接弹窗，以及停止后的 Agent 删除规则。

并行 UI 接线使用新增 [Workbench API V0.2](docs/workbench-api-v02.md) 与
[接口样例](examples/workbench-api-v02/README.md)。复用现有 Jobs 与宿主，保留旧页面接口；
配置检查不调用模型或银行，不代表真实授权已通过。本批没有替换已运行页面或实施 AG-UI。

这是一个独立的 Builder 概念验证仓。它根据 Finance Harness 的 `solution.yaml` 自动装配 Java Candidate，并输出锁定资产版本与验证报告。

当前已验证两个财富目标：客户财富体检助手与财富经理访前助手。

新增独立的 [干净 Runtime 财富只读入口](docs/clean-runtime-readonly-v01.md)：不复制手机银行工程，
Builder 生成薄启动类和资产，以独立 JAR 依赖原生 OpenJiuwen SDK 与 Harness，并执行十个受控案例。
这是脚本模型/虚构数据参考模板，不把历史健康检查、冻结回放或受控成功视为真实授权验收。

```text
solution.yaml
  → Schema 校验
  → Harness / Pack / Provider / Integration / Eval 引用解析
  → 资产摘要与提交锁定
  → Java Candidate + 财富 Skill 装配
  → Harness 测试与 Maven 安装
  → Java Backend 精确识别并编译目标 JAR
  → 同一 Candidate 实例完成启动、健康检查、A2A 协议探针和停止
  → solution-lock.json + candidate-descriptor.json + build-report.json
```

## 安装与一条命令

```powershell
cd community\finance\builder
uv sync --package finance-solution-builder
```

若使用 `pip`，请从 Builder 根目录依次安装公共 Base 和本项目：

```powershell
python -m pip install -e .\base -e .\finance-solution-builder
```

以下命令在 `community/finance/builder/finance-solution-builder/` 目录执行。

```powershell
finance-solution-builder build `
  examples\wealth-manager-previsit\solution-build-input.v1.json `
  --harness-repo <finance-harness-path> `
  --output build\wealth-manager-previsit-v1.1 `
  --maven <maven-executable>
```

不安装、直接从源码运行时，先设置：

```powershell
$env:PYTHONPATH = "$PWD\src"
```

## 输出

- `build/wealth-manager-previsit/candidate/`：可编译、可启动的 Java Candidate；
- `solution-lock.json`：Harness 提交、Runtime、Pack、Provider、Integration 与 Eval 引用及摘要；
- `candidate/candidate-descriptor.json`：实际 JAR、入口、调用协议、能力与 Provider 绑定；
- `runtime/evidence/`：本轮受管实例的启动、就绪和协议调用证据；
- `build-report.json`：各阶段结果、耗时、命令、告警和验证范围。

架构边界见 [docs/architecture.md](docs/architecture.md)，V1.1 双 Candidate 运行证据见 [docs/verification-wealth-v1.1.md](docs/verification-wealth-v1.1.md)。
冷启动、自优化和扩展边界见 [docs/cold-start-and-continuous-improvement.md](docs/cold-start-and-continuous-improvement.md)。
投研 Domain Pack 的冷启动盘点见 [docs/investment-research-domain-pack-bootstrap.md](docs/investment-research-domain-pack-bootstrap.md)。

## Candidate Backend v1

0.2.0 的 Candidate Backend 公共边界把具体语言和 Runtime 的构建、启动与调用细节封装在
Backend 内。Builder、Orchestrator 和 Evaluation 只依赖 `CandidateTarget`、
`CandidateDescriptor`、`RunHandle` 和统一生命周期结果。

```python
from finance_solution_builder import BackendRegistry, CandidateTarget

registry = BackendRegistry([java_backend, python_backend])
backend = registry.select(target, backend_id="openjiuwen-java")
preflight = backend.preflight(preflight_request)
```

Registry 只接受显式选择或唯一完整匹配；不会静默改用另一种语言、Runtime、协议或产物。
实现新的 Backend 后，应运行 `tests/candidate_backend/` 中的同一组契约断言。当前
`openjiuwen-java` Backend 已实现确定性预检、装配、Maven 构建、Descriptor、受管进程、
健康检查、A2A 协议调用、公共证据和幂等停止。Python 侧保留两条边界清晰的路径：现有 Finance Studio
使用 `directory-manifest` 与 `finance-chat`；标准 Python Solution 使用可移植 ZipApp 与 A2A 协议。

Java Backend 只启动并停止自己登记的进程。它从生成工程的 Maven 模型读取模块、版本、
`finalName` 和 `mainClass`，因此不会从 `target/` 中猜测第一个 JAR。启动前会再次校验
Descriptor、Lock 摘要和实际 JAR 摘要；任一不一致都失败关闭。传统 `build` 命令完成一次
受控协议探针后停止实例，后续 Evaluation 则可以持有同一个 Run Handle，在评测结束后再停止。

- L2 组件与生命周期：[docs/architecture/L2-architecture.md](docs/architecture/L2-architecture.md)
- 公共协议：[docs/architecture/contracts/candidate-backend-protocol.md](docs/architecture/contracts/candidate-backend-protocol.md)
- JSON Schema：[contracts/json-schema/candidate-backend.v1.schema.json](contracts/json-schema/candidate-backend.v1.schema.json)

## 当前已知边界

两个外部 GitHub 私有资产现在可通过 GitHub API 解析并锁定。若运行环境的 Git Smart HTTP 不可用，Builder 会通过当前 `gh` 登录态下载同一固定提交的归档，不改变锁定语义。

`--allow-template-fallback` 仅供引用确实不可获取时的概念验证，正式验证不应使用。真实 OpenJiuwen 模板是多模块 Maven 工程，Builder 会保留其父工程与兄弟模块，注入员工辅助 Host Wiring、能力卡和财富 Skill，再执行定向资产测试与 Spring Boot 健康检查。

Builder 还会运行 `finance-skill-eval` 的冻结证据 replay。报告明确区分该回放和当前 Candidate 的在线评测，不能用历史回放冒充实时业务效果。

Java Backend 的 A2A 探针只证明生成服务能够按 Descriptor 声明的协议接收请求。它不会携带真实
客户资料，也不等同于银行测试环境中的业务 Evaluation；后者没有执行时，
`candidateOnlineEvaluationExecuted` 始终保持 `false`。

## 构建过程 WebUI

完成两套示例构建后，可以启动只读的 Agent 构造工作台。工作台采用六步 Storyline，构造者
每次只处理当前阶段，按“明确目标 → 选择组件 → 装配 Candidate → 构建启动 → 真实业务 Evaluation →
交付裁决”逐步推进。每一页都说明本步操作和完成标准。组件页解释每类资产是什么并给出本轮示例；装配页使用可交互的动态连线
展示组件、接口及传递的数据。组件按可跨场景复用的共享资产和随业务变化的场景资产分区，
点击组件或接口时保持当前阅读位置；装配页分别解释构建期拼装和运行期银行能力调用，并为
每段连接展示用途、执行方式、实际接口、字段释义和完整脱敏示例。构建页用循环动画说明
Orchestrator 如何根据 Evaluation 的错误分析，让 Developer 受控调整结构或参数，并在达标或
迭代次数用完时停止。真实业务 Evaluation 仍需授权银行测试环境；开发侧冻结回放只证明评测
契约可运行，不作为当前 Candidate 的业务效果。完整 Builder 活动与证据按需展开：

```powershell
python examples\build-visualizer\server.py --port 18117
```

浏览器访问：

```text
http://127.0.0.1:18117/?demo=wealth-manager-previsit-v1.1
```

原有的 Solution/Harness 概念讲解页保留在 `http://127.0.0.1:18117/guide.html`。若代码与本地
构建数据位于不同目录，可以显式指定只读数据根目录：

```powershell
python examples\build-visualizer\server.py `
  --port 18117 `
  --data-root <builder-workspace-with-build-output>
```

WebUI 读取 `build/wealth-manager-previsit-v1.1/` 和 `build/wealth-customer-diagnosis-v1.1/` 的真实 Lock、Report 与冻结评测证据，不在仓库中复制构建数据。在线业务验收尚未执行时，页面只显示明确标注的演示推演，且不会将其计入交付裁决。

`build/`、`output/`、`.tmp/`、录屏、语音、截图和运行日志均为本地生成内容，已由本目录
`.gitignore` 排除，不属于代码交付。

Candidate 在未注入 EA 凭据时仍能启动并通过 `/actuator/health`；部署环境注入 EA 地址、Runtime 身份和一次性 Token 密钥后，OpenJiuwen Agent 才会在 A2A 调用中启用真实 Employee Agent Provider，不生成模拟客户事实。

固定提交的 Runtime 模板与 Eval Pack 使用 Builder 级共享缓存。删除某个 Candidate 输出不会删除缓存；相同引用的后续构建不会重复下载。Builder 还会在写入输出前检查组合是否受支持，预检失败不会删除上一份成功产物。

`examples/mobile-banking-antifraud-transfer/` 已记录第三套规划输入。该组合缺少 Task 型多 Provider Host Adapter、反诈 Capability Catalog 绑定和独立 Eval Pack，当前会在预检阶段明确拒绝，避免把“可编译”误报为“已具备业务能力”。
## 单服务器部署

本地默认模式保持不变。登录保护、HTTPS 反代、独立数据目录和 systemd 模板见
[单服务器演示部署 V0.1](docs/server-demo-v01.md)。

私有专业方法的草稿、导入、校验、受控装配检查、不可变版本和新 Agent 应用 API，见
[Private Method Assets V0.1](docs/private-method-assets-v01.md)。阶段 A 提供后端及
可重跑 Java 验证入口；阶段 B 提供资产页面、模块内能力选择与已配置模型的回答对照。
受控装配检查与真实模型预览分别记录，均不自动代表专业质量通过。

业界标准页、实际生成的三端页面及线上验收边界见
[2026-09-10 交付记录](docs/standards-terminal-release-20260910.md)。

业务资产的组成关系、真实文件、可下载开发样例，以及导入到发布使用的步骤见
[业务资产图形化与开发接入 V0.2](docs/package-studio-v02.md)。
展示聚合、版本派生草稿与操作引导见 [业务资产 V0.2.1](docs/package-studio-v021.md)。
