# Finance Agent Harness

Finance Agent Harness 是金融 Agent 的能力接入与验证底座。

它不替代 Agent Runtime，也不重做银行业务系统。V1 只解决一个具体问题：让 Agent 通过统一合同调用金融查询和办理能力，并留下可验证的结果。

> Integration 接 Agent Runtime，Provider Adapter 接银行系统。

## 当前状态

`Reusable Financial Agent Kit V0.1 Candidate · Controlled Runtime Validation · Live Authorization Pending`

新增 [财富只读接入套件](examples/solutions/wealth-facts-kit/README.md)：从模板、可信身份绑定、原生工具调用到结果与证据核验的一条可重跑入口。共同财富事实单独版本化，原客户/员工能力不变。本轮使用真实 Java Runtime、受控 HTTP 数据和脚本模型；不宣称真实客户 READY、跨银行替换或模型质量已验收。

V1 按两个连续阶段推进：

1. 手机银行作为首个 Java 消费者，接入 `non-ai-banking` 参考业务环境；
2. 从 Employee Agent 提取财富 Skill、SOP 和评测案例，验证同一 Harness 能支撑财富专业能力。

## V1 架构

```text
Mobile Banking                 Wealth Manager Agent
       \                              /
        +------ OpenJiuwen Runtime --+
                        ↓
             OpenJiuwen Integration
                        ↓
              Finance Harness Core
                        ↓
       non-ai-banking / Employee Agent Providers
                        ↓
           Banking Simulator / Employee Agent
```

对于新建 Agent，已经支持一条更直接的装配链：

```text
solution.yaml
    ↓
Capability Pack + Per-capability Provider Binding
    ↓
OpenJiuwen Java Integration
    ↓
Runtime 原生 ToolCard / LocalFunction
    ↓
Finance Contract / Provider / Evidence
```

干净 OpenJiuwen Agent 不会自动获得任何金融能力。只有 `solution.yaml` 显式引用 Capability Pack 后，Integration 才注册工具及其使用提示；卸载 Pack 后工具随即消失。

一个 Solution 可以组合多个 Provider。`capabilityBindings` 明确每项金融能力由哪个 Provider 提供；装配器在启动前检查缺失、重复、错误和不支持的绑定，并把实际 Provider 及版本逐项写入 `solution-lock.json`。单 Provider 方案保持兼容。

Java Core 首版只定义四个公共对象：

- `FinanceContextV1`：谁在什么渠道、为谁发起请求；
- `CapabilityRequestV1`：需要查询或办理什么；
- `CapabilityResultV1`：银行系统返回的业务状态和结果；
- `EvidenceReceiptV1`：本次请求用了什么能力、来源和结果。

Provider SPI 只提供两个入口：

- `query`：无副作用金融查询；
- `executeTask`：消费银行后端已有的预检、确认、提交和状态协议。

Provider 另用 `finance-provider.v1` 声明实际支持的能力。装配器在向 Runtime 注册工具前核验 Capability Pack 与 Provider，通用 `ProviderConformanceKit` 用固定测试向量检查请求标识、统一状态和续办结构。

## 首版证明

1. 手机银行通过 Harness 完成一条真实只读查询；
2. 手机银行通过 Harness 完成一条需要确认的模拟办理；
3. 面客与员工辅助 Wealth Domain Pack 使用同一 Core 合同和 Evidence 语义，消费各自宿主的 Provider。

已有阶段验证记录（具体环境和限制以对应报告为准；不是本轮全部重跑）：

- `retail-banking-basics` 可声明式安装到未预置金融工具的 OpenJiuwen Java Agent；
- 安装回执与 `solution-lock.json` 可追溯 Pack、Integration、Provider、能力和内容摘要；
- Pack 卸载后，Agent 与 Runtime 均不再暴露对应工具；
- `account.balance.query` 真实只读查询；
- `wealth.customer-context.query` 面客财富诊断；
- `wealth.previsit-context.query` 员工辅助财富访前上下文；
- `insurance.customer-context.query` 保险顾问只读客户上下文与访前诊断装配；
- `account.balance.query` 与 `fraud.transfer-risk.assess` 跨 Provider 装配的内部风险审核助手；
- `channel.preferences.update` 预检、确认、单次提交与重复确认幂等；
- `transfer.internal.create` 反诈预检、确认提交、处理中和未知结果查证；
- 财富上下文必需/可选数据并行汇聚，以及 `READY/PARTIAL` 降级结果。

员工辅助财富场景已进一步通过真实模型与真实 EA 接口验证：干净 OpenJiuwen 同时安装 Domain Pack 和 Capability Pack，模型自主选择财富访前工具，Harness 使用短期签名身份调用 EA，并消费 EA 返回的可验证降级结果。当前测试 Adoption 未取得客户数据源授权，因此该次证据证明的是完整调用链和安全降级，不冒充客户专属 `READY` 验收。

