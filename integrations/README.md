# Runtime Integrations

Runtime Integration 把 Finance Agent Harness 的金融语义映射到具体 Agent Runtime 生命周期。

V1：

```text
runtimes/openjiuwen/
├── runtime-contract/
├── enterprise-remote/
│   ├── ea-ts-host-adapter/
│   └── python-runtime-bridge/
└── java-embedded/
    └── mobile-java-host-adapter/
```

Integration 负责事件和类型翻译，不实现第二套 Finance Policy。
