# Finance Profiles

Profile 是长期方向，用于表达员工辅助、客户自服务等不同主体关系和默认 Context 投影。

简化版 V1 不实现 Profile Engine。手机银行所需的客户身份、渠道、Purpose 和认证级别直接进入 `FinanceContextV1`；员工辅助 Profile 在 EA 直接接入时再提炼。

Profile 不是实时授权权威来源，最终权限和业务准入仍来自银行系统。
