# Provider Adapters

Provider Adapter 负责把 Harness 标准请求连接到银行业务系统。

V1 只实现 `non-ai-banking`：

```text
non-ai-banking/
├── query
├── session-and-error-mapping
├── task-preflight-confirm-submit
└── evidence-mapping
```

银行后端是认证、权限、风险、幂等和业务状态的权威来源。Adapter 不复制这些规则。
