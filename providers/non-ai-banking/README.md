# non-ai-banking Provider

这是 Finance Harness V1 的首个参考 Provider Adapter。

当前能力：

- 自动创建并复用 Simulator 会话；
- 将 `account.balance.query` 映射到 `GET /api/v1/accounts`；
- 传递 request、trace、channel 和 app version；
- 将统一银行响应与结构化错误映射为 `CapabilityResultV1`；
- 支持生成 `EvidenceReceiptV1`。

它不包含 OpenJiuwen 或手机银行应用类型。确认任务链将在查询链验证后增量加入。

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
