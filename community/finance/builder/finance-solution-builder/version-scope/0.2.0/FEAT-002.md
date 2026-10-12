---
schema: speckit-feature-record/v1
feature_id: FEAT-002
title: OpenJiuwen Java Backend 迁移与行为保持
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/31
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-08T06:17:47Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-08T17:32:31Z'
  decision_source: conversation
priority: P0
mvp: true
delivery_order: 2
delivery:
  phase: done
  branch: codex/finance-solution-builder-feat-002
  pull_request: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/125
  review_target:
    type: pull-request
    url: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/125
    revision: 8009d5718f6802db9350b8f01f5be75a7c2ba34d
  merged_commit: b88cc92c51e73071f43562012a38d7dd2fb2e238
dependencies:
- FEAT-001
external_prerequisites:
- 可固定版本的 OpenJiuwen Java 模板与 Finance Harness 资产
shared_contracts:
- community/finance/builder/finance-solution-builder/docs/architecture/contracts/candidate-backend-protocol.md
ownership:
  owner: ''
  collaborators: []
architecture_impact:
  level: L2
  update_required: true
  reason: L1 已定义 Java Backend 边界；本 Feature 补充真实 Java 适配、受管进程和兼容投影的内部职责
  affected_files:
  - community/finance/builder/finance-solution-builder/docs/architecture/L2-architecture.md
definition_of_done:
  code_complete: true
  tests_complete: true
  evidence_complete: true
  architecture_synchronized: true
  review_passed: true
release:
  target: 0.2.0
  delivered_in: finance-solution-builder-v0.2.0
  evidence: git-tag:finance-solution-builder-v0.2.0
completion:
  completed_by: Euphoria_Yan
  completed_at: '2026-09-15T16:06:37Z'
  evidence_source: post-merge-release-backfill
---

# FEAT-002: OpenJiuwen Java Backend 迁移与行为保持

## User Value

现有财富 Solution 可以继续生成、启动和交付 OpenJiuwen Java Candidate，同时成为统一 Candidate Backend
的首个真实实现。

## Scope

- 将 0.1.x 的 Java 环境预检、资产解析、装配、Maven 构建、启动、健康检查、调用和停止接入 FEAT-001，
  Candidate 在 Sandbox Evaluation 完成前保持运行。
- 生成完整 Candidate Descriptor，并保持 Lock、Report、缓存、失败关闭与单一交付包语义。
- 全部必需资产必须成功解析；根据 Harness 发布的元数据校验能力、Provider 操作类型与 Host Wiring，显式选择
  实际 JAR，不再由通用流程猜测第一个产物。
- 以两个已验证财富 Solution 做迁移前后对照。

## Non-Goals

- 重写 Finance Harness、OpenJiuwen Java 模板或银行 Provider Adapter。
- 借迁移改变 Solution 输入或降低既有门禁。

## User Stories

### US-001: 无损迁移现有 Java 构建

作为财富 Solution 负责人，我希望两个既有财富 Solution 通过统一的 OpenJiuwen Java Backend 完成构建，
从而在升级 Builder 架构后继续得到等价的 Candidate、Lock 和 Build Report。

- Preconditions: FEAT-001 Candidate Backend v1 契约可用；两个既有财富 Solution、固定版本的 Finance Harness
  资产与 OpenJiuwen Java 模板可解析。
- Main scenario: Builder 使用 Java Backend 执行环境预检、资产解析、工程装配和 Maven 构建，输出 Candidate
  Descriptor、锁定文件、构建报告与单一交付包；迁移前后的资产身份、版本、摘要和门禁结论保持等价。
- Boundary or failure scenario: 不改变现有 Solution 输入、命令行入口、缓存命中语义或既有门禁；运行标识、时间戳、
  临时路径等非确定字段不要求字节级一致。
- Verification: `VER-001`、`VER-002`。

### US-002: 明确识别实际交付的 Java Candidate

作为 Agent 构造者，我希望 Candidate Descriptor 精确描述本轮真正构建出的 Java Agent，
从而让后续 Runtime 和 Evaluation 能校验并调用正确的产物，避免猜测某个 JAR 或入口。

- Preconditions: Finance Harness 元数据已经声明运行时、能力、Provider 绑定、Host Wiring 和可执行产物。
- Main scenario: Java Backend 根据已解析元数据显式选择实际可执行 JAR，并记录 Backend、语言、Runtime、入口、
  健康检查、调用协议、能力、工具、Provider 绑定、资源引用和内容摘要。
- Boundary or failure scenario: 多个候选 JAR、缺失入口、摘要不一致、能力或 Provider 绑定不一致时失败关闭，
  不选择目录中的第一个文件，也不改用其他 Backend。
- Verification: `VER-003`、`VER-004`。

### US-003: 在评测期间可靠控制 Java Candidate 生命周期

作为上线前验收人员，我希望同一个 Java Candidate 实例在启动和就绪后持续服务到本轮调用结束，
从而让后续 Sandbox Evaluation 可以在一个受控实例上执行测试并完整回收进程。

- Preconditions: Candidate 已通过构建与身份校验；调用方提供受控运行环境和非持久化凭据注入。
- Main scenario: Backend 启动 Candidate，完成健康检查，接受规范化调用并收集证据；只有调用方明确停止、取消、
  超时或失败时才结束实例，停止操作可以安全重复执行。
- Boundary or failure scenario: 启动、健康检查或调用失败时保留可公开的结构化失败证据并清理已创建资源；凭据、
  未授权业务原文和内部推理不得进入 Descriptor、Lock、Report、日志或交付包。
