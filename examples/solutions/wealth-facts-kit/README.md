# 金融 Agent 接入套件 V0.1：财富只读切片

状态：候选 V0.1。真实 OpenJiuwen Java AgentLoop、工具安装与 Provider HTTP 适配可复跑；模型是本地脚本响应，数据是虚构 Fixture。不是生产服务，不证明真实授权、模型质量或银行替换已验收。

## 快速运行

环境：JDK 21、Maven 3.8+、Python 3.10+、Bash。本机需允许随机 loopback 端口；首次构建需联网下载 Maven/Python 依赖，无需 API Key，不访问银行/EA 生产环境。CI 使用 JDK 21/Python 3.12。

在 Git 工作副本根目录执行：

```bash
python3 -m venv .venv
.venv/bin/pip install -r engineering/requirements-conformance.txt
.venv/bin/python engineering/verify_contracts.py
mvn -B -ntp verify
bash examples/solutions/wealth-facts-kit/run-controlled.sh
.venv/bin/python engineering/verify_wealth_kit.py
```

脚本先构建并测试依赖模块，然后在独立 JVM 中运行五份配置；任一步失败即非零退出，不跳过关键模块。全部源服务为进程内临时 HTTP Server，完成后关闭，不占用固定服务端口。运行单份配置：

```bash
bash examples/solutions/wealth-facts-kit/run-controlled.sh examples/solutions/wealth-facts-kit/employee-ea.json
```

参数是仓库相对路径。完整报告校验要求先运行默认五份配置，缺报告会失败。第一次下载依赖时间不代表 Agent 延迟。

| 配置 | 源与身份 | 要验证什么 |
| --- | --- | --- |
| customer-bank.json | CUSTOMER + 真实银行 Adapter + 本地模拟 HTTP | 资产/风险事实、会话主体检查、证据 |
| customer-snapshot.json | 同 CUSTOMER + 受控 Snapshot Adapter | 仅改 binding，原 Skill 与消费者不变 |
| employee-ea.json | EMPLOYEE + 真实 EA Adapter + 本地模拟 HTTP | adoption 身份绑定、同一事实消费者、去掉员工私有字段 |
| customer-denied.json / employee-denied.json | 两种源，宿主无实授 | 原生调用失败、业务数据读取 0 次、写入 0 次 |

更多负例在 `WealthKitTest`：过期授权、错主体、模型填写客户选择器、坏响应、缺币种依据、缺测评及真实零值。修改本地配置的 `scenario` 可以单独复跑对应案例；`LIVE` 模式明确拒绝，不能改一个标志便获得真实权限。

## 实际调用链

```text
solution.yaml + 场景配置
  -> OpenJiuwenSolutionAssembler / Domain Pack / Tool
  -> ReActAgent -> 原生工具调用
  -> Bridge 实授校验 -> BoundFinanceProvider 当前宿主 Grant
  -> WealthFactsProvider -> 源 BoundFinanceProvider
  -> 银行 Session 或 EA adoption 身份检查 -> 原源能力
  -> 确定性映射 -> WealthFactsV1 -> 同一个只读消费者
  -> 投影后的工具结果 -> Agent -> 结果、Trace、Lock
```

客户/员工原能力与原响应保留，新 `wealth.facts.describe` 是显式安装的只读入口。共享消费者只描述资产范围、风险记录和缺口，不做推荐、适当性或购买。员工私有字段在映射白名单处去除后才进入模型，不能靠 Prompt 保密。

## 看结果与证据

每次运行写入 `target/runs/<BINDING>-<SCENARIO>/`：

- `report.json`：真实原生工具结果、Bridge Evidence、读取/写入计数、实际注册回调、运行耗时及未验收项。
- 同一报告记录源 Provider 的实际 Descriptor、显式映射依据、构建输入摘要及本模板未预先固定发布摘要的状态。
- `solution-lock.json`：安装能力、Skill/Pack 来源摘要、包装器和内层 Provider 的本地运行制品摘要。它不证明远端银行代码版本或行为。
- `controlled-model-trace.json`：两次脚本模型收到的原生消息，报告绑定其 SHA-256；只含受控样例。

正常结果应为 `status=COMPLETED`，金额 `1000000.12345678 CNY`，适当性 `NOT_EVALUATED`。拒绝场景不能有事实，`sourceReadCount=0`。执行完成不等于事实齐全，缺事实会有独立 `gaps` 和 `PARTIAL_FOR_DESCRIPTION`。

`verify_wealth_kit.py` 重新校验文件摘要、原生事件、共同 Schema、两种同身份 Provider 的业务输出一致及拒绝计数。它不把这五次实验变成真实数据验收。报告目录复跑会覆盖，保留对比时应归档整份目录，不能只截取结果文字。

