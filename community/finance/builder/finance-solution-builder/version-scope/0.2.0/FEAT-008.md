---
schema: speckit-feature-record/v1
feature_id: FEAT-008
title: 自优化过程工作台
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/31
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-08T06:17:47Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-11T01:35:09Z'
  decision_source: conversation
priority: P1
mvp: true
delivery_order: 8
delivery:
  phase: done
  branch: codex/finance-solution-builder-feat-008
  pull_request: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/143
  review_target:
    type: pull-request
    url: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/143
    revision: 669a3f414ef06f01c860635be2c7516ccb5432d6
  merged_commit: e06740a260f375d1b7615a1538b45e8825bc9e91
dependencies:
- FEAT-002
- FEAT-005
- FEAT-006
- FEAT-007
external_prerequisites: []
shared_contracts: []
ownership:
  owner: ''
  collaborators: []
architecture_impact:
  level: L1
  update_required: false
  reason: 投影既有构建评测与优化事件
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

# FEAT-008: 自优化过程工作台

## User Value

Agent 构造者可以按 Storyline 查看本轮 Candidate、逐样本评测进度、Finding、修正对象、资产版本和指标变化，
并在需要时作出审批或停止决定。

## Scope

- 展示 Candidate Descriptor、数据快照、验收规则和当前证据级别。
- 按轮次展示 Evaluation 进度、结构化 Finding、修改原因、实际变更、Lock 差异和指标趋势。
- 显示预算、停止原因、待人工/外部动作和 Harness Change Proposal。
- 保留现有确定性装配 Storyline 和宽屏目标，不另造运行事实。

## Non-Goals

- 在浏览器中直接执行构造 Agent 私有命令或展示模型思维链。
- 窄屏适配、生产多租户运营台或在线训练平台。

## User Stories

### US-001: 看懂自优化全过程

作为 Agent 构造者，我希望沿统一 Storyline 查看本轮 Candidate 从评测、诊断、修正、重建到复测的全过程，从而理解 Builder 每一轮为什么调整、调整了什么以及效果是否提升。

### US-002: 掌握逐样本评测和逐轮变化

作为 Agent 构造者，我希望查看真实的逐样本评测进度、结构化 Finding、修正对象、Lock 差异和指标趋势，从而判断问题定位与修正结果是否有可核验的证据。

### US-003: 处理人工决定和停止结果

作为 Solution 负责人，我希望页面明确展示待批准修改、外部资产阻塞、剩余预算、停止原因和下一步操作，从而在正确的节点批准、拒绝、继续处理或结束任务。

## Verification

- `VER-001`：选择一次真实构建后，页面按构建输入、Candidate、Evaluation、Finding、修正、重建、复测和最终裁决的顺序展示 Storyline；选择节点时显示该节点的真实状态、时间、输入引用、输出引用和结果摘要。
- `VER-002`：页面展示 Candidate Descriptor、数据快照、验收规则和证据级别，并用稳定身份关联 Candidate、Dataset、Finding、实际变更、父 Lock、新 Lock 和指标；引用缺失或身份不一致时显示不可核验，不补造运行事实。
- `VER-003`：Evaluation 执行期间展示已完成样本数、总样本数、当前阶段、累计指标和安全的当前样本摘要；长记录可在受控滚动区域查看，不展示未经授权的 Query 原文、完整 Prompt 或模型内部推理。
- `VER-004`：每轮详情同时展示结构化 Finding、责任归属、选择的修正对象、修正原因、实际文件或配置变更、父子 Lock 差异以及修正前后指标；没有发生的动作明确标记为未执行。
- `VER-005`：页面持续展示轮次预算、修正预算和 Holdout 状态；达到验收规则、预算耗尽、连续无提升、等待人工批准或外部阻塞时，显示稳定停止原因和唯一明确的下一步操作。
- `VER-006`：历史证据回放、Sandbox Candidate Evaluation、授权 live smoke 和生产在线评测使用不同证据级别；未执行、不可比或结果不确定的 Evaluation 不显示为通过，也不参与达标结论。
- `VER-007`：在两个财富 Solution 之间切换时，页面分别展示各自的真实资产、Candidate、评测和优化记录；不同 Solution 或不同构建会话的数据不得串用。
- `VER-008`：审批通过、审批拒绝、外部资产修订提案、验收达标和停止任务均留下可公开的结果摘要与引用；浏览器不直接执行 Construction Agent 私有命令，也不展示凭据、宿主绝对路径或敏感业务原文。

## Dependencies

- FEAT-002、FEAT-005、FEAT-006、FEAT-007。

## Shared Contracts

- 只消费已评审的 Build、Evaluation、Optimization 和 Change Proposal 事件/报告契约。

## Decisions

- 2026-09-08：Euphoria_Yan 接受本 Feature。
- 2026-09-12：Euphoria_Yan 接受宽屏自优化工作台 HLD、模块内只读 Optimization View 接口和代码级验证边界，并同意进入任务实施。
- 2026-09-12：Euphoria_Yan 接受 !143 评审提出的狭窄架构边界扩展：在 Workbench 内部增加 Build 与真实 Optimization Controller 的自动绑定桥；不在普通 Build 中隐式运行 Optimization，也不增加浏览器写接口或第二套 Controller。
- 2026-09-12：复审确认内部桥接方法仍无生产调用者；修订为由受控后端入口读取当前 Build 内带摘要的 Request/Evaluation 引用并调用该桥。前端不渲染此入口，也不能提交模型命令、宿主路径或适配器选择。

## Delivery History

- 2026-09-12：PR !143 合入 `finance`，merge commit 为 `e06740a260f375d1b7615a1538b45e8825bc9e91`；尚未记录版本发布证据。

- 2026-09-12：T001～T004 开始实施，仅执行代码级验证。
- 2026-09-12：Finance Harness #11 与 Builder !142 已合入；T001～T004 完成确定性检查，进入 tasks-ready。
- 2026-09-12：T001～T004 实现完成，进入 PR 评审；未提前记录评审通过、合并或发布事实。
- 2026-09-12：实现提交通过本地自审并发布 GitCode PR !143，等待独立评审与人工合并决定。
- 2026-09-12：针对 !143 首轮评审，补充真实 Optimization 运行完成后的 Build 自动绑定、Build 内证据归一化、发布前投影校验及回归测试；继续保持 `reviewing`。

- 目标版本：`0.2.0`。
