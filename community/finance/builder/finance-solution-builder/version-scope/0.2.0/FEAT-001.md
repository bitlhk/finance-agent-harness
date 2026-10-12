---
schema: speckit-feature-record/v1
feature_id: FEAT-001
title: 语言无关的 Candidate Backend 与描述
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/31
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-08T06:17:47Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-08T10:22:20Z'
  decision_source: conversation
priority: P0
mvp: true
delivery_order: 1
delivery:
  phase: done
  branch: codex/finance-solution-builder-0.2.0-features
  pull_request: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/124
  review_target:
    type: pull-request
    url: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/124
    revision: 1f30c12035ad15ac1d1354953a8b8782af3b4f44
  merged_commit: 18e02c79fc9d3dae54fcb727f6fc6f313820463d
dependencies: []
external_prerequisites: []
shared_contracts:
- community/finance/builder/base/docs/architecture/contracts/candidate-backend-protocol.md
ownership:
  owner: ''
  collaborators: []
architecture_impact:
  level: L2
  update_required: true
  reason: 新增 Candidate Backend v1 公共模型、生命周期 SPI、确定性注册和可复用 conformance 边界
  affected_files:
  - community/finance/builder/finance-solution-builder/docs/architecture/L1-architecture.md
  - community/finance/builder/finance-solution-builder/docs/architecture/L2-architecture.md
  - community/finance/builder/finance-solution-builder/docs/architecture/contracts/candidate-backend-protocol.md
definition_of_done:
  code_complete: true
  tests_complete: true
  evidence_complete: true
  architecture_synchronized: true
  review_passed: true
release:
  target: 0.2.0
  delivered_in: finance-solution-builder-v0.2.0
  evidence: git-tag:finance-solution-builder-v0.2.0
completion:
  completed_by: Euphoria_Yan
  completed_at: '2026-09-15T16:06:37Z'
  evidence_source: post-merge-release-backfill
---

# FEAT-001: 语言无关的 Candidate Backend 与描述

## User Value

Agent 构造者可以用同一套 Builder 流程管理不同语言和 Runtime 的 Candidate，而不必让 Orchestrator、
Evaluation 和 WebUI 理解 Maven、JAR、Python 包或各 Runtime 的私有细节。

## Scope

- 定义 Candidate Descriptor 的身份、Backend、语言、Runtime、实际产物及摘要、入口、调用协议、健康检查、
  实际安装能力/工具、能力到 Provider 绑定和 Lock/Report 关联语义。
- 定义 Backend 的环境预检、装配、构建、启动、调用、证据收集与停止生命周期。
- 统一公共状态、失败类别和证据引用；语言专有信息进入版本化扩展区。
- 使用轻量测试 Backend 验证生命周期和错误边界。

## Non-Goals

- 实现 Java 或 Python 的具体装配逻辑。
- 要求不同语言使用相同构建工具、进程模型或内部目录结构。

## User Stories

### US-001: 用统一描述识别本轮 Candidate

作为 Agent 构造者，我希望每个构建结果都提供语言无关且可校验的 Candidate 描述，从而在不阅读工程内部
代码的情况下知道它是什么、由什么产物构成、怎样启动和调用，以及怎样判断它已经就绪。

- Preconditions: Solution 输入和实际资产闭包已经校验并锁定，Candidate Backend 已产生候选产物。
- Main scenario: Backend 返回 Candidate 描述；Builder 校验其身份、Backend、语言、Runtime、实际产物、入口、
  调用协议、健康检查、实际安装能力/工具、能力到 Provider 绑定和内容摘要，并将描述与本轮 Build Task、
  Solution Lock、Build Report 和产物关联。
- Boundary or failure scenario: 描述缺少必需信息、Schema 版本不受支持、内容摘要与实际产物不一致，或描述
  引用了本轮交付边界之外的产物时，Candidate 不得进入启动或 Evaluation。
- Verification: `VER-001`、`VER-002`、`VER-003`。

### US-002: 在投入构建前确认 Backend 能力

