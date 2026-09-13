# 中文只读专业方法候选

本次只更新方法资产，不修改 Core、Provider、授权机制或 Runtime。

| 方法 | 原版本 | 中文候选版本 | 能力依赖 |
| --- | --- | --- | --- |
| 财富事实核验 | wealth-shared-facts 0.1.0 | 0.1.1 | wealth-facts-description 0.1.0 不变 |
| 月度收支核验 | cashflow-customer-self-service 0.1.1 | 0.1.2 | cashflow-readonly 0.1.0 不变 |

余额方法归 Builder Host，不另造一个 Domain Pack。Catalog 升为 0.2.2；同一机器 ID
继续使用新方法版本。现有英文版本由原提交及已生成制品保留，不重写部署目录、客户 SOP 或旧 Lock。
旧 Solution 如果仍指向旧版本，应继续配套原资产快照；不能用新正文冒充旧版本。
Builder 新建配方需同步引用本次版本。旧发布方法的用户主动升级是新版本操作，不是自动替换。

## 约束与验证

`conformance/fixtures/methods/chinese-readonly.v0.1.json` 固定英文基线与 39 项中文约束，
保留机器状态码、工具 ID、月份、范围、时区、分类冲突、趋势不可比和只读授权边界。
它是语言审查对照表，不是新的运行协议，也不能证明模型一定遵守方法。

Builder 配套 `engineering/verify_chinese_methods.py` 校验原文依据、中文条目、导读摘要、
新建定制副本和当前目录一致性。运行时仍由现有宿主、Integrator 和 Provider 实施授权与参数检查。
受控模型测试用于验证加载和执行边界；真实模型专业质量与业务 Owner 审核单独待验收。

本候选不自动合并或部署，不包含 UI 或模拟转账的交付。
