---
schema: speckit-feature-record/v1
feature_id: FEAT-010
title: OpenJiuwen Python Candidate Backend
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/31
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-12T13:38:27Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-12T13:58:57Z'
  decision_source: conversation
priority: P1
mvp: false
delivery_order: 10
delivery:
  phase: done
  branch: codex/finance-solution-builder-feat-010-spec
  pull_request: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/pull/158
  review_target:
    type: pull-request
    url: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/pull/158
    revision: 7c65c11a6b20325ca46fb14a657494633e6b680e
  merged_commit: e5efe5e8c7dc0a4df4cdc175d0d3cd5c268a4abc
dependencies:
- FEAT-001
- FEAT-005
external_prerequisites:
- 可发布并固定版本的 OpenJiuwen Python Candidate 模板与 Runtime Integration 资产
- 真实银行 Canonical JSON/HTTP Adapter、Provider 测试身份与获批数据
shared_contracts: []
ownership:
  owner: ''
  collaborators: []
architecture_impact:
  level: L2
  update_required: true
  reason: 第二 Candidate Backend 复用公共生命周期，但新增 Python 产物、受管进程、OpenJiuwen 服务入口和 Canonical
    JSON/HTTP 接线，需要在 L2 明确内部边界。
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

# FEAT-010: OpenJiuwen Python Candidate Backend

## User Value

Agent 构造者可以用同一 Solution 构建、评测和交付 Python Candidate，并验证 Builder Agent 使用 Python 不代表
生成的 Agent 只能是 Python 或只能是 Java。

## Scope

- 生成 Python Candidate Descriptor，定义包/环境、服务入口、健康检查、调用和停止实现。
- 消费 Harness 的 Canonical JSON/HTTP 或经评审的 Python SDK，不复制金融合同语义。
- 使用 FEAT-005 的同一逻辑 Evaluation 和 Conformance 语义完成真实验证。

## Non-Goals

- 在本 Feature 内建设完整 Harness Python SDK。
- 要求 Python Candidate 与 Java Candidate 使用相同物理产物或启动命令。

## User Stories

### US-001: 构建可追溯的 Python Candidate

作为 Agent 构造者，我希望为同一份 Solution 显式选择 OpenJiuwen Python Backend 并生成可追溯的 Candidate，从而根据交付环境选择 Python，而不改变金融能力合同和 Solution 语义。

- 前置条件：Solution 已通过输入校验；运行环境提供可固定版本的 Python Runtime、Candidate 模板和 Harness 消费方式；资产组合可由 Catalog 验证。
- 主场景：Backend 在写入前完成预检，Builder 锁定资产、装配 Candidate，并生成相互绑定的 Descriptor、Lock 和 Build Report。
- 边界或失败场景：Runtime、模板、资产、接线缺失或不兼容时，在替换既有输出前失败；不得静默回退到 Java。
- 验证（`VER-001`、`VER-002`）。

### US-002: 运行并验证同一个 Python Candidate

作为 Solution 负责人，我希望 Builder 管理同一个 Python Candidate 的启动、健康检查、金融能力调用、证据收集和停止，从而确认交付物在受控环境中能够真实运行并在失败后完成清理。

- 前置条件：Candidate 产物、Descriptor、Lock 和摘要一致；运行配置由受信环境注入。
- 主场景：Backend 按 Descriptor 启动受管实例，完成健康检查、至少一次金融能力调用、证据收集和安全停止。
- 边界或失败场景：任一阶段失败或身份不一致时记录明确阶段、清理实例，并停止进入 Evaluation。
- 验证（`VER-003`、`VER-004`）。

### US-003: 使用统一标准评测 Python Candidate

作为 Solution 负责人，我希望 Java 与 Python Candidate 使用同一份冻结 Evaluation 输入和逻辑验收规则，从而比较业务效果时不受实现语言和构建工具差异干扰。

- 前置条件：Evaluation Snapshot、数据分区、Eval Pack、量化规则和证据级别已冻结，Python Candidate 已通过运行门禁。
- 主场景：Evaluation 通过统一 Candidate 调用边界逐样本执行，按相同逻辑 Schema 计算指标、Finding、时延和三态结论。
- 边界或失败场景：输入、规则或 Harness 接线不完整时返回不可判定，不以历史回放或健康检查代替本轮业务评测。
- 验证（`VER-005`、`VER-006`）。

### US-004: 保持 Harness 合同与现有 Java 交付兼容

作为 Builder 与 Harness 维护者，我希望 Python Candidate 通过明确版本的 Canonical JSON/HTTP 或经评审的 Python SDK 消费 Finance Harness 合同，从而避免在 Candidate 中复制金融语义，并保持现有 Java 构建链不回退。

