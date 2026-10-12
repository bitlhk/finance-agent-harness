---
schema: speckit-shared-contract/v1
contract_id: CONTRACT-SOLUTION-BUILD-V1
title: Solution Build Protocol v1
status: accepted
owner: Finance Builder Base
collaborators:
  - Finance Solution Builder
review:
  decided_by: Euphoria_Yan
  decided_at: '2026-09-05T02:27:30Z'
related_features:
  - FEAT-001
compatibility: backward-compatible
---

# CONTRACT-SOLUTION-BUILD-V1: Solution Build Protocol v1

## Purpose And Boundary

本契约统一一次 Solution 构建的阶段、状态、事件、资产锁、构建报告、取消和恢复语义。它由
`finance_builder_base` 提供，供不同金融 Builder、Developer Backend、WebUI 和验证执行器共同消费。

Base 不解析具体 Solution YAML/JSON，不选择专业 Agent，不实现 Runtime、Harness、Maven 或场景逻辑。
宿主 Builder 负责把已校验输入映射为 Build Definition 和有序 Build Plan，并注入阶段 Runner 与存储。

## Consumers

- Finance Solution Builder 的 Build Orchestrator；
- Developer Backend SPI；
- Dryrun/Evaluation Runner；
- 展示构建进度和结果的 WebUI；
- 后续选择接入的 Intent、Scheduling 等 Builder。

## Inputs

- 稳定 `task_id`；
- 冻结输入摘要 `input_digest`；
- 宿主提供的有序 Build Plan；
- 已解析且实际参与装配的资产闭包；
- 调用方注入的 Stage Runner、取消信号和 Checkpoint Store。

Backend 或场景专用字段只能进入带命名空间的扩展区，不能改变标准阶段和公共状态语义。

## Outputs

- 按任务有序的 Build Event；
- 唯一任务终态；
- 记录实际装配资产的 Solution Asset Lock；
- 记录实际执行检查的 Solution Build Report；
- 可选安全检查点和公共安全诊断引用。

## Semantics And Invariants

1. 标准阶段依次为：输入接收、输入校验、资产解析、Candidate 装配、Candidate 构建、Candidate 启动、
   Candidate 验证、交付打包；不适用阶段也必须产生 `skipped` 结果。
2. 阶段状态允许 pending、running、passed、failed、skipped、blocked、cancelled；任务只允许 completed、
   failed、blocked、cancelled 中的一个终态。
3. 终态后拒绝新增阶段进展。`task_id + sequence` 决定任务内事件顺序，`event_id` 用于幂等去重；事件
   重放保留原身份和顺序。
4. Lock 只表达实际解析并参与装配的资产身份、版本、修订、摘要和依赖关系；规范化排序后必须确定性输出。
5. Report 只表达实际执行结果。未执行检查只能是 `skipped` 或 `blocked`，不得计为通过。
6. Lock 与 Report 通过 task id、input digest 和 lock digest 关联，但两者不能合并，也不能互相推导通过。
7. 取消后不得启动新阶段；无法立即中断的 Runner 先进入取消处理中，结束后任务进入 cancelled。
8. 恢复只复用相同 input digest 的安全检查点。外部动作还需稳定幂等身份，并由 Runner 明确声明可重放。
9. 事件、错误和报告不得包含凭据、宿主绝对路径、模型思维链或未脱敏业务原文。

## Errors And Boundary Cases

公共失败至少记录稳定错误类别、所属阶段、责任边界、公共安全摘要、可执行下一步和可选诊断引用。
Backend 私有错误必须映射到公共类别；私有日志只作为受控证据引用。输入不一致、非法阶段转换、终态后
追加事件、无法证明安全重放等情况必须显式拒绝，不得静默继续。

## Compatibility

- 本契约在 Base `0.1.x` 中纯增量引入，不改变既有 `BuilderTask`、`TaskStatus`、`Artifact`、
  `ArtifactManifest` 的字段、枚举和序列化形状。
- v1 公共类型允许增加具有默认值的向后兼容字段；删除、重命名、收窄枚举或改变既有状态含义需要新版本。
- 存量 Builder 通过适配层逐步接入，不要求同版本一次性迁移。
- Base 不新增 OpenJiuwen、Maven、Finance Harness 或场景依赖。

## Verification

- 轻量测试 Runner 覆盖阶段顺序、唯一终态、失败、取消和恢复；
- 相同输入与资产闭包生成字节语义一致的 Lock；
- Report 对 passed/failed/skipped/blocked 如实计数；
- 公共文本与引用通过敏感信息和宿主路径门禁；
- Base 现有 API 及 Intent/Scheduling 消费者兼容测试保持通过；
- 一个真实 Backend 对本 SPI 的实现由 FEAT-003 验证。

## Decisions

- 2026-09-05：Euphoria_Yan 接受新增独立 `solution_build` 组件，宿主提供 Build Plan，Base 负责公共执行
  语义；Checkpoint Store 外置，Lock 与 Report 分离，现有 Base 模型保持不变。
