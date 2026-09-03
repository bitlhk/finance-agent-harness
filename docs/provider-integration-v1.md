# Provider 接入规范 V1

## 目的

Provider 是 Finance Harness 面向银行系统的标准接入口。Harness 定义 Agent 需要的金融能力语义，银行实现这些语义与本行系统之间的映射。

```text
Agent Runtime
  -> Capability Pack（Agent 能看见什么工具）
  -> Finance Contract（统一请求与结果）
  -> Provider Adapter（本行怎么实现）
  -> 银行 CRM / 产品 / 账户 / 风控 / MCP / API
```

三者职责不同：

- **Domain Pack**：告诉 Agent 业务上应该怎么判断，例如保险访前如何识别保障矛盾；
- **Capability Pack**：定义 Agent 可以调用的标准能力、输入和用途，例如 `insurance.customer-context.query`；
- **Provider**：把这个能力接到某家银行已有系统，并返回统一状态和来源证据。

## 银行需要实现什么

Java V1 实现 `DescribedFinanceProvider`：

```java
public final class BankCustomerProvider implements DescribedFinanceProvider {
    public ProviderDescriptorV1 descriptor() { ... }
    public CapabilityResultV1 query(CapabilityRequestV1 request) { ... }
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { ... }
}
```

`descriptor()` 只声明本 Adapter 支持的标准能力和调用类型：

```json
{
  "schemaVersion": "finance-provider.v1",
  "providerId": "bank-customer-provider",
  "version": "1.0.0",
  "capabilities": [
    {
      "capabilityId": "insurance.customer-context.query",
      "operation": "QUERY"
    }
  ]
}
```

Provider 必须：

1. 保留 `requestId` 和 `traceId`；
2. 将银行业务结果转换为 `CapabilityResultV1`，失败时返回结构化错误而不是抛出未处理异常；
3. 对等待、确认和结果未知返回对应状态与 Provider 自己的任务引用；
4. 不把凭据、完整上游报文或不应暴露的客户字段写入返回结果；
5. 不在 Adapter 中复制本应由银行系统判定的授权、风控、产品或交易规则。

## 装配与验证

Builder 或应用选择：

```text
Domain Pack + Capability Pack + Provider Ref + Runtime Integration
```

装配器在注册工具前核对 Provider ID、版本及能力覆盖。Provider 还应使用 `ProviderConformanceKit` 跑一组本行 Fixture，至少覆盖成功、业务拒绝、暂时失败，以及有状态任务适用时的确认和查证。

Conformance 通过只证明接口可被 Harness 稳定消费；本行规则、数据映射和专业结果仍需银行测试与领域评测验证。

## 当前样例

- 面客零售：`non-ai-banking-reference`；
- 转账反诈：`fraud-mining-reference`；
- 财富经理访前：`employee-agent-reference`；
- 保险顾问访前：`employee-agent-insurance-reference`。

新增银行时无需修改 Core，只需实现相同标准能力的 Provider，并在 Solution 中替换 Provider 引用。
