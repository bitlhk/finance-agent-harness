# ADR-0003：Agent 业务组件生命周期与环境要求

状态：`Proposed / Review Feedback Incorporated`

日期：2026-09-01

> 本 ADR 的生命周期方向继续保留，但 Manifest、Preflight、Builder 和局部优化平台不再属于简化版 V1，见 [ADR-0004](0004-simplified-v1-implementation.md)。

## Context

架构评审提出两条关联意见：

1. Agent 构建不能只完成软件装配。首次发布前应通过 Sandbox、专业评测和局部优化突破冷启动质量基线；
2. Agent 应被视为可嵌入客户系统的业务组件，必须定义知识、数据、Skill、Connector 和必要执行中间件，并假设客户环境尚未具备这些能力。

原架构已经定义 Core、Profile、Domain Pack、Runtime Integration、Provider、Builder 和 Eval，但对最终 Agent Solution、发布前优化内循环及客户环境能力匹配表达不足。

## Decision

两条意见均按 `ACCEPTED WITH SCOPE` 采纳。

### Agent Solution 是一等交付物

V1 增加最小 `AgentSolutionManifestV1`，描述业务目标、任务与输出合同、Runtime / Model、Profile、Domain Pack、Knowledge、Data、Connector、Policy、Evidence 和 Experience Baseline 的逻辑引用。

Manifest 不承载完整 Policy、知识正文、密钥、连接串、Runtime 全量配置或部署拓扑。

### 发布前局部优化是构建的一部分

构建流程调整为：

```text
Compose
  → Validate
  → Environment Preflight
  → Sandbox Evaluate
  → Bounded Local Optimize
  → Holdout Verify
  → Human Approve
  → Lock
  → Release
```

V1 允许针对 Skill、SOP、Prompt、Context 装配、Tool 描述和批准的 Model Profile 进行 1 至 3 轮局部优化。

Builder 不得自动修改 Core Contract、Profile、安全 Policy、银行权限或生产数据，不得自行完成发布。

### 客户环境能力必须显式声明

V1 增加最小 `EnvironmentCapabilityProfileV1`，声明 Runtime、中间件、Enterprise Provider 和 Operations 能力。

`DeploymentPreflightReportV1` 匹配 Solution Requirements 与 Environment Capabilities，输出 Ready、Degraded 或 Incompatible，并记录实际 Binding 和 Conformance 证据。

### Harness 定义要求，不重做全部中间件

能力按三类管理：

1. Runtime 通用能力，由 OpenJiuwen 等 Runtime 提供；
2. 金融语义中间件，由 Harness 定义合同并提供必要参考实现；
3. 客户业务能力，由银行系统和 Provider Adapter 提供。

Harness 只为 V1 跨渠道财富场景实际需要的关键缺口提供轻量实现。

### 运行后的进化保持工程可控

Runtime 记录 SolutionLock、Evidence 和必要业务结果信号。真实 Good Case / Bad Case 可以形成后续 Candidate，但必须经过离线评测、回归、人工确认和版本发布。

V1 不实现生产在线学习或 Agent 自主改写自身。

## Consequences

正向影响：

- 构建结果从“可以启动”提升为“达到初始业务体验基线”；
- 客户集成要求可以在部署前明确；
- 缺失能力不会在运行时以未知错误暴露；
- Builder、Runtime、Provider、Eval 和业务结果形成完整生命周期；
- 后续可以基于真实业务表现持续改进，而不依赖用户主动点赞。

代价：

- V1 增加三个最小交付合同；
- 演示需要增加 Preflight 和发布前优化步骤；
- Solution 资产、环境能力和实际 Binding 需要版本化；
- 需要保持优化集与留出验收集独立。

## Explicit Non-goals

- 完整可视化 Builder；
- 自动多轮 Prompt / Skill 搜索平台；
- 模型训练或微调平台；
- 生产在线自学习；
- 自动发布；
- 重做通用 Session、消息队列、模型网关、RAG、密钥或可观测平台；
- 为所有银行中间件提供产品级替代实现。

## Validation

V1 必须通过一个财富 Agent Solution 证明：

1. Manifest 可以完整声明初始业务组件所需资产和能力；
2. Preflight 能在 EA 与手机银行两个环境中识别实际能力与缺口；
3. Initial Candidate 至少经过一次局部优化；
4. 冻结留出案例证明优化有效且关键能力无回归；
5. SolutionLock 可以关联运行 Evidence；
6. 非原作者可以依据文档重复构建和运行演示。