- Verification: `VER-005`、`VER-006`。

### US-004: 在昂贵构建前拒绝不完整的 Java 装配

作为 Solution 负责人，我希望 Builder 在下载依赖或编译前发现缺失和不兼容的必需资产，
从而让问题以清晰责任边界返回，避免在 Maven 或运行阶段以模糊错误暴露。

- Preconditions: Solution 声明 Profile、Domain/Capability Pack、Provider、Eval Pack 和 Runtime 需求，
  Harness Catalog 提供相应版本与兼容元数据。
- Main scenario: Java Backend 在预检阶段验证全部必需资产、能力、Provider 操作类型、Host Wiring 与显式产物选择，
  校验通过后才进入下载、装配和编译。
- Boundary or failure scenario: 任一必需资产未解析、Provider 不覆盖所需能力或操作类型、Host Wiring 不完整时，
  构建返回 blocked，不降级为 warning、不使用场景默认值补齐，也不声称 live smoke 或业务 Evaluation 已通过。
- Verification: `VER-007`、`VER-008`。

## Verification

- `VER-001`: `wealth-customer-diagnosis` 与 `wealth-manager-previsit` 两个固定输入在迁移前后解析到相同的必需资产
  身份、版本和摘要，并保持相同的预检、缓存、构建门禁与交付结论；运行标识、时间戳和临时路径从等价比较中排除。
- `VER-002`: 两个输入均通过统一 Java Backend 生成可编译 Candidate、Lock、Build Report 和单一交付包；现有
  Solution 输入和命令行用法无需修改，既有报告字段继续可读。
- `VER-003`: Candidate Descriptor 与本轮显式选定的实际 JAR 一致，并完整记录 Backend、语言、Runtime、入口、
  健康检查、调用协议、能力、工具、Provider 绑定、资源引用和内容摘要。
- `VER-004`: 多 JAR 无权威选择、入口或内容摘要被篡改、Descriptor 与 Lock 或 Provider 绑定不一致时，在启动前返回
  稳定的结构化失败；不得猜测第一个 JAR、切换 Backend 或使用未锁定产物。
- `VER-005`: 同一 Candidate 实例按 `start → health → invoke → collect_evidence → stop` 完成一次受控调用；健康检查
  成功后实例保持运行，直至调用方停止、取消、超时或失败，重复 stop 不残留受管进程。
- `VER-006`: 构建、启动、健康检查和调用各失败点均执行清理并输出阶段明确、长度受限、可复现的证据；敏感扫描确认
  Descriptor、Lock、Report、日志和交付包不含凭据、真实客户身份、未授权业务原文或模型内部推理。
- `VER-007`: Profile、Domain/Capability Pack、Provider、Eval Pack 或 Runtime 任一必需资产未解析，Provider 未覆盖
  所需能力/操作类型，或 Host Wiring 不完整时，Java Backend 在下载依赖或编译前返回 blocked，且未写出可交付 Candidate。
- `VER-008`: 未执行的 live smoke、Sandbox Evaluation 或在线业务 Evaluation 以未执行状态记录，不计入通过；Java
  Backend 迁移不得降低既有工程检查或把历史证据回放当作本轮真实调用。

## Dependencies

- FEAT-001。

## Shared Contracts

- `docs/architecture/contracts/candidate-backend-protocol.md`。FEAT-001 已通过 PR !124 合入，
  本 Feature 只实现该公共契约，不修改其字段与生命周期语义。

## Decisions

- 2026-09-08：Euphoria_Yan 接受本 Feature。
- 2026-09-08：补充全部必需资产失败关闭、显式产物选择和 Candidate 保活至评测完成的约束。
- 2026-09-08：Euphoria_Yan 确认 US-001～US-004 与 VER-001～VER-008 的详细行为。
- 2026-09-09：Euphoria_Yan 接受 L2/HLD，确认保留 SolutionBuilder 兼容门面，并由 Java Backend 管理技术运行适配。
- 2026-09-09：T001～T005、TEST-001～TEST-015 通过 Plan-and-Task 机械检查，进入实施准备。
- 2026-09-09：Euphoria_Yan 授权按 Finance Harness 当前发布的固定 Git Integration 完成客户自助场景验证，不恢复已迁出的仓内 Integration 副本。
- 2026-09-09：两个财富 Candidate 均完成编译、启动、健康检查、A2A 协议调用、证据收集和停止；在线银行业务验收仍明确记录为未执行。
- 2026-09-09：本地差异评审经一轮自动修复后结论为 GO-WITH-RISK；Runtime 版本已按实际 Maven 模型记录，Feature 进入 ready-to-merge。
- 2026-09-09：依赖 Feature FEAT-001 已通过 PR !124 合入 `finance`；本 Feature 在合并基线
  `18e02c79fc9d3dae54fcb727f6fc6f313820463d` 上重新完成 Candidate Backend、Builder 与 Base 回归。

- 2026-09-09：PR !125 外部评审发现可执行 JAR 门禁与生命周期失败证据未闭环；修复已完成并进入复审，评审通过前不预置 ready-to-merge。

## Delivery History

- 2026-09-09：PR !125 合入 `finance`，merge commit 为 `b88cc92c51e73071f43562012a38d7dd2fb2e238`；尚未记录版本发布证据。

- 0.1.x 基线由 PR !118 提供；本 Feature 只负责无损迁移。
