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

## 客户经理服务准备候选

新增独立的 `wealth-allocation-context` Capability Pack，将
`wealth.allocation-context.query` 映射到 EA 的 `prepare_wealth_allocation_context`。
旧访前 Pack 的工具集合及业务范围保持不变，新能力必须显式安装并由宿主授予
`wealth:allocation:read`，目录声明不会授予权限。

参数仅映射 `customerRef`、`amount`、`horizonMonths`、`channel`、`keyword`、
`productType` 和 `maxProducts`。可选参数没有提供时不补默认业务值；无效值在签发
Token 和发出请求前拒绝。身份字段不能通过工具参数覆盖。

产品结果保留 EA 返回的 `eligibleProducts` 和只用于解释的 `excludedProducts`。
成功候选必须有政策来源、回执和允许候选输出的 Readiness；缺失时不暴露产品。
`no_eligible_products` 保留为空候选结果，不能替换为相邻产品，也不表示完成产品配置。
MCP 查询 `COMPLETED` 仅表示取得上下文，不代表整个访前业务流程已完成。

财富查询返回前重新检查员工绑定及 `authorityRef`，绑定撤销、过期或替换时丢弃结果。
这不能替代宿主对最终响应提交的原子代际校验，也不能撤回已经发出的外部请求。

候选专业方法与 Solution 位于：

- `domain-packs/wealth/service-preparation`
- `examples/solutions/customer-manager-service-preparation/solution.yaml`

当前只验证本地合成 MCP 和 OpenJiuwen 工具装配；员工目录接线、真实授权联调、
经理复核持久化与 CRM 确认写入仍待完成，不得据此启用线上场景。
