---
schema: speckit-feature-record/v1
feature_id: FEAT-004
title: OpenCode 构造 Agent 适配
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/31
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-08T06:17:47Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-10T00:25:16Z'
  decision_source: conversation
priority: P0
mvp: true
delivery_order: 4
delivery:
  phase: done
  branch: codex/finance-solution-builder-feat-004
  pull_request: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/138
  review_target:
    type: pull-request
    url: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/138
    revision: c4a28d2e90dc93ee2ff57249436395ed17d9d567
  merged_commit: d3f3e0c6b2ad6bb4ddb6795cce57cd3e23d8d97d
dependencies:
- FEAT-003
external_prerequisites:
- 可固定版本且许可证兼容的 OpenCode 服务或 SDK
shared_contracts:
- community/finance/builder/finance-solution-builder/docs/architecture/contracts/construction-agent-backend-protocol.md
ownership:
  owner: ''
  collaborators: []
architecture_impact:
  level: L1
  update_required: false
  reason: 实现已定义的 Construction Agent Backend
  affected_files: []
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

# FEAT-004: OpenCode 构造 Agent 适配

## User Value

Agent 构造者可以在 Builder 工作台中委托 OpenCode 完成一次有边界的 Candidate 工程修订，并保留统一的进展、
审批、变更和验证记录。

## Scope

- 将 OpenCode 的会话、权限、事件、结构化输出和取消映射到 FEAT-003。
- 绑定任务工作区和允许写入范围，传递 Finding、验证命令及预算。
- 将 OpenCode 私有失败映射为公共错误，并支持安全终止和可判定恢复。

## Non-Goals

- 将 OpenCode 私有 DTO 提升为 Builder 公共契约。
- 无人工批准地执行越界命令、网络副作用或资产发布。

## User Stories

### US-001: 使用 OpenCode 完成一次受控 Candidate 修订

作为 Agent 构造者，我希望把明确的 Candidate 修订任务交给 OpenCode，从而复用成熟的 Coding Agent
能力，并继续遵守 Builder 已确定的父 Lock、工作区、写入范围、验证命令和预算。

- 前置条件：Builder 已生成通过 FEAT-003 校验的构造任务；OpenCode 入口、版本和 Backend 能力可验证。
- 主场景：适配器在唯一工作区启动 OpenCode，规范化文件变化、验证和终态；只有通过全部验证才返回成功。
- 边界或失败场景：入口不可用、身份/工作区不匹配、越界写入、验证缺失或超预算时失败关闭。
- 验证（`VER-001`、`VER-002`）。

### US-002: 决定 OpenCode 请求的高风险动作

作为 Agent 构造者，我希望工作台展示 OpenCode 提出的外部副作用或写入范围扩张请求，并由我明确批准或
拒绝，从而避免 Coding Agent 自行扩大本轮权限。

- 前置条件：运行中的 OpenCode 准备执行当前任务未授权的外部动作或修改额外路径。
- 主场景：适配器产生审批事件并暂停；只有匹配的批准可以继续，拒绝形成稳定的非成功终态。
- 边界或失败场景：缺少审批、编号错误、跨任务或重复审批不产生新的外部副作用。
- 验证（`VER-003`）。

### US-003: 中止、超时或恢复 OpenCode 任务

作为 Agent 构造者，我希望取消方向错误或耗时过长的任务，并在恢复条件可证明时继续原任务，从而控制
时间和成本，同时避免重复执行已经完成的动作。

- 前置条件：适配器保存 Builder 任务、OpenCode 会话、最后事件序号和工作区身份。
- 主场景：取消终止受管进程；预算耗尽形成超时；安全恢复从已确认游标继续。
- 边界或失败场景：会话丢失、状态不可确认、工作区或父 Lock 变化时明确失败，不静默创建替代会话。
- 验证（`VER-004`、`VER-005`）。

### US-004: 在统一工作台查看公开安全的构造过程

作为工作台用户，我希望 OpenCode 的进展、文件变化、验证、审批和终态使用 FEAT-003 的统一格式展示，
从而无需理解 OpenCode 私有会话或事件字段，也能复核本轮做了什么。

- 前置条件：OpenCode 返回供应商事件、输出或错误，适配器持有对应 Builder 任务上下文。
- 主场景：适配器输出规范化摘要、仓库相对路径和证据引用，并保持事件顺序与唯一终态。
- 边界或失败场景：凭据、宿主路径、业务原文、完整 Prompt、内部推理和私有对象不进入公共结果。
- 验证（`VER-006`）。

## Verification

- `VER-001`：合法任务通过可控 OpenCode 测试进程完成真实工作区修改；事件与实际差异一致，全部验证通过，
  结果通过 FEAT-003 公共校验。
- `VER-002`：入口不可用、版本/能力不兼容、身份或工作区不匹配、越界、验证缺失和超预算均在明确阶段
  失败，不返回可用 Candidate。
- `VER-003`：网络副作用或范围扩张先产生唯一审批请求；批准只执行一次，拒绝和错误/重复审批不产生
  未授权副作用。
- `VER-004`：取消终止受管进程并产生唯一取消终态；预算耗尽产生超时，终态后无新增进展或文件变化。
- `VER-005`：重复启动保持幂等；恢复只在会话、工作区和父 Lock 可验证时从确认游标继续，否则明确失败。
- `VER-006`：Orchestrator、WebUI 和持久化数据只消费 FEAT-003 公共模型，不包含 OpenCode 私有对象、凭据、
  宿主绝对路径、完整 Prompt 或模型内部推理。

## Dependencies

- FEAT-003。

## Shared Contracts

- [Construction Agent Backend Protocol v1](../../docs/architecture/contracts/construction-agent-backend-protocol.md)。

## Decisions

- 2026-09-08：Euphoria_Yan 接受本 Feature。
- 2026-09-09：Euphoria_Yan 接受 HLD，固定 OpenCode 1.18.29 的本地 HTTP Server 接入面，并继续拆分实施任务。
- 2026-09-09：计划、任务和实施权限检查通过，进入实施。
- 2026-09-10：安装固定版本 OpenCode 1.18.29，并使用脱敏临时 Candidate 完成真实的启动、Session、修改、差异校验、Builder 验证和停止；实测发现 Session Diff 可能为空，适配器已增加工作区内容摘要交叉核对并补充回归。
- 2026-09-10：自动质量复审补齐审批期间的连续事件流、供应商失败公共化和允许写入根的解析后校验。
- 2026-09-10：本地差异评审结论为 GO-WITH-RISK；Windows 下两项符号链接集成用例因权限跳过，审批流修正后的外部模型重放受宿主安全策略限制，扩大模块回归中的外部 Harness/Windows 失败不属于本 Feature 回归。
- 2026-09-10：Euphoria_Yan 明确接受 FEAT-004 的 US-001～US-004 与 VER-001～VER-006，包括 0.2.0 对活动 OpenCode 任务恢复失败关闭的边界。
- 2026-09-10：固定修订 `cec393af906aae752246e087a1cf4ed363d31901` 复审结论为 GO-WITH-RISK；代码、范围与行为验收无未解决问题，保留外部模型重放、Windows 符号链接权限、策略级隔离和活动恢复不支持四项已知风险。

## Delivery History

- 2026-09-10：PR !138 合入 `finance`，merge commit 为 `d3f3e0c6b2ad6bb4ddb6795cce57cd3e23d8d97d`；尚未记录版本发布证据。

- 目标版本：`0.2.0`。
