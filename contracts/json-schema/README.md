# Canonical Contracts

本目录将存放 Finance Agent Harness 的 JSON Schema 2020-12 公共合同。

V1 计划：

```text
principal/finance-principal.v1.schema.json
context/finance-context-pack.v1.schema.json
action/finance-action.v1.schema.json
policy/finance-policy-decision.v1.schema.json
approval/finance-approval-grant.v1.schema.json
evidence/finance-evidence-receipt.v1.schema.json
runtime/runtime-capability-profile.v1.schema.json
```

约束：

- 对象默认拒绝未知字段；
- 所有引用显式版本化；
- 时间统一使用 RFC 3339；
- 金额必须显式货币和精度；
- 需要摘要的对象使用 RFC 8785 JCS + SHA-256；
- 每个 Schema 同时提供正向和负向 Fixture；
- TypeScript 和 Java 必须通过同一 Conformance Vector。

首轮评审不在文档 PR 中提前冻结完整字段，避免未经应用验证形成过度设计。Walking Skeleton 将以最小真实场景逐项补齐。
