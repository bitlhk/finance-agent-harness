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

## 客户服务准备 Host 接线候选

`EmployeeAgentWealthSession` 复用 `EmployeeAgentWealthFactoryV01`、
`ManagedProviderFactoriesV01`、`BoundFinanceProvider` 和现有 EA MCP Provider。
它不是登录服务、授权服务器或新的任务引擎，不自动安装能力，不自动创建 EA 岗位。

构造时由可信 Host 提供当前登录 Owner、会话、Runtime 身份、实际安装能力集合、
`Authority` 供应器和逐次 Token 供应器。`Authority` 组合既有员工归属绑定及实授 Grant，
由 EA／银行授权来源核实；浏览器、模型、能力包和邮箱均不能生成该对象。
供应器应读取同一版本的当前授权快照，不在每次读取时自造或续期授权。

查询只支持显式安装的访前上下文和配置候选上下文；`customerRef` 由 Host 单独传入，
且必须属于实授客户集合。身份、认证级别、Scope 和会话不接受工具参数覆盖。
未授权、未安装、过期、Owner／岗位／会话不一致在源数据读取前拒绝；返回后再次核对
完整快照，包括客户集合和 Scope，变化时不暴露事实。关闭后不可恢复或再次读取。

没有持锁网络等待或自动重试。已派发的外部查询不能因此撤销。
该适配不代替运行 Controller 的最终响应代际 CAS；关闭与最后一次校验之后的响应提交竞争
仍由 Host 控制，不能仅凭此类宣称请求生命周期贯通。

本地合成 HTTP 测试：`EmployeeAgentWealthSessionTest`。
当前尚未接入线上 Linggan→EA 授权快照解析、受管 Java 页面或经理复核保存，
不能把测试通过称为真实账号／CRM 已打通。旧六卡和生产环境不受本候选影响。
