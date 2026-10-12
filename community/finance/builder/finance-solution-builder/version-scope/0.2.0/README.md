# Finance Solution Builder 0.2.0

本版本面向上线前冷启动：在 0.1.x Java 确定性装配基线上，增加真实 Candidate Evaluation、可替换构造
Agent、受预算约束的优化闭环和跨仓资产修订提案。

## MVP 路径

```text
统一 Candidate Backend
  -> 迁移现有 Java 装配链
  -> 统一构造 Agent 执行与事件
  -> 接入 OpenCode 参考实现
  -> 实际评测本轮 Candidate
  -> 完成一次受控修订、重建与 Holdout 回归
  -> 输出 Harness 资产提案
  -> 在 WebUI 展示完整过程
```

Codex 构造适配和 OpenJiuwen Python Candidate Backend 独立排在 MVP 之后，用于验证两个公共边界确实没有
固化 OpenCode 或 Java 的实现细节。

Euphoria_Yan 于 2026-09-08 接受 L1 与 FEAT-001～008，随后分别解除 FEAT-009～010 的暂缓状态并完成
行为、架构与实施验收。FEAT-001～010 的代码均已合入 `finance`；Codex 构造适配和 OpenJiuwen Python
Candidate Backend 作为 MVP 后的扩展性验证，证明公共边界没有固化 OpenCode 或 Java 的实现细节。

Finance Solution Builder 0.2.0 已通过 `finance-solution-builder-v0.2.0` 标签发布，FEAT-001～010 已分别
记录原合并提交、交付版本与发布证据。真实银行在线业务评测仍依赖获批数据和 Provider 环境，不纳入本次
代码版本的发布完成声明。

## 发布状态

0.2.0 的包版本、能力摘要、验证口径、标签和已知边界统一记录在
[release-notes.md](release-notes.md)。Feature 发布记录回填合入后，可以关闭 Requirement #31；后续真实
银行环境验证作为独立交付继续跟踪。
