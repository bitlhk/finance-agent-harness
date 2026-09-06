# Provider Adapters

Provider Adapter 把 Harness 的标准能力请求翻译为银行已有 API、MCP 或服务调用，再把业务结果翻译回统一结果。它是银行差异的接入位置，不是第二套银行业务系统。

当前参考实现：

- `non-ai-banking`：账户、面客财富、渠道设置和转账；
- `fraud-mining`：转账反诈风险评估；
- `employee-agent`：员工辅助财富访前和保险客户上下文。

每个可装配 Provider 实现 `DescribedFinanceProvider`，通过 `finance-provider.v1` 声明真正支持的 `capabilityId + operation`。装配器会在启动前校验 Capability Pack 与 Provider 的声明是否一致，避免 Agent 看见一个实际不可调用的工具。

银行后端仍是认证、权限、适当性、风险、幂等和业务状态的权威来源。Adapter 只做协议、字段、错误和证据映射，不复制这些规则。接入步骤见 [Provider 接入规范](../docs/provider-integration-v1.md)。
