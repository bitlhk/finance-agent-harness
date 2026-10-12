---
schema: speckit-feature-record/v1
feature_id: FEAT-007
title: Harness 资产修订提案与治理元数据
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/31
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-08T06:17:47Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-10T10:05:28Z'
  decision_source: conversation
priority: P1
mvp: true
delivery_order: 7
delivery:
  phase: done
  branch: codex/finance-solution-builder-feat-007
  pull_request: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/142
  review_target:
    type: pull-request
    url: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/142
    revision: 4508c2fe72fefe8f3b5991450a79f4933741eeab
  merged_commit: b26496b93ae726941ac7e186de9aff11830cd88f
dependencies:
- FEAT-005
- FEAT-006
external_prerequisites:
- Finance Harness Owner 已合入 Catalog 元数据和 Change Proposal：https://github.com/bitlhk/finance-agent-harness/pull/11
- finance-agent-harness@9d8fb89683cdc60da8167f95294237104030d01b
- contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json
- contracts/json-schema/governance/finance-asset-change-proposal.v1.schema.json
shared_contracts: []
ownership:
  owner: ''
  collaborators: []
architecture_impact:
  level: L1
  update_required: true
  reason: 建立 Builder 与 Harness 之间的机器可读治理边界
  affected_files:
  - community/finance/builder/finance-solution-builder/docs/architecture/L1-architecture.md
  - community/finance/builder/finance-solution-builder/docs/architecture/asset-governance/L2-architecture.md
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

# FEAT-007: Harness 资产修订提案与治理元数据

## User Value

Harness、Pack 和 Provider 维护者可以收到包含版本、复现条件、责任归属和回归证据的修订提案；Builder 也能
根据 Harness 发布的兼容与权限元数据正确选择装配和修正路径。

## Scope

- Builder 生成关联目标资产、Owner、父版本、Finding、建议差异和验证结果的 Change Proposal。
- 消费 Harness 发布的资产身份、依赖、兼容关系、Owner、允许优化范围和验证入口。
- 明确 Capability Pack 的解析来源：由 Solution 显式引用或由 Domain Pack 声明依赖；无论采用哪种方式，
  Builder 都必须将实际解析到的完整能力资产闭包写入 Lock。
- 将现有场景组合硬编码迁移到机器可读元数据，并保留兼容窗口。
- 建立固定修订的跨仓契约样例和验证命令。

## Non-Goals

- 由 Builder 直接合并或发布 Harness 资产。
- 将 Orchestrator、Evaluation、Data Factory、Session 或 WebUI 搬入 Harness。

## User Stories

### US-001: 从 Harness Catalog 解析可装配资产

作为 Agent 构造者，我希望 Builder 从固定版本的 Harness Catalog 解析 Solution 所需资产及其依赖，从而
获得完整、可复现且不依赖场景特判的装配清单。

### US-002: 在治理信息不足时安全停止

作为 Solution 负责人，我希望 Builder 在资产组合、Owner、允许修改范围、审批策略或验证入口不明确时停止
自动处理并说明缺少什么，从而避免错误装配或越权修改 Harness 资产。

### US-003: 将外部问题交给正确的资产维护者

作为 Harness、Pack 或 Provider 维护者，我希望收到能够定位资产版本、复现问题并说明验证结果的修订提案，
从而审核跨场景修复，而不必反查 Builder 的私有日志。

### US-004: 使用发布后的新资产重新验证 Candidate

作为 Solution 负责人，我希望 Builder 在 Harness Owner 发布新资产版本后重新解析、锁定并执行相同条件的
回归，从而确认修订确实解决问题且没有破坏既有能力。

## Verification

- `VER-001`：Builder 只消费带版本和内容摘要的 Catalog 快照；解析结果记录 Catalog 身份、固定修订和实际使用
  的资产版本，输入不变时输出一致。
- `VER-002`：Solution 显式引用和 Domain Pack 传递依赖都进入同一资产闭包；重复引用只保留一次，依赖缺失、
  循环、版本冲突或兼容关系不成立时在装配前失败关闭。
- `VER-003`：至少一个既有财富 Solution 改由 Catalog 解析；生成的 Lock 完整记录 Runtime、Integration、Profile、
  Domain/Capability Pack、Provider 和 Eval Pack，且与迁移前有效组合保持语义一致。
- `VER-004`：每个可能进入优化路由的 Harness 资产都能读取 Owner、允许修改范围、审批策略和验证入口；任一
  必需治理字段缺失时不得创建写任务，也不得回退到场景名称或代码分支猜测。
- `VER-005`：合同、Pack、Provider、Integration、Eval 或外部环境 Finding 能生成提案；提案包含目标资产、Owner、
  父版本与摘要、Finding/证据引用、脱敏复现条件、建议变更摘要、验证入口和已有回归结果。
- `VER-006`：Builder 只生成并保存待审提案，不修改、提交、合并或发布 Harness 资产；Owner 缺失时输出结构化
  阻塞结果和所需元数据，不伪造责任方或目标版本。
- `VER-007`：公开 Catalog 解析结果、阻塞结果和提案不包含凭据、真实客户身份、宿主绝对路径、未经授权原文、
  完整 Prompt 或模型内部推理。
- `VER-008`：Builder 与 Harness 在固定修订上运行同一契约样例和验证命令；Harness 发布新版本后，Builder 生成
  新 Lock，并在相同数据快照、验收规则、运行配置和证据层级下回归，旧结果不得冒充新版本验证结果。

## Dependencies

- FEAT-005、FEAT-006；依赖 Finance Harness 联合设计。

## Shared Contracts

- Finance Harness：`contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json`。
- Finance Harness：`contracts/json-schema/governance/finance-asset-change-proposal.v1.schema.json`。
- Builder 验证固定 Harness 合并修订：`9d8fb89683cdc60da8167f95294237104030d01b`。

## Decisions

- 2026-09-08：Euphoria_Yan 接受本 Feature。
- 2026-09-08：补充 Capability Pack 显式引用或 Domain Pack 传递依赖两种解析方式及完整 Lock 要求。
- 2026-09-10：Euphoria_Yan 接受完整实施范围及关联 Harness 契约交付。

## Delivery History

- 2026-09-12：PR !142 合入 `finance`，merge commit 为 `b26496b93ae726941ac7e186de9aff11830cd88f`；尚未记录版本发布证据。

- 2026-09-10：实现提交至 [!142](https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/pull/142)，依赖 Finance Harness [#11](https://github.com/bitlhk/finance-agent-harness/pull/11) 先合入。

- 2026-09-10：根据评审补强非 Git Harness 归档完整性校验，并将发布复验绑定到原 Proposal、前后 Catalog 与完整资产闭包。

- 2026-09-11：根据复审将非 Git Harness 归档改为由 Builder 外部配置提供发布源、完整 commit SHA 和树摘要；归档内元数据只作来源记录与缓存校验，不能再自证可信身份。

- 2026-09-12：Finance Harness [#11](https://github.com/bitlhk/finance-agent-harness/pull/11) 已合入；Builder 已固定合并提交 `9d8fb89683cdc60da8167f95294237104030d01b`，并通过 Catalog、Solution、Proposal 与发布后复验的跨仓验证。

- 目标版本：`0.2.0`。
