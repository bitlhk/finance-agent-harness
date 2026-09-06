# 渠道偏好确认任务 Live Smoke

验证日期：`2026-09-02`

验证链路：

```text
CapabilityRequestV1
-> NonAiBankingProvider
-> preflight
-> task create
-> NEED_CONFIRMATION
-> confirm
-> submit
-> COMPLETED
-> replay remains COMPLETED
```

本次使用当前渠道偏好作为提交值，只验证任务协议，不改变用户可见偏好。提交的 Evidence
只保留能力、Provider、来源、状态和任务标识，不包含登录信息或客户业务数据。

执行入口：

```text
NonAiBankingTaskLiveSmokeTest
FinanceHarnessTaskLiveSmokeTest
```