- 前置条件：Harness 发布可固定版本的合同、能力标识、Provider SPI 和兼容元数据；Plan 已选择一种可验证的 Python 消费方式。
- 主场景：Python Backend 只负责 Runtime、打包、服务入口和协议适配，金融输入输出继续以 Harness 合同为准，Provider 通过已声明适配器绑定。
- 边界或失败场景：合同版本、能力标识或响应 Schema 不兼容时失败；敏感配置和未经授权数据不得进入交付与证据。
- 验证（`VER-007`、`VER-008`）。

## Verification

- `VER-001`：显式选择 `openjiuwen-python` 时，Backend 在写入交付目录前校验 Python 与 OpenJiuwen 版本、Candidate 模板、服务入口、Harness 消费方式及资产兼容组合；缺项或不兼容返回稳定失败。
- `VER-002`：成功构建输出 Python Candidate、语言无关 Descriptor、Lock 和 Build Report；产物摘要、启动入口、健康检查和调用协议一致，且 Python 失败不会选择 Java Candidate。
- `VER-003`：Builder 按 Descriptor 启动一个受管 Python Candidate，通过该实例完成健康检查、至少一次金融能力调用和证据收集，再安全停止；结果绑定同一 Candidate、Lock 和运行句柄。
- `VER-004`：启动、健康检查、调用、证据收集、身份校验或停止任一阶段失败时，报告保留明确阶段和公开安全证据；已启动进程最多停止一次且无受管实例残留。
- `VER-005`：同一冻结 Evaluation Snapshot 和 Eval Pack 可分别调用 Java 与 Python Candidate；两者输出相同逻辑结构的逐样本结果、指标、Finding、时延和三态结论。
- `VER-006`：缺少冻结输入、可计算规则或 Harness 接线时，Python Candidate Evaluation 返回不可判定；历史回放、健康检查和 live smoke 不得冒充本轮 Sandbox 业务验收。
- `VER-007`：Python Candidate 通过固定版本的 Canonical JSON/HTTP 或经评审的 Python SDK 消费 Harness 合同；能力标识与请求/响应 Schema 可核验，Candidate 不复制或改写金融合同语义。
- `VER-008`：Python Backend 加入后，Java Backend、既有 Solution、Lock、Report、Workbench 与 Evaluation 回归保持通过；公开产物和证据不包含凭据、宿主绝对路径、真实客户身份或未经授权业务原文。

## Dependencies

- FEAT-001、FEAT-005；开工前须确定外部服务化和 Harness 消费方式。

## Shared Contracts

- 待 FEAT-001 与 Harness 跨仓契约评审后登记。

## Decisions

- 2026-09-08：Euphoria_Yan 决定暂缓到 Java 闭环稳定后再实现。
- 2026-09-12：Euphoria_Yan 解除暂缓并接受继续细化；Feature 恢复 proposed，随后由具名接受校验记录正式接受决定。
- 2026-09-12：Euphoria_Yan 确认 US-001～US-004 与 VER-001～VER-008，Feature 进入 L2 规划。
- 2026-09-12：Euphoria_Yan 接受 Python ZipApp、OpenJiuwen 0.1.16 服务形状、Canonical JSON/HTTP、显式 Backend 选择与统一 Evaluation 的 HLD，并批准继续拆分任务。

## Delivery History

- 目标版本：`0.2.0` 后段或后续版本。
- 2026-09-13：完成 Python ZipApp Backend、受管生命周期、显式选择、统一 Evaluation、L2 与运行指南；进入独立审阅，真实银行验收保持未执行。
- 2026-09-13：本地独立复核结论为 GO-WITH-RISK；30 项聚焦测试与 125 项受影响模块回归通过，提交 PR !158，生产 Python 资产发布和真实银行验收继续作为外部前置。
- 2026-09-13：PR 复核发现主线新增金融 Studio 专用 Python Backend 与本 Feature 同名但契约不同；修复采用双实现显式命名，保留主线 `openjiuwen-python-finance`，将本 Feature 明确为 `openjiuwen-python` ZipApp/A2A Backend，并重新进入审阅。
- 2026-09-13：独立复审确认同名冲突、路由边界与回退风险已关闭，结论为 GO-WITH-RISK；外部银行验收和 Harness Catalog 正式资产发布继续作为已知后续事项，Feature 进入待合入状态。
- 2026-09-13：PR !158 合入 `finance`，merge commit 为 `e5efe5e8c7dc0a4df4cdc175d0d3cd5c268a4abc`；尚未记录版本发布证据。
