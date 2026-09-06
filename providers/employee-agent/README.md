# Employee Agent 财富与保险 Provider

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
    new EmployeeAgentRuntimeIdentity(runtimeId, agentId, adoptId),
    context -> runtimeTokenBroker.issue(context),
    Duration.ofSeconds(20),
    verifiedAdoptionOwnerSupplier
);
```

Java Runtime 可以直接使用：

```text
integrations/employee-agent-java
```

其中 `EaRuntimeTokenSupplier` 已按 EA 现有签名合同实现每请求动态签发。不得把 `INTERNAL_API_KEY`、签名密钥或固定 Token 写入 Solution、Domain Pack、构建输入或代码仓。

## 边界

财富和保险均要求 `Supplier<EmployeeAgentIdentityBinding>`。供应器来自可信宿主解析的
adoption-owner 记录，包含 `principalId`、完整 `runtime/agent/adoption`、`authorityRef` 和
`expiresAt`；每次调用重读，不能从模型参数、员工标签或 Token 存在推导。
缺失、撤销、过期或身份不匹配在取 Token 和读取 EA 之前拒绝。
旧四参数 Config 保留源码兼容，但不再允许源数据读取；必须补第五个可信供应器。

`EmployeeAgentInsuranceProvider` 通过 `get_customer_profile` 读取保险客户投影，验证
请求 `customerRef == Context.subjectRef`，并要求返回 `subjectId` 一致才暴露结果。
EA 拒绝或异常的原始文字不直接回流，改为稳定的错误码和修复动作。
这不代替 EA 的客户关系、岗位用途或字段权限检查；外层 `BoundFinanceProvider` 实授校验仍需配置。

- 只接受 `EMPLOYEE` Principal；
- 只实现只读访前上下文查询；
- 不允许客户侧 Profile 调用；
- 不将 EA 内部凭据写入结果或 Evidence；
- 不把该参考 Provider 表述为银行生产 CRM Provider。
