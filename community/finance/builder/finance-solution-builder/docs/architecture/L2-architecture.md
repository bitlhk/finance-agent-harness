# Finance Solution Builder L2 架构

## 1. 目的与范围

本 L2 在既有“Solution 输入 → Lock → Candidate → Report”流程中增加 Candidate Backend
边界。上层 Builder、Orchestrator 和 Evaluation 使用同一套描述与生命周期，不需要理解 Maven、
JAR、Python 包、进程管理或具体 Runtime 的内部结构。

FEAT-001 交付公共契约、确定性选择和轻量一致性测试；FEAT-002 将既有 Java 构建迁移到
真实 OpenJiuwen Java Backend；FEAT-010 以同一公共契约交付 OpenJiuwen Python Backend。上层兼容面继续保持
Solution 输入、CLI、Lock 和 Build Report，不保留第二条 Java 生产路径。

## 2. 逻辑组件

```text
Solution + 已锁定资产
          │
          ▼
┌──────────────────────┐    CandidateTarget    ┌──────────────────┐
│ Finance Solution     │ ────────────────────▶ │ Backend Registry │
│ Builder / Orchestrator│ ◀──────────────────── │ 唯一选择或明确失败 │
└──────────┬───────────┘    CandidateBackend   └──────────────────┘
           │
           │ 统一生命周期请求/结果
           ▼
┌─────────────────────────────────────────────────────────────────┐
│ Candidate Backend                                               │
│ preflight → assemble → build → start → health → invoke          │
│                                      → collect-evidence → stop   │
└──────────┬──────────────────────────────────────────────────────┘
           │ CandidateDescriptor / RunHandle / LifecycleResult
           ▼
┌──────────────────────┐                 ┌────────────────────────┐
│ Evaluation           │                 │ Build / Eval Report    │
│ 按公开协议逐样本调用   │                 │ 关联 Task、Lock、摘要和证据 │
└──────────────────────┘                 └────────────────────────┘
```

| 组件 | 职责 | 不负责 |
|---|---|---|
| Candidate Contract | 定义语言无关的目标、描述、运行句柄、动作结果和证据引用 | 定义 Maven 或 Python 私有参数 |
| Backend Registry | 根据显式选择或完整能力匹配确定唯一 Backend | 猜测语言、Runtime、Provider 或回退方案 |
| Candidate Backend | 执行目标环境预检、装配、构建、启动、调用和清理 | 决定业务验收标准 |
| Conformance | 用同一组行为检查验证 Backend | 代替真实 Java/Python 工具链验收 |

### 2.1 OpenJiuwen Java Backend 内部职责

```text
已锁定 Harness 资产 + CandidateTarget
                 │
                 ▼
┌────────────────────────┐
│ Java Build Adapter     │
│ 预检 → 装配 → Maven 模型 │
│      → 精确 JAR + 描述   │
└───────────┬────────────┘
            │ CandidateDescriptor
            ▼
┌────────────────────────┐
│ Java Managed Runtime   │
│ start → health → A2A   │
│       → evidence → stop│
└───────────┬────────────┘
            │ LifecycleResult / RunHandle
            ▼
┌────────────────────────┐
│ Compatibility Projection│
│ stages + Lock + Report │
└────────────────────────┘
```

- **Java Build Adapter** 复用 Builder 已解析的固定资产，先校验 Capability、Provider 和 Host
  Wiring，再修改输出或执行 Maven。它从实际 Maven 模型读取模块、artifactId、版本、
  `finalName` 和 `mainClass`，形成唯一产物计划；缺失入口、未解析属性或产物不一致均失败关闭。
- **Java Managed Runtime** 只管理自己启动的进程。同一个 Run Handle 贯穿启动、就绪、
  A2A 调用、证据收集和停止；失败时只回收该实例，重复停止安全。
