# V1 范围与快速实施路线

状态：`In Progress · D3-D4 Complete`

## 1. V1 目标

用 7 至 10 个工作日完成一个可以运行、演示和继续提炼的 Finance Agent Harness V1。

只证明三件事：

1. 手机银行通过 Java Harness 查询 `non-ai-banking`；
2. 手机银行通过同一 Harness 完成一次需要确认的模拟办理；
3. 从 EA 提取的 Wealth Domain Pack 可以消费同一 Core 合同，并通过员工侧 Provider 形成专业财富访前建议。

## 2. V1 范围

### Runtime 与应用

- Runtime：OpenJiuwen Java；
- 首个消费者：Mobile Banking；
- 第二消费者：最小财富参考助手；
- EA TypeScript 直接接入：Java 合同稳定后增量推进。

### Core

- `FinanceContextV1`；
- `CapabilityRequestV1`；
- `CapabilityResultV1`；
- `EvidenceReceiptV1`；
- `FinanceProvider.query`；
- `FinanceProvider.executeTask`。

### Integration 与 Provider

- OpenJiuwen Java Integration；
- non-ai-banking Provider Adapter；
- Employee Agent 财富访前 Reference Provider；
- 一条 Query；
- 一条确认 Task。

### Domain

- Wealth Domain Pack 最小版；
- 一份 Skill；
- 三份核心 SOP；
- 一组固定财富案例与评测引用。

## 3. 明确不做

- 独立 Harness 服务；
- 独立 Policy 或 Approval Engine；
- TS/Python Core；
- Builder、Manifest、Lock、Preflight 平台；
- 自动优化和自动发布；
- 完整 Knowledge 或 Security 产品；
- 全量替换手机银行现有执行框架；
- 保险、投研、风控 Domain Pack。

## 4. 快速节奏

### D1-D2：合同与包装边界

交付：

- 四个 JSON Schema；
- Java DTO 与 `FinanceProvider` SPI；
- 手机银行现有对象到 Harness 对象的映射表；
- 首批 Contract Fixture。

验收：合同不引用 OpenJiuwen、手机银行 Agent ID 或 non-ai HTTP 类型。

### D3-D4：只读查询

状态：`Complete`

交付：

- `non-ai-banking` Provider Adapter；
- 将现有 `UpstreamHttpQueryAdapter` 包装到 `query`；
- `account.balance.query` Live Smoke；
- Evidence Receipt。

验收：返回模拟银行账户事实，带来源、数据时间、requestId 和 traceId。

### D5-D6：确认办理

交付：

- 将现有 `BankingTaskAdapter` 包装到 `executeTask`；
- 一条 R2 模拟任务；
- 确认前不提交，确认后只提交一次；
- 状态和错误映射测试。

优先选择预约或渠道偏好，不使用资金交易作为第一条办理用例。

### D7-D8：财富能力

交付：

- 从 EA 复制并清理财富 Skill/SOP 专业资产；
- `domain-packs/wealth`；
- 最小财富参考助手；
- 客户、资产和产品查询映射。
- 员工辅助语义能力到 EA 财富访前 MCP 的映射。

验收：面客侧输出客户约束、组合方向和下一步；员工侧输出访前核心矛盾、关键问题和面谈目标，而不是只复述客户资料。

### D9-D10：复跑与演示

交付：

- Contract、Adapter、Live Smoke 汇总；
- `finance-skill-eval` 财富对照结果引用；
- 演示脚本；
- V1 复盘和后续差距清单。

## 5. Definition of Done

1. 四个合同有严格 Schema、Java 类型和正反 Fixture；
2. Query 与 Task Adapter 定向测试通过；
3. 部署环境只读链路可重复运行；
4. 模拟办理确认链路无重复提交；
5. Evidence 能说明调用能力、Provider、来源和最终状态；
6. Wealth Domain Pack 至少完成一个专业财富任务；
7. 手机银行原有入口和体验不变；
8. 文档没有把 Simulator 表述为生产系统。

## 6. 完成 V1 后再决定

- 是否将现有 EA 直接接入 Harness；
- 是否提升 Profile、Policy、Approval 为独立合同；
- 是否让 Builder 消费 Domain Pack 和 Provider Contract；
- 是否增加保险作为第二个 Domain Pack；
- 是否开始 V1.2 Solution Composition。

决策依据是 V1 的真实差距，不根据长期架构图提前开发。