SDK 0.1.13 的同步路径把工具 Map 序列化为文本，而非 JSON；结构化结果从 `AFTER_TOOL_CALL` 的原生 `ToolCallInputs` 取得，并核对模型实际收到的文本。`AFTER_INVOKE` 注册回调观察到的 `contextEvent` 可能仍为 `AFTER_MODEL_CALL`，报告同时保留注册 hook 与 SDK 实际值，不虚构新事件。本例不用 SSE，`streamCheck=NOT_APPLICABLE_NON_STREAMING`，不据此宣称流式已验证。

## 开发者要配置、编写什么

| 接入点 | 专属工作 | 可复用机制 |
| --- | --- | --- |
| 场景/Agent | 选择共享 Pack，配置模型端点和会话 | 同一个 Assembler、原生工具安装/卸载、消费者 |
| 可信宿主 | 从权威身份系统解析 principal、subject、purpose、session、当前 scopes、失效时间 | `BoundFinanceProvider.Grant` 每次调用/恢复重查；权限不够先拒绝 |
| 银行来源 | 配置只读账号，核实 Session.customerRef 与宿主映射 | `NonAiBankingProvider` 不把 loginName 当 principalId |
| EA 来源 | 注入当前 adoption-owner 的可信对应和短期 Token | `EmployeeAgentIdentityBinding` 与返回客户检查 |
| 语义依据 | 填来源命名空间、接口版本、资产范围、币种、风险体系、日期时区及依据引用 | `WealthSourceSemantics`、十进制映射、缺失/到期/冲突处理 |
| 新来源 | 实现/复用源 QUERY Adapter；不同源形状写独立确定性 mapper | 共同合同、消费者、共享 Skill 和同一组案例不改 |

最小宿主装配可参考 `WealthKitApplication.run()`，但必须替换其中 `fixture:host-authority`、固定客户、固定授权和脚本模型。它们是测试源，不是可复制到生产的身份认证实现。

以银行源为例，源 `BoundFinanceProvider` 构造需要三项：源 Provider、**宿主当前实授 Supplier**、`Map.of("wealth.customer-context.query", Set.of("wealth:customer:read"))`。外层共同能力需要 `wealth:facts:read`，实际授权须同时覆盖源范围，不能从这张需求表生成实授。`InvocationDefaults.currentScopes` 同样读取当前授权，而不是永久缓存初始 scopes。

EA 还须给五参数 `EmployeeAgentWealthProvider.Config` 注入 `Supplier<EmployeeAgentIdentityBinding>`，由已验证的宿主绑定 principal 与 runtime/agent/adoption。只有 Token、用户名相同或 `scopeVerified=true` 都不能代替这一步。原 EA 服务仍负责客户归属与源数据权限。

真实接入的必需材料、责任人和验收入口见 [实施记录](../../../docs/financial-agent-kit-v01-worklog.md)。在 A3 身份/实授/错主体负例通过前，不连接真实客户数据。EA 的币种、估值时点和风险体系未证实时保持缺口，不能默认 CNY 或借当前样例补齐。

## 兼容与安全边界

- 四个 Core JSON 外壳不变；财富合同独立为 `wealth-facts.v0.1`，不向原严格响应悄悄加字段。
- 旧 EA 四参数 Config 保留源码兼容，但未注入可信身份会 fail-closed。旧示例里的历史真实 Smoke 不能代表新授权接线已完成。
- 能力包的 requiredScopes 不再赋成实授。旧宿主需提供当前 grant/scope 来源；仅修改 Profile 标签不会放行。
- 源 JSON 小数现在精确读取为 BigDecimal，JSON 数值形状不变；若旧 Java 消费者把 Number 强转 Double，须改为明确十进制读取。
- 模型 `confirmationText` 不是可信确认。宿主通过 `Installation.confirmationBinding/confirm` 接入真实 UI 事件；无宿主接线则办理保持待确认，查询仍可用。
- 补参只限 Adapter 明示的本地收集阶段和列出的缺字段。冻结参数不能原地修改；拒绝授权不等于银行取消。PROCESSING/UNKNOWN 保留原引用先查证，不自动新建。
- 复用文件 Checkpoint 的单机实现。默认 Memory 不支持重启；需显式注入文件 Store 和稳定宿主 session/agent 身份。目录只可由可信宿主访问，保存了办理参数；格式、终态留存/清理由宿主管理。未验证多进程、多节点及长期格式迁移。
- 无声明 digest 的模板处于 `CONTENT_OBSERVED_NOT_PINNED` 使用模式：Lock 记录本次内容，不是与发布基准校验通过；固定发布资产须填声明摘要。已有摘要不匹配会在安装前拒绝。

## 验收界限

共同语义、原生调用边界及受控同 Profile 换源可以自动验证；EA 和银行跨角色演示不是同条件 Provider 替换证明。真实授权只读、EA READY、真实第二 Provider、真实模型输出质量、非原作者独立接入均待单独验收。本轮不提供提效百分比，CLI 耗时不等于人工接入耗时。
