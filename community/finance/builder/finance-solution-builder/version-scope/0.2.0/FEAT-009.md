---
schema: speckit-feature-record/v1
feature_id: FEAT-009
title: Codex 构造 Agent 适配
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/31
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-12T07:48:32Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-12T08:14:03Z'
  decision_source: conversation
priority: P1
mvp: false
delivery_order: 9
delivery:
  phase: done
  branch: codex/finance-solution-builder-feat-009-spec
  pull_request: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/pull/150
  review_target:
    type: pull-request
    url: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/pull/150
    revision: a882922ac7156ead51816d4d656ef3c17e624d23
  merged_commit: df63b1709605521a1fcae170bfed7708a714cce3
dependencies:
- FEAT-003
- FEAT-006
external_prerequisites:
- 运行环境提供可固定版本且部署规则兼容的 Codex CLI app-server
shared_contracts:
- community/finance/builder/finance-solution-builder/docs/architecture/contracts/construction-agent-backend-protocol.md
ownership:
  owner: ''
  collaborators: []
architecture_impact:
  level: L2
  update_required: true
  reason: Codex 复用既有公共 SPI，但新增 app-server 进程、协议映射、审批和清理的数据流
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

# FEAT-009: Codex 构造 Agent 适配

## User Value

Agent 构造者可以在相同权限、事件和质量门禁下选择 Codex 完成 Candidate 修订，并验证 Builder 可以承接不同
先进 Coding Agent，而不是绑定单一实现。

## Scope

- 通过运行环境提供的固定版本 Codex CLI app-server 接入 Codex；Builder 不打包或分发 Codex 可执行文件。
- 使用本地 stdio JSONL 传输，将 initialize、thread/turn、流式事件、审批、中断和终态映射到 FEAT-003。
- 在任务开始前核验 Codex 版本、协议 Schema、身份配置和 Backend 能力；不兼容时在修改工作区前失败。
- 用与 OpenCode 相同的受控修订场景执行公共契约一致性测试，并保持现有自优化闭环可用。
- 将 Codex 私有字段限制在适配层内，不改变 FEAT-003 公共必填字段。

## Non-Goals

- 为 Codex 建立独立的 Builder 流程或 UI。
- 把 Codex 私有协议复制进 Base 公共模型。
- 在 Builder 产物中打包 Codex CLI、账户凭据或模型配置。
- 在 v1 中承诺活动任务恢复；无法同时核验任务、父 Lock 与工作区身份时，恢复请求失败关闭。

## User Stories

### US-001: 选择 Codex 执行受控 Candidate 修订

作为 Agent 构造者，我希望在保持 Solution、Finding、父 Lock 和验收流程不变的情况下选择 Codex，从而按当前环境使用另一种构造 Agent 完成同一类 Candidate 修订。

- 前置条件：Builder 已生成通过 FEAT-003 校验的任务；运行环境提供固定版本 Codex CLI app-server，
  并以受信方式提供账户、模型及策略配置。
- 主场景：适配器完成 app-server 初始化，建立 thread 和 turn，持续接收事件，校验实际差异和验证结果，
  再输出 FEAT-003 的公共终态。
- 边界或失败场景：Codex 不可用、版本或协议不兼容、初始化失败、身份不匹配时，在修改工作区前失败，
  且不静默切换到 OpenCode。
- 验证（`VER-001`、`VER-002`）。

### US-002: 决定 Codex 请求的受控动作

作为 Agent 构造者，我希望工作台把 Codex 发出的受控命令、文件范围扩张或外部访问请求转换为明确的审批事项，从而只放行本轮任务已经授权的动作。

- 前置条件：Codex 在执行过程中发出命令、文件范围扩张、网络访问或权限请求，并按运行策略要求审批。
- 主场景：适配器将请求与当前任务、thread、turn 和允许范围绑定，产生 FEAT-003 审批事件；匹配的批准
  只执行一次，拒绝后不产生对应副作用。
- 边界或失败场景：跨任务、过期、重复、超出允许路径或扩大权限的请求不得执行；供应商的会话级授权
  不得绕过 Builder 的单次任务边界。
- 验证（`VER-003`、`VER-004`）。

### US-003: 中止异常或超出预算的 Codex 任务

作为 Solution 负责人，我希望 Builder 能够取消方向错误、失败或超出预算的 Codex 任务，从而及时停止新的修改并保留可复核的失败事实。

- 前置条件：Codex 任务已经启动，且 Builder 持有任务、thread、turn、事件游标和受管进程身份。
- 主场景：用户取消或预算耗尽时，适配器中断当前 turn、停止受管 app-server，并返回唯一的取消或超时终态。
- 边界或失败场景：连接中断、事件乱序、工作区变化、父 Lock 变化或任务身份无法核验时失败关闭；v1
  对活动任务声明 `supports_resume=false`，终态查询保持幂等。
- 验证（`VER-005`、`VER-006`）。

### US-004: 用统一记录复核不同构造 Agent

