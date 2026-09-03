# OpenJiuwen Java Integration

该模块把 OpenJiuwen 宿主的一次能力调用转换成 Finance Harness 的统一请求、结果和 Evidence。

它不依赖手机银行、Employee Agent 或银行系统类型，也不接管 Agent Loop。应用可以保留一层很薄的 Host Adapter，把自己的任务对象映射为 `OpenJiuwenInvocationV1`，再把 `OpenJiuwenExecutionV1` 映射回宿主结果；新建 OpenJiuwen Agent 也可以通过 Capability Pack 直接安装原生工具。

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

模块当前按 OpenJiuwen Java `0.1.13` 编译，与手机银行首个消费者保持一致。OpenJiuwen 依赖被标记为 Maven optional，应用仍需显式选择自己的 Runtime 版本；升级 Runtime 时必须运行本模块 Conformance。

## Bridge 接入

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

## Capability Pack 装配

主要入口：

- `FinanceCapabilityPackLoader`：读取 `pack.yaml`；
- `OpenJiuwenCapabilityInstaller`：注册/卸载 OpenJiuwen 原生 `ToolCard`、`LocalFunction` 和 Pack 使用提示；
- `OpenJiuwenSolutionAssembler`：从最小 `solution.yaml` 解析 Pack 引用并生成 `solution-lock.json`。

Task 工具采用两阶段调用：首次调用由 Provider 做预检并返回 `NEED_USER`，Integration 返回 `financeTaskHandle`；用户明确确认后，Runtime 使用该句柄和 `confirmationText` 继续同一 Provider 任务与幂等键。

完整的干净 Runtime 样例位于：

```text
examples/solutions/openjiuwen-retail-banking/
```

## Domain Pack 装配

`FinanceDomainPackLoader` 读取 Domain Pack 的 `pack.yaml` 和引用的 `SKILL.md`；`OpenJiuwenDomainPackInstaller` 将专业 SOP 作为命名 Prompt Section 安装到 Agent，并在安装回执中记录 Skill 内容摘要。`OpenJiuwenSolutionAssembler` 会同时校验：Domain Pack 声明的每项金融能力，都必须由当前 Solution 引用的 Capability Pack 提供。

Domain Pack 只回答“应该如何专业判断”，Capability Pack 只回答“Runtime 可以调用什么工具”，两者不互相猜测。完整的员工辅助财富样例位于：

```text
examples/solutions/openjiuwen-wealth-manager-previsit/
```

## 验证

```bash
mvn -pl integrations/openjiuwen-java -am test
```

测试覆盖 Bridge 映射、原生工具安装、账户查询、转账确认续办、锁文件和卸载。手机银行是当前第一个真实消费者，其 Host Adapter 只保留本地类型映射。
