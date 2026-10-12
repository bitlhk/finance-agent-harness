---
schema: speckit-feature-record/v1
feature_id: FEAT-005
title: Sandbox Candidate Evaluation 与数据隔离
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/31
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-08T06:17:47Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-10T01:51:21Z'
  decision_source: conversation
priority: P0
mvp: true
delivery_order: 5
delivery:
  phase: done
  branch: codex/finance-solution-builder-feat-005
  pull_request: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/140
  review_target:
    type: pull-request
    url: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/140
    revision: 13c0a943a1390d7bae9afa40611c25ca5f732762
  merged_commit: 72e570819cab8f047be1af9332e3d9f465828eb7
dependencies:
- FEAT-001
- FEAT-002
external_prerequisites:
- 首个受控场景的脱敏测试数据与冻结量化验收规则
shared_contracts: []
ownership:
  owner: ''
  collaborators: []
architecture_impact:
  level: L1
  update_required: true
  reason: 新增实际 Candidate 评测和优化集回归集 Holdout 隔离
  affected_files:
  - community/finance/builder/finance-solution-builder/docs/architecture/L1-architecture.md
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

# FEAT-005: Sandbox Candidate Evaluation 与数据隔离

## User Value

Solution 负责人可以看到本轮 Candidate 在固定数据和量化规则下的真实效果、时延及 Bad Case，而不再依赖历史
证据回放判断能否上线。

## Scope

- 冻结 Dataset Snapshot，并记录优化集、回归集和 Holdout 的用途、版本、来源、内容摘要与授权范围。
- 通过 Candidate Backend 在受控 Sandbox 中实际执行本轮 Candidate 的启动、健康检查、逐样本调用、
  证据收集和停止。
- 记录逐样本结果与时延，按冻结规则计算量化指标与 `passed / failed / indeterminate` 三态结论。
- 输出结构化 Finding，按 Candidate、Solution/资产组合、Provider/Harness/外部环境、数据或验收规则给出责任边界建议。
- 分别报告合同/Schema、Conformance、本轮 Candidate Sandbox Evaluation、授权 live smoke 和生产在线业务验收；
  历史 replay 只作为辅助证据，不能替代本轮 Candidate 调用。

## Non-Goals

- 使用真实生产数据作为默认评测输入。
- 让 LLM 自由决定通过，或用 Holdout 直接指导修订。
- 在本 Feature 中修改 Candidate、Solution、Harness 资产、数据或验收规则。
- 在本 Feature 中实现 Orchestrator 优化循环、Harness 资产修订提案或 WebUI 过程展示。
- 将授权 live smoke 或生产在线业务验收作为 `0.2.0` 的默认执行步骤。

## User Stories

### US-001: 冻结本轮评测输入与验收规则

作为 Solution 负责人，我希望 Builder 将本轮 Candidate、数据集和量化验收规则绑定成可复现的评测快照，
从而明确本次结果评测了什么，并能在相同条件下复核。

- 前置条件：本轮 Candidate Descriptor、父 Lock、数据来源授权和可计算的验收规则均已提供。
- 主场景：Builder 固定 Candidate/Lock/产物摘要、数据集用途与内容摘要、验收规则及其摘要，并拒绝角色重叠
  或身份不完整的数据。
- 边界或失败场景：数据来源未经授权、样本身份重复、优化集/回归集/Holdout 重叠、验收规则不可计算，或
  Candidate 身份无法与父 Lock 对齐时，在启动 Candidate 前失败关闭。
- 验证（`VER-001`、`VER-002`）。

### US-002: 在 Sandbox 中实际评测本轮 Candidate

作为 Agent 构造者，我希望 Builder 在受控 Sandbox 中实际启动并逐条调用本轮 Candidate，从而在交付前看到
它面对冻结测试数据时的真实业务效果、时延和失败阶段。

- 前置条件：US-001 的评测快照通过校验，Candidate Backend 支持本轮 Descriptor 声明的生命周期和调用协议。
- 主场景：Builder 按 `启动 → 健康检查 → 逐样本调用 → 收集证据 → 停止` 的顺序运行 Candidate，记录每条
  样本的结构化结果、时延、状态和证据引用。
- 边界或失败场景：Candidate 身份漂移、启动或健康检查失败、调用不完整、证据不可验证或清理失败时，
  不得输出通过结论；已启动实例仍须进入受控停止与失败证据收口。
- 验证（`VER-003`、`VER-004`）。

### US-003: 计算质量结论并形成结构化诊断

作为 Solution 负责人，我希望 Builder 使用已冻结的量化规则汇总指标，并把 Bad Case 归因到可处理的责任边界，
从而判断当前 Candidate 是否达标，并为下一轮修正提供明确输入。

