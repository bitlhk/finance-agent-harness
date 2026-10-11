# 反诈风险只读能力（候选）

`fraud-risk-readonly@0.1.0-rc.1` 仅包含 `fraud.transfer-risk.assess`（QUERY）。
它独立于保留不变的 `risk-review-basics@0.1.0`，不包含余额或转账能力。

`assembly.v0.1.json` 是 Builder 通用组件准入声明，默认关联
`transfer-anti-fraud@0.1.1-rc.1`；Provider 为 `fraud-mining-reference@0.1.0-SNAPSHOT`。
宿主仍必须显式绑定能力、配置连接并授予 `fraud-risk:read`；声明不授予业务权限。

`rc.1` 表示供维护者评审及固定版本装配的发布候选，不能解释为已经发布或生产验收。
Provider Maven SNAPSHOT 与当前实现保持一致；可复现交付还必须锁定 Harness 提交与内容摘要，
不能仅凭 SNAPSHOT 名称保证不可变。正式资产接纳及发布由维护者决定。
