---
schema: speckit-feature-record/v1
feature_id: FEAT-001
title: Base Solution Build Protocol
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/28
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:20:05Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:26:04Z'
  decision_source: conversation
delivery:
  phase: ready-to-merge
  branch: codex/harness-builder-feature-split
  pull_request: ''
  review_target:
    type: local-diff
    url: ''
    revision: sha256:db87458a96ba37c92243cc0d636d8c250b985e25a56e7e000fd01e5db07527be
  merged_commit: ''
dependencies: []
shared_contracts:
  - community/finance/builder/base/docs/architecture/contracts/solution-build-protocol.md
ownership:
  owner: ''
  collaborators: []
release:
  target: 0.1.0
  delivered_in: ''
  evidence: ''
completion:
  completed_by: ''
  completed_at: ''
  evidence_source: ''
architecture_impact:
  level: L2
  update_required: true
  reason: 在 finance_builder_base 中增加跨 Builder 的 Solution 构建流程和公共语义
  affected_files:
  - community/finance/builder/base/docs/architecture/L2-architecture.md
definition_of_done:
  code_complete: true
  tests_complete: true
  evidence_complete: true
  architecture_synchronized: true
  review_passed: true
---

# FEAT-001: Base Solution Build Protocol

## User Value

金融 Builder 维护者可以复用一套稳定的构建阶段、状态和产物语义，使不同 Builder 的构建进度、失败原因、
锁文件和报告能够以相同方式被编排、展示和审计。

## Scope

- 定义 Solution Build Task 的标准阶段、终态、取消和恢复语义；
- 定义 Solution、Asset、Resolved Asset、Lock、Build Report 和阶段事件的公共语义；
- 定义失败所属阶段、责任边界和未执行验证的诚实状态；
- 提供 Runtime 与场景无关的参考流水线组合，不替 Orchestrator 固定业务决策。

## Non-Goals

- OpenJiuwen Java、Maven 或 Finance Harness 的具体装配；
- 电诈、财富、日程等场景字段；
- 强制现有 Builder 在本 Feature 中同步迁移；
- 用 Base 决定下一步调用哪个专业 Agent。

## User Stories

### US-001：统一观察一次构建

作为金融 Builder 使用者，我希望不同 Builder 以一致的阶段和状态呈现一次构建，以便理解当前进度、最终结果和仍未执行的工作。

### US-002：区分资产事实与验证事实

作为交付审阅者，我希望资产锁与构建报告分别说明“实际装配了什么”和“实际验证了什么”，以便复现 Candidate 并识别版本漂移或虚假通过。

### US-003：一致地失败、取消与恢复

作为构建任务操作者，我希望失败、取消和恢复具有一致且可执行的语义，以便修正问题后从安全位置继续，而不重复未知的外部动作。

### US-004：具体 Backend 不侵入 Base

作为 Builder 与 Developer 维护者，我希望通过 Runtime 和场景无关的协议接入具体 Backend，以便复用公共流程，而不把 Maven、OpenJiuwen Java 或业务规则引入 Base。

## Verification

- **VER-001-01** 每个构建任务具有稳定身份，并按“接收输入、校验、解析资产、装配、构建、启动、验证、打包”的语义顺序发布阶段事件；不适用阶段必须显式标记而不是消失。
- **VER-001-02** 任务只产生一个明确终态；完成、失败、阻塞和取消不会被同时声明，终态之后不再接受新的阶段进展。
- **VER-001-03** 对同一冻结输入和同一已解析资产闭包重复执行时，资产锁中的身份、版本、摘要和依赖关系保持一致。
- **VER-001-04** 资产锁只记录本轮实际解析并参与装配的资产；构建报告逐项记录检查结果，未执行检查只能是 `skipped` 或 `blocked`，不得计为通过。
- **VER-001-05** 缺少输入、资产不兼容、Backend 失败或验证失败时，结果包含失败阶段、稳定错误类别、责任边界和可执行下一步，且不会生成成功终态。
- **VER-001-06** 收到取消请求后不再启动新阶段，已完成记录和可审计产物仍可读取；正在执行且无法立即中断的动作被标记为取消处理中。
- **VER-001-07** 恢复任务时只复用已完成且输入摘要一致的安全检查点；外部动作仅在具有相同幂等身份并由 Backend 声明可重放时才允许再次执行。
- **VER-001-08** 同一公共协议可驱动轻量测试 Backend 和一个真实 Backend；Base 的契约测试不安装或导入 Maven、OpenJiuwen Java、Finance Harness 及任何场景资产。
- **VER-001-09** 公共事件、错误和报告不包含凭据值、宿主绝对路径或未脱敏业务原文；允许保留可追溯的相对引用和摘要。

## Dependencies

- 无内部依赖。

## Shared Contracts

Solution Build Task、Asset、Lock、Report 和 Build Event 是候选公共契约；字段与路径须在后续架构评审中确定。

## Decisions

- 2026-09-05：Euphoria_Yan 接受 Feature 范围与建设价值。
- 2026-09-05：Euphoria_Yan 确认标准格式 User Stories 与 Verification 行为。
- 2026-09-05：Euphoria_Yan 接受 FEAT-001 HLD，确认增量 `solution_build` 组件、宿主提供 Build Plan、
  Checkpoint Store 外置、Lock/Report 分离与现有 Base 模型兼容策略。

## Delivery History

- 2026-09-05：Plan、Tasks、实现权限边界与确定性检查已就绪。
- 2026-09-05：T001-T006 实现与架构同步完成；两轮自动评审补齐公共记录安全、状态关联和 Runner 异常边界后，Base 回归 72 passed、2 skipped。
- 2026-09-05：本地复审结论 `GO-WITH-RISK`，实现已就绪并等待提交 PR；真实 Backend 证据由 FEAT-003 交付。
