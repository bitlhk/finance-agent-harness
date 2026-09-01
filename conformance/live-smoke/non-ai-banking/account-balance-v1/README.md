# non-ai-banking 账户查询 Live Smoke

执行时间：2026-09-01

目标环境：`http://116.204.36.83:8086`

源码审查基线：`wuxiaojingjing/non-ai-banking@e8f5122b5e18887fa8bc6a1c2a528038fde81e35`

能力：`account.balance.query`

结果：`COMPLETED`

业务来源：`account-core-simulator`

本目录只保存最小 Evidence Receipt，不保存账户号、余额或其他客户数据。部署环境没有暴露 commit SHA，因此源码审查基线不等同于已证明的部署身份。

复跑方式：

```bash
NON_AI_BANKING_LIVE=true \
NON_AI_BANKING_BASE_URL=http://116.204.36.83:8086 \
NON_AI_BANKING_LOGIN_NAME='<simulator-login>' \
NON_AI_BANKING_LOGIN_SECRET='<simulator-secret>' \
mvn -pl providers/non-ai-banking -am \
  -Dtest=NonAiBankingLiveSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
