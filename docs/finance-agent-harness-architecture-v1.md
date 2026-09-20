# Finance Agent Harness 简化版总体架构 V1

状态：`Historical Design / 2026-09-01`

本文保留最初 Java V1 的决策与范围，不再作为当前能力清单。当前入口见 [当前架构](current-architecture.md) 和 [开发指导](public/developer-guide.md)；这里的“后续范围”不能覆盖后来已实现的 Builder、Packager 和 Python 桥接。

更新时间：2026-09-01

## 1. 目标

V1 不建设完整的金融 Agent 平台，只验证一条最小且真实的公共链路：

> Agent 可以通过统一金融合同连接模拟银行业务环境，完成查询和办理，并把相同能力复用于财富专业场景。

长期架构中的 Profile、独立 Policy、Builder、多 Runtime、多语言 SDK 和持续优化继续保留，但不进入 V1 必做范围。

## 2. 一句话定义

Finance Agent Harness 是 Agent Runtime 与银行业务系统之间的轻量金融能力接入层。

它负责：

- 统一请求与结果的金融语义；
- 屏蔽不同银行 API 的差异；
- 保留业务来源、状态和执行证据；
- 为金融 Skill 和 Domain Pack 提供稳定能力接口。

它不负责：

- Agent 如何规划和对话；
- 银行如何做权限、风控、适当性和交易记账；
- Builder 如何生成完整 Agent 应用。

## 3. V1 总体链路

```text
┌─────────────────────────────┐
│ Mobile Banking Application  │
└──────────────┬──────────────┘
               ▼
┌─────────────────────────────┐
│ OpenJiuwen Runtime          │
│ Planning / Session / Tool   │
└──────────────┬──────────────┘
               ▼
┌─────────────────────────────┐
│ OpenJiuwen Integration      │
│ Runtime Call ↔ Harness Call │
└──────────────┬──────────────┘
               ▼
┌─────────────────────────────┐
│ Finance Harness Java Core   │
│ Context / Request / Result  │
│ Evidence / Provider SPI     │
└──────────────┬──────────────┘
               ▼
┌─────────────────────────────┐
│ non-ai-banking Adapter      │
│ Query / Task Protocol       │
└──────────────┬──────────────┘
               ▼
┌─────────────────────────────┐
│ non-ai-banking Simulator    │
│ Account / Wealth / Task     │
└─────────────────────────────┘
```

已经验证的环境：

- 手机银行：`http://116.204.36.83:8090`；
- 模拟银行：`http://116.204.36.83:8086`；
- 已验证只读链路：`agent.account → account.balance.query → account-core-simulator`。

## 4. 五个组件

### 4.1 Mobile Banking Application

保留页面、对话体验、部门 Agent、能力召回和任务展示。V1 不重构这些产品能力。

### 4.2 OpenJiuwen Runtime

继续负责 Agent Loop、Planning、Session、Tool Calling 和 Multi-Agent。Harness 不复制这些能力。

### 4.3 OpenJiuwen Integration

这是北向集成层，负责：

- 将 OpenJiuwen Tool/Capability 调用转换为 `CapabilityRequestV1`；
- 将 `CapabilityResultV1` 转换回 Runtime 可消费结果；
- 将 Runtime Trace 与 `EvidenceReceiptV1` 关联。

V1 只覆盖手机银行实际使用的 Java 生命周期，不先抽象所有 OpenJiuwen Hook。

### 4.4 Finance Harness Java Core

Core 保持轻量，只提供四个合同和 Provider SPI，不直接连接 HTTP，也不包含手机银行 Agent ID。

### 4.5 non-ai-banking Provider Adapter

这是南向适配层，负责：

- 只读 Query；
- Session 与认证上下文映射；
- `preflight → task → confirm → submit → status/reconcile`；
- 后端错误、幂等状态和业务回执映射。

银行后端是业务规则权威来源。Harness 不重新判断其授权、风险和交易结果。

## 5. 四个最小合同

### 5.1 FinanceContextV1

表达一次调用所需的最小可信上下文：

```text
requestId
traceId
principalId
subjectRef
channel
purpose
sessionRef
scopes[]
authenticationLevel
```

V1 不单独建立 Principal 和 Profile 引擎。`employee-assisted` 与 `customer-self-service` 后续可以作为 `FinanceContext` 的配置预设。

### 5.2 CapabilityRequestV1

```text
capabilityId
operation: QUERY | TASK
context
parameters
idempotencyKey?
taskRef?
confirmation?
```

Request 不包含 OpenJiuwen 私有类，也不包含银行连接地址和凭证。

### 5.3 CapabilityResultV1

```text
status
data
error
missingInputs[]
nextActions[]
taskState?
sourceSystem
asOf
```

建议的最小状态：

```text
COMPLETED
NEED_USER
NEED_CONFIRMATION
PROCESSING
FAILED
UNKNOWN
```

