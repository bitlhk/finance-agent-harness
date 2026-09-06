# OpenJiuwen 财富访前真实链路验证

验证日期：2026-09-03

## 验证目标

证明一个未预置财富能力的 OpenJiuwen Java Agent，可以由 Finance Harness 的 Solution 声明式装配专业 SOP 和金融工具，并通过 Employee Agent Provider 执行真实财富访前调用。

```text
用户问题
  -> OpenJiuwen + GLM-5.2
  -> wealth-employee-assisted Domain Pack
  -> wealth-manager-previsit Capability Pack
  -> wealth.previsit-context.query
  -> EmployeeAgentWealthProvider
  -> EA prepare_wealth_previsit_context
  -> Canonical CapabilityResult
  -> 专业访前输出
```

## 验证结果

| 检查项 | 结果 |
|---|---|
| Domain Pack 安装 | 通过，安装 `wealth-employee-assisted@0.1.0` |
| Capability Pack 安装 | 通过，注册 `wealth_previsit_context_query` |
| 模型自主选工具 | 通过，GLM-5.2 根据访前任务调用已安装工具 |
| Java 到 EA 真实调用 | 通过，使用短期签名 Runtime Identity |
| EA 结果映射 | 通过，映射为 Canonical `CapabilityResultV1` |
| 降级语义 | 通过，EA `DEGRADED` 被保留为可使用结果而非传输故障 |
| 专业输出 | 通过，不编造客户事实，给出最多三个关键问题和下一步 |
| 客户专属 READY | 未验收，当前测试 Adoption 没有客户数据源授权 |

真实 Provider Smoke 返回 `COMPLETED`，其业务内容状态为 `DEGRADED`。这两个状态不冲突：前者表示 Harness 与 EA 的调用已经完成，后者表示 EA 明确告知客户事实依赖未就绪，只允许生成通用访前检查清单。

## 修复记录

Java `HttpClient` 默认协议协商在 EA 的 HTTP/1.1 MCP Endpoint 上导致请求达到 30 秒超时。Provider 固定使用 HTTP/1.1 后，同一签名请求可以即时返回；单元测试同时锁定了 HTTP/1.1 行为。

## 可重复执行

先由部署环境注入模型凭据、EA Runtime Identity、签名密钥和授权客户引用，再执行：

```bash
./examples/solutions/openjiuwen-wealth-manager-previsit/run-live-demo.sh
```

验证产物：

```text
examples/solutions/openjiuwen-wealth-manager-previsit/target/solution-lock.json
examples/solutions/openjiuwen-wealth-manager-previsit/target/live-demo/run-report.json
integrations/employee-agent-java/target/live-smoke/employee-agent-wealth-result.json
```

Lock 和 Report 记录 Domain Pack、Skill 摘要、Capability Pack、Integration、Provider、模型和对话结果，不记录模型密钥、签名密钥或 Runtime Token。
