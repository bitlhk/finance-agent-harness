# Wealth Domain Pack

财富 Domain Pack 保存可复用的财富专业 SOP，不进入 Finance Harness Core，也不替代银行自己的适当性和产品准入规则。

`customer-self-service` 包服务手机银行客户，验证“资产事实 -> 专业诊断 -> 关键问题 -> 下一步”的最小闭环。

`employee-assisted` 包服务财富经理，复用 EA `wealth-manager-assistant@2.1.5` 已验证的访前诊断方法，验证“授权客户事实 -> 触发事件 -> 核心矛盾 -> 关键问题 -> 面谈目标”。

应用可以安装或绑定对应 Skill；Provider 只提供已核验上下文，不生成推荐结论。不同应用可以使用不同 Provider 实现，不要求把面客客户查询和员工代客查询强行接到同一个银行接口。