- **Compatibility Projection** 由 `SolutionBuilder` 承担，把公共生命周期结果写回既有
  stages、`solution-lock.json` 和 `build-report.json`，并额外保存
  `candidate-descriptor.json`。上层不读取 Java typed extension。

### 2.2 OpenJiuwen Python ZipApp Backend 内部职责

本节描述标准 Solution 构建路径使用的 `OpenJiuwenPythonZipAppBackend`
（backend id `openjiuwen-python`）。仓库同时保留金融 Studio/local-read-only 路径使用的
`OpenJiuwenPythonBackend`（backend id `openjiuwen-python-finance`）：后者生成
`directory-manifest` 并使用 `finance-chat`，不参与本节的 ZipApp/A2A 生命周期。Builder 依据已校验的
Solution 路径选择其中一个实现，禁止在失败时互相回退。

```text
已锁定模板、Pack、Schema/Fixture 与 Provider 引用
                         │
                         ▼
┌─────────────────────────────────────────┐
│ Python Build Adapter                    │
│ 无写入预检 → 装配 → 语法检查 → 确定性 ZipApp │
└────────────────────┬────────────────────┘
                     │ CandidateDescriptor
                     ▼
┌─────────────────────────────────────────┐
│ Python Managed Runtime                  │
│ 受信解释器 → /health → A2A → 证据 → stop   │
└───────────────┬─────────────────────────┘
                │
        ┌───────┴────────┐
        ▼                ▼
 北向：Builder/Evaluation  南向：Harness Adapter
 A2A + HTTP health       canonical-json-http.v1
```

- **Python Build Adapter** 要求模板显式声明 `moduleEntrypoint`、产物名、健康/A2A 路径、
  `openjiuwen==0.1.16`、`openjiuwen-runtime-service==0.1.0` 和
  `canonical-json-http.v1`。它先用受控解释器核对实际依赖，再复制已锁定资产、检查 Python
  源码并按固定文件顺序、时间戳和权限生成单文件 ZipApp。
- **Python Managed Runtime** 在启动前重新校验 Descriptor 与 ZipApp 摘要，只管理本 Backend
  创建的进程。健康失败、调用失败和正常完成均通过公共生命周期返回；已知 Handle 的重复停止安全。
- **双协议边界** 明确分离：北向 A2A 是 Builder/Evaluation 调用 Candidate 的入口；南向
  Canonical JSON/HTTP 是 Candidate 调用 Finance Harness Adapter、再由 Provider 对接银行系统的入口。
  Adapter 地址和凭据由运行环境注入，不写入 Candidate、Lock、Report 或公开证据。
- Python 与 Java Descriptor 均进入同一个 Sandbox Evaluation。样本快照、分区、指标、验收规则和
  三态结论不按语言分叉；语言私有事实仅存在于 `openjiuwen-python` typed extension。

### 2.3 Construction Agent Backend

Construction Agent Backend 是 Candidate Backend 的并列扩展轴。Candidate Backend 负责把已确定的
Solution 构建、启动并提供调用接口；Construction Agent Backend 负责让构造型 Agent 在给定工作区和
写入范围内修订 Candidate 工程。两者都由 Orchestrator 调用，彼此不直接依赖。

```text
Finding + Parent Lock + 受控工作区 + 验证命令 + 预算
                         │
                         ▼
┌──────────────────┐  start/resume  ┌────────────────────────────┐
│ Builder           │ ─────────────▶ │ Construction Agent Backend │
│ Orchestrator      │ ◀───────────── │ 具体适配由后续 Feature 提供  │
└────────┬─────────┘  events/result  └─────────────┬──────────────┘
         │                                         │
         │ approve/cancel                          │ 只在允许范围内修改
         │                                         ▼
         │                              ┌────────────────────────┐
         └─────────────────────────────▶│ Candidate 工作区       │
                                        │ source/config/tests    │
                                        └────────────────────────┘
```

