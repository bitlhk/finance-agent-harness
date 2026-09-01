# Runtime Integrations

Runtime Integration 负责把 Finance Harness 接入 Agent Runtime。

V1 当前包含：

```text
OpenJiuwen Java Host Bridge（由手机银行参考实现提供）
├── capability-request-mapper
├── capability-result-mapper
└── evidence-mapper

employee-agent-java/
└── EA 受管 MCP 动态 Runtime Token
```

Integration 只做生命周期、类型和宿主身份协议转换，不实现银行 Policy。具体应用、Runtime 和银行 Provider 均不得反向进入 Core。
