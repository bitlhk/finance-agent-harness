# Builder 多 Provider 装配交接 V1

## 目标

Builder 从静态资产目录选择模块，并通过 `capabilityBindings` 把每项金融能力绑定到明确的 Provider。Builder 不从 Skill 文本推测工具，也不根据 Provider 名称猜测路由。

机器可读目录：

```text
catalog/finance-assets.v1.json
```

可运行装配样例：

```text
examples/solutions/openjiuwen-risk-review/solution.yaml
```

Builder 输入样例：

```text
examples/solutions/risk-review/solution-build-input.v1.json
```

## 装配规则

单 Provider Solution 可以省略 `capabilityBindings`，保持已有方案兼容。声明两个或更多 Provider 时，每项已安装能力必须显式绑定：

```yaml
providerRefs:
  - id: non-ai-banking-reference
    version: 0.1.0-SNAPSHOT
    source: repo://providers/non-ai-banking
  - id: fraud-mining-reference
    version: 0.1.0-SNAPSHOT
    source: repo://providers/fraud-mining

capabilityBindings:
  account.balance.query: non-ai-banking-reference
  fraud.transfer-risk.assess: fraud-mining-reference
```

装配器必须拒绝以下输入：

- 能力没有绑定；
- 绑定到未声明 Provider；
- Provider Descriptor 未声明对应能力与操作类型；
- 重复 Provider 引用；
- Provider 被声明但没有任何能力使用；
- 两个 Capability Pack 重复声明同一能力编号。

## 锁定结果

`solution-lock.json` 的每项能力记录：

```json
{
  "capabilityId": "fraud.transfer-risk.assess",
  "toolName": "fraud_transfer_risk_assess",
  "operation": "QUERY",
  "providerId": "fraud-mining-reference",
  "providerVersion": "0.1.0-SNAPSHOT"
}
```

这让构建报告能够回答“哪项能力实际由哪个 Provider 提供”，而不是只记录一个 Solution 级默认 Provider。

## Builder 首轮联调

1. 读取资产目录，展示 Runtime Integration、Profile、Domain Pack、Capability Pack、Provider 和 Eval Pack；
2. 选择“转账风险审核”时预填两个 Provider 与能力绑定；
3. 输出符合 Schema 的 `solution.yaml`；
4. 调用 OpenJiuwen Java Assembler 完成组合预检与 Candidate 装配；
5. 保存 `solution-lock.json` 和 `build-report.json`；
6. 运行账户查询、反诈查询两个 Smoke Case；
7. 将 Candidate 交给 `finance-skill-eval` 对比无 Domain Pack 与有 Domain Pack。

V1 不建设动态资产市场。静态目录和确定性装配已经足以验证 Builder 的模块选择、组合、锁定与评测闭环。