- 前置条件：本轮逐样本结果已经收集，指标计算方法与验收表达式均可确定执行。
- 主场景：Builder 对已执行样本计算指标，按冻结验收表达式给出三态结论，并将失败样本聚合为带证据的
  Finding，标明建议责任边界。
- 边界或失败场景：样本未执行完、指标缺失、规则引用未知指标或必要证据不足时，结论为
  `indeterminate`；Evaluation 不直接修改任何资产，也不自行降低阈值。
- 验证（`VER-005`、`VER-006`）。

### US-004: 隔离 Holdout 并区分不同证据层级

作为评测负责人，我希望优化集、回归集和 Holdout 相互隔离，并让报告明确区分历史回放、Sandbox、授权
live smoke 与生产在线验收，从而避免记住已知 Bad Case 或把低层级证据误报为上线通过。

- 前置条件：数据快照已声明每个分区的用途，评测任务已声明本次实际执行的证据层级。
- 主场景：普通优化过程只能读取优化集和允许的回归信息；最终门禁才解封 Holdout，并记录访问事实；报告
  分别记录各证据层级的 executed、status 和 evidence。
- 边界或失败场景：Holdout 标签或逐样本结果被提前读取、历史回放被标为本轮 Sandbox、或未执行层级被
  汇总为通过时，评测失败关闭。
- 验证（`VER-007`、`VER-008`）。

## Verification

- `VER-001`：评测快照记录 Candidate Descriptor 标识、父 Lock 标识、产物摘要、每个数据分区的稳定标识、
  版本、用途、来源、授权与内容摘要，以及量化验收规则和规则摘要；任一必要身份或授权缺失时不得启动 Candidate。
- `VER-002`：样本以稳定标识和内容摘要去重；优化集、回归集与 Holdout 出现样本重叠、用途冲突或未经授权
  内容时，评测请求失败并列出可公开的问题摘要，不产生可用评测结论。
- `VER-003`：Evaluation 只调用 Descriptor 指向、与父 Lock 和产物摘要一致且由 Candidate Backend 确认正在
  运行的本轮 Candidate；执行顺序固定为启动、健康检查、逐样本调用、证据收集和停止，任何阶段失败均不得通过。
- `VER-004`：每条样本结果记录样本引用与摘要、期望和实际结构化结果或获授权摘要、状态、时延、错误阶段
  和证据引用；报告不保存凭据、未经授权的原文、完整 Prompt 或模型内部推理。
- `VER-005`：指标计算与验收表达式只消费冻结快照和本轮已记录结果；完整执行且表达式为真时结论为
  `passed`，完整执行且表达式为假时为 `failed`，执行或证据不完整时为 `indeterminate`，未执行项不得计入通过。
- `VER-006`：Finding 使用稳定标识关联失败样本与证据，并至少能把建议责任边界区分为 Candidate 配置/实现、
  Solution/资产组合、Provider/Harness/外部环境、数据或验收规则；本 Feature 不据此自动修改任何对象。
- `VER-007`：优化过程无法读取冻结 Holdout 的标签或逐样本结果；只有最终门禁能够解封 Holdout，并记录
  访问者、用途、快照摘要和时间，提前访问或分区重叠均失败关闭。
- `VER-008`：合同/Schema、Conformance、历史 replay、本轮 Sandbox、授权 live smoke 和生产在线验收分别
  记录 executed/status/evidence；历史或前一层通过、以及未执行的后一层，均不能被汇总成本轮 Sandbox 或在线通过。
- `VER-009`：相同 Candidate、数据快照和规则会使用相同样本集合与稳定执行顺序；对已捕获结果的指标计算
  保持确定性。若 Candidate 输出本身存在随机性，报告记录重复运行的差异，不宣称逐次预测完全相同。

## Dependencies

- FEAT-001、FEAT-002。

## Shared Contracts

- 复用 `finance_builder_base.evaluation` 的 Case、Metric、Rule 与 Report 语义，并在 HLD 中评审需要新增的
  Dataset Snapshot、Evidence Level 和 Finding 版本化契约。

## Decisions

- 2026-09-08：Euphoria_Yan 接受本 Feature。
- 2026-09-08：确认四层验证证据分别报告，历史回放不能替代本轮 Candidate Sandbox Evaluation。
- 2026-09-10：实现按六层证据契约展开，将 Replay、Sandbox、live smoke 和生产在线验收分别记录；
  Evaluation 三态结论与 Build Report 状态保持分离。

## Delivery History

- 2026-09-10：PR !140 合入 `finance`，merge commit 为 `72e570819cab8f047be1af9332e3d9f465828eb7`；尚未记录版本发布证据。

- 目标版本：`0.2.0`。
