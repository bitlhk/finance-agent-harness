# 转账风险审核 Domain Skill 真实 A/B 验证

验证日期：2026-09-03

## 验证目标

证明 Finance Harness 不把反诈规则写入 Core，而是通过多 Provider 装配提供账户事实和反诈判断，再由可安装的 Domain Skill 提升 Agent 的风险审核专业性。

```text
同一 GLM-5.2 + 同一 OpenJiuwen Runtime
  + 同一 risk-review-basics Capability Pack
  + 同一账户与反诈 Provider Fixture
  -> 左：无 Domain Skill
  -> 右：transfer-risk-review@0.1.0
  -> 匿名模型 Judge
```

## 验证结果

| 案例 | Provider 行为 | Judge 结果 | 主要差异 |
|---|---|---|---|
| 已知涉诈命中 | 账户成功、反诈成功 | 右侧胜，高置信度 | 右侧区分模型线索与已核验事实，保留最终决策边界 |
| 未命中已知风险 | 账户成功、反诈成功 | 右侧胜，高置信度 | 右侧不把未命中解释为绝对安全或自动放行 |
| 风险服务不可用 | 账户成功、反诈失败 | 右侧胜，高置信度 | 右侧明确无返回不等于无风险，并给出重试与人工审核路径 |

汇总结果：右侧 `3/3` 胜，左侧 `0` 胜，无平局。报告指纹：

```text
b55c9c78eb6d6896f1c9c50d9d4a9d4e75c46218b9943571526490620966cd80
```

## 证据边界

- 六次回答均由真实 OpenJiuwen Candidate 调用 GLM-5.2 生成，不是预置回放答案。
- 两侧实际 Tool Trace 一致；前两题都是两项能力成功，第三题都是账户成功、反诈失败。
- 右侧运行证据记录了 `transfer-risk-review@0.1.0`，左侧 `loadedSkills` 为空。
- Provider 使用进程内受控 Fixture，验证的是装配、工具选择、降级处理和 Domain Skill 增益，不冒充银行生产反诈数据。
- Judge 使用同一 OpenAI 兼容模型接口做匿名比较；该结果是 AI 专业评测证据，不冒充独立人类专家签署。
- 报告未保存模型密钥、Authorization、Bearer Token 或服务地址。

## 结论

这次验证同时证明了三件事：

1. 一个 Agent 可以通过 `capabilityBindings` 同时消费多个 Provider；
2. 银行反诈判断和账户事实留在 Provider，Harness Core 只负责统一合同、装配与证据；
3. Domain Skill 的价值体现在解释风险证据、处理未知状态和形成可执行审核建议，而不是增加更多格式字段。

完整本地报告由 `finance-skill-eval` 生成在：

```text
reports/risk-review/live/report.json
reports/risk-review/live/report.html
```
