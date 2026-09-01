# Finance Agent Harness 总体架构 V1

状态：`Architecture Baseline Candidate`

更新时间：2026-09-01

## 1. 文档目的

本文定义 Finance Agent Harness 的产品定位、技术边界、核心合同、首版范围、应用验证方式和团队协作原则。

本文不是一次性完成所有金融 Agent 能力的蓝图。V1 的目标是以岗位智能体和手机银行两个真实应用证明：同一套金融共性合同和控制语义可以被不同渠道、不同语言和不同运行形态共同消费。

## 2. 一句话定义

Finance Agent Harness 是夹在 Agent Runtime 与金融企业系统之间的金融领域工程层。它向上适配 Agent Runtime，向下适配银行数据与业务系统，为金融应用提供统一的 Principal、Context、Action、Policy、Approval、Evidence、Profile 和 Domain Pack。

它不接管 Agent Loop，也不替代具体应用。

核心原则：

> Core 定义金融共性。Profile 表达主体关系。Integration 适配 Agent Runtime。Provider Adapter 适配银行系统。Application 决定最终用户体验。

## 3. 为什么需要 Harness

当前岗位智能体、手机银行、Builder 和 Skill Eval 已经分别形成一批有价值的能力，但这些能力仍带有各自产品、语言和 Runtime 的形状：

- 岗位智能体有较强的客户 Context、Profile、Evidence、Skill 和工作空间能力；
- 手机银行有较强的 Capability Catalog、分级控制、确认、恢复和幂等能力；
- Builder 有受控装配、验证、评测、修正和追溯闭环；
- Finance Skill Eval 有 Eval Pack、A/B 编排、Judge 和可视化报告能力。

如果继续按应用分别演进，会形成多套近似但不兼容的金融语义。Finance Agent Harness 的任务不是简单搬运代码，而是把已经被真实应用证明的问题提炼为 Runtime-neutral 的金融合同，再由两个应用共同验证。

## 4. 总体架构

### 4.1 领导视图

```text
┌─────────────────────────────────────────────────────────┐
│                  Financial Applications                  │
│                                                         │
│   Employee Agent      Mobile Banking      Other Agent   │
└────────────┬────────────────┬────────────────┬───────────┘
             │                │                │
             └────────────────┴────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────┐
│                    Agent Runtime Layer                   │
│                                                         │
│       OpenJiuwen V1       Other Runtimes Future         │
│   Planning / Tool / Session / Checkpoint / Multi-Agent  │
└─────────────────────────────┬───────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────┐
│                    Finance Agent Harness                 │
│                                                         │
│  Core Contracts      Profiles        Domain Packs       │
│  Context / Action    Employee        Wealth             │
│  Policy / Approval   Customer        Insurance Future   │
│  Evidence            Internal Future Research Future    │
│                                                         │
│  Runtime Integrations     Provider Adapters              │
│  Conformance / SDK        Engineering Plane              │
└─────────────────────────────┬───────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────┐
│                Enterprise Systems and Data              │
│       CRM / Product / Account / Knowledge / MCP / API   │
└─────────────────────────────────────────────────────────┘
```

### 4.2 技术视图

```text
Financial Application
        │
        ▼
Agent Runtime
        │
        ▼
Northbound Runtime Integration
        │
        ├──────────────► Finance Profile
        │
        ▼
Finance Harness Core
        │
        ├──────────────► Domain Pack
        │
        ▼
Southbound Provider Contract
        │
        ▼
Enterprise Provider Adapter
        │
        ▼
Bank Enterprise System
```

Finance Eval Lab 位于生产热路径之外。它读取 Domain Pack、Eval Pack 和 Evidence，并通过 Runtime Adapter 调用应用或 Agent Runtime 做 A/B、Judge 和回归验证。

## 5. 五层职责

### 5.1 Financial Application

负责最终产品形态和用户体验：

- 岗位智能体首页、工作空间、Skill 市场、会话和管理后台；
- 手机银行页面、菜单、客户操作路径和前端交互；
- 研发智能体或其他未来金融应用。

