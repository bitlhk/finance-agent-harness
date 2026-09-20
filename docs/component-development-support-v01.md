# 组件开发支持声明 V0.1

本规范是资产接入收尾的候选扩展，不改变已发布的 Domain Pack、Capability Pack 或方法派生 V0.1 合同。是否已部署，以对应 Builder 发布记录为准。

## 开发者交什么

维护组件时，在 `metadata/development-support/<组件>/` 新建不可变的 JSON 声明，由 `metadata/development-support/index.v0.1.json` 以路径和 SHA-256 固定引用。索引只负责发现，不集中维护业务问法。现有参考：

- `account-balance-readonly/0.1.1.json`：余额默认案例、Provider 正常与拒绝路径的定向命令。
- `cashflow-customer-self-service/0.1.1.json`：原收支方法的两个默认案例、业务结构 fixtures 与定向命令。
- `wealth-shared-facts/0.1.0.json`：财富方法默认案例与基础检查说明。

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

从资产详情的“检查与生成”查看案例、验证入口和范围，再选择“定制方法”，修改 SOP 或案例，保存、检查、试跑、发布新版本。构建 Agent 后仍需核对该版本安装 Lock 和真实运行记录。

案例存在、文件可读、目录准入、方法加载、工具边界通过、专业质量确认是不同结论。派生方法的试跑不能自动给原组件背书；平台全量测试、其他版本、不同 Runtime 或缺少绑定的历史报告也不能作为本版本通过依据。真人独立开发继续单独验收。
