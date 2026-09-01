# Wealth Reuse Example

本示例用于简化版 V1 的第二阶段验证：从 Employee Agent 提取财富 Skill、SOP 和评测案例，让一个最小财富参考助手消费与手机银行相同的 Harness Core 和 `non-ai-banking` Provider。

它用于证明：

- Harness 不只是手机银行内部重构；
- 同一金融事实可以支持客户查询和财富专业分析；
- Domain Pack 承载专业 Know-how，Core 不承载财富话术；
- `finance-skill-eval` 可以比较无 Skill、旧 Skill 和 Wealth Domain Pack。

V1 不要求同步重构现有 EA TypeScript Runtime。EA 直接 Integration 在 Java 合同稳定后增加。
