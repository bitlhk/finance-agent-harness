# Wealth Reuse Example

本示例用于简化版 V1 的财富复用验证：先让手机银行的客户自助财富 Agent 消费 Harness Core、`non-ai-banking` Provider 和 Wealth Domain Pack，再增量增加 Employee Agent 的客户经理 Profile。

它用于证明：

- Harness 不只是手机银行内部重构；
- 同一金融事实可以支持客户查询和财富专业诊断；
- Domain Pack 承载专业 Know-how，Core 不承载财富话术；
- `finance-skill-eval` 可以比较无 Skill、旧 Skill 和 Wealth Domain Pack。

## 当前切片

```text
Mobile Banking / OpenJiuwen Java
  -> FinanceHarnessQueryAdapter
  -> wealth.customer-context.query
  -> non-ai-banking Provider
  -> wealth aggregate + risk assessment
  -> wealth-customer-diagnosis Skill
```

Provider 只返回可追溯事实；资产结构、流动性、集中度和关键问题的判断由 Wealth Domain Pack 完成。当前没有 `eligibleProducts`，因此不生成具体产品推荐或确定性配置比例。

V1 不要求同步重构现有 EA TypeScript Runtime。下一步是在同一财富事实合同上增加 `employee-assisted` Pack，用客户经理访前诊断验证跨渠道复用。

## 客户经理侧

`domain-packs/wealth/employee-assisted` 已提炼 EA `wealth-manager-assistant@2.1.5` 的访前诊断主链。示例绑定将语义能力 `wealth.previsit-context.query` 映射到 EA 已有的 `prepare_wealth_previsit_context`，不复制 EA 的客户权限、Twenty CRM 或 Runtime 治理实现。

两侧共享的是 Finance Harness 请求与证据语义以及财富专业方法，不要求共享同一个银行 Provider：

```text
customer-self-service -> non-ai-banking Provider
employee-assisted     -> EA Customer/Portfolio Provider
```

Builder 首个目标仍使用已经在线验证的 `customer-self-service` Java 链路；客户经理目标在 EA Provider Binding 验证后追加。
