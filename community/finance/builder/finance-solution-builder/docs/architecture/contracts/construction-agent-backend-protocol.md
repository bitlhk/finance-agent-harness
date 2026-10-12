---
schema: speckit-shared-contract/v1
contract_id: CONTRACT-CONSTRUCTION-AGENT-BACKEND-V1
title: Construction Agent Backend Protocol v1
status: accepted
owner: Finance Solution Builder
collaborators:
  - Orchestrator
  - Construction Agent Backend maintainers
review:
  decided_by: Euphoria_Yan
  decided_at: '2026-09-09T09:05:01Z'
related_features:
  - FEAT-003
  - FEAT-004
  - FEAT-009
compatibility: backward-compatible
---

# CONTRACT-CONSTRUCTION-AGENT-BACKEND-V1: Construction Agent Backend Protocol v1

## Purpose And Boundary

本契约统一 Orchestrator 委托 Coding Agent 修订 Candidate 工程时的任务、权限、进展、审批、恢复、
取消和终态语义。它让 Builder 可以替换不同的构造 Agent，同时继续使用一致的控制和审计方式。

Orchestrator 决定修订目标、父 Lock、工作区、允许写入范围、验证命令、预算和审批结果。Backend
负责在这些边界内执行并报告，不得改变验收标准、扩大权限或决定最终交付。

本契约不定义 OpenCode、Codex 或其他产品的私有会话协议，不负责 Candidate 的业务评测，也不要求
不同 Backend 使用相同进程、模型或内部实现。

## Consumers

- Finance Solution Builder Orchestrator；
- 后续 OpenCode、Codex 等 Construction Agent Backend 适配；
- 展示构造进度、文件变化和审批请求的 Builder WebUI；
- 使用构造结果执行重建和回归的后续优化流程。

## Inputs

一次构造任务至少包含：

- 稳定任务身份和幂等键；
- 本轮待修 Candidate 身份与父 Lock 摘要；
- 一项明确的修订目标和已脱敏 Finding 引用；
- 唯一工作区引用及允许写入的仓库相对路径集合；
- 由 Builder 固定的验证命令；
- 时间、迭代轮次、工具调用次数和成本微单位预算；
- 外部副作用的审批策略。

缺少任一必需上下文时，Backend 不得开始执行。凭据由受信运行环境注入，不能进入任务输入。

## Outputs

- 关联任务和父 Lock 的稳定任务句柄；
- 可从游标续读的有序事件批次；
- 需要人工决定时的结构化审批请求；
- 成功、失败、取消或超时中的唯一终态；
- 成功时的修改摘要、实际变更路径、验证结果与证据引用；
- 失败时的稳定错误类别、公开安全摘要和可执行下一步。

输出不包含完整 Prompt、模型内部推理、凭据、宿主绝对路径或未经授权的业务原文。

## Public Operations And Types

| 操作 | 输入 | 输出 | 约束 |
|---|---|---|---|
| `start` | `ConstructionTaskRequest` | `ConstructionTaskHandle` | 幂等键绑定完整请求；重复请求不创建第二个任务 |
| `resume` | `ResumeRequest` | `ConstructionTaskHandle` | 只恢复已有任务；确认序号不能超过最新事件 |
| `events` | `EventBatchRequest` | `ConstructionEventBatch` | 只返回游标之后的有序公共事件 |
| `approve` | `ApprovalDecision` | `ConstructionTaskHandle` | 只接受当前待批 ID；重复同一决定不产生副作用 |
| `cancel` | `CancelRequest` | `ConstructionResult` | 首次取消产生唯一终态，重复取消返回同一结果 |
| `result` | `ResultRequest` | `ConstructionResult` | 任务未终止时不返回伪造结果 |

`ConstructionTaskRequest` 固定 Candidate、父 Lock、Finding 引用、`WorkspaceBoundary`、
`ValidationCommand` 和 `ConstructionBudget`。`ConstructionEvent` 只表达 started、progress、
file-change、tool-result、approval-required 及唯一终态；`ConstructionResult` 汇总实际变更路径、
验证结果、预算用量和证据引用。

## Semantics And Invariants

