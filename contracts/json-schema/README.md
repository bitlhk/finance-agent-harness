# Canonical Contracts

本目录存放语言无关的 JSON Schema 2020-12 合同。

简化版 V1 只有四个：

```text
context/finance-context.v1.schema.json
capability/capability-request.v1.schema.json
capability/capability-result.v1.schema.json
evidence/evidence-receipt.v1.schema.json
```

约束：

- 对象默认拒绝未知字段；
- 所有合同显式版本化；
- 时间统一使用 RFC 3339；
- 每个 Schema 提供正向和负向 Fixture；
- 不引用 OpenJiuwen、手机银行或 non-ai-banking 私有类型。

Principal、Profile、Policy、Approval 和 Runtime Capability 暂不作为独立 V1 合同。只有真实链路证明四个合同无法表达时，才通过 ADR 增加。
