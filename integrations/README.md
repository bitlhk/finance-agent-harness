# Runtime Integrations

Runtime Integration 负责把 Finance Harness 接入 Agent Runtime。

V1 只实现：

```text
openjiuwen-java/
├── capability-request-mapper
├── capability-result-mapper
└── evidence-mapper
```

Integration 只做生命周期与类型转换，不实现银行 Policy。Employee Agent 的 TypeScript Integration 在 Java 链路稳定后增加。