这些状态映射后端事实，不在 Core 中重新实现状态机。

### 5.4 EvidenceReceiptV1

```text
receiptId
requestId
traceId
capabilityId
operation
providerId
sourceSystem
resultStatus
taskRef?
startedAt
finishedAt
```

首版 Evidence 用于演示、问题定位和 Eval，不建设审计认证平台。

## 6. Provider SPI

Java Core 只定义一套 Provider SPI，包含两个入口：

```java
public interface FinanceProvider {
    CapabilityResult query(CapabilityRequest request);
    CapabilityResult executeTask(CapabilityRequest request);
}
```

`query` 必须无业务副作用。

`executeTask` 不等于直接执行交易，它消费 Provider 已有的预检、确认、提交和查询协议。Provider 返回 `NEED_CONFIRMATION` 时，Runtime 负责向用户展示并在下一轮带回确认。

等待、恢复、并行读取和结果未知的增量语义见
[`ADR-0006：金融能力执行原语与演进边界`](adr/0006-finance-capability-execution-primitives.md)。
Core 只定义可交换语义，不实现工作流调度、事件总线或银行业务状态机。

## 7. 从手机银行提取什么

第一批以 `sync/20260903-local`（`faf47f5`，已合入 `main@f89c9fb`）为事实来源：

| 现有资产 | V1 去向 | 处理方式 |
|---|---|---|
| `UpstreamSupplyClient` | non-ai Provider Adapter | 提炼 HTTP、Session 和错误映射 |
| `UpstreamHttpQueryAdapter` | `FinanceProvider.query` | 保留 Query 语义，移除手机银行类型 |
| `BankingTaskAdapter` | `FinanceProvider.executeTask` | 保留任务协议，业务话术留在应用 |
| capability cards | 手机银行/Domain Pack | 不进入 Core |
| R0-R3 与部门映射 | customer-self-service 配置 | 不升格为通用枚举 |
| execution evidence map | `EvidenceReceiptV1` | 只提炼稳定公共字段 |

迁移方式采用行为保持：先包装现有实现，再逐步移动公共代码，不重写已验证的任务链。

## 8. 财富能力的第二阶段验证

从 Employee Agent 提取的不是 Runtime 代码，而是专业资产：

```text
wealth-manager Skill
财富 SOP
Context Requirements
Golden Cases
Judge Rubric
```

它们组成 `domain-packs/wealth`，通过同一 Finance Provider 获取客户、账户、持仓、产品和政策事实。

V1 优先使用一个最小 Java 财富参考助手验证 Domain Pack；现有 EA TypeScript Integration 在 Java 合同稳定后再接入，不作为首条链路的阻塞项。

## 9. 知识与安全如何处理

### 知识

V1 不建设 Knowledge Core。制度、产品说明和业务知识通过 Query Provider 返回，并保留 `sourceSystem` 与 `asOf`。财富 SOP 进入 Domain Pack。

### 安全

V1 不建设独立 Policy Engine。认证、权限、风险、适当性、幂等和交易结果由银行后端决定；Harness 保留后端决定并正确驱动确认或失败流程。

通用 Prompt Guard、WAF、IAM 和密钥管理继续使用现有平台能力。

## 10. 评测与质量

V1 使用三层验证：

1. Contract Test：四个合同严格解析；
2. Adapter Test：查询和任务协议映射正确；
3. Live Smoke：对部署环境完成一条查询和一条确认任务。

财富专业质量继续由独立 `finance-skill-eval` 验证：同一模型、同一数据快照下比较无 Skill、旧 Skill 和 Wealth Domain Pack。

## 11. V1 成功标准

1. 手机银行现有用户体验不变；
2. 余额或财富查询通过 Harness 返回真实模拟银行数据；
3. 一项模拟办理正确停在确认、确认后只提交一次；
4. Query、Task 和 Evidence 不再依赖手机银行内部类型；
5. Wealth Domain Pack 使用同一 Provider 完成一个专业任务；
6. 非原作者可以根据文档复跑。

## 12. V1 非目标

- 六类独立治理合同；
- Profile/Policy/Approval 引擎；
- TypeScript、Java、Python 三套 Core；
- 第二个 Runtime；
- 中央 Harness Service；
- Builder 和 Solution Manifest；
- 自动 Skill 优化与自动发布；
- 全量迁移手机银行和 EA；
- 生产审计认证。

## 13. 长期扩展

只有在 V1 被两个真实消费者证明后，再按实际需求增加：

- EA TypeScript Integration；
- `employee-assisted` / `customer-self-service` Profile；
- Knowledge、Policy 和 Approval 的独立合同；
- Builder 与 Solution Manifest；
- 第二个 Runtime；
- 真实银行 Provider。

这些方向由 ADR-0001 至 ADR-0003 保留，不影响 V1 快速迭代。
