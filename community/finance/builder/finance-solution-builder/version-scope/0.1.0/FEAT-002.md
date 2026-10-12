---
schema: speckit-feature-record/v1
feature_id: FEAT-002
title: Developer Build Contract 与 Backend SPI
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/28
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:20:05Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:29:16Z'
  decision_source: conversation
delivery:
  phase: planning
  branch: ''
  pull_request: ''
  review_target:
    type: ''
    url: ''
    revision: ''
  merged_commit: ''
dependencies:
- FEAT-001
shared_contracts: []
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
  reason: 建立所有 Developer 与不同 Runtime Backend 之间的稳定交付边界
  affected_files:
  - community/finance/builder/base/docs/architecture/L2-architecture.md
definition_of_done:
  code_complete: false
  tests_complete: false
  evidence_complete: false
  architecture_synchronized: false
  review_passed: false
---

# FEAT-002: Developer Build Contract 与 Backend SPI

## User Value

Builder 开发者可以通过实现统一 Backend 契约支持新的 Agent Runtime，而不修改 Build Orchestrator、WebUI
或 Evaluation 主流程；Candidate 的身份、编译、启动和失败语义保持一致。

## Scope

- Developer Backend 的能力声明和组合支持查询；
- 已解析构建上下文、Candidate、Manifest 和环境变量模板的交付语义；
- 装配、编译、启动、健康检查、停止和结构化事件边界；
- Developer 与 Evaluation 的责任分离及结构化诊断回流入口；
- 一个轻量测试 Backend，用于独立验证 SPI，而不复制第二套生产实现。

## Non-Goals

- 定义任何 Runtime 的文件结构或构建命令；
- 由 Developer 执行最终 Evaluation 或降低验收门禁；
- 允许 Backend 重新解析未锁定的外部引用；
- 在本 Feature 中迁移全部现有 Developer。

## User Stories

### US-002-01：构建前确认 Backend 是否适用

作为 Builder 使用者，我希望在产生下载、写入或编译等副作用前确认 Developer Backend 是否支持当前 Runtime 和资产组合，以便尽早发现无法构建的 Solution。

### US-002-02：通过统一上下文生成可追溯 Candidate

作为 Developer Backend 实现者，我希望接收已经校验和解析的构建上下文，并按统一结构返回 Candidate、Manifest 和产物引用，以便接入 Builder 而不依赖 WebUI 或某个场景的私有逻辑。

### US-002-03：一致管理 Candidate 生命周期

作为构建任务操作者，我希望不同 Developer Backend 以一致方式报告装配、编译、启动、健康检查和停止结果，以便 Builder 能可靠展示进展并在失败后完成清理。

### US-002-04：保持 Developer 与 Evaluation 分离

作为质量审阅者，我希望 Developer 只交付可运行 Candidate 和稳定调用信息，并通过结构化诊断接收重建要求，以便 Evaluation 独立判断质量且每次修正都有来源可追溯。

## Verification

- **VER-002-01** Backend 能声明协议版本、支持的 Runtime、语言、资产类型和生命周期动作；Builder 可在执行构建前查询并得到确定的支持或拒绝结论。
- **VER-002-02** 不支持的 Runtime、语言、协议版本或必要资产组合在任何下载、工作区写入和编译前失败，并返回不兼容项及可执行下一步。
- **VER-002-03** Backend 只接收已校验的 Solution 摘要、已解析资产、授权工作区和任务身份；不得自行把未锁定引用替换成其他版本。
- **VER-002-04** 成功装配后统一结果至少包含 Candidate 身份、Developer Manifest、入口信息、环境变量模板和可追溯产物引用，且全部绑定当前构建任务。
- **VER-002-05** Developer Manifest 记录 Backend、Runtime、模板和实际消费资产的版本或摘要；其内容与本轮 Asset Lock 不矛盾。
- **VER-002-06** 装配、编译、启动、健康检查和停止均产生与 FEAT-001 一致的阶段结果；Backend 不支持的可选动作被明确标记为不适用。
- **VER-002-07** 生命周期动作超时或失败时，结果包含稳定阶段和错误类别；已启动进程由同一 Backend 停止或报告明确的待清理状态。
- **VER-002-08** Backend 返回的事件、命令摘要和错误不得包含凭据、完整环境变量、宿主绝对路径或未脱敏业务原文。
- **VER-002-09** Developer 输出可以包含 Candidate 调用入口和健康状态，但不得包含 Evaluation 通过结论；编译成功和健康检查成功不能替代业务验收。
- **VER-002-10** Evaluation 的结构化诊断可绑定原 Candidate、输入和资产锁触发一次新构建；新旧 Candidate 具有不同身份并保留修正来源，Developer 不直接修改 Evaluation 的标准或结论。

## Dependencies

- FEAT-001 Base Solution Build Protocol。

## Shared Contracts

Developer Build Context、Backend Capability、Candidate Manifest 和 Lifecycle Result 是候选公共契约。

## Decisions

- 2026-09-05：Euphoria_Yan 接受 Feature 范围与建设价值。
- 2026-09-05：Euphoria_Yan 确认标准格式 User Stories 与 Verification 行为。

## Delivery History