Application 不定义跨应用的 Finance Contract。

### 5.2 Agent Runtime

负责通用 Agent 执行：

- Planning、ReAct 和 Agent Loop；
- Tool Calling；
- Session、Memory、Checkpoint 和 Resume；
- Multi-Agent；
- 通用 Trace。

Finance Agent Harness 不重做这些能力。

### 5.3 Runtime Integration

负责把 Harness 接入具体 Runtime 生命周期：

- Principal 注入；
- Context 请求和返回映射；
- Tool Call 转换为 FinanceAction；
- Before Side Effect 拦截；
- Policy Decision 转换为 Runtime Allow、Block 或 Interrupt；
- Approval、Resume 和 Trace 映射；
- Runtime Capability 声明和验证。

Integration 只负责翻译，不承载第二套金融政策引擎。

### 5.4 Finance Harness

负责金融上应该发生什么：

- 谁以什么身份、为谁、基于什么目的行动；
- 任务需要哪些金融事实和知识；
- 当前数据是否完整、有效和可使用；
- Agent 试图执行什么金融动作；
- 该动作允许、拒绝、需确认还是需加强认证；
- 确认授权绑定到哪个主体、动作、参数和政策快照；
- 执行过程如何形成可复核证据；
- 不同主体关系下如何投影 Context 和控制动作；
- 财富、保险、投研等岗位应该如何专业地完成任务。

### 5.5 Enterprise Provider

负责把金融合同映射到真实银行系统：

- Customer Provider；
- Portfolio Provider；
- Product Provider；
- Policy / Knowledge Provider；
- Account Provider；
- Finance Capability Provider。

Core 定义统一语义，Provider Adapter 处理银行差异。Core 不直接包含数据库地址、密钥或银行私有字段。

## 6. Runtime Plane 与 Engineering Plane

### 6.1 Runtime Plane

运行时请求热路径中的能力：

```text
Principal
  + Profile
  + Purpose
  + ContextNeed / FinanceAction
        ↓
Context / Policy / Approval
        ↓
Provider Execution
        ↓
FinanceEvidenceReceipt
```

Runtime Plane V1 包含：

- Core Contracts；
- Context 与 Knowledge Semantics；
- Action、Policy 和 Approval；
- Evidence；
- 两个 Profile；
- Wealth Domain Pack 的最小消费合同；
- OpenJiuwen Integration；
- Provider Contracts。

### 6.2 Engineering Plane

构建期能力，不进入生产请求热路径：

- Contract SDK；
- Provider Adapter Toolkit；
- Conformance 和 Sandbox；
- Solution Builder；
- Domain Pack Authoring；
- Eval Integration。

V1 定义最小 Agent Solution Contract、环境要求和发布前质量基线，并用手工或 CLI 方式完成一次可重复装配。完整可视化 Builder、通用装配插件和 Manifest / Lock 管理界面在 V1.2 开始实现。

### 6.3 Agent 是可嵌入的业务组件

Finance Agent Harness 的最终交付物不是一组 Schema 或 Adapter，而是可以嵌入客户业务系统的 Agent 业务组件。

一个 Agent Solution 至少需要明确：

```text
业务目标与目标用户
任务入口、触发方式和业务输出
Runtime 与 Model Profile
Finance Profile
Domain Pack / Skill / SOP
Knowledge Requirements
Data / Context Requirements
Connector / Capability Requirements
Policy / Approval / Evidence Requirements
Experience Baseline 与 Eval Pack
```

其中 Manifest 只记录逻辑引用和要求，不内嵌全部知识、Policy、连接参数和 Runtime 配置。

完整生命周期分为：

```text
Build：定义 → 装配 → Sandbox → 局部优化 → 留出集验收 → 发布
Run：集成 → 触发 → Context 装配 → 专业执行 → 业务结果 → Evidence
Improve：业务信号 → Good/Bad Case → 诊断 → Candidate → 回归 → 再发布
```

V1 实现 Build 的最小发布前优化和 Run 的必要证据，只为 Improve 保留版本和业务信号，不建设自动在线学习平台。

