# non-ai-banking Provider

这是 Finance Harness V1 的首个参考 Provider Adapter。

当前能力：

- 自动创建并复用 Simulator 会话；
- 将 `account.balance.query` 映射到 `GET /api/v1/accounts`；
- 将 `channel.preferences.update` 映射到 Simulator 的
  `preflight -> task create -> confirm -> submit` 原生任务协议；
- 将 `transfer.internal.create` 映射到相同公共任务合同，并保留银行侧反诈预检；
- 首次调用返回 `NEED_CONFIRMATION`，确认后提交，重复确认复用已完成任务；
- 转账提交超时返回 `UNKNOWN`，后续只执行状态查证，不盲目重复提交；
- 财富上下文并行读取必需财富聚合与可选风险测评，支持 `READY/PARTIAL`；
- 传递 request、trace、channel 和 app version；
- 将统一银行响应与结构化错误映射为 `CapabilityResultV1`；
- 支持生成 `EvidenceReceiptV1`。

它不包含 OpenJiuwen 或手机银行应用类型，也不实现银行反诈规则。当前按能力映射银行原生协议，
不抽象通用任务 DSL。

部署环境 Live Smoke：

```bash
NON_AI_BANKING_LIVE=true \
NON_AI_BANKING_BASE_URL=http://116.204.36.83:8086 \
NON_AI_BANKING_LOGIN_NAME='<simulator-login>' \
NON_AI_BANKING_LOGIN_SECRET='<simulator-secret>' \
mvn -pl providers/non-ai-banking -am \
  -Dtest=NonAiBankingLiveSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

确认任务 Live Smoke：

```bash
NON_AI_BANKING_TASK_LIVE=true \
NON_AI_BANKING_BASE_URL=http://116.204.36.83:8086 \
NON_AI_BANKING_LOGIN_NAME='<simulator-login>' \
NON_AI_BANKING_LOGIN_SECRET='<simulator-secret>' \
mvn -pl providers/non-ai-banking -am \
  -Dtest=NonAiBankingTaskLiveSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

反诈与转账任务 Live Smoke 见
[`conformance/live-smoke/non-ai-banking/transfer-task-v1`](../../conformance/live-smoke/non-ai-banking/transfer-task-v1/README.md)。
