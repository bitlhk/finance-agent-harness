# 手机银行到 Finance Harness V1 的映射

状态：`Baseline Mapped`

参考分支：`mobile-banking-3.0@sync/20260901-local`

参考提交：`845dd26e72ae41119ffeaa24426f54d2bf9cf037`

## 1. 原则

首轮接入采用包装，不重写已经通过测试和部署验证的手机银行执行链。

Harness 提炼公共金融能力边界；手机银行继续拥有 Agent 编排、卡片、交互文案和页面体验；`non-ai-banking` 继续拥有模拟业务规则、任务状态和数据事实。

## 2. 对象映射

| 手机银行现有对象 | Harness V1 对象 | 映射说明 |
|---|---|---|
| 登录会话、调用上下文 | `FinanceContextV1` | 映射 request、trace、客户主体、渠道、用途、认证等级和 scope |
| `TaskEnvelope.capabilityId` | `CapabilityRequestV1.capabilityId` | 保留能力标识，不携带 Agent ID |
| `TaskEnvelope.arguments` | `CapabilityRequestV1.parameters` | 只放业务参数，过滤编排控制字段 |
| `TaskEnvelope.taskId` | `CapabilityRequestV1.taskRef` | 仅任务续跑时使用 |
| Adapter checkpoint | `CapabilityRequestV1.continuation` | 作为 Provider 拥有的不透明续跑状态传递 |
| 用户确认文本 | `CapabilityRequestV1.confirmation` | 显式记录确认内容和时间 |
| `ExecutionResult.status` | `CapabilityResultV1.status` | 统一映射完成、补充信息、确认、处理中和失败 |
| `ExecutionResult.content/data` | `CapabilityResultV1.content/data` | 保留可展示说明和结构化业务结果 |
| `ExecutionResult.question` | `content + nextActions` | 由 Integration 转成下一步交互，不进入 Core 专用字段 |
| `ExecutionResult.checkpoint` | `CapabilityResultV1.task.continuation` | 不解释银行内部任务字段 |
| `executionEvidence` 与上游响应元数据 | `EvidenceReceiptV1` | 形成独立、最小的调用回执 |

## 3. 代码职责映射

### 北向：OpenJiuwen Integration

负责：

- 将 OpenJiuwen 的任务调用转换为 `CapabilityRequestV1`；
- 将 `CapabilityResultV1` 转回手机银行的 `ExecutionResult`；
- 维持 Agent Loop、卡片渲染、追问和恢复体验；
- 不实现银行查询和办理规则。

### 南向：non-ai-banking Provider

现有实现的首轮映射：

| 当前类 | V1 处理方式 |
|---|---|
| `UpstreamSupplyClient` | 复用为 Provider 内部的 HTTP、会话和错误处理客户端 |
| `UpstreamHttpQueryAdapter` | 包装到 `FinanceProvider.query` |
| `BankingTaskAdapter` | 包装到 `FinanceProvider.executeTask` |

Provider 负责调用查询、preflight、task create、confirm、submit、cancel、reconcile，并映射上游错误。

## 4. 保留在手机银行中的内容

以下内容不是 Harness Core：

- Agent ID、路由和多 Agent 编排；
- OpenJiuwen 类型；
- 卡片和 UI 资源；
- 面向客户的产品文案；
- `R0-R3` 等当前应用风险展示或路由策略；
- 手机银行目录结构与部署配置。

## 5. 首轮接入顺序

1. 用适配层构造 `CapabilityRequestV1`；
2. 使用 `FinanceProvider.query` 跑通 `account.balance.query`；
3. 比较接入前后 `ExecutionResult` 的业务数据和用户文案；
4. 再包装一条需要确认的 R2 任务；
5. 生成 `EvidenceReceiptV1`；
6. 通过后再决定哪些手机银行代码迁入 Harness 仓。

## 6. 不确定项

部署环境的精确源码提交尚未由发布系统证明。`845dd26` 是当前远端最新同步分支和映射基线，正式接线前应由手机银行负责人确认部署版本，或在部署信息中补充 commit SHA。