## 7. Contract V1

V1 首批冻结六个 Core Contract，并定义三个最小的集成与交付合同。它们必须保持边界清晰，不能把所有应用配置塞进 Core。

### 7.1 FinancePrincipalV1

表达当前主体关系，不等同于应用用户对象。

最小语义：

```text
principalId
principalType
actingParty
actingFor
subjectRef
organizationRef
channel
purpose
authorizationEvidenceRef
```

示例关系：

- 财富经理为其负责客户提供辅助服务；
- 手机银行客户代表本人操作；
- 内控人员基于审计目的读取受限证据。

### 7.2 FinanceContextPackV1

表达经过来源、时效、权限和用途处理后的金融 Context，不是裸 CRM 返回值。

最小语义：

```text
contextPackId
subjectRef
purpose
profileRef
asOf
readiness
items[]
missingItems[]
conflictingItems[]
sourceBindings[]
provenance[]
```

Readiness V1：

- `READY`：足以完成当前阶段任务；
- `DEGRADED`：可以提供受限结果，但必须说明缺失条件；
- `BLOCKED`：无法合法或可信地完成当前任务。

Context Item 必须可以表达类型、业务含义、来源、数据时间、权威等级、适用目的、有效期、投影策略和内容引用。

### 7.3 FinanceActionV1

把 Runtime Tool Call 转换为金融业务动作。Action 描述业务含义，不携带 Runtime 私有类。

最小语义：

```text
actionId
actionType
domain
operation
principalRef
subjectRef
purpose
resourceRefs[]
parameters
parameterDigest
sideEffectClass
riskClass
idempotencyRequirement
```

V1 至少覆盖只读金融 Context、创建客户咨询请求、创建客户跟进和需要人工确认的写操作。

### 7.4 FinancePolicyDecisionV1

Policy 输入 Principal、Profile、Context、Action 和当前 Policy Snapshot，输出确定性控制结论。

V1 Decision：

- `ALLOW`；
- `DENY`；
- `REQUIRE_APPROVAL`；
- `REQUIRE_STEP_UP`。

Decision 必须记录 `decisionId`、`reasonCodes`、`obligations`、`policySnapshotRef`、`decisionFingerprint` 和时间。

### 7.5 FinanceApprovalGrantV1

Approval 不是 `confirmed=true`，而是短时、一次性、绑定具体动作的授权凭证。

最小语义：

```text
grantId
principalRef
actionRef
parameterDigest
policySnapshotRef
scope
issuedBy
issuedAt
expiresAt
singleUse
authenticationLevel
```

V1 必须验证过期、主体不匹配、参数被修改、政策快照变化、重复使用和动作类型不匹配。

### 7.6 FinanceEvidenceReceiptV1

把通用 Trace 提升为金融业务证据。Evidence 不应只是任意 `Map`。

最小语义：

```text
receiptId
taskRef
principalRef
profileRef
solutionLockRef
domainPackRefs[]
contextPackRef
policyDecisionRefs[]
approvalGrantRefs[]
actions[]
providerExecutionRefs[]
runtimeTraceRef
result
startedAt
finishedAt
receiptFingerprint
```

Evidence 用于执行回执、问题定位、Skill / Agent Eval、跨应用一致性证明和后续审计对接。

### 7.7 RuntimeCapabilityProfileV1

用于声明并验证 Runtime 可以承载哪些 Harness 能力：

```text
supportsContextInjection
supportsBeforeToolCall
supportsBeforeSideEffect
supportsInterrupt
supportsResume
supportsCheckpoint
supportsTypedTrace
supportsSubAgentTrace
```

Runtime 自报能力不等于已经支持。每个声明必须由对应 Conformance Case 验证。

### 7.8 AgentSolutionManifestV1

AgentSolutionManifest 描述一个金融 Agent 业务组件“是什么”和“需要什么”，不描述所有物理部署参数。

最小范围：

