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

Assembler 在安装前读取 `BaseAgent` 实际 CodeSource 的 Maven 元数据和 Integration
制品内的构建元数据。Maven-only 输入核对 `templateRef/integrationRef` 完整坐标；
Builder 源码引用需显式补 `runtime.resolvedArtifacts`，原来源字段仍保留，不能将
Host Bridge 或 EA Host Integration 来源误当 SDK/Assembler 制品。缺解析会明确报迁移错误；
伪造的原 Maven 引用或解析坐标仍拒绝，未降低版本门禁。
不匹配报 `RUNTIME_REFERENCE_MISMATCH`；元数据无法观察则拒绝，不用声明补证据。
Lock 原 Runtime 字段保留输入语义，带解析的输入新增 `runtime.resolvedArtifacts`（实测坐标）；
Maven-only 旧 Lock 不增加该字段。`assets` 包含 `RUNTIME_TEMPLATE/RUNTIME_INTEGRATION`，
保存实测版本与类目录/JAR 摘要。旧 Lock 不具备这一保证，须重新装配。
SNAPSHOT 同版本不保证同内容，复现必须比较制品摘要；不代表远端服务被锁定。
源码引用的内容不由此 Assembler 获取/核验，不能把保留出处误称源码已安装。
详细迁移和额外 Host 依赖见 [Builder 交接](../../docs/builder-handoff-wealth-v1.md)。

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

### Provider 独立调用上下文

联合安装仍使用一次 `prepare` 和一份完整 Solution Lock，不拆成多个 Agent 或局部 Lock。
Host 可使用 `installWithProviderContexts(prepared, agent, contexts, simulated, checkpoints)`，
为每个实际选中的 Provider 提供 `ProviderInvocation(defaults, hostSessionRef)`。
`contexts` 必须与已解析的 Provider 集合精确一致；缺少或多余项在 SDK 注册前拒绝。
该映射只能来自可信 Host，不能反序列化浏览器或模型提供的身份、权限或会话。

纯查询调用可使用 `prepared.openQueriesWithProviderContexts(contexts, simulated)`；
该入口仍拒绝任何 TASK。原生工具安装继续按 Capability Pack 的 QUERY/TASK 类型分流，
TASK 保留原来的可信人工确认、检查点和幂等处理，不因联合知识查询而改成 QUERY。
旧 `install` / `openQueries` 接口与旧 TASK 检查点身份保持兼容。
新增按 Provider 的 TASK 检查点作用域包含各自的身份、对象、用途、权限及 Host 会话，
不复用另一种上下文的待确认任务。

这里仅隔离调用上下文，不授予权限，也不处理跨 Provider 数据外发许可。
联合业务 Host 仍须独立检查每个来源的当前授权、检索入参的数据边界和历史查看权限。
安装测试不是实际采集器资格或普通工作台 Build/模型联合验收。

### Checkpoint 数值与补参兼容

文件 Store 单独启用十进制精确读取，浮点 JSON 数字读为 `BigDecimal`；不改变全局
`HarnessJson`。Map/嵌套列表的参数指纹继续按键排序、数字去尾零，不改旧指纹算法；
`10`、`10.00` 与 `10.0` 按同一数值比较。历史 Double 已舍入并写回的值不能自动恢复，
不得据此重提未知银行任务，仍按原 taskRef 查证。
恢复、确认及终态缓存覆盖高精度回归；范围为同 JVM 重建控制器/Store，非多进程验收。

Adapter 的 `InputCollectionProvider` 明确声明未尝试银行写入时，只允许补列出的
缺失键、null 或空白字符串；非空值不能改。冻结、待确认及 UNKNOWN/PROCESSING
沿用原限制，无 taskRef 本身不是可补参证明。

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
