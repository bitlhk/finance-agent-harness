# non-ai-banking 转账任务 Live Smoke

该烟测直接验证公共 `NonAiBankingProvider` 与部署中的模拟银行任务协议，不依赖大模型：

```text
涉诈收款人 -> preflight FAILED -> 不创建任务
普通收款人 -> NEED_CONFIRMATION -> confirm -> submit once -> COMPLETED
重复确认 -> 返回同一终态，不重复提交
```

显式设置环境变量后执行：

```bash
NON_AI_BANKING_TRANSFER_LIVE=true \
NON_AI_BANKING_BASE_URL=http://127.0.0.1:18088 \
NON_AI_BANKING_LOGIN_NAME='<login>' \
NON_AI_BANKING_LOGIN_SECRET='<secret>' \
mvn -pl providers/non-ai-banking -am \
  -Dtest=NonAiBankingTransferLiveSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

成功后在 `providers/non-ai-banking/target/live-smoke/transfer-task-receipts.json` 生成两份标准证据回执。
该测试会在 Simulator 中产生一笔 `0.01` 元模拟转账记录，因此默认不启用。
运行目标必须已经启用银行侧 `TransferRiskProvider`；未启用反诈的普通 Simulator 不能用于涉诈阻断验收。
