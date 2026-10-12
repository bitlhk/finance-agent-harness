---
schema: speckit-feature-record/v1
feature_id: FEAT-006
title: 受预算约束的 Candidate 自优化闭环
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/31
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-08T06:17:47Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-10T05:19:59Z'
  decision_source: conversation
priority: P0
mvp: true
delivery_order: 6
delivery:
  phase: done
  branch: codex/finance-solution-builder-feat-006
  pull_request: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/141
  review_target:
    type: pull-request
    url: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/141
    revision: d84bf3847f5ee09904065c7ff0a5380bb58550da
  merged_commit: b5041157461d2c09732412272a028bdc957b4e1d
dependencies:
- FEAT-002
- FEAT-004
- FEAT-005
external_prerequisites: []
shared_contracts: []
ownership:
  owner: ''
  collaborators: []
architecture_impact:
  level: L2
  update_required: true
  reason: 明确评测归因、受控差异层、重建、回归比较、预算与停止的确定性控制闭环
  affected_files:
  - community/finance/builder/finance-solution-builder/docs/architecture/L1-architecture.md
  - community/finance/builder/finance-solution-builder/docs/architecture/optimization/L2-architecture.md
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

# FEAT-006: 受预算约束的 Candidate 自优化闭环

## User Value

Agent 构造者可以让 Builder 根据真实 Finding 自动选择一个有权限的修正对象，完成修订、重建和回归，并清楚
知道为什么继续、为什么停止以及效果是否提升。

## Scope

- 将 Finding 归因到 Builder 配置、Candidate 覆盖层、数据、Harness 资产或外部环境。
- 一轮只选择一个修正目标，应用可自动、需批准和禁止修改边界。
- Harness 合同、Pack、Provider、Integration 或 Eval 资产只有在存在 Owner、允许修改范围和验证入口元数据时
  才能进入治理路由；元数据缺失时失败关闭并等待人工处理。
- 驱动 Construction Agent 修订、Candidate 重建、受影响用例复测、回归集与最终 Holdout。
- 记录父/新 Lock、数据摘要、Finding、变更、指标差异、预算和停止原因。

## Non-Goals

- 在线自学习、自动发布或覆盖已发布 Harness 资产。
- 通过降低阈值、修改 Holdout 或删除失败样本取得通过。

## User Stories

### US-001: 从评测问题中选择一个可处理的修正目标

作为 Agent 构造者，我希望 Builder 根据本轮 Evaluation Finding、资产归属和修改权限选择一个明确的修正
目标，以便让每轮优化都有可解释、可执行的处理方向。

- 前置条件：本轮 Evaluation Report、Candidate Descriptor、父 Lock、数据快照和权限元数据均可验证。
- 主要场景：Orchestrator 将 Finding 确定性映射到 Builder 配置、Candidate 覆盖层、优化数据、Harness 资产
  或外部环境；一轮只产生一个修正目标，并记录选择依据和允许的下一步。
- 边界或失败场景：Finding 缺少证据、责任边界不明确、多个目标无法确定优先级，或目标没有 Owner、允许
  修改范围及验证入口时，不发起写操作，进入等待人工或外部处理状态。
- Verification（`VER-001`、`VER-002`）。

### US-002: 在授权边界内修订并重建 Candidate

作为 Agent 构造者，我希望 Builder 对允许自动处理的目标执行一次受控修订并重新构建 Candidate，以便用
新的可运行版本验证修订是否解决了当前问题。

- 前置条件：US-001 已选出 Builder 配置、Candidate 覆盖层或允许补充的优化数据，并给出明确权限、预算和
  验证命令。
- 主要场景：Builder 为 Construction Agent 或 Data Factory 创建受控任务，保存父 Lock 和原始快照，校验
  实际改动后重新装配、构建并生成新 Lock；冻结验收规则、回归数据和 Holdout 不随修订改变。
- 边界或失败场景：任务越出允许路径、试图修改验收阈值或冻结数据、验证失败、构建失败或产物身份不一致
  时，本轮失败关闭，不把该 Candidate 标记为可用版本。
- Verification（`VER-003`、`VER-004`）。

### US-003: 用相同标准比较新旧 Candidate 并保留最佳版本

作为 Solution 负责人，我希望 Builder 在可比条件下复测新 Candidate，并保留最近一次已验证的最佳版本，
以便避免一次局部修复造成整体质量回退。

- 前置条件：新 Candidate 已通过构建和运行门禁，父 Candidate、数据快照、规则摘要和指标均可追溯。
- 主要场景：Builder 先运行受影响用例，再运行冻结回归集；只有必需门禁不回退且目标指标取得可验证提升时，
  才把新 Candidate 记录为当前最佳版本。达到最终门禁条件后，再使用冻结 Holdout 作一次最终判断。
