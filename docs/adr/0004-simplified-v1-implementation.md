# ADR-0004：简化版 V1 实施基线

状态：`Accepted`

日期：2026-09-01

## Context

ADR-0001 至 ADR-0003 建立了完整长期架构，包括六类 Core Contract、Profile、跨语言 SDK、Builder、环境 Preflight 和 Agent 生命周期。这些方向仍然合理，但同时进入 V1 会扩大首版范围，延迟真实效果验证。

手机银行最新开发分支已经提供 Java 查询适配器、任务适配器，并实际连接 `non-ai-banking` Simulator。部署环境已验证一条真实余额查询链路。

因此 V1 应从现有可运行链路提炼最小公共边界，而不是先实现完整长期模型。

## Decision

1. V1 以 Java 为唯一 Core 实现语言；
2. 手机银行是首个消费者；
3. OpenJiuwen Java 是首个 Runtime Integration；
4. `non-ai-banking` 是首个 Reference Provider；
5. Core 只定义 `FinanceContextV1`、`CapabilityRequestV1`、`CapabilityResultV1` 和 `EvidenceReceiptV1`；
6. Provider SPI 只提供 `query` 与 `executeTask`；
7. 银行后端继续拥有认证、权限、风险、适当性、幂等和业务状态权威；
8. 首版以包装和提炼现有手机银行实现为主，不重写已经验证的任务链；
9. 第二阶段从 EA 提取 Wealth Domain Pack，优先验证专业资产复用；
10. EA TypeScript 直接接入、Profile、独立 Policy/Approval、跨语言 Core 和 Builder 后置。

## Consequences

正向影响：

- 可以在 7 至 10 个工作日形成可演示 V1；
- 首版以真实业务环境和现有代码为基础；
- 避免重复实现银行后端治理；
- 先证明查询、办理和专业复用，再决定抽象深度；
- Java 产物更接近银行生产技术栈。

代价：

- V1 暂不能声称跨语言、跨 Runtime；
- `FinanceContextV1` 会暂时承载部分未来 Principal/Profile 语义；
- `CapabilityResultV1` 会暂时承载部分未来 Policy/Approval 状态；
- 未来拆分合同需要保持向后兼容或发布 V2。

这些是有意接受的演进成本。

## Superseded V1 Requirements

以下要求不再是 V1 Definition of Done：

- 六个独立 Core Contract 同时冻结；
- TypeScript 和 Java 双参考实现；
- Python Runtime Bridge；
- EA 与手机银行同时改造；
- Profile 和 Context Projection 引擎；
- 独立 Policy/Approval Engine；
- AgentSolutionManifest、EnvironmentCapabilityProfile 和 SolutionLock；
- Builder 接入；
- 发布前自动或半自动局部优化平台。

ADR-0001 至 ADR-0003 继续作为长期方向记录，不删除。

## Validation

V1 必须通过：

1. 一条手机银行只读查询；
2. 一条需要确认的模拟办理；
3. Query/Task Adapter 定向测试；
4. 一项 Wealth Domain Pack 专业任务；
5. 一份可复跑的 Evidence 与演示说明。

如果这些验证暴露出四个合同无法表达的真实缺口，再通过 ADR 增加合同，不提前扩展。