- Orchestrator 固定任务目标、父 Lock、允许写入范围、验证命令、预算和审批结果。
- Backend 返回稳定任务句柄，并通过游标提供可重放的有序事件；它不能扩大权限或修改验收标准。
- 审批、取消、恢复和结果查询均使用公共模型。OpenCode、Codex 等私有会话与流协议留在具体适配层。
- 成功结果必须包含已通过的验证结果；越界写入、预算耗尽、拒绝审批或未验证工作区不能成为成功结果。
- 共享协议见
  [construction-agent-backend-protocol.md](contracts/construction-agent-backend-protocol.md)。

### 2.4 Codex Construction Agent Adapter

Codex 是 Construction Agent Backend 的一个可替换实现。它不改变 Orchestrator、Optimization
Controller 或公共任务模型；供应商协议只存在于适配层。

```text
ConstructionTaskRequest
          │
          ▼
┌───────────────────────┐   stdio JSON-RPC   ┌──────────────────────┐
│ Codex Adapter         │ ─────────────────▶ │ Codex app-server     │
│ 公共状态机与结果校验  │ ◀───────────────── │ thread / turn / item │
└──────────┬────────────┘  通知与单次审批     └──────────────────────┘
           │
           ├─ 实际扫描任务前后工作区，确定 changed paths
           ├─ Builder 执行冻结的 validation commands
           └─ 生成公共事件与唯一 ConstructionResult
```

- 每个任务拥有独立 app-server 进程、ephemeral thread 和 turn；不监听网络端口。
- 修改工作区前核验 CLI 精确版本、运行时生成的 v2 Schema、账户状态和模型可用性。
- 命令、文件范围扩张和权限请求按当前 task/thread/turn 绑定。批准只对当前请求生效；权限批准最多
  对当前 turn 生效，不写入会话级长期规则。
- App Server 的 diff 只作为过程交叉检查。实际文件摘要、允许写入范围、链接边界和 Builder 验证
  命令共同决定成功结果。
- Token 用量结合部署方明确配置的核算费率形成公共成本；缺少用量或费率时失败关闭，不能以零值
  代替未知成本。
- 取消和预算耗尽调用 `turn/interrupt` 并停止自有进程。v1 不恢复活动任务；终态结果可以幂等读取。
- 接入和升级步骤见 [codex-construction-agent.md](../codex-construction-agent.md)。

## 3. 数据所有权

| 数据 | 所有者 | 说明 |
|---|---|---|
| `CandidateTarget` | Builder | 本轮要求的语言、Runtime、产物、协议、资产、能力、Profile 和 Host Wiring |
| `BackendCapability` | Backend | Backend 明确声明可支持的组合和生命周期动作 |
| `CandidateDescriptor` | Backend 产生，Builder 校验 | Candidate 的静态事实；通过 Task、Lock 和产物摘要绑定本轮交付 |
| `RunHandle` | Backend | 本轮受控实例的动态事实；只暴露公共端点与证据引用 |
| `LifecycleResult` | Backend | 每个动作的状态、耗时、公共错误与证据引用 |
| `solution-lock.json` | Builder | 实际采用的资产版本真值；Descriptor 只引用其摘要和已安装资产身份 |
| Java Maven 执行计划 | OpenJiuwen Java Backend | Java 私有事实；只在 typed extension 中投影必要的模块、入口和启动模式 |
| 受管 Java 进程 | Java Managed Runtime | 仅对 Backend 创建并登记的实例负责；不按端口猜测或终止外部进程 |
| Python 模板声明与 ZipApp | OpenJiuwen Python ZipApp Backend | 模板固定入口、依赖与双协议路径；ZipApp 摘要进入公共 Descriptor |
| 受管 Python ZipApp 进程 | Python ZipApp Managed Runtime | 仅使用已预检解释器启动已验证 ZipApp；不安装依赖或接管外部进程 |
| `ConstructionTaskRequest` | Orchestrator | 修订目标、父 Lock、工作区、写入范围、验证命令、预算与审批类别 |
| `ConstructionTaskHandle` | Construction Agent Backend | 任务身份、当前状态、最新事件序号和待审批身份 |
| `ConstructionEventBatch` | Construction Agent Backend | 按游标返回可公开、可重放的进展、变更、验证、审批和终态事实 |
| `ConstructionResult` | Construction Agent Backend 产生，Orchestrator 校验 | 唯一终态、实际变更、验证结果、预算用量和证据引用 |
| Codex thread、turn、JSON-RPC 与模型配置 | Codex Adapter | 仅在任务进程内使用，不进入公共事件、Lock、Report 或交付物 |