```text
solutionId / version
businessPurpose / targetUsers
taskContracts / outputContracts
runtimeIntegrationRef / modelProfileRef
profileRef / domainPackRefs
knowledgeRequirementRefs
dataContextRequirementRefs
connectorCapabilityRequirementRefs
policyApprovalEvidenceRequirementRefs
experienceBaselineRef / evaluationPackRef
```

### 7.9 EnvironmentCapabilityProfileV1

部署时必须假设客户环境中的 Runtime、中间件和企业 Provider 尚不存在或能力未知。客户环境需要显式声明可以提供的能力及版本：

```text
Runtime：Context Injection / Tool Hook / Interrupt / Resume / Trace
Middleware：Principal Resolver / Context Assembler / Approval / Idempotency / Evidence Sink
Enterprise Provider：Customer / Portfolio / Product / Policy / Action
Operations：Secret / Timeout / Retry / Retention / Observability
```

`RuntimeCapabilityProfileV1` 是 Environment Capability 的 Runtime 子集。

### 7.10 DeploymentPreflightReportV1

Builder 或部署工具在运行前匹配 Solution Requirements 与 Environment Capabilities，输出：

- `READY`：必要能力已绑定；
- `DEGRADED`：可以运行受限任务，明确不可用能力和降级路径；
- `INCOMPATIBLE`：缺少必要能力，不能部署。

Preflight 同时输出实际 Binding、缺失项、替代实现和 Conformance 证据。不能等 Agent 运行后才发现客户没有身份、HITL、幂等、产品或 Evidence 能力。

## 8. Profile 是一等公民

Profile 表达“主体关系 + 授权语义 + 默认控制要求”，但不是实时授权的权威来源。

Core 只认 Principal、ActingParty、Purpose、Consent、Authority、ApprovalRequirement、ContextProjection 和 EvidenceObligation。Profile 负责组合这些积木。

### 8.1 employee-assisted

- Principal 为员工，ActingFor 为客户；
- 需要客户归属或授权证据；
- 可读取员工工作所需的经营 Context；
- 写操作受员工权限、客户关系和确认控制；
- 证据记录员工、客户、目的和业务动作。

### 8.2 customer-self-service

- Principal 和 ActingFor 均为客户本人；
- Context 仅投影本人可见数据；
- 交易或敏感动作需要确认或加强认证；
- 不暴露员工经营字段、客户归属和内部备注；
- 证据记录客户确认和渠道。

同一底层 Customer / Policy 数据，在两个 Profile 下可以产生不同 Context Projection 和 Policy Decision，但使用同一 Evidence Schema。

## 9. Knowledge 的沉淀边界

Finance Agent Harness 不重做 Parser、Chunking、Embedding、Vector Database 或 Rerank。

知识方向沉淀 `FinanceKnowledgeNeed`、`KnowledgeAssetRef`、`CurrentPolicySnapshot` 及 Authority、EffectiveAt、ExpiresAt、Applicability、Purpose Binding、Citation、Provenance、Freshness、Readiness 等金融知识可用性语义。

知识 Provider 可以连接现有知识库、RAG、文件、MCP 或制度中心。Agent 消费的是经过版本和适用性说明的知识结果，而不是不带来源的文本片段。

V1 重点验证当前有效政策、已过期政策、多来源冲突、必要知识缺失和 Purpose 不匹配。

## 10. 安全的沉淀边界

Finance Agent Harness 不重做通用 Prompt Injection 检测器。安全方向重点沉淀 FinanceAction、Before Side Effect Interceptor、Policy Decision、ApprovalGrant、幂等、重放、参数篡改保护和 Evidence。

Runtime 负责通用执行和暂停恢复，Harness 负责决定金融动作应当 Allow、Deny、Require Approval 还是 Require Step Up。

## 11. OpenJiuwen Integration V1

V1 只支持 OpenJiuwen，但合同不绑定 OpenJiuwen 私有类型。

```text
fin-openjiuwen-contract
├── openjiuwen-enterprise-remote
│   ├── ea-ts-host-adapter
│   └── python-runtime-bridge
└── openjiuwen-java-embedded
    └── mobile-java-host-adapter
```

