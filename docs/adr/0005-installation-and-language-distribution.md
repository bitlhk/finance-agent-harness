# ADR-0005：安装与语言分发策略

状态：`Accepted`

日期：2026-09-01

## Context

银行 Agent 可能使用 Java、Python 或混合技术栈。Java 适合作为首个生产参考实现，但不能成为 Canonical Contract 的所有者，否则 Python Runtime 和开发态 Builder 会被迫依赖 Java 进程。

## Decision

1. `contracts/json-schema` 是唯一语言无关合同源；
2. Java SDK 作为 V1 首个 Maven 安装包；
3. 出现首个 Python Consumer 时，提供消费同一 Schema 和 Fixture 的轻量 Python SDK；
4. 同进程且同语言时使用本地 SDK；
5. Java/Python 混用时通过 Canonical JSON/HTTP Adapter 连接，不要求一方重写为另一种语言；
6. HTTP Adapter 是合同的远程承载方式，不在 V1 建设中央 Harness 服务；
7. Java、Python 和未来其他 SDK 必须通过同一组 Conformance Fixture。

## Target Installation Forms

```text
Canonical Schema Bundle
├── Maven: finance-harness-core
├── Maven: finance-harness-openjiuwen-java
├── PyPI: finance-agent-harness      # 有 Python Consumer 后实现
└── JSON/HTTP Adapter                # 混合语言时实现
```

Java V1 的两个安装单元分别承担不同职责：

- `finance-harness-core`：Canonical Contract 的 Java 类型与 Provider SPI；
- `finance-harness-openjiuwen-java`：OpenJiuwen 宿主调用到 Core Contract 的通用 Bridge。

具体应用的任务对象、Agent ID、Spring 装配和 UI 状态不进入上述安装单元；它们由消费端薄 Host Adapter 负责。

Builder 可以继续使用 Python。它消费 Schema、配置和资产引用，生成或装配 Java/Python Solution；Builder 的实现语言不决定产物语言。

## V1 Boundary

V1 继续完成 Java 手机银行和 `non-ai-banking` 真实链路。Python SDK 与 HTTP Adapter 不作为当前只读查询和确认任务的前置条件，但现有合同不得引入 Java、OpenJiuwen 或手机银行专用类型。
