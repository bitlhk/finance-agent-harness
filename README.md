# Finance Agent Harness

Finance Agent Harness 是金融 Agent 的能力接入与验证底座。

它不替代 Agent Runtime，也不重做银行业务系统。V1 只解决一个具体问题：让 Agent 通过统一合同调用金融查询和办理能力，并留下可验证的结果。

> Integration 接 Agent Runtime，Provider Adapter 接银行系统。

## 当前状态

`Simplified V1 In Progress · D3-D4 Query Complete`

V1 按两个连续阶段推进：

1. 手机银行作为首个 Java 消费者，接入 `non-ai-banking` 参考业务环境；
2. 从 Employee Agent 提取财富 Skill、SOP 和评测案例，验证同一 Harness 能支撑财富专业能力。

## V1 架构

```text
Mobile Banking Application
          ↓
OpenJiuwen Runtime
          ↓
OpenJiuwen Integration
          ↓
Finance Harness Java Core
          ↓
non-ai-banking Provider Adapter
          ↓
non-ai-banking Simulator
```

Java Core 首版只定义四个公共对象：

- `FinanceContextV1`：谁在什么渠道、为谁发起请求；
- `CapabilityRequestV1`：需要查询或办理什么；
- `CapabilityResultV1`：银行系统返回的业务状态和结果；
- `EvidenceReceiptV1`：本次请求用了什么能力、来源和结果。

Provider SPI 只提供两个入口：

- `query`：无副作用金融查询；
- `executeTask`：消费银行后端已有的预检、确认、提交和状态协议。

## 首版证明

1. 手机银行通过 Harness 完成一条真实只读查询；
2. 手机银行通过 Harness 完成一条需要确认的模拟办理；
3. Wealth Domain Pack 使用同一 Context、Provider 和 Evidence 形成专业财富建议。

第三项优先验证专业资产复用，不要求 V1 同时重构 Employee Agent 的 TypeScript Runtime。现有 EA 的直接 Integration 在 Java 骨架稳定后增量接入。

## 文档入口

- [简化版总体架构](docs/finance-agent-harness-architecture-v1.md)
- [V1 范围与快速路线](docs/v1-scope-and-roadmap.md)
- [V1 演示故事](docs/v1-demo-story.md)
- [手机银行 V1 映射](docs/mobile-banking-v1-mapping.md)
- [职责与评审规则](docs/ownership-and-review.md)
- [ADR-0004：简化版 V1 实施基线](docs/adr/0004-simplified-v1-implementation.md)
- [ADR-0005：安装与语言分发策略](docs/adr/0005-installation-and-language-distribution.md)
- [ADR-0001：长期架构基线](docs/adr/0001-architecture-baseline.md)
- [ADR-0002：合同与语言策略](docs/adr/0002-contract-and-language-strategy.md)
- [ADR-0003：Agent 业务组件生命周期](docs/adr/0003-agent-solution-lifecycle.md)

## 仓库结构

```text
finance-agent-harness/
├── contracts/                       # 四个语言无关合同
├── packages/java/                   # V1 Java Core 和 Provider SPI
├── integrations/openjiuwen-java/    # OpenJiuwen Integration
├── providers/non-ai-banking/        # 首个参考 Provider Adapter
├── domain-packs/wealth/              # 财富 Skill、SOP 和评测引用
├── conformance/                      # 合同与 Adapter 验证
├── examples/mobile-banking/          # 首个消费者
└── docs/                             # 架构、路线和 ADR
```

目录可以按实施逐步创建，不要求在第一笔代码中一次补齐。

## V1 不做

- 不建设中央 Harness 服务；
- 不实现独立 Policy Engine；
- 不复制银行的授权、适当性和交易规则；
- 不同时维护 TypeScript、Java、Python 三套 Core；
- 不建设 Builder、Solution Manifest 或自动优化平台；
- 不重做知识平台、模型网关和通用安全平台；
- 不全量迁移手机银行或 Employee Agent。

长期设计仍保留 Profile、Builder、多 Runtime、跨语言 Conformance 和持续改进方向，但不作为 V1 开工前置条件。