OpenJiuwen Java Bridge 已作为独立 Maven 模块交付。手机银行通过依赖该模块消费 Harness，应用仓仅保留原生任务类型的薄映射；公共 Integration 不再由手机银行代码定义。

当前财富切片先完成客户资产诊断，不在 Harness Core 中写财富推荐规则：

- `wealth.customer-context.query` 由 Provider 组合财富聚合与风险测评事实；
- `domain-packs/wealth/customer-self-service` 负责财富诊断 SOP；
- 具体产品推荐留待银行提供 `eligibleProducts` 后由后续 Skill 完成。

第三项优先验证专业资产复用，不要求 V1 同时重构 Employee Agent 的 TypeScript Runtime。现有 EA 的直接 Integration 在 Java 骨架稳定后增量接入。

员工辅助侧新增 `providers/employee-agent` 参考 Provider，将 `wealth.previsit-context.query` 映射到 EA 已有 `prepare_wealth_previsit_context`。它通过宿主提供的一次性 Runtime Token 调用 EA，不复制 EA 的权限、客户归属和财富上下文装配。

同一个 EA Provider 模块还提供保险顾问只读映射：`insurance.customer-context.query` 调用 EA 的 `get_customer_profile`，保险专业判断来自独立 Domain Pack。它证明扩展业务域不需要修改 Harness Core。

`integrations/employee-agent-java` 提供 Java Runtime 的动态 Token 签发，并已通过 EA TypeScript 验签器的跨语言验证。该实现仍在 Integration 层，Core 主代码不包含 EA 类型或协议。

## 文档入口

- [V0.1 快速接入与兼容说明](examples/solutions/wealth-facts-kit/README.md)
- [财富字段定义与依据](docs/wealth-field-basis-v01.md)
- [本轮执行记录与外部验收清单](docs/financial-agent-kit-v01-worklog.md)
- [V0.1 分项验证报告](docs/verification/2026-09-06-financial-agent-kit-v01.md)
- [共同财富事实候选 ADR](docs/adr/0008-common-wealth-facts-candidate.md)
- [简化版总体架构](docs/finance-agent-harness-architecture-v1.md)
- [V1 范围与快速路线](docs/v1-scope-and-roadmap.md)
- [V1 演示故事](docs/v1-demo-story.md)
- [手机银行 V1 映射](docs/mobile-banking-v1-mapping.md)
- [Builder 财富场景交接](docs/builder-handoff-wealth-v1.md)
- [Builder 多 Provider 装配交接](docs/builder-handoff-multi-provider-v1.md)
- [Provider 接入规范](docs/provider-integration-v1.md)
- [金融能力扩展路线](docs/domain-capability-roadmap-v1.md)
- [跨应用财富验证记录](examples/cross-channel-wealth/verification.md)
- [OpenJiuwen 财富访前真实链路验证](docs/verification/2026-09-03-openjiuwen-wealth-previsit-live-smoke.md)
- [转账风险审核 Domain Skill 真实 A/B 验证](docs/verification/2026-09-03-risk-review-domain-skill-live-ab.md)
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
├── providers/employee-agent/        # EA 财富与保险宿主兼容 Provider
├── capability-packs/                # 可安装的金融工具语义、Schema 和 Smoke
├── catalog/                         # Builder 可读取的最小静态资产目录
├── domain-packs/wealth/              # 面客与员工辅助财富 Skill、SOP 和评测引用
├── domain-packs/insurance/           # 保险顾问访前 Skill 与业务边界
├── domain-packs/risk/                # 内部风险审核方法与业务输出结构
├── domain-packs/anti-fraud/          # 面客转账反诈能力边界
├── conformance/                      # 合同与 Adapter 验证
├── examples/mobile-banking/          # 首个应用消费者
├── examples/solutions/               # Builder/装配输入与干净 Runtime 示例
└── docs/                             # 架构、路线和 ADR
```

Java V1 可通过 `mvn install` 安装到本机 Maven 仓库。当前以 Maven 模块发布，后续是否进入私有制品库由真实交付环境决定，不建设中央 Harness 服务。

## V1 不做

- 不建设中央 Harness 服务；
- 不实现独立 Policy Engine；
- 不复制银行的授权、适当性和交易规则；
- 不同时维护 TypeScript、Java、Python 三套 Core；
- 不在本仓实现完整 Builder、通用 Solution Manifest 或自动优化平台；
- 不重做知识平台、模型网关和通用安全平台；
- 不全量迁移手机银行或 Employee Agent。

V1 只为外部 Builder 提供一个最小构建输入、已验证资产引用和 OpenJiuwen Java 薄装配入口。长期设计仍保留 Profile、多 Runtime、跨语言 Conformance 和持续改进方向，但不作为 V1 开工前置条件。
