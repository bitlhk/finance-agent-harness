# Builder 财富场景交接 V1

## 目标

Builder 使用已经验证的 Finance Harness 资产，在不手工修改财富业务代码的前提下，装配并启动一个 OpenJiuwen Java 客户财富体检 Agent。

首版不是通用 Agent 工厂，只证明一条可重复的受控装配链：

```text
solution-build-input.v1.json
  -> 解析已验证资产引用
  -> 基于 OpenJiuwen Java 参考模板生成 Candidate
  -> 绑定 Finance Harness Provider 和 Wealth Domain Pack
  -> 编译与定向测试
  -> 输出可运行包、锁文件和验证报告
```

## 输入

Canonical Schema：

```text
contracts/json-schema/solution/solution-build-input.v1.schema.json
```

面客侧样例：

```text
examples/solutions/wealth-customer-diagnosis/solution-build-input.v1.json
```

员工辅助侧样例：

```text
examples/solutions/wealth-manager-previsit/solution-build-input.v1.json
```

输入只描述逻辑组合：Runtime 模板、Profile、Domain Pack、Capability Pack、Provider、模型引用和 Eval Pack。不得写入模型密钥、银行凭据、部署拓扑或大段 Runtime 私有配置。

Builder 可从 `catalog/finance-assets.v1.json` 读取当前可选资产。多 Provider Solution 必须使用 `capabilityBindings` 明确每项能力的 Provider，详见 `docs/builder-handoff-multi-provider-v1.md`。

需要向干净 Runtime 发布工具时，通过 `capabilityPackRefs` 引用安装资产。参考输入：

```text
examples/solutions/openjiuwen-retail-banking/solution.yaml
```

`domainPackRefs` 提供专业 SOP，`capabilityPackRefs` 提供 Runtime 可见工具，二者职责不同；Builder 不应从 SOP 文本猜测工具定义。

## Builder 首版职责

1. 校验输入 Schema；
2. 解析 `templateRef` 和 `integrationRef`；
3. 安装 Harness Core 与 `integrations/openjiuwen-java` Maven 依赖；
4. 将 Domain Pack 安装到模板约定的 Skill 目录；
5. 将 Capability Pack 交给 Runtime Integration 注册为原生工具；
6. 生成 Provider 与模型的环境变量模板，不生成真实密钥；
7. 执行编译、资产测试、工具安装/卸载和 Provider Smoke；
8. 输出 Candidate、`solution-lock.json` 和 `build-report.json`。

Builder 可以继续使用 Python。`backend=java` 表示产物为 Java Agent，不要求 Builder 自身改写为 Java。

员工辅助样例的 `templateRef` 提供通用 OpenJiuwen Host Bridge，`integrationRef` 另外安装 `finance-integration-employee-agent-java`，将其中的 `EaRuntimeTokenSupplier` 注入 `EmployeeAgentWealthProvider`。Builder 只生成环境变量占位和 Host Wiring，不签发 Token、不写入 `INTERNAL_API_KEY`，也不把任何凭据写进 Lock 或 Report。真实签名密钥由部署环境的 Secret Manager 注入，Integration 按调用生成一次性 Token。

## 最小输出

`solution-lock.json` 至少记录：

```json
{
  "solutionId": "wealth-customer-diagnosis-demo",
  "resolvedAt": "2026-09-01T00:00:00Z",
  "runtimeTemplate": { "source": "...", "revision": "..." },
  "assets": [
    { "id": "wealth-customer-self-service", "version": "0.1.0", "digest": "sha256:..." },
    { "id": "non-ai-banking-reference", "version": "0.1.0-SNAPSHOT", "digest": "sha256:..." }
  ]
}
```

`build-report.json` 至少记录各阶段是否成功、耗时、测试命令和失败原因。V1 不要求定义完整发布审批状态机。

## 验收

- 从输入文件到可启动 Candidate 控制在 `10-15` 分钟；
- 参考场景不手工修改财富业务代码；
- Harness、OpenJiuwen Integration 和 Wealth Domain Pack 的版本可追溯；
- 编译、财富资产测试和真实模拟银行财富查询通过；
- 模型配置可用时，能够回答“帮我看看当前资产结构和近期需要关注什么”；
- 使用 `finance-skill-eval` 对照有 Pack 与无 Pack 的专业输出。

员工辅助目标额外要求：

- `wealth.previsit-context.query` 映射到 `prepare_wealth_previsit_context`；
- `customerRef` 映射为 `customer_id`；
- 只允许 `EMPLOYEE` Principal；
- Java Integration 与 EA 使用相同的 `runtimeId / agentId / adoptId / audience` 身份合同；
- EA `isError`、JSON-RPC Error 和认证失败均映射为 Canonical Failure；
- 构建产物和报告中不得出现 Runtime Token。

## 分工

Finance Harness 维护合同、Provider、Domain Pack、参考输入和验收案例。Builder 维护输入解析、模板装配、代码生成、锁文件和构建报告。Builder 不修改 Harness Core，也不自行发明财富 SOP。

Builder 已完成面客和员工辅助两个 Candidate 的资产解析、编译、启动、锁文件和构建报告验证。员工辅助资产也已完成真实模型到 EA 的在线调用：模型可自主选择由 Capability Pack 安装的财富访前工具，Java Provider 使用短期签名身份调用 EA，并正确消费 EA 的业务降级结果。当前测试 Adoption 没有客户数据源授权，因此客户专属 `READY` 结果仍需使用银行或 EA 提供的有效授权身份验收；不得在 Builder 中模拟员工权限或客户归属。
