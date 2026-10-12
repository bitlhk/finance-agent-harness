---
schema: speckit-feature-record/v1
feature_id: FEAT-005
title: Finance Solution Builder WebUI
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/28
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:20:06Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:42:29Z'
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
- FEAT-004
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
  level: L1
  update_required: true
  reason: 新增参考产品的人机入口和构建过程投影
  affected_files:
  - community/finance/builder/finance-solution-builder/docs/architecture/L1-architecture.md
definition_of_done:
  code_complete: false
  tests_complete: false
  evidence_complete: false
  architecture_synchronized: false
  review_passed: false
---

# FEAT-005: Finance Solution Builder WebUI

## User Value

Solution 设计和演示人员可以在一个宽屏工作台中选择或加载 Solution、理解资产组合、启动构建与 Dryrun，
并实时看到 Builder 的复杂工作，而不需要阅读命令行和 Maven 日志。

## Scope

- Solution/场景选择、资产关系和标准描述视图；
- 构建阶段、实时活动、Candidate 状态和 Dryrun Case 时间线；
- Capability 调用、Provider、耗时、断言、证据和失败归因的脱敏展示；
- 构建历史的归档/删除语义和单一交付包下载；
- 使用统一 Build/Dryrun 事件，不解析 Backend 私有日志。

## Non-Goals

- 窄屏和移动端适配；
- 在浏览器保存真实凭据或直接访问银行系统；
- 图形化编辑任意代码、Prompt 或 Maven 工程；
- 用演示动画替代真实构建和 Dryrun 状态。

## User Stories

### US-005-01：图形化定义并理解 Solution

作为 Solution 设计人员，我希望从场景模板加载或编辑 Solution，并同步查看标准描述和资产关系，以便在构建前明确将组合哪些能力和资产。

### US-005-02：观察 Builder 的真实构建过程

作为会议演示人员，我希望实时看到资产解析、装配、编译、启动和就绪检查的详细活动，以便观众理解 Builder 实际完成了什么工作。

### US-005-03：逐 Case 观察 Dryrun 与 Evaluation

作为质量审阅者，我希望看到每个验证 Case 的当前步骤、Capability 调用、断言与证据摘要，以便判断整体结论来自哪些实际检查。

### US-005-04：理解结果并获得单一交付包

作为交付人员，我希望完成或失败后看到明确结论和下一步，并在通过门禁后下载一个完整交付包，以便继续验收或部署准备。

### US-005-05：管理最近构建及其文件

作为 Builder 使用者，我希望能够归档不再活跃的构建，或在明确确认范围后删除任务及其工作区和交付物，以便管理本地空间且避免误删。

## Verification

- **VER-005-01** 用户从模板加载、表单编辑或文本导入 Solution 后，结构化表单、标准描述和资产关系图表达同一内容；无法无损表达的字段不会被静默删除。
- **VER-005-02** 缺少必要资产、组合不兼容或输入包含真实秘密值时，页面在创建构建任务前指出具体问题；敏感配置只接受环境变量引用。
- **VER-005-03** 在 1920×1080 页面中无需浏览器开发工具即可看到 Solution 摘要、当前阶段、关键资产、最近活动、验证进度和下一步；首版不承诺窄屏适配。
- **VER-005-04** 点击开始构建后，空白等待说明被真实任务视图替代，并明确显示任务身份、当前状态和开始时间。
- **VER-005-05** 页面状态只来自当前任务的统一 Build/Dryrun 事件；刷新或重新打开页面能恢复已持久化的任务状态和活动顺序。
- **VER-005-06** 构建活动区可滚动，展示阶段、执行方、时间、状态和脱敏摘要；“清空”只清除当前浏览器视图，不删除服务端审计记录。
- **VER-005-07** WebUI 不解析 Maven 输出、Backend 私有日志或场景专用文本来推断状态；未知事件可安全显示但不能伪造完成状态。
- **VER-005-08** Dryrun/Evaluation 逐 Case 显示总数、已完成数、当前 Case、步骤、Capability 调用、耗时和断言摘要，Case 终态与总进度一致。
- **VER-005-09** 失败 Case 可展开查看脱敏证据与归因；离线 Dryrun、live smoke 和在线 Evaluation 的等级及未执行状态分别展示。
- **VER-005-10** 完成、阻塞、失败或取消后，页面给出与结构化结果一致的下一步；不存在操作时不显示无效按钮。
- **VER-005-11** 只有满足交付门禁时才提供单一压缩包下载；压缩包仅包含交付 Manifest 允许的文件，并显示文件数量、摘要和生成状态。
- **VER-005-12** 归档操作从最近构建的活跃列表隐藏记录，但保留任务、工作区、交付物和审计信息，并可重新查看。
- **VER-005-13** 删除操作在执行前显示将删除的任务记录、工作区和交付物范围；用户明确确认后只删除该任务拥有的路径，不允许越出任务工作区。
- **VER-005-14** 页面、接口响应、下载文件名和浏览器日志不包含宿主绝对路径、Token、真实客户标识、完整上游响应或模型内部推理。

## Dependencies

- FEAT-001 Base Solution Build Protocol；
- FEAT-004 场景 Dryrun 与 Evaluation 契约。

## Shared Contracts

无已提升契约；WebUI 消费 FEAT-001/004 后续评审通过的事件和结果契约。

## Decisions

- 2026-09-05：Euphoria_Yan 接受 Feature 范围与建设价值。
- 2026-09-05：Euphoria_Yan 确认标准格式 User Stories 与 Verification 行为。

## Delivery History
