---
schema: speckit-feature-record/v1
feature_id: FEAT-003
title: 构造 Agent 的受控执行契约
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/31
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-08T06:17:47Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-13T05:56:15Z'
  decision_source: conversation
priority: P0
mvp: true
delivery_order: 3
delivery:
  phase: done
  branch: codex/finance-solution-builder-feat-003
  pull_request: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/131
  review_target:
    type: pull-request
    url: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/131
    revision: 6888619f2758e322ddf0d61c2ec52c07eb21a58d
  merged_commit: 66408b1b0afd8c3cab3cecb33d35d5a575868769
dependencies:
- FEAT-001
external_prerequisites: []
shared_contracts:
- community/finance/builder/finance-solution-builder/docs/architecture/contracts/construction-agent-backend-protocol.md
ownership:
  owner: ''
  collaborators: []
architecture_impact:
  level: L2
  update_required: true
  reason: 新增可替换构造 Agent 与 Orchestrator 之间的稳定控制边界和共享协议
  affected_files:
  - community/finance/builder/finance-solution-builder/docs/architecture/L2-architecture.md
  - community/finance/builder/finance-solution-builder/docs/architecture/contracts/construction-agent-backend-protocol.md
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

# FEAT-003: 构造 Agent 的受控执行契约

## User Value

Agent 构造者可以选择不同的 Coding Agent 执行工程修订，同时继续使用一致的工作区隔离、审批、事件、取消、
验证和审计体验。

## Scope

- 定义构造任务的创建/恢复、目标、父 Lock、工作区、允许修改范围、验证命令和预算。
- 规范化进展、文件变更、工具结果、审批请求、取消、超时和终态事件。
- 规定外部副作用、恢复幂等、敏感内容和模型内部推理的边界。
- 用假 Backend 验证契约，不绑定具体构造 Agent。

## Non-Goals

- 实现 OpenCode 或 Codex 的具体适配。
- 让构造 Agent 决定资产权限、验收阈值或最终交付。

## User Stories

### US-001: 受控地委托一次 Candidate 修订

作为 Agent 构造者，我希望 Orchestrator 将修订目标、Finding、父 Lock、工作区、允许修改范围、验证命令和
预算完整交给构造 Agent，从而让每次代码或配置修改都有明确依据，并且不会越出本轮授权边界。

- 前置条件：本轮 Candidate 已有稳定身份和父 Lock；工作区、允许写入路径、验证命令和预算均已由 Builder 确定。
- 主场景：Orchestrator 创建构造任务；Backend 在唯一工作区中执行修订，持续返回规范化事件，并以成功、失败、
  取消、超时或等待审批之一结束。
- 边界或失败场景：缺少必需上下文、请求越界写入、预算耗尽、未经批准的外部副作用或终态后继续写入时，
  Backend 失败关闭且不把结果登记为可用 Candidate。
- 验证（`VER-001`、`VER-002`、`VER-003`）。

### US-002: 看到一致的构造进展和审批请求

作为工作台用户，我希望不同构造 Agent 都产生同一语义、可按顺序重放的进展、变更、验证、审批和终态事件，
从而能够在同一工作台理解本轮做了什么、为什么暂停以及最终是否形成有效修订。

- 前置条件：构造任务已经创建，且事件关联同一任务、父 Lock 和工作区。
- 主场景：工作台按事件序号展示阶段进展、文件变更摘要、工具结果、审批请求和终态；任务恢复后从已确认位置续读。
- 边界或失败场景：重复事件不会造成重复副作用；乱序或终态后的事件被拒绝；日志和持久化事件不得包含凭据、
  宿主绝对路径或模型内部推理。
- 验证（`VER-004`、`VER-005`、`VER-006`）。

## Verification

- `VER-001`：当创建请求缺少任务标识、父 Lock、唯一工作区、允许修改范围、验证命令或非负预算时，公共契约返回
  明确的输入错误，且 Backend 不开始执行。
- `VER-002`：当构造 Agent 报告修改路径时，Backend 只接受解析后仍位于工作区且命中允许范围的路径；任一越界路径
  使本轮终止为失败，修改结果不得登记为可用 Candidate。
- `VER-003`：当时间、轮次、工具调用或成本预算耗尽，或者出现未经批准的外部副作用请求时，Backend 进入超时、
  取消或等待审批状态，并且不会把未验证工作区标记为完成。
- `VER-004`：假 Backend 能依次产生已开始、进展、文件变更、工具结果、审批请求和唯一终态事件；事件具有单调递增
  序号，任务恢复后能够从已确认序号继续读取而不重复已完成的副作用。
- `VER-005`：任务进入成功、失败、取消或超时终态后，后续进展事件和状态迁移均被拒绝；重复取消与重复恢复保持幂等。
- `VER-006`：输入、事件、持久化快照和结果在序列化后不包含凭据值、宿主绝对路径或模型内部推理；只保留脱敏摘要、
  仓库相对路径和证据引用。
## Dependencies

- FEAT-001 提供待修改 Candidate 的稳定身份。

## Shared Contracts

- [Construction Agent Backend Protocol v1](../../docs/architecture/contracts/construction-agent-backend-protocol.md)
  定义任务、事件、审批、恢复、取消、预算与公开信息边界。

## Decisions

- 2026-09-08：Euphoria_Yan 接受本 Feature。
- 2026-09-09：Euphoria_Yan 接受 Construction Agent Backend v1 HLD，并选择继续拆分实施任务。
- 2026-09-13：Euphoria_Yan 在 VER-001～VER-006 逐条技术验收通过后，接受本 Feature 的行为验收。

## Delivery History

- 2026-09-09：PR !131 合入 `finance`，merge commit 为 `66408b1b0afd8c3cab3cecb33d35d5a575868769`；尚未记录版本发布证据。

- 目标版本：`0.2.0`。
- 2026-09-09：任务就绪检查通过，进入实施阶段。
- 2026-09-09：完整模块 147 项回归通过；自动评审发现的两处契约校验缺口已修复，提交前本地复审结论为 GO。
