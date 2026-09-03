# Conformance

简化版 V1 的验证范围：

- 运行合同与构建合同的 JSON Fixture；
- Java DTO 与 Schema 一致；
- Provider 描述、能力覆盖和统一结果行为；
- OpenJiuwen Bridge 的 Query 映射；
- OpenJiuwen Bridge 的确认、续办、幂等和错误状态映射；
- Consumer Host Adapter 的本地类型映射；
- 部署环境 Live Smoke；
- Evidence 必要字段完整性。

V1 不要求 TypeScript/Java 双实现、跨 Runtime、Profile 投影或独立 Approval 重放测试。对应验证在相关能力真正进入实现时增加。

首批正反样例位于 `fixtures/v1`，由 Java Core 测试直接读取，避免文档样例与实际类型漂移。

## V1 验证矩阵

```text
Canonical Contract
  -> packages/java/core 测试 JSON Schema、DTO 和 Fixture

Runtime Integration
  -> integrations/openjiuwen-java 测试 Query、Task、Checkpoint 和 Evidence

Provider
  -> ProviderConformanceKit 测试描述、请求标识、状态和续办结构
  -> 各 providers/* 测试银行协议和错误转换

Consumer
  -> 手机银行测试 Host 类型映射、路由与会话续办

Live Smoke
  -> 账户查询、财富诊断、渠道设置确认三条完整链路
```

仓内执行：

```bash
mvn test
```

Consumer 测试仍在对应应用仓执行。Conformance 验证公共合同和可观察行为，不要求应用使用同一种内部类型。

银行 Provider 可在自己的测试中直接调用：

```java
ProviderConformanceKit.verify(provider, cases);
```

该 Kit 只检查 Harness 接口一致性，不判断银行自己的风控或产品规则是否正确。
