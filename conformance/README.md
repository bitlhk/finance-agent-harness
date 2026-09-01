# Conformance

简化版 V1 的验证范围：

- 四个 JSON Schema 的正负 Fixture；
- Java DTO 与 Schema 一致；
- Query Adapter 映射；
- Task Adapter 的确认、幂等和错误状态映射；
- 部署环境 Live Smoke；
- Evidence 必要字段完整性。

V1 不要求 TypeScript/Java 双实现、跨 Runtime、Profile 投影或独立 Approval 重放测试。对应验证在相关能力真正进入实现时增加。

首批正反样例位于 `fixtures/v1`，由 Java Core 测试直接读取，避免文档样例与实际类型漂移。
