# 组件开发支持声明 V0.1

本规范是资产接入收尾的候选扩展，不改变已发布的 Domain Pack、Capability Pack 或方法派生 V0.1 合同。是否已部署，以对应 Builder 发布记录为准。

## 开发者交什么

维护组件时，在 `metadata/development-support/<组件>/` 新建不可变的 JSON 声明，由 `metadata/development-support/index.v0.1.json` 以路径和 SHA-256 固定引用。索引只负责发现，不集中维护业务问法。现有参考：

- `account-balance-readonly/0.1.1.json`：余额默认案例、Provider 正常与拒绝路径的定向命令。
- `cashflow-customer-self-service/0.1.1.json`：原收支方法的两个默认案例、业务结构 fixtures 与定向命令。
- `wealth-shared-facts/0.1.1.json`：财富方法默认案例，以及准备、隔离缓存、受控运行、报告复核四步说明；旧 `0.1.0.json` 保留。

字段职责：

| 字段 | 内容和约束 |
| --- | --- |
| `subject` | 被支持组件的类型、ID、版本、来源和内容摘要；不是声明文件自身的摘要 |
| `scope` | 能力及可选 Runtime、Integrator、Profile、Host、测试数据条件 |
| `methodCases` | 已有 `name/query/expectedTerms` 结构；用于复制到新方法草稿 |
| `caseRefs` | 其他案例的路径、原格式和摘要；不会将 smoke 的 `expectedBehavior` 猜成关键词 |
| `commands` | 固定源码提交、工作目录和开发验证命令，只供查看；不是可执行上传脚本 |

先按真实内容计算 subject 和案例摘要，再计算声明文件摘要，最后更新索引引用。声明放在原包之外，不在不可变包旁补文件，也不让包摘要包含引用自身的声明。Schema 位于 `contracts/json-schema/catalog/finance-component-development-support.v0.1.schema.json` 和 `finance-development-support-index.v0.1.schema.json`。

## 默认案例如何生效

Builder 按所选组件的固定包引用读取声明，不再根据余额、财富或收支的业务 ID 生成默认问法。用户提交的案例优先，包括显式空列表。没有适用声明时允许保存待完善草稿，但仍需补充案例才能通过原有验证与发布门槛。

声明要求特定 Runtime 或 Host 而当前尚未选择时，不会提前套用；多个匹配声明给出冲突案例时明确拒绝。复制时记录声明与案例摘要，修改后的案例需要重新验证。`expectedTerms` 和其他评分依据留在评测侧，不写入 Agent 提示。

旧发布包继续使用内嵌案例。目录默认案例更新不覆盖旧方法，也不改变旧 Agent 的安装 Lock。

## 文件身份与关系图

文件身份同时包含来源、版本、角色与已记录摘要。不同包都含 `SKILL.md` 是合法的；同一来源和版本出现不同内容或角色则是冲突。来源证据不足的旧记录标记为“来源待核对”，不生成历史摘要。

Builder 保留人可读的 `path` 和节点 `files`，以 `fileId`、节点 `fileIds` 和能力声明定位标识贯通预览及下载。旧裸路径只能在唯一匹配时解析；多来源时必须选择，不自动取第一项。展示合法多版本不等于允许同一次安装占用相同工具或能力定义；实际安装仍由既有准入与 Assembler 校验。

## 怎样验证

```bash
python3 -m unittest engineering.test_development_support
```

随后按各支持声明内的固定提交和命令，执行组件定向测试。命令指向既有源码，不代表页面能执行任意命令，也不代表当前组件已经通过测试。

### 财富声明的准备、运行与复核

`wealth-shared-facts/0.1.1.json` 的命令固定在源码提交
`14c985bb02b76c9832e7a558fb0c0b2beeb86327`，沿用现有财富套件入口，不改 Pack、Skill、Provider 或默认问法。
严格 V0.1 schema 没有声明级或命令级 `description`/`notes` 字段；前置说明使用已有 `commands.name` 与本节文档，
不向旧 schema 偷加字段。声明消费者只展示命令，不能自动执行或根据 `caseRefs` 调度任务。

先建立该固定提交的独立工作副本，再进入其根目录。例如从已有 Harness Git 仓库手动执行：

```bash
git worktree add --detach ../wealth-command-14c985bb 14c985bb02b76c9832e7a558fb0c0b2beeb86327
cd ../wealth-command-14c985bb
```

需要 Bash、JDK 21（确认 `java` 在 PATH 中）、Maven 3.8+、Python 3.10+ 和可用的 venv/pip；首次准备需要下载依赖。
在**同一终端**按声明顺序手动执行四条命令：

1. 在工作副本创建 `.venv` 并安装 `engineering/requirements-conformance.txt`。不要用缺 `referencing` 等依赖的系统解释器直接复核。
2. `mktemp` 新建独占 Maven 缓存，并导出 `WEALTH_M2`。该路径位于 `${TMPDIR:-/tmp}`，不在源码或共享 `~/.m2` 内；记下路径以便后续归档/清理。不要另开终端丢失变量，也不要指向正被其他任务写入的缓存。
3. 运行现有 `examples/solutions/wealth-facts-kit/run-controlled.sh`。命令清空继承环境，只保留 HOME、PATH、LANG 与固定私有缓存的 MAVEN_OPTS；未准备缓存变量会在启动前失败，不回退到共享仓库。既有入口先测试并安装所需依赖，再为五个受控场景启动独立 JVM；不跳过测试、不调用外部模型或生产业务。
4. 使用 `.venv/bin/python engineering/verify_wealth_kit.py` 读取并校验运行报告。它不生成报告，输入不全应失败。

可以在运行前把已有依赖缓存**复制**到新建的 `WEALTH_M2` 来减少下载；只能读取源缓存，不能将共享目录设为本次 local repository，不能用硬链接把两份缓存变成同一可写制品。所有 Maven install 与下载只写本次缓存。

五份报告位于独立工作副本的 `examples/solutions/wealth-facts-kit/target/runs/`，场景为
`BANK_HTTP-NORMAL`、`SNAPSHOT-NORMAL`、`EA_HTTP-NORMAL`、`BANK_HTTP-DENIED`、`EA_HTTP-DENIED`。
每份含 `report.json`、`solution-lock.json`、`controlled-model-trace.json`；Maven 测试结果另在相关模块 `target/surefire-reports/`。
复核只向 stdout 输出场景结果与总结，核对 lock/trace 摘要、共享事实、原生回调和拒绝/零写入边界。
重跑会覆盖同名运行目录，保留证据时归档完整目录与日志；不要把旧输出混入新副本。

此处使用既有本地 HTTP 和脚本模型，结论限于受控调用、事实语义和拒绝检查。报告明确标记
`CONTROLLED_HTTP_AND_SCRIPTED_MODEL`、`NOT_EVALUATED_SCRIPTED_MODEL` 与 `liveAuthorization=NOT_RUN`。
它不是 AgentScope 验收、真实银行授权或模型质量通过；声明本身也不记录 PASS。

从资产详情的“检查与生成”查看案例、验证入口和范围，再选择“定制方法”，修改 SOP 或案例，保存、检查、试跑、发布新版本。构建 Agent 后仍需核对该版本安装 Lock 和真实运行记录。

案例存在、文件可读、目录准入、方法加载、工具边界通过、专业质量确认是不同结论。派生方法的试跑不能自动给原组件背书；平台全量测试、其他版本、不同 Runtime 或缺少绑定的历史报告也不能作为本版本通过依据。真人独立开发继续单独验收。
