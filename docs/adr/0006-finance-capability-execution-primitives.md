# ADR-0006：金融能力执行原语与演进边界

状态：`Accepted for incremental validation`

日期：2026-09-03

## Context

当前 V1 已用四个合同表达只读查询、需要用户确认的办理、处理中任务和结果未知状态。后续财富聚合、反诈转账、贷款申请、投诉处理、投研取数等业务会进一步出现：

- 多个独立事实可以并行获取；
- 用户输入、明确确认或强认证后才能继续；
- 银行任务已经受理，但需要等待外部系统完成；
- 调用超时后结果未知，只能查证，不能直接重放；
- 部分数据缺失时仍可输出有价值的降级结果。

这些场景需要统一语义，但 Finance Harness Core 不能因此演变成工作流引擎、消息队列或银行交易状态机。

## Decision

Finance Contract 逐步形成三组原语：

```text
调用原语
Capability / Context / Request / Result / Failure

控制原语
Execution Budget / Wait Condition / Resume Directive / Task Reference

组合原语
Requirement / Parallel Group / Join Policy / Readiness
```

其中：

- Core 定义可交换的语义和约束；
- Runtime Integration 负责暂停、恢复、并发调用和结果汇聚；
- Provider 负责映射银行系统协议；
- 银行系统继续拥有业务状态、风险决策和最终执行结果；
- Domain Pack 定义完成某项金融任务需要哪些事实以及如何使用，不负责线程、队列或重试调度。

## 1. 已有 V1 原语

V1 继续保持以下合同不变：

```text
FinanceContextV1
CapabilityRequestV1
CapabilityResultV1
EvidenceReceiptV1
FinanceProvider.query / executeTask
```

已有状态的统一解释为：

| 状态 | 含义 | Runtime 行为 |
|---|---|---|
| `COMPLETED` | 已得到权威结果 | 继续任务或结束 |
| `NEED_USER` | 缺少用户输入 | 暂停，等待新输入 |
| `NEED_CONFIRMATION` | 需要对当前动作明确确认 | 展示摘要，暂停并绑定原任务恢复 |
| `PROCESSING` | 银行任务已受理但尚未终态 | 释放当前调用，后续查询或事件恢复 |
| `FAILED` | 已确定失败 | 按错误类别停止或重新发起新请求 |
| `UNKNOWN` | 无法确定副作用是否发生 | 禁止盲目重放，必须查证或对账 |

`PROCESSING` 和 `UNKNOWN` 不能都被翻译成“再试一次”。前者等待业务完成，后者先查证执行结果。

## 2. 等待不是一种状态

金融业务中的等待至少分为四类：

| 等待类型 | 示例 | 处理原则 |
|---|---|---|
| 同步短等待 | 余额查询、名单匹配、产品查询 | 受调用预算约束，在当前请求内完成 |
| 交互等待 | 补资料、明确确认、OTP/强认证 | Runtime 暂停会话，不占用工作线程 |
| 业务处理等待 | 贷款审核、投诉处理、跨行转账处理中 | 银行返回 `taskRef`，通过查询、回调或事件恢复 |
| 结果未知等待 | 提交超时、网络中断但银行可能已受理 | 使用 `taskRef`/幂等键查证，禁止直接再次提交 |

预约日期、定投计划等“等待到某个时间”由银行任务或调度系统持有，不允许 Agent Runtime 长时间挂起一个请求。

## 3. 下一批候选控制原语

只有在第二个真实消费者出现时，才将以下候选提升为正式 JSON Schema。

### 3.1 CapabilityDescriptor

能力描述用于让 Runtime、Builder 和 Eval 在调用前知道基本执行形态：

```yaml
capabilityId: transfer.internal.create
version: 1.0.0
operation: TASK
effect: FUNDS_MOVEMENT
interaction: INTERACTIVE
parallelSafe: false
supportsCancel: true
supportsReconcile: true
inputSchemaRef: contract://transfer/internal-create/input/v1
outputSchemaRef: contract://transfer/internal-create/output/v1
```

最小分类为：

```text
operation: QUERY / TASK
effect: READ_ONLY / STATE_CHANGE / FUNDS_MOVEMENT
interaction: IMMEDIATE / INTERACTIVE / DEFERRED
```

`CapabilityDescriptor` 是调用与装配元数据，不是银行风险决策。即使描述为可调用，银行后台仍可在
本次预检中返回拒绝、需要确认或需要人工处理。`TASK` 默认不得并行提交；只有只读且明确声明
`parallelSafe` 的能力才能由 Runtime 自动并行。

### 3.2 ExecutionBudget

表达一次 Provider 调用愿意同步等待多久：

```yaml
deadlineAt: 2026-09-03T10:00:03Z
maxProviderWaitMs: 3000
responseMode: WAIT_WITHIN_BUDGET  # 或 ACCEPT_DEFERRED
```

它用于让 Provider 在预算不足时返回 `PROCESSING` 或可解释失败，不用于控制银行内部处理时长。

### 3.3 WaitCondition

表达当前任务为什么暂停、由什么信号恢复：