- 边界或失败场景：比较条件不同、指标不可计算、质量无提升、任一必需门禁回退或 Holdout 未获授权时，
  保留此前最佳版本，并记录未晋级原因。
- Verification（`VER-005`、`VER-006`）。

### US-004: 在达标、预算耗尽或需要外部处理时停止

作为 Solution 负责人，我希望 Builder 在明确条件下结束优化并给出完整记录，以便控制时间和成本，并让
后续人员能够复核每轮为什么继续或停止。

- 前置条件：任务已声明最大轮次、最长时间、成本上限、连续无提升上限和可用修改权限。
- 主要场景：每轮更新已用预算、最佳 Candidate、指标变化和待处理 Finding；达标时结束并输出最佳版本，
  触及任一预算、连续无提升、需要批准或依赖外部处理时按稳定原因停止。
- 边界或失败场景：Harness 合同、Pack、Provider、Integration、Eval 资产或外部环境需要变更时，只记录
  责任方、证据和建议动作；未获得对应治理元数据和人工决定前不继续自动修订。
- Verification（`VER-007`、`VER-008`、`VER-009`）。

## Verification

- `VER-001`：每轮输入包含 Evaluation Report、Candidate Descriptor、父 Lock、数据与规则摘要；输出恰好一个
  带稳定类型、Finding 引用、选择依据、目标引用和允许动作的修正目标，输入相同则路由结果相同。
- `VER-002`：证据不足、责任不明、多目标优先级不确定，或目标缺少 Owner、允许修改范围、审批策略或验证
  入口时，本轮不得创建 Construction/Data Factory 写任务，并输出可公开的等待原因和所需信息。
- `VER-003`：允许自动处理的 Builder 配置或 Candidate 覆盖层通过 Construction Agent Backend 执行；只允许
  补充的优化数据通过 Data Factory 产生新版本和来源记录。每轮只修改一个目标，且实际 Diff 完全位于授权范围。
- `VER-004`：每次修订都关联父 Lock、父 Candidate、原始数据与规则摘要；重建成功后生成不同的新 Lock 和
  Candidate 版本身份，逻辑 Candidate ID 可以保持不变。越界写入、修改冻结回归/Holdout、改变阈值、验证失败、
  构建失败或身份不一致均失败关闭。
- `VER-005`：新旧 Candidate 只在数据快照、验收规则、运行配置和证据层级一致时比较；报告依次记录受影响
  用例和冻结回归集结果，不可比时不计算提升或替换最佳版本。
- `VER-006`：新 Candidate 只有在所有必需门禁不回退且至少一个目标指标取得严格提升时才能成为当前最佳；
  无提升、指标不可计算或任一门禁回退时保留此前最佳 Candidate。Holdout 仅在最终门禁获授权后解封一次。
- `VER-007`：达到验收规则、最大轮次、最长时间、成本上限、连续无提升上限、需要人工批准、外部阻塞和
  无可执行目标分别产生稳定停止原因；终态产生后不得再启动新的修订或评测任务。
- `VER-008`：每轮 Optimization Record 至少记录父/新 Lock、父/新 Candidate、数据和规则摘要、Finding、
  修正目标、权限与审批事实、变更摘要、验证证据、指标差异、预算消耗、最佳版本和继续/停止决定。
- `VER-009`：Harness 合同、Pack、Provider、Integration 或 Eval 资产没有完整治理元数据时只输出外部处理
  建议；不得覆盖已发布资产，不得按场景名称猜测 Owner、仓库、写入路径或验证方式。公开记录不包含凭据、
  真实客户身份、未经授权原文、完整 Prompt 或模型内部推理。

## Dependencies

- FEAT-002、FEAT-004、FEAT-005。

## Shared Contracts

- Finance Solution Builder 模块内 `finance_solution_builder.optimization` v1 契约；首个版本不提升到 Base。

## Decisions

- 2026-09-08：Euphoria_Yan 接受本 Feature。
- 2026-09-08：确认 Harness 资产治理元数据缺失时失败关闭，不以场景硬编码继续自动修订。
- 2026-09-10：Euphoria_Yan 接受 Optimization Controller HLD、受控差异层与模块内 v1 契约，并同意继续拆分 Tasks。
- 2026-09-10：确认本 Feature 实施后只进行代码测试、静态检查和确定性 Fake 闭环，不执行浏览器实际测试。

## Delivery History

- 2026-09-10：PR !141 合入 `finance`，merge commit 为 `b5041157461d2c09732412272a028bdc957b4e1d`；尚未记录版本发布证据。

- 目标版本：`0.2.0`。