统一生命周期语义：

```text
BeforeTask
ContextRequested
BeforeToolCall
BeforeSideEffect
ApprovalRequested
ApprovalResolved
AfterToolCall
OnResume
OnFinish
```

EA TypeScript Host 持有 Principal、Profile、Context、Approval 和 Evidence；Python Runtime Bridge 只映射 OpenJiuwen 企业版生命周期事件，不复制金融 Policy。

手机银行 Java Host Adapter 直接消费 Java SDK，将 Capability 调用映射为 FinanceAction，将确认布尔或 Capability ID 提升为 ApprovalGrant，将任意 Map 证据提升为 FinanceEvidenceReceipt。

## 12. Provider 架构

北向 Runtime 不应裸接金融 CRM、Product 或 Account API。推荐链路：

```text
Runtime
  ↓
Runtime Integration
  ↓
Finance ContextNeed / FinanceAction
  ↓
Finance Harness
  ↓
Provider Contract
  ↓
Bank Provider Adapter
  ↓
Enterprise System
```

V1 Provider Contracts 包含 Customer、Portfolio、Product、Policy / Knowledge、Advisory Request 和 Follow-up。计算器、文档解析和普通搜索等通用工具仍可由 Runtime 直接管理。

## 13. Domain Pack

Domain Pack 解决通用 Runtime “会调工具但不懂金融岗位怎么专业工作”的问题。

V1 Wealth Pack 最小包含：

- Skill / SOP 引用；
- Context Requirements；
- Capability Requirements；
- Task Patterns；
- 专业规则和沟通模式；
- Golden Tasks；
- Judge Rubric；
- Eval Pack 引用。

Domain Pack 不包含银行密钥和系统地址。制度内容可以由银行 Policy Provider 在部署时提供。

## 14. Builder 与 Solution Composition

Builder 定位为 Finance Harness Engineering Plane 中带发布前局部优化能力的受控装配器。

```text
业务目标与 Solution Requirements
        ↓
AI 辅助生成 SolutionManifest Draft
        ↓
Builder 确定性解析、环境 Preflight 和构建
        ↓
Sandbox / Conformance / 专业评测
        ↓
限定范围的局部优化
        ↓
冻结留出集回归验证
        ↓
Human Review
        ↓
SolutionLock
        ↓
Release
```

Builder 不进入生产 Runtime 热路径。

V1 只要求定义最小 Manifest、Environment Capability 和 Preflight，并用手工或 CLI 方式完成一个财富 Solution 的装配、局部优化与锁定。Builder 团队同时用现有 Scheduling / Intent Builder 压测 AssetRef 和 Composition 表达，不在原 Builder 仓库中发展与 Harness 平行的公共资产模型。

发布前局部优化边界：

- 优先修改 Skill、SOP、Prompt、Context 装配、Tool 描述和批准的 Model Profile；
- 不自动修改 Core Contract、Finance Profile、安全 Policy 或银行权限；
- 优化集与冻结留出集分离；
- Builder 与 Judge 角色分离；
- V1 每个 Candidate 最多进行 2 至 3 轮局部优化；
- 没有稳定提升或出现关键回归时保留原 Candidate；
- AI 不能自行从 Draft 跳转到 Published。

V1.2 完善：

- `SolutionManifest`：描述 `solutionId`、Application Prototype、Runtime Integration、Profile、Domain Pack、Provider Contract、Model Profile、Evaluation Pack、Native Manifest 和 Builder 的逻辑组合；
- `SolutionLock`：记录本次真正解析出的版本、Digest、Provider Contract、模型 Profile、原生 Manifest 和构建证据。

Manifest 回答“由哪些资产组成”，Lock 回答“本次实际用了哪些版本”。AI 生成的资产默认为未验证候选，Builder、Judge 和 Human Review 保持角色隔离。

## 15. Finance Skill Eval 的关系

`finance-skill-eval` 继续保持独立仓库和独立组件定位，不并入 Runtime Core。

