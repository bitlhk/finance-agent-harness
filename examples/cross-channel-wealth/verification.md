# 跨应用财富验证记录

验证日期：2026-09-01

## 面客侧

```text
OpenJiuwen Java
  -> FinanceHarnessQueryAdapter
  -> wealth.customer-context.query
  -> non-ai-banking Provider
  -> 财富聚合 + 风险测评
  -> wealth-customer-diagnosis
```

结果：

- Harness Core 与 Provider 单元测试通过；
- 手机银行 OpenJiuwen 财富桥在线调用 `non-ai-banking:8086` 通过；
- 财富 Skill 和 Tool 资产装配测试通过；
- 当前验证到真实业务事实返回，完整模型对话仍需要部署环境的模型配置。

## 员工辅助侧

```text
Employee Agent
  -> prepare_wealth_previsit_context
  -> EA 客户画像、持仓与制度 Provider
  -> wealth-manager-assistant@2.1.5
  -> employee-assisted Wealth Pack
```

结果：

- EA 财富访前上下文与 Skill 契约定向测试 `33/33` 通过；
- `finance-skill-eval` 财富管理回放 `4/4` 完成；
- 有专业 Skill 的右侧在两项财富任务中均胜出；
- Harness 示例绑定只映射语义能力，不复制 EA 的 Twenty CRM、客户权限或 Runtime 逻辑。

## Builder 就绪

- `solution-build-input.v1.schema.json` 通过 JSON Schema 2020-12 校验；
- 两个 Wealth Domain Pack 和 EA Host Binding 均可被 YAML 解析；
- 首个 Builder 输入固定使用已在线验证的 Java 面客链路；
- 客户经理构建目标等待对应 Provider Binding 后追加，不在 Builder 中模拟客户归属。

## 结论

当前证据证明了同一 Harness 合同与财富专业资产能够服务面客和员工辅助两种场景，并允许不同宿主保留各自的 Provider。它不宣称两个应用共享同一个银行接口，也不把受控回放表述为生产模型运行认证。