1. `start` 只接受完整且合法的任务，返回与任务身份绑定的句柄；重复幂等请求返回同一任务事实，
   不重复创建副作用。
2. `resume` 恢复已有任务事实，不新建任务。未知句柄或身份不匹配必须拒绝。
3. `events` 使用任务内单调递增序号和游标返回批次。调用方确认游标后，后续读取不重复已确认事件；
   重放保留原事件身份、顺序和内容。
4. 标准事件至少覆盖 `started`、`progress`、`file-change`、`tool-result`、
   `approval-required` 以及一个终态事件。供应商专有事实只能进入带命名空间的可选扩展区。
5. Backend 报告的每个变更路径必须规范化为仓库相对路径，并命中任务允许范围。任一越界路径使
   本轮失败关闭，相关结果不得登记为可用 Candidate。
6. 时间、迭代、工具调用或成本预算由 Orchestrator 给定且不可被 Backend 上调。预算耗尽后不得
   启动新的工具或修改操作，任务进入超时或失败终态。
7. 外部网络写入、发布、提交、部署或其他受控副作用只能产生 `approval-required`。`approve` 只接受
   与当前待批请求匹配的决定；未批准前不得执行副作用。
8. `cancel` 幂等。收到取消后不得开始新动作；正在执行且无法立即中止的动作结束后必须进入取消终态。
9. `result` 只返回当前任务事实。成功结果必须证明所有声明的验证命令已执行并通过；未验证或等待审批
   的工作区不能成为成功结果。
10. 成功、失败、取消和超时均为终态。终态只能出现一次，之后拒绝追加进展、文件变化或状态迁移。

## Errors And Boundary Cases

以下情况必须在执行前或发现时失败关闭：

- 任务、Candidate、父 Lock、工作区或幂等身份缺失或不匹配；
- 工作区引用不是仓库相对路径，或者解析后越出当前工作区；
- 允许路径为空、重复、互相矛盾，或实际变更未命中允许范围；
- 验证命令缺失，预算为负数、浮点金额或超出契约计量范围；
- 事件序号重复、倒退、跳过已声明终态，或者终态后仍有新事件；
- 审批请求已经失效、审批身份不匹配或未经批准即执行副作用；
- 公共载荷包含凭据字段、敏感 URL、宿主绝对路径、完整 Prompt 或模型内部推理。

公共错误只记录稳定错误码、发生阶段、公开摘要、责任边界和下一步。Backend 私有日志只能通过受控
证据引用访问，不能复制进公共事件或结果。

## Compatibility

- v1 wire 字段使用 camelCase，Schema 版本为 `1.0`。
- 消费者容忍具有默认值的新可选字段，但继续严格校验必需字段、已知字段值、状态和事件不变量。
- 删除或重命名必需字段、收窄既有状态含义、改变幂等或终态语义，需要发布新的契约版本。
- 供应商私有会话、工具、模型和流式数据只能由具体 Backend 适配，不得成为 v1 公共必填字段。
- 本契约是新增边界，不改变现有 Solution 输入、Candidate Backend、OpenJiuwen Java 构建、CLI、
  Lock 或 Build Report。

## Verification

- 模型与 JSON Schema 往返一致，且覆盖缺字段、非法枚举、非法预算和兼容性反例；
- 确定性假 Backend 覆盖创建、事件读取、审批暂停与恢复、取消、超时、失败和成功；
- 事件序号、恢复游标、重复审批、重复取消和唯一终态通过一致性测试；
- 工作区路径和允许范围覆盖合法、穿越、绝对路径、越界变更和符号链接等边界；
- 所有输入、事件、快照、错误和结果经过敏感信息与宿主路径扫描；
- Finance Solution Builder 现有 Java Candidate、CLI、WebUI 与报告回归保持通过；
- 后续每个真实 Construction Agent Backend 必须通过同一组公共一致性测试。

## Decisions

- 2026-09-09：Euphoria_Yan 接受 Construction Agent Backend 与 Candidate Backend 并列；当前契约
  保留在 Finance Solution Builder，使用任务句柄、拉取式事件批次和恢复游标，具体供应商适配与
  自优化循环由后续 Feature 交付。决定记录见
  [Issue #31 评论](https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/31#comment_188902150)。