静态描述与动态运行状态分离。端口、实例状态和启动时间只进入 `RunHandle`；构建工具参数、
进程号和宿主目录不进入公共契约。

## 4. 生命周期与门禁

1. Registry 在任何下载、编译或启动前完成 Backend 选择。无匹配或多匹配都停止。
2. `preflight` 检查已解析资产闭包，以及 Backend 对目标语言、Runtime、协议和动作的支持。
   具体 Backend 继续检查 Domain/Capability Pack、Provider 操作、Profile、Host Wiring 和工具链。
3. `assemble` 只使用已锁定资产生成装配工作区；`build` 产生并返回 Descriptor。
4. Builder 校验产物位于交付目录内且 SHA-256 与 Descriptor 一致，才允许 `start`。
5. `health` 通过后 Evaluation 才能用 Descriptor 声明的协议执行 `invoke`。
6. 受控协议探针访问 Descriptor 声明的 A2A 入口；健康接口不能代替调用。
7. 正常完成、失败或中止都执行证据收集与 `stop`。已知 Handle 的重复停止必须安全。

构造任务使用独立控制生命周期：`start` 创建一次幂等任务，`events` 按序号拉取事件，`resume`
恢复已存在的任务事实，`approve` 处理当前待审批项，`cancel` 幂等终止，`result` 只在终态返回。
任一终态后不得产生新的进展或文件变更事件。

Construction Agent 任务终态限定为 `succeeded`、`failed`、`cancelled` 和 `timed-out`；失败、取消或
超时必须携带稳定、可公开展示的错误或停止原因。Candidate Backend 各阶段的通用操作结果另行使用
`failed`、`blocked`、`skipped` 和 `cancelled`，不会把未执行动作省略成成功。两类公共信息均不得包含
凭据、宿主绝对路径、未经授权的业务原文或模型内部推理。

## 5. 兼容与扩展

v1 wire 字段使用 camelCase，Schema 版本为 `1.0`。消费者忽略未知可选字段，但继续严格校验
必需字段、枚举和已知字段值。新增语言或 Runtime 的专有信息放入
`TypedExtension { extensionType, schemaVersion, payload }`；上层流程不解析该 payload。

删除、重命名公共必需字段，收窄状态含义，或把语言专有字段变成公共必填项，必须发布新的
契约版本。公共协议详见
[candidate-backend-protocol.md](contracts/candidate-backend-protocol.md)。

## 6. 跨 Feature 验收

- FEAT-001：轻量 Backend 通过完整公共 conformance，证明契约可执行。
- FEAT-002：真实 OpenJiuwen Java Backend 通过同一 conformance；固定财富经理 Candidate
  已完成编译、启动、健康检查、A2A 协议调用、证据收集和停止。银行测试环境中的在线业务
  Evaluation 继续单独记录，不能由协议探针或冻结证据回放替代。
- FEAT-010：Python Backend 通过同一 conformance，并证明上层流程无需按语言分叉。
- FEAT-003：确定性 Fake Backend 通过 Construction Agent 公共 conformance，证明任务、事件、审批、
  恢复、取消、预算和隐私边界可执行。
- FEAT-004/FEAT-009：具体构造 Agent 和优化循环只消费 FEAT-003 协议，不反向修改其权限归属。
