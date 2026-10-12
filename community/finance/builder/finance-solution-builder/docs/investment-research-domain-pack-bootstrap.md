# 投研 Domain Pack 冷启动盘点

## 可复用起点

`finance-skill-eval` 已提供 `investment-research-assistant-value-v1`，包含两类冻结案例：公司研究简报与财务质量分析。现有材料给出了：

- 目标 Skill：`investment-research-assistant@1.1.0`；
- 专业 Judge：研究判断、盈利与估值驱动、证据与反证、失效条件、验证窗口和跟踪项；
- Required Tools：股价指标、基本面、技术面、事件和风险指标；
- 两组 A/B 冻结证据。

这足以作为验收和需求反推的起点，但不足以直接构造在线投研 Candidate。

## 缺失资产

1. Harness 中尚无投研 Domain Pack，也没有目标 Skill 本体；
2. 尚无上述五类工具的统一 Capability Catalog；
3. 尚无对接 Wind 或替代授权数据源的 Provider Adapter；
4. 尚无 OpenJiuwen Host Binding 和数据权限配置；
5. 当前只有两条冻结案例，不能证明跨公司、跨行业和跨报告期泛化；
6. 尚无投研 Solution 构建输入及当前 Candidate 在线 Evaluation Adapter。

## 建议冷启动顺序

1. 从 Eval Pack 的 Required Tools 反推最小 Capability Requirement；
2. 明确首个授权数据源和动态数据日期/口径合同；
3. 将现有投研 Skill 清理成 Domain Pack，区分 SOP、工具依赖和 Judge Rubric；
4. 先覆盖一个公司研究任务和一个财务质量任务，再增加跨行业与缺失数据反例；
5. 形成参考 Solution，完成锁定、装配、在线数据调用和同数据集对照评测；
6. Bad Case 必须归因到 Skill、数据 Provider、字段口径或 Eval，而不是统一改 Prompt。

投研的首要外部依赖是授权数据与口径，不是再写一个通用 Agent 模板。在数据 Provider 未明确前，只能建设 Pack 草案和冻结评测，不能报告真实在线投研能力。
