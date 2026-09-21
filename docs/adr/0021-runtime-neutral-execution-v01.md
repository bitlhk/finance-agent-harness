# ADR-0021：公共执行机制与 Runtime 接入边界

状态：候选，按切片验证；不是已交付的跨 Runtime TASK 支持承诺。

日期：2026-09-21。

基线：Harness `53bf77eafa9d4c84f8092931b8c43b6574f231bb`，包含 AgentScope QUERY 接入；远端 main 为 `2436d8e0d5e0a4e9de89b25a6079b1851b6a7e9e`。本分支承接未合并的 PR32，不授权合并其他业务 PR。保留公开指南 V1.2，本候选不直接刷新线上支持矩阵。

## 决策

建立一个嵌入式 Java 模块 `finance-harness-execution`，承接确需跨 Runtime 一致的金融执行规则；各 Runtime 的 Integrator 只承担框架接线。Execution 不是 Planner、工作流引擎、中央服务或银行交易引擎。

本决策细化 ADR-0006，不改变其中“Runtime 负责实际暂停、恢复及调度；Provider 映射银行协议；银行拥有业务终态”的边界，也不把候选 WaitCondition 等合同提前升格。Assembler 的准备、核验与安装职责继续遵守 ADR-0011。

## 统一归属规则

| 变化来源或职责 | 归属 | 不应承担 |
| --- | --- | --- |
| 银行接口、字段、源身份协议、业务状态码变化 | Provider / Adapter | Runtime 会话和工具注册 |
| Runtime 工具类型、消息、回调、暂停恢复 API 变化 | Integrator | 银行规则、重复实现确认语义 |
| 登录、实授权限来源、会话代际、部署及存储实现变化 | Host / 基础设施适配 | 用模型参数授予权限 |
| 跨银行、跨框架必须一致的参数冻结、确认绑定、检查点及恢复约束 | Execution | 调度线程、决定银行终态、盲目重试 |
| 请求、结果、可信上下文、Provider SPI | Core | 大型执行状态机 |
| 金额、资产等业务数据含义与口径 | 领域合同 | 具体银行传输协议 |
| 专业分析、解释方法、任务步骤 | Domain Pack / Skill | 将自然语言步骤当作强制策略 |
| 依赖解析、版本核验、运行环境匹配、安装 Lock | Assembler / Builder 装配接线 | 金融业务 ID 分支 |

一段代码跨越多项职责时先分离边界；不能按文件所在目录把整段实现机械搬迁。重复代码不是唯一提取理由，少量框架转换可以独立保留。

进入 Execution 必须满足：不引用 Runtime SDK；不引用具体 Provider 或银行接口；可信身份、授权、确认、时间和存储的输入来源明确；可在受控替身下独立测试；说明第二个实际消费者及验证计划。缺少第二个已验收消费者时只能标候选公共机制。

## 允许的依赖方向

```text
Host -> Integrator -> Execution -> Core / Provider SPI
                    Integrator -> Runtime SDK
Provider implementation -> Core / 领域合同
Assembler -> 资产解析与兼容核验 -> Integrator 安装入口
```

这些是依赖职责，不是每个调用必须逐层经过的流水线。已经稳定的 QUERY 路径不为图形对称强行增加执行对象。Execution 不依赖 Integrator、具体 Provider、Builder 或 UI；公共模块不能反向调用宿主的私有实现。

## 分批实施及验收门槛

### E1：最小提取，OpenJiuwen 行为不变

- 第一切片提取检查点键、参数冻结/规范化指纹、补参判断和清理旧确认的恢复规则；旧 Bridge 方法保留代理，调用者无需一次迁移。
- 保留 `financeHarness.*` 检查点键、指纹规范化结果、空值处理和现有补参语义。没有独立兼容设计，不更改持久化格式或已签发确认绑定。
- 当前冻结只沿用旧实现对 Map/List 的复制和空值处理；其他对象保持原行为。这不是任意 Java 对象的不可变性保证，调用入口仍须执行既有参数合同检查。扩大值类型限制须另做兼容评审，不能夹带在机械提取中。
- 身份构建、授权检查顺序、Provider 调用、框架状态转换、银行协议与 UI 均不随第一切片移动。
- 固定旧指纹/检查点样例，覆盖嵌套值、字段顺序、等价数值、允许补参、禁止改值、UNKNOWN 禁止补参、恢复清除旧确认，运行既有 OpenJiuwen 测试。
- 第一切片不是完整 TASK 公共化，不宣称 AgentScope 已支持办理。

#### 后续切片：确认值与续办校验

`TaskConfirmationPolicy` 承接确认字段格式、最长五分钟有效期、指纹匹配及时间边界；`TaskCheckpointPolicy` 承接已保存主体/能力匹配与冻结参数接受规则。旧 `HostTaskConfirmation` 仍保留原构造器和字段，只委托公共值校验。

