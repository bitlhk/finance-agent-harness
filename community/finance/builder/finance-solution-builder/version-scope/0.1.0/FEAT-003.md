---
schema: speckit-feature-record/v1
feature_id: FEAT-003
title: OpenJiuwen Java + Finance Harness Backend
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/28
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:20:05Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-09-04T19:31:43Z'
  decision_source: conversation
delivery:
  phase: planning
  branch: ''
  pull_request: ''
  review_target:
    type: ''
    url: ''
    revision: ''
  merged_commit: ''
dependencies:
- FEAT-001
- FEAT-002
shared_contracts: []
ownership:
  owner: ''
  collaborators: []
release:
  target: 0.1.0
  delivered_in: ''
  evidence: ''
completion:
  completed_by: ''
  completed_at: ''
  evidence_source: ''
architecture_impact:
  level: L1
  update_required: true
  reason: 首个生产形状 Backend 落实新 L1 的 Runtime 与 Harness 装配边界
  affected_files:
  - community/finance/builder/finance-solution-builder/docs/architecture/L1-architecture.md
definition_of_done:
  code_complete: false
  tests_complete: false
  evidence_complete: false
  architecture_synchronized: false
  review_passed: false
---

# FEAT-003: OpenJiuwen Java + Finance Harness Backend

## User Value

Solution 设计人员可以从标准 Solution 输入构建并启动真实 OpenJiuwen Java Candidate，同时锁定 Harness、
Integration、Pack 和 Provider 资产，无需手工修改参考模板。

## Scope

- 复用独立 Finance Solution Builder 已验证的 Schema、资产解析、缓存和 Java 构建能力；
- 固定修订的 OpenJiuwen Java Runtime Template 解析；
- Finance Harness Maven 依赖、Pack、Provider 和 Integration 安装；
- 支持 Query 与 Task 两类能力的 Host Wiring 和 Capability Catalog 装配；
- Candidate 编译、启动、健康检查、停止、Lock 和 Build Report。

## Non-Goals

- 把 Maven、OpenJiuwen Java 或 Harness 依赖提升进 Base；
- 实现银行 Provider 业务逻辑；
- 内嵌真实地址、Token 或模型密钥；
- 用健康检查替代场景 Dryrun 或 Evaluation。

## User Stories

### US-003-01：从标准输入装配 Java Candidate

作为 Solution 设计人员，我希望 Builder 根据已校验并锁定的 Solution 资产生成 OpenJiuwen Java Candidate，以便无需复制参考原型或手工修改模板。

### US-003-02：组合 Query 与 Task 能力

作为场景方案设计人员，我希望同一个 Candidate 能通过 Finance Harness 装配多个 Provider 提供的 Query 和 Task 能力，以便表达“先查询判断、再执行受控动作”的完整业务场景。

### US-003-03：启动并管理真实 Candidate

作为构建任务操作者，我希望 Builder 能编译、启动、检查和停止实际生成的 Candidate，以便后续 Dryrun 和 Evaluation 验证的是本轮产物而不是契约替身。

### US-003-04：获得可复现且不含凭据的交付

作为交付审阅者，我希望 Lock 和 Build Report 准确记录 Runtime、Harness、Integration、Pack、Provider 与模板版本，以便复现 Candidate 并确认交付中没有混入真实凭据。

## Verification

- **VER-003-01** Backend 仅接受通过 Schema、兼容性与资产解析门禁的 Solution Build Context；缺少固定模板或必要资产时不会开始生成 Candidate。
- **VER-003-02** 使用固定修订的 OpenJiuwen Java 模板和 Finance Harness 资产，在干净环境完成 Host Wiring 生成及 Maven 编译，无需修改模板仓中的原始源码。
- **VER-003-03** Candidate Capability Catalog 同时包含至少一个 Harness Query 能力和一个 Harness Task 能力，每个能力可追溯到明确 Provider 与能力合同。
- **VER-003-04** 多 Provider 的注册名称、能力编号或必要配置冲突时，Backend 在编译前拒绝装配并指出冲突资产，不以覆盖顺序静默选择。
- **VER-003-05** Candidate 从统一 Host 入口把 Query 与 Task 请求分发到各自 Harness Provider；Builder 不实现或改写 Provider 内部业务逻辑。
- **VER-003-06** Maven 编译成功后，Backend 返回实际生成 Candidate 的启动入口；启动后的 Candidate 在限定时间内通过就绪检查。
- **VER-003-07** 就绪检查必须验证本轮 Candidate 身份而不只验证端口存活；停止操作可重复执行，且不残留本轮 Backend 拥有的进程。
- **VER-003-08** Asset Lock 与 Developer Manifest 记录实际使用的 Runtime 模板、Harness、Integration、Pack 和 Provider 版本或摘要，并且彼此一致。
- **VER-003-09** 同一固定输入重复构建得到相同资产修订与摘要；已验证的固定资产缓存命中时不重复下载，摘要不一致时拒绝复用缓存。
- **VER-003-10** Candidate、Lock、Report、日志和交付配置不含真实地址、Token、私钥或模型密钥；需要的敏感配置只以环境变量名称和示例占位符表达。
- **VER-003-11** Backend 只报告编译、启动和就绪状态；未执行场景 Dryrun 或 Evaluation 时，不生成业务验收通过结论。

## Dependencies

- FEAT-001 Base Solution Build Protocol；
- FEAT-002 Developer Build Contract 与 Backend SPI；
- 外部：可解析的 Finance Harness 资产与固定提交 OpenJiuwen Java 模板。

## Shared Contracts

无已提升契约；本 Backend 消费 FEAT-001/002 后续评审通过的公共契约。

## Decisions

- 2026-09-05：Euphoria_Yan 接受 Feature 范围与建设价值。
- 2026-09-05：Euphoria_Yan 确认标准格式 User Stories 与 Verification 行为。

## Delivery History
