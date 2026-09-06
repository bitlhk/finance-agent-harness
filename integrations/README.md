# Runtime Integrations

Runtime Integration 负责把 Finance Harness 接入 Agent Runtime。

V1 当前包含：

```text
openjiuwen-java/
├── OpenJiuwenInvocationV1
├── OpenJiuwenFinanceBridge
└── OpenJiuwenExecutionV1

employee-agent-java/
└── EA 受管 MCP 动态 Runtime Token
```

`openjiuwen-java` 是可独立安装的通用 Bridge，负责请求、结果、确认续办和 Evidence 映射。手机银行等消费者只保留自己的 Host 类型转换与运行装配。

Integration 只做生命周期、类型和宿主身份协议转换，不实现银行 Policy。具体应用和银行 Provider 均不得反向进入 Core，应用专用 Runtime 类型不得进入通用 Integration。
