# Canonical Contracts

本目录存放语言无关的 JSON Schema 2020-12 合同。

简化版 V1 的生产运行面只有四个合同：

```text
context/finance-context.v1.schema.json
capability/capability-request.v1.schema.json
capability/capability-result.v1.schema.json
evidence/evidence-receipt.v1.schema.json
```

为让外部 Builder 消费这些已验证资产，工程面增加一个轻量构建输入：

```text
solution/solution-build-input.v1.schema.json
```

它只描述 Runtime 模板、Profile、Domain Pack、Provider、模型引用和 Eval Pack，不进入生产请求热路径，也不是完整的 Solution Manifest。

可安装能力通过下面的构建期合同描述：

```text
capability/capability-pack.v1.schema.json
```

Capability Pack 只发布工具语义、输入 Schema、调用类型和最小 Scope。它不包含银行地址、凭据、客户数据或业务判定规则。Runtime Integration 将它转换为本地 Tool，Provider Binding 决定调用哪套银行系统。

Provider 通过下面的构建期合同声明它真正实现的标准能力：

```text
provider/provider-descriptor.v1.schema.json
```

装配时必须满足 `Capability Pack 要求 ⊆ Provider 声明`。业务参数和银行内部规则仍由 Provider 后面的银行系统定义，不进入该描述文件。

约束：

- 对象默认拒绝未知字段；
- 所有合同显式版本化；
- 时间统一使用 RFC 3339；
- 每个 Schema 提供正向和负向 Fixture；
- 不引用 OpenJiuwen、手机银行或 non-ai-banking 私有类型。

Principal、Profile、Policy、Approval 和 Runtime Capability 暂不作为独立运行合同。只有真实链路证明四个运行合同无法表达时，才通过 ADR 增加。
资产发现与跨仓治理使用两个构建期合同：

```text
catalog/finance-asset-catalog.v1.schema.json
governance/finance-asset-change-proposal.v1.schema.json
```

Catalog 记录资产身份、依赖、兼容关系和治理策略。Change Proposal 记录 Builder 发现的外部资产问题、父 Lock、脱敏复现条件和验证入口；它只是一份待 Owner 审核的建议，不授予 Builder 修改、提交或发布 Harness 资产的权限。
