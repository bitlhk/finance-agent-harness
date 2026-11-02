# 反诈转账受控合同验证资产 V0.1

`anti-fraud-transfer@0.1.0` 是待评审的合成验证定义。Catalog 记录不表示发布、
实际执行通过或模型业务效果达标。消费方必须固定经过评审的 Harness 完整提交。

## 验证对象

`validation-cases.v1.json` 定义八类安排、动作和断言；它不包含运行回执、客户数据、
账户标识或业务风险阈值。风险能力是 QUERY，转账能力是 TASK。
银行的预检结果决定能否办理；模型不能覆盖银行拒绝或自行生成可信确认。

Builder 的受信 Host 使用既有 `HostTaskConfirmation`、`InstalledTaskController`
及持久化任务检查点执行案例。正常确认绑定原任务与冻结参数；未知结果查证原任务；
模拟器和检查点按 owner/build/session 隔离。模型输出中的任意回显不能作为执行证据。

## 消费与验收

本资产目前交付案例规范和离线结构门禁，尚未交付通用案例执行器。
Builder Java Host 接线必须逐案例执行并收集可信银行/Host 回执，才能报告运行验收结果。
现有 Java 行为可参考 `NonAiBankingTransferProviderTest` 与 `TaskRecoveryTest`，
这些已有测试的历史成绩不能代替本次现场 Candidate 验证。

离线门禁：在仓根执行
`python -m unittest discover -s engineering -p test_antifraud_validation_asset.py`。
它验证结构、覆盖、能力操作类型、Catalog 来源和摘要，不执行转账。
本资产不评估模型准确率、反诈召回、时延或真实资金安全；这些需独立数据与业务验收。
