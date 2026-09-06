# OpenJiuwen 财富经理访前助手

这个示例证明：同一个干净 OpenJiuwen Java Agent 可以通过 Solution 同时安装两类独立资产：

- `wealth-employee-assisted` Domain Pack：提供财富经理访前诊断 SOP；
- `wealth-manager-previsit` Capability Pack：发布 `wealth_previsit_context_query` 原生工具。

工具通过 `employee-agent-reference` Provider 调用 EA 现有的 `prepare_wealth_previsit_context`。EA 继续负责员工身份、客户归属、客户画像、持仓、风险测评和制度装配；Harness 不复制这些业务实现。

## 本地真实联调

先配置模型、EA 身份和授权测试客户：

```bash
export FINANCE_AGENT_MODEL_API_KEY='<model-api-key>'
export FINANCE_AGENT_MODEL_BASE_URL='<openai-compatible-base-url>'
export FINANCE_AGENT_MODEL='glm-5.2'

export EA_BASE_URL='http://127.0.0.1:5180'
export EA_RUNTIME_ID='finance-harness-wealth-demo'
export EA_AGENT_ID='<active-ea-agent-id>'
export EA_ADOPT_ID='<active-ea-adoption-id>'
export EA_CUSTOMER_REF='<authorized-customer-ref>'
export INTERNAL_RUNTIME_TOKEN_SECRET='<same-secret-as-ea>'
```

执行：

```bash
./examples/solutions/openjiuwen-wealth-manager-previsit/run-live-demo.sh
```

输出包括：

```text
target/solution-lock.json
target/live-demo/run-report.json
```

Lock 同时记录 Domain Pack、Skill 内容摘要、Capability Pack、OpenJiuwen Integration 和 Provider；运行报告不保存模型密钥、签名密钥或 Runtime Token。
