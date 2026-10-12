---
schema: speckit-feature-record/v1
feature_id: FEAT-004
title: 场景 Dryrun 与 Evaluation 契约
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/28
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:20:06Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:39:48Z'
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
  reason: 定义跨场景的 Dryrun Pack、执行、证据和 Evaluation 分级语义
  affected_files:
  - community/finance/builder/base/docs/architecture/L2-architecture.md
  - community/finance/builder/finance-solution-builder/docs/architecture/L1-architecture.md
definition_of_done:
  code_complete: false
  tests_complete: false
  evidence_complete: false
  architecture_synchronized: false
  review_passed: false
---

# FEAT-004: 场景 Dryrun 与 Evaluation 契约

## User Value

业务专家和质量审阅者可以用版本化场景检查 Candidate 是否真正按金融能力合同完成业务路径，并清楚区分
离线模拟、授权环境检查和当前 Candidate 在线评测，避免“能启动”等同于“业务可用”。

## Scope

- Dryrun Pack 的场景、前置、输入、动作、约束、禁止动作、证据和清理语义；
- 确定性 Runner、逐 Case 结果、Capability 调用摘要和证据回执；
- offline dryrun、live smoke、online evaluation 三类证据级别；
- 失败归因到 Solution、Backend、Pack、Provider、外部环境或 Eval Pack；
- 结构化进度事件，供 Orchestrator 和 WebUI 消费。

## Non-Goals

- 在 Runner 中硬编码具体场景规则；
- 默认访问真实银行或客户数据；
- 由 LLM 决定确定性金融门禁；
- 自动修改或发布 Pack、Provider 和 Candidate。

## User Stories

### US-004-01：由业务专家定义场景验证

作为业务专家，我希望使用可读、可版本化的 Dryrun Pack 描述业务场景、允许和禁止的动作及验收断言，以便审核验证内容而不修改 Runner 代码。

### US-004-02：对本轮 Candidate 执行可观察验证

作为 Builder 使用者，我希望逐 Case 看到当前 Candidate 实际执行的步骤、Capability 调用和断言结果，以便确认验证不是历史回放或契约替身。

### US-004-03：区分不同等级的验证证据

作为质量审阅者，我希望离线 Dryrun、授权环境 live smoke 和当前 Candidate 在线 Evaluation 分别报告，以便不会把局部或低等级验证解释为完整业务验收。

### US-004-04：把失败反馈给正确责任方

作为 Builder 维护者，我希望失败被归因到 Solution、Developer Backend、Pack、Provider、外部环境或 Evaluation 资产，以便选择正确修正对象，而不是让 Developer 盲目重建。

## Verification

- **VER-004-01** 每个 Dryrun Pack 具有稳定身份和版本，并声明场景、前置条件、输入引用、执行步骤、允许调用、禁止调用、断言、证据要求和清理要求。
- **VER-004-02** Pack Schema、能力引用和必要输入在启动 Candidate 调用前完成校验；无效 Pack 不产生业务动作。
- **VER-004-03** 每个 Run 固定绑定 Candidate 身份、Asset Lock、Pack 版本和输入摘要，报告不能复用其他 Candidate 的历史结果冒充当前执行。
- **VER-004-04** 执行过程中逐 Case 发布开始、步骤、Capability 调用、断言和终态事件；总进度与 Case 终态数量一致。
- **VER-004-05** 每个 Case 结果记录实际调用的能力编号、Provider、耗时、脱敏结果摘要、断言与证据引用；没有调用证据的步骤不能标记为已执行。
- **VER-004-06** Pack 声明的禁止调用一旦发生，本 Case 与依赖该约束的总门禁必然失败，并保留脱敏调用证据。
- **VER-004-07** 同一 Candidate、冻结 Pack 和冻结输入在确定性 Provider fixture 上重复执行时，Case 顺序、确定性断言和最终结论一致；时间戳与耗时不参与一致性比较。
- **VER-004-08** 离线 Dryrun、live smoke 和在线 Evaluation 使用不同等级字段与结论；任一等级未执行时只能标记为 `skipped` 或 `blocked`，不得由其他等级的通过结果补齐。
- **VER-004-09** live smoke 默认不执行；只有显式启用且部署环境提供授权配置时才运行，缺少授权时不得尝试外部连接。
- **VER-004-10** 外部服务不可用、合同不兼容、Candidate 行为错误、Pack 错误和 Evaluation 基础设施错误产生不同归因及可执行下一步。
- **VER-004-11** Runner 不修改 Candidate、Pack、Provider、输入数据或验收标准；优化建议以结构化诊断输出并绑定原 Run 和失败证据。
- **VER-004-12** Case 失败、Run 取消或 Runner 异常时仍执行已声明且安全的清理步骤；无法完成的清理动作进入待处理结果。
- **VER-004-13** 进度事件、报告和证据引用不包含真实客户数据、凭据、完整上游响应或模型内部推理。

## Dependencies

- FEAT-001 Base Solution Build Protocol。

## Shared Contracts

Dryrun Pack、Case Result、Evidence Reference 和 Evaluation Level 是候选公共契约。

## Decisions

- 2026-09-05：Euphoria_Yan 接受 Feature 范围与建设价值。
- 2026-09-05：Euphoria_Yan 确认标准格式 User Stories 与 Verification 行为。

## Delivery History
