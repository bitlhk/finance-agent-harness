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

Task 工具首次调用返回 `financeTaskHandle`。模型携带句柄继续调用只负责查询进度；包括“确认提交”在内的 `confirmationText` 均不构成授权。

宿主在展示原任务摘要时，读取并保存 `Installation.confirmationBinding(handle)`，绑定到当前认证用户的确认表单。收到该用户明确的批准事件后，调用 `Installation.confirm(handle, HostTaskConfirmation)`。事件有效期最多五分钟；金额、收款人、摘要或原任务改变后，旧绑定不可使用。用户拒绝时调用 `Installation.decline(handle)`，该操作关闭本地授权，不声称撤销已提交的银行任务。

```java
// In the authenticated host handler, using the binding captured when the form was shown.
Instant now = Instant.now();
var approval = new HostTaskConfirmation(userEventId, displayedBinding, now, now.plusSeconds(60));
var result = installation.confirm(handle, approval);
```

宿主必须校验用户、会话及原表单，再构造此对象。不要从模型 JSON 或未经验证的 HTTP 参数直接反序列化确认对象，也不要将此接口注册为模型工具。直接调用 Bridge 的宿主改用 `executeTask(invocation, approval)`；原有单参数方法只负责准备和观察任务。

需要重启恢复时，使用 Installer / Assembler 的新增重载，传入稳定的宿主会话 ID 和 `TaskCheckpointStore`。仓内 `FileTaskCheckpointStore` 是 Linux 单机参考实现，采用文件锁、原子写和受限权限；生产宿主可以用原有数据库实现同一存储接口。默认 `Memory` 仅适合开发，不提供进程重启恢复。关闭 Installation 不删除宿主保存的任务。

```java
var store = new FileTaskCheckpointStore(privateStateDirectory);
var solution = assembler.assemble(root, solutionFile, agent, providers, defaults,
        simulated, store, authenticatedSessionId);
var installation = solution.installationForCapability("transfer.internal.create");
```

确认调用会先持久化待查证状态，再进入银行 Provider。响应丢失或崩溃后沿原句柄、taskRef 和幂等键查证；并发或重复确认复用已记录的终态结果。未知句柄不再提示用户直接重建任务。源数据、存储权限、保存期限和多机部署锁由宿主负责。

装配时校验已声明的 `sha256:` 摘要。Domain Pack 使用目录树摘要，Capability Pack 使用 `pack.yaml` 摘要；本地 Profile、Evaluation 和 Provider 源码引用也会记录摘要，目录树排除 `target/build/logs/.git`。Provider 实际加载的类目录或 JAR 单独记录为 `PROVIDER_RUNTIME`。外部引用的摘要如果无法解析会拒绝装配；没有摘要的外部评测引用仍属于待验证输入，不能声称已经锁定。完整依赖、模型配置和业务输出合同将在下一阶段固化。

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
