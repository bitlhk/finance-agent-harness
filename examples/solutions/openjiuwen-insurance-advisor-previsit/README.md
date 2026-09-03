# OpenJiuwen 保险顾问访前助手

这个装配样例证明 Finance Harness 的 Core、Provider SPI 和 OpenJiuwen Integration 并未写死财富业务。Builder 只需替换三类资产即可形成保险顾问候选：

- `insurance-employee-assisted`：车险续保和保障回顾的专业判断方法；
- `insurance-advisor-previsit`：向 Runtime 发布的标准能力与工具说明；
- `employee-agent-insurance-reference`：把标准能力映射到 EA `get_customer_profile`。

V1 仅覆盖只读访前诊断，不包含正式产品、报价、核保、投保、理赔或写操作。保险规则留在 Domain Pack 和银行系统，Harness Core 不包含保险业务规则。
