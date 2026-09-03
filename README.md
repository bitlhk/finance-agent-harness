# Finance Agent Harness

Finance Agent Harness 是金融 Agent 的能力接入与验证底座。

它不替代 Agent Runtime，也不重做银行业务系统。V1 只解决一个具体问题：让 Agent 通过统一合同调用金融查询和办理能力，并留下可验证的结果。

> Integration 接 Agent Runtime，Provider Adapter 接银行系统。

## 当前状态

`Runtime Plane V1 Verified · Installable OpenJiuwen Java Bridge Available`

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
3. 面客与员工辅助 Wealth Domain Pack 使用同一 Core 合同和 Evidence 语义，消费各自宿主的 Provider。

当前已验证：

- `account.balance.query` 真实只读查询；
- `wealth.customer-context.query` 面客财富诊断；
- `wealth.previsit-context.query` 员工辅助财富访前上下文；
- `channel.preferences.update` 预检、确认、单次提交与重复确认幂等；
- `transfer.internal.create` 反诈预检、确认提交、处理中和未知结果查证；
- 财富上下文必需/可选数据并行汇聚，以及 `READY/PARTIAL` 降级结果。

OpenJiuwen Java Bridge 已作为独立 Maven 模块交付。手机银行通过依赖该模块消费 Harness，应用仓仅保留原生任务类型的薄映射；公共 Integration 不再由手机银行代码定义。

当前财富切片先完成客户资产诊断，不在 Harness Core 中写财富推荐规则：

- `wealth.customer-context.query` 由 Provider 组合财富聚合与风险测评事实；
- `domain-packs/wealth/customer-self-service` 负责财富诊断 SOP；
- 具体产品推荐留待银行提供 `eligibleProducts` 后由后续 Skill 完成。

第三项优先验证专业资产复用，不要求 V1 同时重构 Employee Agent 的 TypeScript Runtime。现有 EA 的直接 Integration 在 Java 骨架稳定后增量接入。

员工辅助侧新增 `providers/employee-agent` 参考 Provider，将 `wealth.previsit-context.query` 映射到 EA 已有 `prepare_wealth_previsit_context`。它通过宿主提供的一次性 Runtime Token 调用 EA，不复制 EA 的权限、客户归属和财富上下文装配。

`integrations/employee-agent-java` 提供 Java Runtime 的动态 Token 签发，并已通过 EA TypeScript 验签器的跨语言验证。该实现仍在 Integration 层，Core 主代码不包含 EA 类型或协议。

## 文档入口

- [简化版总体架构](docs/finance-agent-harness-architecture-v1.md)
- [V1 范围与快速路线](docs/v1-scope-and-roadmap.md)
- [V1 演示故事](docs/v1-demo-story.md)
- [手机银行 V1 映射](docs/mobile-banking-v1-mapping.md)
- [Builder 财富场景交接](docs/builder-handoff-wealth-v1.md)
- [跨应用财富验证记录](examples/cross-channel-wealth/verification.md)
- [职责与评审规则](docs/ownership-and-review.md)
- [ADR-0004：简化版 V1 实施基线](docs/adr/0004-simplified-v1-implementation.md)
- [ADR-0005：安装与语言分发策略](docs/adr/0005-installation-and-language-distribution.md)
- [ADR-0006：金融能力执行原语与演进边界](docs/adr/0006-finance-capability-execution-primitives.md)
- [ADR-0001：长期架构基线](docs/adr/0001-architecture-baseline.md)
- [ADR-0002：合同与语言策略](docs/adr/0002-contract-and-language-strategy.md)
- [ADR-0003：Agent 业务组件生命周期](docs/adr/0003-agent-solution-lifecycle.md)

## 仓库结构

```text
finance-agent-harness/
├── contracts/                       # 四个运行合同和一个轻量构建输入
├── packages/java/                   # V1 Java Core 和 Provider SPI
├── integrations/openjiuwen-java/    # OpenJiuwen Integration
├── integrations/employee-agent-java/ # EA Java Runtime 身份 Integration
├── providers/non-ai-banking/        # 首个参考 Provider Adapter
├── providers/fraud-mining/          # 外部反诈能力参考 Provider
├── providers/employee-agent/        # EA 财富访前宿主兼容 Provider
├── domain-packs/wealth/              # 面客与员工辅助财富 Skill、SOP 和评测引用
├── domain-packs/anti-fraud/          # 反诈能力说明、映射与评测资产
├── conformance/                      # 合同与 Adapter 验证
├── examples/mobile-banking/          # 首个消费者
└── docs/                             # 架构、路线和 ADR
```

Java V1 可通过 `mvn install` 安装到本机 Maven 仓库。当前以 Maven 模块发布，后续是否进入私有制品库由真实交付环境决定，不建设中央 Harness 服务。

## V1 不做

- 不建设中央 Harness 服务；
- 不实现独立 Policy Engine；
- 不复制银行的授权、适当性和交易规则；
- 不同时维护 TypeScript、Java、Python 三套 Core；
- 不在本仓实现 Builder、完整 Solution Manifest 或自动优化平台；
- 不重做知识平台、模型网关和通用安全平台；
- 不全量迁移手机银行或 Employee Agent。

V1 只为外部 Builder 提供一个最小构建输入和已验证资产引用。长期设计仍保留 Profile、多 Runtime、跨语言 Conformance 和持续改进方向，但不作为 V1 开工前置条件。
