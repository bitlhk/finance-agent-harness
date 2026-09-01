# 职责与架构评审规则

## 1. 评审目的

本轮不是追求全员共识或把文档润色到完美，而是完成一次短周期 Architecture Alignment：

- 是否存在阻止开工的架构问题；
- 每位负责人是否认可自己负责的合同和边界；
- 是否接受第一阶段交付；
- 是否有人提出明确、更小且可验证的替代方案。

## 2. 评审范围

建议只邀请以下 5 个接口人：

- Architecture / Contract Owner；
- Mobile Owner；
- Knowledge Owner；
- Security Owner；
- Builder Owner。

必要时增加 OpenJiuwen Runtime 接口人。不做大范围群体评审。

评审窗口：2 个工作日。

## 3. 评审方式

所有意见直接写在 Architecture PR：

- 小修改使用 GitHub Suggestion；
- 架构替代方案单独提交 PR 或 ADR；
- 不直接修改主文档；
- 不把意见分散到聊天、邮件和多个副本文档中。

意见标签：

- `[BLOCKER]`：不解决不能开工或合入；
- `[CONCERN]`：需要明确取舍、边界或风险；
- `[SUGGESTION]`：不影响开工，可进入后续优化；
- `[QUESTION]`：需要补充解释。

## 4. 每位评审人只需回答三个问题

1. 是否存在 Blocker？
2. 与自己相关的 Contract、边界和 Owner 是否合理？
3. 是否接受自己的首个交付及时间点？

## 5. 合入条件

- 所有 `[BLOCKER]` 已关闭或转为明确 ADR；
- Knowledge、Security、Mobile、Builder Owner 确认边界；
- 没有因为评审扩大 V1 Scope；
- V1 Demo Story 不变；
- Walking Skeleton 可以继续并行推进。

合入后状态：`Architecture Baseline / Approved for Implementation`。

## 6. Ownership

| Owner | 核心所有权 | 首个交付 |
|---|---|---|
| Architecture / Contract | 总架构、Schema、Profile、ADR、兼容和 V1 验收 | Contract Skeleton + Review Closure |
| Context & Knowledge | Context / Knowledge Contract、Authority、Freshness、Provenance、Provider | 5 组 Policy / Knowledge Fixture |
| Action & Security | Action、Decision、Approval、Evidence、安全边界 | 6 组 Approval / Tamper / Replay Fixture |
| Runtime Integration | 生命周期合同、RuntimeCapabilityProfile、TS/Java 语义一致 | OpenJiuwen Lifecycle Mapping |
| EA Adapter | EA TS Host、Shadow Evidence、企业版 Python Bridge 协同 | EA Context Shadow |
| Mobile Adapter | Java Host、Invocation Context、确认/恢复/幂等 | Mobile Action Mapping |
| Builder / Engineering | Contract 消费验证，V1.2 Manifest / Lock | AssetRef Gap Report |
| Domain & Eval | Wealth Pack、Golden Case、Eval Pack、专家 Rubric | Cross-channel Wealth Eval |
| CI / Conformance | Schema Bundle、测试向量、跨语言一致性 | TS/Java Fixture Runner |

人数不足时可以一人兼任多个 Owner，但每个目录和合同只能有一个最终 Owner。

## 7. 公共能力准入

声称属于 Finance Agent Harness 的公共能力，至少满足以下之一：

- 被 EA 与手机银行两个真实消费者验证；
- 通过两个 Consumer 级 Conformance Adapter；
- 有 ADR 说明为什么暂时只有一个消费者，以及退出条件。

从现有应用抽取的代码默认仍是候选，不能因为“通用看起来不错”就进入 Core。

## 8. PR 最小要求

涉及合同或行为变更的 PR 应同时包含：

- Contract / Schema；
- 正向 Fixture；
- 至少一个负向 Fixture；
- 参考实现或 Adapter；
- Conformance 结果；
- 兼容性说明；
- 必要的 ADR 或文档更新。

## 9. 决策权

- Contract 由 Architecture Owner 与对应领域 Owner 共同批准；
- Profile 由 Architecture Owner 批准，应用 Owner 提供验证；
- Runtime Lifecycle 由 Integration Owner 批准；
- 银行私有 Policy 和 Provider 配置由具体项目拥有；
- 应用 UI、用户流程和 Host Wiring 由应用团队拥有；
- Builder 不得自行修改稳定 Core Contract，需通过本仓 PR。