作为 Agent 构造者，我希望 Builder 在下载、编译或启动前确认所选 Backend 支持目标语言、Runtime、产物和
调用协议，从而尽早发现不支持或含糊的组合，避免产生无法运行的 Candidate。

- Preconditions: 构建请求已经声明目标 Candidate 的语言、Runtime、产物类型、调用协议和所需能力，并提供
  Harness 发布的资产依赖与能力元数据。
- Main scenario: Builder 根据显式选择或唯一匹配结果定位 Backend，在任何下载、编译或启动前确认全部必需
  资产已解析，检查 Domain/Capability Pack、Provider 操作类型、Profile 和 Host Wiring 的能力覆盖，并记录
  实际采用的 Backend 身份和预检证据。
- Boundary or failure scenario: 没有 Backend、存在多个无法消歧的 Backend、必需资产或运行工具缺失、能力覆盖
  不完整、Provider 操作类型不兼容或 Host Wiring 不完整时，任务以明确的 blocked/failed 结果停止，不回退到
  另一个语言、Provider、场景配方或未声明的实现。
- Verification: `VER-004`、`VER-005`。

### US-003: 通过统一生命周期评测 Candidate

作为 Evaluation 维护者，我希望通过同一生命周期构建、启动、检查、调用和停止不同 Candidate，从而让
逐样本评测与 Candidate 的实现语言、构建工具和进程模型解耦。

- Preconditions: Candidate 描述有效，Backend 预检通过，受控运行环境和调用输入已经准备完成。
- Main scenario: Builder 依次完成构建、启动和健康检查并保持本轮实例运行；Evaluation 通过描述中声明的协议
  提交调用并取得规范化结果；完成、中止或失败后收集证据并由同一 Backend 停止本轮实例。
- Boundary or failure scenario: 构建失败、启动超时、健康检查失败、调用协议不匹配或停止失败时，Backend
  返回稳定阶段、错误类别和可安全展示的下一步；后续阶段不得伪装为已执行。
- Verification: `VER-006`、`VER-007`、`VER-008`、`VER-009`。

### US-004: 获得可追溯且安全的运行证据

作为评审或审计人员，我希望 Candidate 的身份、实际生命周期结果和证据可以关联到同一 Build Task 与 Lock，
从而复核评测确实针对本轮产物，同时避免在报告中泄露凭据、宿主路径或业务原文。

- Preconditions: Backend 已执行至少一个生命周期动作。
- Main scenario: 每个动作记录 Candidate 身份、所属阶段、状态、耗时和公共证据引用；成功与失败均可由
  Build Report 和后续 Evaluation Report 追溯到同一 Candidate 描述及内容摘要。
- Boundary or failure scenario: 私有日志只能作为受控引用；凭据、宿主绝对路径、未经授权的原始输入输出和
  模型内部推理不得进入描述、公共事件、报告或交付包。
- Verification: `VER-010`、`VER-011`、`VER-012`。

### US-005: 增加新语言而不改变上层流程

作为 Candidate Backend 开发者，我希望通过实现同一公共生命周期和版本化扩展区接入新语言，从而无需
修改 Orchestrator、Evaluation 的业务流程或复制另一套 Candidate 状态语义。

- Preconditions: 新 Backend 能提供目标语言所需的构建、启动、调用、健康检查和停止实现。
- Main scenario: 新 Backend 使用相同公共描述和生命周期结果，仅在版本化扩展区表达语言或 Runtime 专有
  信息；公共测试 Backend 和后续真实 Backend 使用同一契约测试。
- Boundary or failure scenario: 删除必需字段、改变既有状态含义、把私有构建工具字段变成公共必填项或要求
  上层识别具体语言时，变化不能作为 v1 兼容实现进入。
- Verification: `VER-013`、`VER-014`、`VER-015`。

## Verification

- `VER-001`：合法 Candidate 描述完整包含身份、Backend、语言、Runtime、实际产物及摘要、入口、调用协议、
  健康检查、实际安装能力/工具和能力到 Provider 绑定；规范化序列化后重复读取结果一致。
