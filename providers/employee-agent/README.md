# Employee Agent 财富 Provider

这是一个宿主兼容 Provider，用于把 Harness 语义能力：

```text
wealth.previsit-context.query
```

映射到 Employee Agent 已有的 MCP 工具：

```text
prepare_wealth_previsit_context
```

它只做协议转换，不复制 EA 的客户归属、岗位权限、客户画像、持仓查询、制度装配或财富 SOP。

## 输入与结果

Canonical 输入参数：

```json
{
  "customerRef": "C005"
}
```

Provider 将其转换为 MCP 参数 `customer_id`，并把 `EA_WEALTH_PREVISIT_CONTEXT` 结果转换为 `CapabilityResultV1`。EA 的完整 Context Receipt 仍由 EA 持有，Harness 只保留 `receiptId`、`taskId`、指纹、时间和 Readiness 摘要。

## Runtime Token

EA 的生产 MCP 请求使用短时、一次性签名 Runtime Token。Provider 因此接收 `RuntimeTokenSupplier`，并在每次调用前取一个新 Token：

```java
new EmployeeAgentWealthProvider.Config(
    eaBaseUri,
    new RuntimeIdentity(runtimeId, agentId, adoptId),
    context -> runtimeTokenBroker.issue(context),
    Duration.ofSeconds(20)
);
```

不得把 `INTERNAL_API_KEY`、签名密钥或固定 Token 写入 Solution、Domain Pack、构建输入或代码仓。Token 的签发由 EA Runtime Integration 或受信任宿主负责。

## 边界

- 只接受 `EMPLOYEE` Principal；
- 只实现只读访前上下文查询；
- 不允许客户侧 Profile 调用；
- 不将 EA 内部凭据写入结果或 Evidence；
- 不把该参考 Provider 表述为银行生产 CRM Provider。
