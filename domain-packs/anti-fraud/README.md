# 转账反诈 Domain Pack

该 Pack 定义转账风险评估能力的业务语义。实际风险名单、规则、模型、阈值和处置策略由银行风险系统或项目配置提供，不进入 Harness Core。

最小链路：

```text
手机银行 / Agent
  -> 转账预检
  -> fraud.transfer-risk.assess
  -> ALLOW / BLOCK / FAILED
  -> 继续、阻断或转人工复核
```

转账提交前应重新确认风险结论仍然有效，避免只在预检阶段检查后被绕过。