- `VER-002`：缺少任一必需信息、Schema 版本不受支持或字段值不合法时，描述校验返回稳定错误且不启动 Candidate。
- `VER-003`：篡改 Candidate 产物、入口或摘要后，描述与实际产物绑定校验失败；失败结果关联当前 Build Task
  和 Solution Lock，且不会复用另一轮 Candidate。
- `VER-004`：对已支持组合，Builder 在任何下载、编译或启动动作前选出唯一 Backend，确认全部必需资产已解析，
  并记录 Backend 身份、Domain/Capability Pack、Provider 操作类型、Profile、Host Wiring 的能力匹配和环境预检结果。
- `VER-005`：无匹配、匹配歧义、必需资产或工具缺失、能力覆盖不完整、Provider 操作类型或 Host Wiring 不兼容
  分别返回稳定的 blocked/failed 结果；不得静默换用其他语言、Runtime、Provider、场景配方、产物类型或调用协议。
- `VER-006`：测试 Backend 按构建、启动、健康检查、调用、证据收集和停止的声明顺序执行；Candidate 在健康
  检查通过后持续运行到 Evaluation 完成、中止或失败；不适用动作显式标记 skipped，不从报告中消失。
- `VER-007`：调用请求只依赖公共 Candidate 身份、协议和载荷引用；规范化响应能关联请求、Candidate 与证据，
  不暴露 Backend 私有进程或构建工具字段。
- `VER-008`：构建失败、启动超时、健康检查失败和调用失败都会停止依赖它的后续动作，并保留已完成动作的事实。
- `VER-009`：停止操作可安全重复；部分启动后失败、Evaluation 中止和正常完成三条路径最终都不会留下由本轮
  Builder 管理的活动 Candidate 实例。
- `VER-010`：Descriptor、Backend 装配结果、Build Event、Build Report 和后续 Evaluation Report 通过 task id、
  Candidate 身份、Lock 摘要和实际产物摘要形成一致关联；身份或摘要不一致时拒绝证据归并。
- `VER-011`：公共事件为每个生命周期动作记录阶段、状态、耗时、公共错误和证据引用；不要求上层解析 Maven、
  Python 或 Runtime 私有日志才能判断结果。
- `VER-012`：受控正反样本扫描确认 Candidate 描述、公共事件、报告和交付包不包含凭据、宿主绝对路径、
  未经授权原文或模型内部推理。
- `VER-013`：FEAT-001 的轻量测试 Backend 通过完整 Descriptor 与生命周期 conformance，并发布可复用
  测试套件；FEAT-002 的真实 OpenJiuwen Java Backend 和 FEAT-010 的 Python Backend 分别以通过同一
  套件作为各自验收门禁。
- `VER-014`：Orchestrator 和 Evaluation 的契约测试不导入 Maven、JAR、Python 模块或任一 Backend 私有 DTO。
- `VER-015`：v1 新增可选字段不影响旧消费者；删除、重命名、收窄状态或增加语言专有公共必填字段会被
  兼容性检查拒绝，并要求新的契约版本。

## Dependencies

- 无 Feature 依赖；复用已接受的 Solution Build Protocol。

## Shared Contracts

- `community/finance/builder/base/docs/architecture/contracts/solution-build-protocol.md`

## Decisions

- 2026-09-08：Euphoria_Yan 接受本 Feature。
- 2026-09-08：依据 Finance Harness 开发指导，补充实际安装能力、Provider 绑定、能力覆盖预检和跨报告证据关联。
- 2026-09-08：Euphoria_Yan 接受详细行为与 HLD；FEAT-001 发布轻量 Backend conformance，真实 Java/Python Backend 分别由 FEAT-002/010 使用同一套件验收。

## Delivery History

- 2026-09-09：PR !124 合入 `finance`，merge commit 为 `18e02c79fc9d3dae54fcb727f6fc6f313820463d`；尚未记录版本发布证据。

- 目标版本：`0.2.0`。