```yaml
reason: USER_CONFIRMATION
resumeMode: NEXT_REQUEST
expiresAt: 2026-09-03T10:05:00Z
retryAfterMs: null
cancelSupported: true
reconcileSupported: false
```

候选 `reason` 只保留跨业务稳定集合：

```text
USER_INPUT
USER_CONFIRMATION
STEP_UP_AUTHENTICATION
HUMAN_REVIEW
EXTERNAL_RESULT
SCHEDULED_TIME
```

候选 `resumeMode`：

```text
NEXT_REQUEST
POLL
CALLBACK
EVENT
TIMER
```

### 3.4 OpaqueContinuation

当前 `continuation` 仍用于 V1 Host Checkpoint。演进时应满足：

- 对模型不可见；
- 不由前端自由修改；
- `taskRef` 是银行业务任务的权威标识；
- 涉及敏感状态时使用 Provider 签发的 opaque token，而不是暴露内部字段 Map。

## 4. 并行只表达依赖，不在 Core 中调度

并行主要用于财富画像、投研和风险信号聚合：

```text
客户事实 ─┐
持仓快照 ─┼─ 并行读取 ─→ Readiness ─→ Domain Skill
风险测评 ─┤
产品货架 ─┘
```

Domain Pack 或构建期资产可以声明最小 Requirement Set：

```yaml
requirements:
  - id: customer-profile
    capabilityId: customer.profile.query
    required: true
    parallelGroup: wealth-context
    onFailure: FAIL
  - id: portfolio
    capabilityId: portfolio.snapshot.query
    required: true
    parallelGroup: wealth-context
    onFailure: DEGRADE
  - id: interaction-history
    capabilityId: customer.interactions.query
    required: false
    parallelGroup: wealth-context
    onFailure: OMIT

joinPolicy: ALL_REQUIRED
```

V1.1 只考虑：

```text
required / optional
parallelGroup
onFailure = FAIL / DEGRADE / OMIT
joinPolicy = ALL_REQUIRED / BEST_EFFORT
```

不设计通用 DAG、循环、补偿事务、Quorum 或分布式调度 DSL。Runtime Integration 可以把同组独立请求映射到 OpenJiuwen 的并发机制，银行内部的多路风控仍由银行系统自行编排。

## 5. Readiness 与部分结果

并行汇聚后使用统一 Readiness 语义：

```text
READY      所有必需事实可用
PARTIAL    核心任务仍可继续，但必须标明缺失和限制
BLOCKED    缺少决定性事实，不能形成目标结论
```

Readiness 不是授权或适当性引擎。它只回答：

> 完成当前专业任务所需的事实是否足够。

例如产品数据缺失时，财富 Skill 可以保留客户分析但停止具体产品推荐；客户事实缺失时，只能提供通用核验清单。

## 6. 真实业务映射

| 业务 | 调用形态 | 等待/并行语义 |
|---|---|---|
| 账户余额 | 单次 Query | 同步短等待 |
| 财富客户诊断 | 多个 Query | 并行读取，必需/可选事实汇聚 |
| 反诈名单匹配 | 银行预检内的风险调用 | 同步短等待；超时由银行决定阻断或人工复核 |
| 行内转账 | Task | 预检后等待确认/强认证；提交后可能 Processing/Unknown |
| 投诉受理 | Task | 创建后长期 Processing，通过状态查询或事件更新 |
| 贷款/保险申请 | Task | 补材料、人工审核和外部结果多次恢复 |
| 投资研究 | 多源 Query | 并行取数，允许有证据标识的部分结果 |
| 预约/定投 | Task | 银行系统持有计划，Runtime 不持续挂起 |

## 7. 演进顺序

### 当前立即验证

当前已完成：

1. 用 V1 状态验证确认、Processing 和 Unknown；
2. 反诈转账由银行预检同步消费风险结果；
3. 财富上下文明确必需事实、可选事实和 PARTIAL 输出；
4. 覆盖重复确认、提交断连、结果未知和只查证不重提测试。

`ExecutionBudget`、`WaitCondition` 和 `RequirementSet` 仍是候选合同，不因本次实现提前升格。

### 出现真实需求后再升格

1. 首个长任务接入时，冻结 `ExecutionBudget` 与 `WaitCondition`；
2. 第二个并行上下文场景接入时，冻结 `RequirementSet` 与 `Readiness`；
3. 出现回调场景时，再定义 Callback/Event Envelope；
4. 出现多个 Runtime 消费者时，再冻结 Runtime Capability Profile。

## Non-goals

- 不在 Core 中实现线程池、队列、事件总线或 DAG 引擎；
- 不在 Core 中实现银行风险、审批、适当性或交易状态机；
- 不让模型决定幂等、重试、结果查证或资金动作终态；
- 不为了未来可能出现的场景提前增加完整 Workflow DSL。

## Validation Principle

一个候选原语进入正式 Core Contract，至少满足其一：

1. 被两个真实业务场景共同需要；
2. 被两个应用消费者共同验证；
3. 有跨语言 Conformance Fixture 证明语义一致。

在此之前，它保持为 ADR 中的候选语义或 Domain Pack 元数据。
