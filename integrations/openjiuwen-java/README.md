# OpenJiuwen Java Integration

该模块把 OpenJiuwen 宿主的一次能力调用转换成 Finance Harness 的统一请求、结果和 Evidence。

它不依赖手机银行、Employee Agent 或银行系统类型，也不接管 Agent Loop。应用只需保留一层很薄的 Host Adapter，把自己的任务对象映射为 `OpenJiuwenInvocationV1`，再把 `OpenJiuwenExecutionV1` 映射回宿主结果。

## 安装

当前 `0.1.0-SNAPSHOT` 可从本仓安装到本机 Maven 仓库：

```bash
mvn install
```

消费端依赖：

```xml
<dependency>
  <groupId>io.github.bitlhk.finance</groupId>
  <artifactId>finance-harness-openjiuwen-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 接入方式

```java
OpenJiuwenFinanceBridge bridge = new OpenJiuwenFinanceBridge(
        financeProvider,
        "bank-provider",
        invocationDefaults,
        supportedCapabilities,
        false);

OpenJiuwenExecutionV1 output = bridge.query(invocation);
// 有副作用任务使用 bridge.executeTask(invocation)
```

Bridge 统一处理：

- `FinanceContextV1` 和 `CapabilityRequestV1` 构造；
- Query 与 Task 状态映射；
- 确认、续办 Checkpoint 和幂等键；
- `EvidenceReceiptV1` 生成与运行证据投影；
- Provider 异常的业务化降级。

应用仍负责：

- OpenJiuwen 原生任务类型与两个 Integration Record 的转换；
- Principal、Channel、Purpose 和 Scope 的宿主配置；
- `FinanceProvider` 的选择和装配；
- UI、会话、模型和 Agent 生命周期。

## 验证

```bash
mvn -pl integrations/openjiuwen-java -am test
```

手机银行是当前第一个真实消费者，其 Host Adapter 只保留本地类型映射。