这不是确认事件的签发或鉴权服务。Host 必须继续认证确认操作并绑定可信任务；模型参数不能构造可信确认。OpenJiuwen 仍先执行原授权检查，再校验续办绑定和参数，然后检查确认并调用 Provider。公共规则不负责银行请求、重试或任务调度。

回归覆盖有效期起止、未来确认、错误指纹、缺失/错误主体、能力不匹配、数值等价、冻结参数变更、允许补参以及已提交/UNKNOWN 禁止补参。它仍属于 E1 行为保持，不构成 E2 第二 Runtime 的验收。

### E2：共享 TASK 执行语义与第二个消费者

当前已提取的候选实现：

- `TaskCoordination`：在 Host Journal 锁内完成句柄归属、实时授权、终态缓存、补参、调用前立即持久化与结果写回。`Journal.put` 必须在返回前持久化；不能把整个 `locked` 变成退出时才提交的数据库事务。
- `TaskProtocol`：控制参数剥离、幂等键、Core TASK 请求、银行结果检查点与查证保留规则。
- `ProviderTaskExecution`：单次动态授权与 Provider TASK 调用，返回 Core 请求/结果和本地拒绝或查证决策，不返回 SDK 对象。必须结合协调器才能获得持久化及重复确认保护。
- OpenJiuwen `InstalledTaskController`：保留旧构造与方法，将原 Host Store 适配为 Journal；终态仍以原 `OpenJiuwenExecutionV1` 磁盘形状保存。旧 Bridge 只转换原有输出及证据。

协调器不解析 response 正文决定银行终态；Backend 返回独立 checkpoint 与 accessDenied 控制信息。共享机制不允许新 Runtime 自动接管旧 Runtime 的任务，安装 scope 和可信任务来源仍由 Host/Integrator 负责。

该部分尚不代表 E2 完成：AgentScope TASK SDK 探针、实际宿主确认入口与受控集成未验收，QUERY-only 门禁继续保留。

- 根据现有调用链提取冻结请求、可信确认验证、检查点结果和恢复决策；以框架无关输入输出表达，不复刻整个 OpenJiuwenExecutionV1 API。
- Host 继续负责可信确认事件、持久化实例和会话归属；Integrator 负责将 Runtime 暂停/继续转换成调用；Provider 继续查证银行任务。
- AgentScope 复用同一执行机制、业务包、Provider 和共享确认界面；不得重新写一套转账规范。
- 必测：重复确认、过期确认、跨任务/跨客户/跨实例确认、冻结后改参、提交前撤权、UNKNOWN 沿原任务查证且不重新提交、持久化恢复、模型/工具异常、观测失败不改写已完成业务。
- 用实际 SDK 探针证明接线可阻断，不以方法存在或 Mock 调用替代。

### E3：兼容装配、资格与受控发布

- 保持既有能力/环境声明为事实来源，不另建平行协议注册表。能力要求、Execution 支持、Integrator/Host 支持及 Provider 支持须共同匹配。
- 新增 JAR 后明确进入生成项目依赖、实际制品锁及受影响资格记录；不得复用旧摘要冒充新制品。
- 验证 Builder 中现有 Java/Python/AgentScope 查询无回归；旧 Agent 仍按原 Lock 运行。旧磁盘检查点恢复必须使用真正旧格式 fixture，不用新实现自产样例代替兼容性证明。
- 上线前重新核对实际分支、CI、并行修改、运行实例、备份与回滚。当前开发分支不修改线上 release、共享 Maven 缓存或旧 Agent 制品。

## 新能力开发决策卡

每项新增填写以下内容，随能力案例一起评审：

1. 用户目标、输入输出口径与能力声明位置。
2. 执行协议和状态：复用什么，是否确实引入新协议。
3. 所需可信身份、授权、确认及上下文来源。
4. Provider 实现、合同版本、连接要求及银行权威结果。
5. 目标 Runtime/Host 的实际支持与缺项，不能把 QUERY 支持外推到 TASK。
6. 公共记录默认获得什么，业务名称/来源操作/特殊阶段由谁声明与采集。
7. 正常、缺数、拒绝及协议边界案例，校验和运行入口。
8. 需要修改的公共文件、理由及兼容/资格影响；没有修改也记录。

同协议新能力应主要增加资产、Provider 和案例；一般不改 Execution 或 Integrator。若需要修改，先区分框架能力缺口、此前硬编码、银行差异和真实新协议，不能为了快速接入扩大公共模块职责。

## 完成标准与不作出的承诺

公共机制的成立需有两个 Runtime 的实际消费者；当前第一切片仅证明可提取且原路径兼容。后续新增能力仍要在声明支持的各 Runtime 跑代表性验证，不是“一次测试、全部框架自动通过”。

本轮不新增 Runtime、不接 Python TASK、不新增业务、不建任务规划器，也不顺带改变快照脱敏规则。真人独立接入与 AI 自动化验证分别记录。公共 API 在上述门槛通过前保持候选，不作稳定版本承诺。
