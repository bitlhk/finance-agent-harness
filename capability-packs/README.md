# Capability Packs

Capability Pack 是构建期可安装资产，用于同时发布“Agent 能看到什么金融工具”和“这些工具怎样映射到 Finance Harness 调用类型”。

一个 Pack 只包含：

- 稳定 `capabilityId`；
- Runtime 安全的 `toolName`；
- 工具名称、说明和输入 Schema；
- `QUERY` 或 `TASK` 调用类型；
- 最小 Scope 和使用提示。

它不包含 Provider 地址、凭据、客户数据、银行阈值或交易规则。安装时仍须显式绑定 Runtime Integration 和 `FinanceProvider`。

当前样例包括零售银行、客户财富上下文、财富经理访前和保险顾问访前。它们使用同一安装机制，证明新增业务域只需增加 Pack 和 Provider 映射，不需要修改 Harness Core。
