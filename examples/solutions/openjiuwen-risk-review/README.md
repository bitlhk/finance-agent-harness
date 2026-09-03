# OpenJiuwen 转账风险审核助手

这个 Solution 演示一个 Agent 通过 Finance Harness 同时装配账户 Provider 和反诈 Provider。

`solution.yaml` 对每项能力显式指定 Provider。装配器会验证能力支持关系，并在 `solution-lock.json` 中记录实际绑定。风险规则保留在银行反诈系统，审核方法保留在 Domain Pack，Harness Core 不包含具体风控业务逻辑。
