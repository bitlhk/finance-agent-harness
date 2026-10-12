---
schema: speckit-feature-record/v1
feature_id: FEAT-006
title: 手机银行反诈端到端参考场景
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/28
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:20:07Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:46:32Z'
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
- FEAT-003
- FEAT-004
- FEAT-005
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
  reason: 以首个多 Provider 场景验证架构边界和端到端能力映射
  affected_files:
  - community/finance/builder/finance-solution-builder/docs/architecture/L1-architecture.md
definition_of_done:
  code_complete: false
  tests_complete: false
  evidence_complete: false
  architecture_synchronized: false
  review_passed: false
---

# FEAT-006: 手机银行反诈端到端参考场景

## User Value

业务评审者可以通过一个真实、可重复的手机银行转账反诈 Demo，看到标准 OpenJiuwen Java Agent 如何通过
Finance Harness 组合风险查询与转账能力，并以场景证据证明风险阻断有效。

## Scope

- 版本化手机银行反诈 Solution 输入和资产闭包；
- `transfer-anti-fraud` Pack、`fraud.transfer-risk.assess` 与转账 Task 能力绑定；
- ALLOW、BLOCK、风险服务失败和转账续办的离线 Dryrun Pack；
- 可选、显式授权的 fraud-mining live smoke；
- WebUI 端到端演示、交付包和可追溯报告。

## Non-Goals

- 复制反诈服务器源码、模型、数据库或历史数据；
- 用大模型直接决定是否阻断；
- 执行真实生产转账或访问真实客户；
- 把反诈规则写入 Base、Builder 或 Harness Core；
- 以一次 live smoke 代替完整 Dryrun 与 Candidate 验证。

## User Stories

### US-006-01：装配手机银行反诈 Candidate

作为手机银行 Solution 设计人员，我希望从版本化 Solution 一次构建出同时具备风险查询和转账能力的 OpenJiuwen Java Candidate，以便演示完整的转账前风险控制流程。

### US-006-02：验证风险判断不会被绕过

作为风控专家，我希望 ALLOW、BLOCK 和风险服务异常路径均有明确调用证据和禁止动作断言，以便确认风险结论被正确执行，异常不会导致错误放行。

### US-006-03：验证转账确认与续办安全

作为支付业务审阅者，我希望 ALLOW 后的转账仍遵循确认、幂等、处理中、结果未知和状态查询合同，以便风险放行不会绕过交易安全边界或产生重复动作。

### US-006-04：通过 WebUI 演示并交付参考 Solution

作为会议演示和交付人员，我希望从 WebUI 启动真实构建、逐 Case 观察 Dryrun，并下载可追溯交付包，以便讲清场景 Harness Builder 如何生成可启动、可验证的标准 Demo。

## Verification

- **VER-006-01** 版本化 Solution 明确引用 OpenJiuwen Java Runtime、Finance Harness、Integration、反诈 Domain Pack、风险 Query Provider、转账 Task Provider 和反诈 Eval Pack，缺少任一必要资产时不开始装配。
- **VER-006-02** 构建完成的 Candidate Capability Catalog 同时包含 `fraud.transfer-risk.assess` 风险查询能力和所选转账 Task 能力，并能通过统一 Host 调用各自 Provider。
- **VER-006-03** 冻结合成安全对象先调用风险查询并得到 `ALLOW`，随后只进入转账预检或待确认状态；用户确认前不得提交转账。
- **VER-006-04** 冻结合成风险对象得到 `BLOCK` 后，本轮不得调用转账创建、确认、提交或其他产生交易副作用的能力。
- **VER-006-05** 风险 Provider 超时、失败、返回未知结果或响应不符合合同时必须失败关闭，报告中不得出现 `ALLOW` 或成功转账结论。
- **VER-006-06** 模型生成的文本、建议或置信度不能把 `BLOCK`、风险未知或风险服务异常覆盖为 `ALLOW`。
- **VER-006-07** ALLOW 路径的转账确认必须使用本轮待确认句柄和稳定幂等身份；缺少确认、句柄不匹配或身份变化时不得执行提交。
- **VER-006-08** 转账返回处理中或结果未知时，通过状态查询续办；重复确认、重试或刷新页面不会产生第二笔业务动作。
- **VER-006-09** 每个 Dryrun Case 均绑定本轮 Candidate、Asset Lock 和实际 Provider 调用证据，不使用历史结果或契约替身完成门禁。
- **VER-006-10** 同一 Candidate、冻结 Pack 和合成输入重复执行离线 Dryrun 时，确定性断言与最终结论一致。
- **VER-006-11** WebUI 从提交 Solution 到交付完整展示资产解析、装配、编译、启动以及各反诈 Case 的实时进展、结论和下一步。
- **VER-006-12** 最终单一交付包可追溯 Runtime、Harness、Integration、两个 Provider、Domain Pack、Eval Pack 和当前 Candidate 的固定版本或摘要。
- **VER-006-13** 未配置 live smoke 时，离线 Dryrun 可以独立完成且 live 状态明确为未执行；显式启用授权环境时，其结果单独记录，不替代离线或在线 Evaluation。
- **VER-006-14** 仓库、页面、日志、报告与交付包不包含参考服务器地址、私钥、Token、真实账号、客户身份、原始风险数据、模型文件或完整上游响应。

## Dependencies

- FEAT-003 OpenJiuwen Java + Finance Harness Backend；
- FEAT-004 场景 Dryrun 与 Evaluation 契约；
- FEAT-005 Finance Solution Builder WebUI；
- 外部：Finance Harness 发布反诈 Pack、Provider 与能力合同，手机银行提供可测试的转账 Provider 合同。

## Shared Contracts

无新增公共契约；场景只消费 Harness 和前置 Feature 已评审的合同。

## Decisions

- 2026-09-05：Euphoria_Yan 接受 Feature 范围与建设价值。
- 2026-09-05：Euphoria_Yan 确认标准格式 User Stories 与 Verification 行为。

## Delivery History
