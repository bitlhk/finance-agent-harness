# Finance Agent Harness

Finance Agent Harness 是面向金融 Agent 的行业能力与工程底座。

它不替代 Agent Runtime，也不是某个具体金融应用。它通过统一的金融合同、Profile、领域能力包、Runtime Integration、Provider Adapter、证据和评测机制，帮助岗位智能体、手机银行智能体及其他金融应用更快构建、验证、集成和持续改进可复用的 Agent 业务组件。

> Core 定义金融共性，Profile 表达主体关系，Integration 适配 Agent Runtime，Provider Adapter 适配银行系统。

## 当前状态

`Architecture Baseline Candidate / V1 Walking Skeleton Ready`

首版聚焦：

- OpenJiuwen Runtime；
- 岗位智能体与手机银行两个真实应用；
- `employee-assisted` 与 `customer-self-service` 两种 Profile；
- 财富管理领域；
- Principal、Context、Action、Policy、Approval、Evidence 六类核心合同；
- TypeScript 参考实现、Java SDK 和 Python 薄桥接；
- 跨应用、跨 Profile、跨语言的一致性验证。

## 文档入口

- [总体架构 V1](docs/finance-agent-harness-architecture-v1.md)
- [V1 范围与路线](docs/v1-scope-and-roadmap.md)
- [跨渠道财富演示故事](docs/v1-demo-story.md)
- [职责与评审规则](docs/ownership-and-review.md)
- [ADR-0001：架构基线](docs/adr/0001-architecture-baseline.md)
- [ADR-0002：合同与语言策略](docs/adr/0002-contract-and-language-strategy.md)
- [ADR-0003：Agent 业务组件生命周期与环境要求](docs/adr/0003-agent-solution-lifecycle.md)

## 仓库结构

```text
finance-agent-harness/
├── contracts/          # 语言无关的 JSON Schema / OpenAPI 合同
├── packages/           # TypeScript 参考实现与 Java SDK
├── profiles/           # 主体关系和默认控制语义
├── domain-packs/       # 财富、保险、投研等金融专业资产
├── integrations/       # Agent Runtime 北向适配
├── providers/          # 银行系统南向适配合同和参考实现
├── conformance/        # 跨语言、跨 Runtime、跨 Provider 一致性验证
├── engineering/        # Builder 等工程平面能力，V1.2 开始实现
├── examples/           # 可演示的参考场景
└── docs/               # 架构、路线、ADR 和协作说明
```

## 非目标

- 不重做 Agent Loop、Planning、Session、Memory 或 Multi-Agent；
- 不把 Finance Harness 建成另一个 Agent Runtime；
- 不重做通用 RAG、向量库、Prompt Guard 或模型网关；
- 不让 Builder 在生产请求热路径中运行；
- 不在 V1 引入自动改 Skill、自动发布或自主 Agent 工厂。

## 相关项目

- `employee-agent`：员工辅助型岗位智能体参考应用；
- `mobile-banking-3.0`：客户自服务型手机银行参考应用；
- `finance-skill-eval`：独立的金融 Skill 评测组件，通过 Eval Pack、Runtime Adapter 和 Evidence 接入本项目。

本仓库当前为私有孵化项目。任何公共合同进入稳定状态前，必须由至少两个消费者通过 Conformance 验证。