它通过 Domain Pack、Eval Pack、Runtime Adapter、Model Adapter、FinanceEvidenceReceipt、SolutionLock 和 Provider / Fixture Snapshot 复用 Harness。Promptfoo 等 Runner 是内部 Adapter，不是 Harness 公共接口。

## 16. 初始体验如何被保证

无法保证生成式模型逐字输出一致，但可以保证可重复、可测量的 Experience Baseline。

### 16.1 确定性软件保证

- 指定资产成功加载；
- Tool / Provider 可调用；
- 卡片、文件、报告和确认流程正确；
- 写操作具备幂等性；
- Evidence 完整生成。

### 16.2 行为边界保证

- 使用已提供的 Context；
- 不把假设写成事实；
- 不覆盖 Runtime 的 Deny / Block；
- 只使用可用且适配的产品集合；
- Context 不足时按照 Readiness 降级。

### 16.3 专业质量保证

- 固定 Golden Case、数据和产品快照；
- 多次运行；
- 专业 Rubric 和 Judge；
- 与无 Skill、上一版 Skill 或基线方案对照。

### 16.4 运行质量保证

- Task 完成率、Tool 成功率和首次可用结果时间；
- 重试、取消和人工修改率；
- 失败时回退到上一 SolutionLock。

V1 不实现自动优化闭环，只保留版本、Evidence 和必要使用信号，为后续真实用户反馈打基础。

### 16.5 发布前局部优化

每个新 Solution 在首次发布前必须突破最小冷启动基线：

```text
Initial Candidate
  → 固定 Sandbox 任务
  → 专业与业务质量诊断
  → 1 至 3 轮局部优化
  → 独立留出集验收
  → Verified Candidate
```

这里验证的是 Agent 的初始业务体验，而不只是软件可以启动。首版不做模型训练和生产在线自学习。

## 17. 第一批资产沉淀

| 来源 | 第一批候选 | Harness 归属 | 处理方式 |
|---|---|---|---|
| 岗位智能体 | Context Need、Readiness、Binding、Projection、Provenance | Core Context / Knowledge | 提炼合同，不直接搬业务对象 |
| 岗位智能体 | Policy Decision、Receipt、Skill Version Evidence | Action / Evidence | 统一为 Core Schema |
| 岗位智能体 | 客户画像、财富 SOP、专家案例 | Wealth Domain Pack | 保留专业语义，不放 Core |
| 手机银行 | Capability Catalog、R0-R3、确认、恢复、幂等 | Action / Approval / Integration | 提炼动作和授权合同 |
| 手机银行 | 用户 Context、Capability ID、执行证据 Map | Application / Integration | 从任务参数中剥离并映射 Core |
| Builder | 受控装配、校验、评测回流、追溯 | Engineering Plane | V1 最小 CLI 验证，V1.2 完整接入 |
| Skill Eval | Eval Pack、Runtime Adapter、Judge、Report | 独立 Eval Lab | 通过合同连接，不搬回 Core |
| 知识系统 | 现有检索和知识库 | Provider | 不重做引擎，补金融知识语义 |
| 安全系统 | 通用 Guardrail | External / Integration | 保留通用能力，补金融副作用控制 |

## 18. 语言与合同策略

V1 不选择单一语言统治所有应用。

- Canonical Contract：JSON Schema 2020-12；
- Provider Remote API：OpenAPI 3.1；
- Canonical Digest：RFC 8785 JCS + SHA-256；
- TypeScript：Core 参考实现和 EA SDK；
- Java：手机银行一等 SDK；
- Python：OpenJiuwen 企业版薄 Runtime Bridge；
- 不在 V1 建中央 Core 微服务。

这样可以同时支持 EA TypeScript、手机银行 Java 和 OpenJiuwen 企业版 Python，而不复制三套业务定义。

## 19. Conformance 策略

任何公共合同进入稳定状态前必须具备 Strict Schema、正向 Fixture、负向 Fixture、Canonical Digest Vector、TypeScript / Java 一致结果，以及 EA 与手机银行两个消费者验证。

核心 Fixture：

