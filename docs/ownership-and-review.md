# 职责与快速评审规则

## 1. 评审目的

本轮只确认简化版 V1 能否开工，不评审长期蓝图是否完美。

评审人只回答：

1. 是否存在阻止手机银行 Query/Task 提炼的 Blocker？
2. 四个合同是否包含了不应进入 Core 的应用或银行私有字段？
3. 是否可以在现有部署环境重复验证？

评审窗口不超过 1 个工作日。

## 2. V1 Ownership

| Owner | 所有权 | 首个交付 |
|---|---|---|
| Architecture / Contract | 四个 Schema、边界、ADR 和验收 | Contract Skeleton |
| Java Core | DTO、Provider SPI、Evidence | Java Core Tests |
| Mobile / Integration | OpenJiuwen Java 映射、入口体验不变 | Balance Query Smoke |
| Provider | non-ai-banking Query/Task 映射 | Query + Confirm Task |
| Domain & Eval | Wealth Pack、Golden Case、专业对照 | Wealth Reuse Result |

人数不足时可以一人兼任多个 Owner。

Knowledge、Security 和 Builder 同事可以 Review 相关边界，但 V1 不要求他们各自建设独立子系统。

## 3. 公共能力准入

首版代码进入 Core 必须满足：

- 不依赖手机银行 Agent ID、UI 或对话话术；
- 不依赖 OpenJiuwen 私有类型；
- 不包含 non-ai-banking 地址和凭证；
- 有一个真实消费者和对应测试；
- 能说明第二个消费者的合理使用方式。

V1 允许先有手机银行一个真实消费者；Wealth Domain Pack 负责在第二阶段证明复用。稳定公共合同最终仍应由两个消费者验证。

## 4. PR 最小要求

涉及合同或行为变更的 PR 应包含：

- Schema 或接口变更；
- 正向 Fixture；
- 至少一个负向 Fixture；
- Adapter 或调用示例；
- 验证结果；
- 必要的兼容性说明。

## 5. 决策原则

> 从真实链路提炼，不从长期架构图生成代码。

如果新抽象没有被当前 Query、Task 或 Wealth 场景使用，不进入 V1。
