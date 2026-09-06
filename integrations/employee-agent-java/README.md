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
var identity = new EmployeeAgentRuntimeIdentity(
    runtimeId,
    agentId,
    adoptId
);
var tokenSupplier = EaRuntimeTokenSupplier.platformToolsFromEnvironment(identity);
var provider = new EmployeeAgentWealthProvider(new EmployeeAgentWealthProvider.Config(
    eaBaseUri,
    identity,
    tokenSupplier,
    Duration.ofSeconds(20),
    verifiedAdoptionOwnerSupplier
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

`verifiedAdoptionOwnerSupplier` 必须读取可信宿主验证的 `EmployeeAgentIdentityBinding`，
而不是根据 `adoptId` 拼接员工 ID。保险使用相同边界及
`EaRuntimeTokenSupplier.customerContextFromEnvironment(identity)`。

### 显式真实烟测

默认跳过，不由普通 CI 启用。财富需 `EMPLOYEE_AGENT_LIVE=true`、`EA_PRINCIPAL_ID` 与
`EA_IDENTITY_BINDING_FILE`；保险需 `EMPLOYEE_AGENT_INSURANCE_LIVE=true`、
`EA_INSURANCE_PRINCIPAL_ID` 与 `EA_INSURANCE_IDENTITY_BINDING_FILE`。
其他 Runtime、客户与密钥配置仍见对应 Smoke 测试。
绑定文件为可信宿主核实后导出的 `EmployeeAgentIdentityBinding` JSON，受宿主文件权限保护，
有效期不应自动续期；Smoke 每次读取并验证，不能自行生成一份自证身份的 JSON。
本仓文件读取只提供人工烟测接线点，不证明导出者权威或完成线上授权验收。
文件、结果可能含客户标识，仅在受控工作目录保留，不能提交仓库。

- 本模块只实现 EA Runtime 身份签发合同；
- EA 继续负责验签、Adoption 状态校验、身份绑定和防重放；
- Provider 继续负责 Canonical Capability 与 EA MCP 的协议映射；
- Core 不依赖本模块；
- Builder 只装配本模块及环境变量占位，不接触真实密钥。
