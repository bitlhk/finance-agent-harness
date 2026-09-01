# Employee Agent Java Integration

该模块为 Java Agent Runtime 提供 EA 受管 MCP 的动态身份 Token。它位于 Integration 层，不属于 Harness Core。

## 使用

部署环境通过 Secret Manager 注入：

```text
INTERNAL_RUNTIME_TOKEN_SECRET
INTERNAL_RUNTIME_TOKEN_TTL_SECONDS
```

创建与 EA Adoption 一致的身份，并装配 Provider：

```java
var identity = new EmployeeAgentWealthProvider.RuntimeIdentity(
    runtimeId,
    agentId,
    adoptId
);
var tokenSupplier = EaRuntimeTokenSupplier.platformToolsFromEnvironment(identity);
var provider = new EmployeeAgentWealthProvider(new EmployeeAgentWealthProvider.Config(
    eaBaseUri,
    identity,
    tokenSupplier,
    Duration.ofSeconds(20)
));
```

每次 Provider 请求都会生成新的短时 JWT，并绑定：

```text
runtimeId
agentId
adoptId
audience
jti
```

`jti` 只能由 EA 消费一次。不得缓存或重用 Token，不得将签名密钥写入 Builder 输入、Solution Lock、日志或代码仓。

## 边界

- 本模块只实现 EA Runtime 身份签发合同；
- EA 继续负责验签、Adoption 状态校验、身份绑定和防重放；
- Provider 继续负责 Canonical Capability 与 EA MCP 的协议映射；
- Core 不依赖本模块；
- Builder 只装配本模块及环境变量占位，不接触真实密钥。