- Approval 过期、主体不匹配、参数摘要篡改、Policy Snapshot 变化和重放；
- Context 过期、来源冲突和必要项缺失；
- Employee / Customer Projection 泄漏；
- Provider 超时和部分失败；
- Runtime 声称能力但 Conformance 不通过。

能力成熟度：

```text
PROPOSED
  ↓
REFERENCE_IMPLEMENTED
  ↓
EA_VERIFIED
  ↓
MOBILE_VERIFIED
  ↓
HARNESS_STABLE
```

## 20. V1 证明目标

### Proof 1：Cross-Application

同一 Core 被岗位智能体和手机银行共同消费。

### Proof 2：Cross-Profile

同一 Customer / Policy Snapshot 在 Employee 和 Customer Profile 下产生不同合法 Context Projection 与动作控制，且使用相同 Evidence Schema。

### Proof 3：Cross-Language

TypeScript 和 Java 对同一 Schema、Digest 和 Policy Fixture 产生一致结果。

V1 先用手工或 CLI 证明最小 Manifest、Preflight、局部优化和 Lock 流程。V1.2 再证明完整 Builder 可以依据 Manifest / Lock 可重复装配 Solution。V2 再选择第二个非 OpenJiuwen Runtime 做 Cross-Runtime 证明。

## 21. V1 非目标

- 同时支持多个 Agent Runtime；
- 中央化 Harness 微服务；
- 完整保险、投研、风控 Domain Pack；
- 自动修改 Skill、模型或 Policy；
- 自主 Agent 工厂；
- 全量迁移岗位智能体和手机银行代码；
- 重做知识平台、模型网关或通用安全产品；
- 完整审计认证平台；
- 自动在线学习、自动改写和自动发布；
- 通用 Session、消息队列、模型网关、RAG 或密钥系统实现；
- 为客户缺失的所有中间件提供完整产品。

## 22. 关键风险

1. EA 与手机银行的客户身份需要统一映射，避免同名不同主体；
2. OpenJiuwen 企业版实际 Hook、Interrupt、Resume 和 Trace 能力需要用 Spike 证实；
3. 手机银行确认和幂等控制需要从普通任务参数提升为系统 Context；
4. TypeScript / Java 的标准化 JSON 和摘要必须完全一致；
5. Provider 需要定义超时、部分失败、重试和数据时效语义；
6. npm、Maven、Python Bridge 和 Schema Bundle 需要统一版本发布；
7. 从现有仓库提炼代码时需要确认许可证和知识产权边界；
8. 凭证、客户数据、Evidence 的保留和脱敏由项目配置控制；
9. Harness 失效时需要明确 Fail Closed、Fail Open 或降级策略。

这些风险需要在 V1 实现中验证，但不改变总体架构。

## 23. 决策冻结

V1 冻结以下判断：

1. Application 直接面向 Agent Runtime；
2. Finance Harness 不替代 Agent Runtime；
3. 金融事实和金融副作用优先经过 Harness；
4. Profile 是一等输入，但不是实时授权来源；
5. Core 不依赖具体 Runtime 实现；
6. TypeScript 为参考实现，Java 为一等 SDK，Python 为薄桥接；
7. V1 不建中央远程 Core 服务；
8. Builder 是构建期受控装配器，不进入运行热路径；
9. Finance Skill Eval 保持独立；
10. Agent Solution 必须显式声明知识、数据、Skill、Connector 和执行环境要求；
11. 新 Solution 发布前进行限定范围的局部优化和独立留出集验收；
12. 先用 EA + 手机银行证明共性，再扩大 Runtime、Domain 和 Builder 范围。

## 24. 开工条件

满足以下条件即可开始 Walking Skeleton：

- 总体架构无 Blocker；
- 六个 Core Contract Owner 明确；
- EA、手机银行、知识、安全和 Builder 各有一个接口人；
- V1 跨渠道财富演示故事冻结；
- Conformance Fixture 与 Contract 同步开发；
- 不在首轮加入新的 Runtime、Domain 或自动优化需求。

当前结论：`Ready for Architecture Alignment Review and V1 Walking Skeleton`。