作为 Builder 维护者，我希望 Codex 与 OpenCode 通过相同的一致性场景并输出同一公共语义，从而证明自优化流程可以替换构造 Agent，而不要求上层理解供应商协议。

- 前置条件：同一脱敏 Candidate、修订目标、父 Lock、工作区边界、验证命令和预算可分别交给两个 Backend。
- 主场景：两种 Backend 都以 FEAT-003 表达进展、变更、验证、审批和终态；Codex 特有信息只作为
  带命名空间的可选扩展或私有证据存在。
- 边界或失败场景：私有 DTO、完整 Prompt、内部推理、账户凭据、宿主绝对路径和未经授权业务原文
  不进入公共事件、结果、Lock 或 Build Report。
- 验证（`VER-007`、`VER-008`）。

## Verification

- `VER-001`：合法 FEAT-003 任务通过固定版本 Codex app-server 完成一次真实临时工作区修改；任务句柄、
  Candidate、父 Lock、事件和实际差异身份一致，全部验证命令通过后才返回成功。
- `VER-002`：Codex CLI 缺失、版本不匹配、生成的协议 Schema 不兼容、初始化/建 thread/建 turn 失败、
  账户或模型不可用时，在工作区发生修改前返回稳定失败；不得自动改用其他 Backend。
- `VER-003`：Codex app-server 发出的命令、文件范围扩张、网络访问和权限审批请求均绑定当前任务及
  供应商 thread/turn，并映射为 FEAT-003 的唯一待批事项；批准、拒绝和重复提交保持幂等。
- `VER-004`：未获批准、审批身份不匹配、请求过期、越出允许写入范围或扩大权限时不产生副作用；
  Adapter 只发送针对当前请求的决定，不使用 `acceptForSession` 扩大后续动作权限。
- `VER-005`：取消使用供应商中断能力停止当前 turn，并清理受管 app-server；预算耗尽、连接失败和
  供应商错误产生唯一的非成功终态，终态后无新增修改或进展。
- `VER-006`：v1 能力声明 `supports_resume=false`；活动任务恢复请求失败关闭并清理，已完成任务的
  结果查询返回同一终态事实，不创建新 thread、turn 或修改。
- `VER-007`：Codex 和 OpenCode 对同一组公共一致性场景均通过，且上层 Orchestrator、Optimization
  Controller、WebUI 和持久化只消费 FEAT-003 公共模型。
- `VER-008`：加入 Codex 后，OpenCode Adapter 与 FEAT-006 完整自优化回归保持通过；公开记录不包含
  Codex 私有 DTO、完整 Prompt、内部推理、凭据、宿主绝对路径或未经授权业务原文。

## Dependencies

- FEAT-003、FEAT-006。

## Shared Contracts

- [Construction Agent Backend Protocol v1](../../docs/architecture/contracts/construction-agent-backend-protocol.md)。

## Decisions

- 2026-09-08：Euphoria_Yan 决定暂缓到 MVP 闭环后，再作为第二适配验证扩展性。
- 2026-09-12：FEAT-001～008 已形成完整 MVP 闭环后，Euphoria_Yan 解除暂缓并接受继续细化本 Feature。
- 2026-09-12：规格细化建议采用固定版本 Codex CLI app-server 的本地 stdio JSONL 接入面；该选择依据
  OpenAI 官方 App Server 文档及本机 `codex-cli 0.153.4` 能力核验，等待 Owner 行为确认。
- 2026-09-12：Euphoria_Yan 接受 US-001～US-004 与 VER-001～VER-008，并要求 User Story 统一使用
  “作为……，我希望……，从而……”单句格式。
- 2026-09-12：Euphoria_Yan 接受 L2 方案并选择继续拆任务；T001～T005 与实施权限边界通过机器检查，
  Feature 进入 tasks-ready，尚未开始源代码实施。
- 2026-09-12：T001～T005 实施完成；固定 Codex 0.153.4 的真实脱敏 Candidate 修订通过，受影响范围
  回归为 142 passed、3 skipped，Feature 进入 reviewing，尚未通过独立审阅。
- 2026-09-12：本地差异审阅首轮发现并修复审批身份与路径绑定缺口；二次结论为 GO-WITH-RISK，
  Feature 进入 ready-to-merge，Windows 新文件 ACL 兼容性保留为后续风险。
- 2026-09-12：PR !150 线上审阅发现文件范围扩张可在未提供 grantRoot 时进入审批，结论为 NO-GO；
  Feature 回退到 reviewing，等待失败关闭修复及复审。
- 2026-09-12：修复提交 22af1476 要求文件范围扩张必须携带至少一个合法授权路径，并覆盖同根因的
  permissions 空路径；复审结论为 GO-WITH-RISK，Feature 恢复 ready-to-merge。

## Delivery History

- 目标版本：`0.2.0` 后段或后续版本。
- 2026-09-12：PR !150 合入 `finance`，merge commit 为 `df63b1709605521a1fcae170bfed7708a714cce3`；尚未记录版本发布证据。
