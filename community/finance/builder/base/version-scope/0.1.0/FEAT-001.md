---
schema: speckit-feature-record/v1
feature_id: FEAT-001
title: 建立并验证 Builder 公共基座
parent_requirement: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/13
acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-08-18T03:07:31Z'
  decision_source: conversation
behavior_acceptance:
  status: accepted
  decided_by: Euphoria_Yan
  decided_at: '2026-08-19T01:27:16Z'
  decision_source: conversation
delivery:
  phase: done
  branch: wip/feat-001-builder-base
  pull_request: 'https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/13'
  review_target:
    type: local-diff
    url: ''
    revision: sha256:ccfc0b69e89df46849aa9653b6472851596a1a47b69598edc4e37e9397e1e1b5
  merged_commit: '564926ea'
dependencies: []
shared_contracts:
- community/finance/builder/base/docs/architecture/contracts/agent-behavior.md
- community/finance/builder/base/docs/architecture/contracts/builder-session.md
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
  level: L2
  update_required: true
  reason: 跨 Builder 公共架构资产和复用机制将迁入统一维护位置，并由两个 Builder 验证。
  affected_files:
  - community/finance/builder/AGENTS.md
  - community/finance/builder/base/docs/architecture/L2-architecture.md
  - community/finance/builder/base/docs/architecture/agent-l1-template.md
  - community/finance/builder/base/docs/architecture/runtime/L1-architecture.md
  - community/finance/builder/base/docs/architecture/contracts/agent-behavior.md
  - community/finance/builder/base/docs/architecture/contracts/builder-session.md
  - community/finance/builder/base/docs/knowledge/base-extension-guide.md
  - community/finance/builder/intent-agent-builder/docs/architecture/runtime/L1-architecture.md
  - community/finance/builder/intent-agent-builder/
  - community/finance/builder/scheduling-agent-builder/
definition_of_done:
  code_complete: true
  tests_complete: true
  evidence_complete: true
  architecture_synchronized: true
  review_passed: true
---

# FEAT-001: 建立并验证 Builder 公共基座

## User Value

作为金融 Builder 的维护者，我希望跨 Builder 的架构规则和已验证公共机制位于不归属于某个具体
Builder 的公共目录，以便 Intent 与 Scheduling Builder 引用同一份公共定义和实现，避免形成
相互漂移的副本。

## 术语说明

- **公共核心**：不包含具体业务场景规则、供多个 Builder 共用的定义和实现。
- **场景扩展接口**：具有明确数据结构和校验规则、由具体 Builder 实现的扩展点。
- **处理单元**：可单独调用并按需组合的一项生成、质检、划分或打包能力。
- **Builder Core**：描述一次构建任务、工作区信息、产物清单、产物关系和候选下一步动作的公共模型。

## Scope

- 建立 `community/finance/builder/base/` 的 README、文档、源码、测试和版本范围骨架；
- 将通用 Agent L1 模板、Runtime L1、Builder Session 和 Agent 行为契约迁入公共架构目录；
- 将 Intent 专属描述从公共文档中移至 Intent 场景说明，公共正文只保留跨 Builder 的共同规则；
- 统一工作区文件操作的安全规则：路径必须位于受信根目录内，拒绝越界、符号链接和目录重叠，
  复制过程不得覆盖既有目标或带入临时状态；
- 统一跨 Builder 契约模型的基础规则：拒绝未声明字段、赋值后继续校验、按约定字段返回错误；公共基座定义
  稳定的领域无关公共核心和场景扩展接口，Intent 与 Scheduling 作为首批一致性验证者，而不是未来
  能力上限；后续 Builder 可以通过具有明确数据结构的扩展模型、组合或注册机制接入，无需修改公共核心；
  日程系统、关系图谱等领域字段本期先由对应扩展拥有；后续满足稳定、领域无关和复用条件时，
  可以经过版本化接口和兼容性评审后迁入公共核心；
- 建立可运行、可验证的 Evaluation 与 DataFactory 公共基类：Evaluation 基类固定评测流程、
  结论规则、评测开始时固定且执行期间不可修改的测试输入和取消语义，场景扩展负责调用适配、指标与
  Bad Case 维度；DataFactory 基类只定义生成规格、交付结构及背景生成、样本合成、质检、划分、
  打包等输入输出明确的处理单元接口，不固定处理单元的编排流程；Base 可以提供一套默认组合实现，
  场景扩展或 Orchestrator 也可以组合相同处理单元；
- 两个基类必须由 Intent、Scheduling 的真实场景适配实现或针对公共接口的契约测试验证，不允许只提交空方法、
  `pass` 或固定返回值形成的占位实现；
- 建立 `base/core` 的 Builder Task（一次可追踪的构建任务）、Workspace Context（当前会话的
  工作区信息）、Manifest（产物清单）、产物关系和候选下一步动作模型，为 Orchestrator 提供
  结构化决策依据；公共核心不规定下一步调用哪个 Agent，也不固定 Orchestrator 的选择策略；
- 为公共实现提供稳定且显式的仓库内引用方式，并由两个 Builder 的真实调用路径验证；具体采用
  可独立安装的 Python 包、workspace 依赖或随 Builder 构建打包，由后续 L2/Plan 决定，本 Feature
  不预设独立发布单元；
- 更新仓库内所有受影响链接、依赖说明和 Builder 资产清单。

## Non-Goals

- 不在本 Feature 中实现由 Builder 生成并等待测试的 Agent 的完整进程管理；
- 不改变 openJiuwen AgentApp、A2A、Session 或 Checkpoint；
- 不在公共基类中实现完整的 Intent、Scheduling 领域生成、指标和归因逻辑；这些能力由场景扩展
  提供，但公共基类和扩展点本身必须具备可执行行为并通过测试；
- 不在 `base/core` 中实现固定的 Agent 调用顺序、迭代状态机或下一步 Agent 的选择策略；
- 不要求公共基座独立发布到包仓库，也不预设其必须成为可独立安装的 Python 包；
- 本期不直接把 Scheduling 现有的关系图谱、日程系统、标准操作或业务规则模型原样提升为公共
  核心；它们先由 Scheduling 扩展拥有。后续如果 Graph Builder 等新场景形成了稳定、领域无关且
  可复用的语义，可以经过版本化接口和兼容性评审后迁入公共能力，而不受本期目录归属限制。

## User Stories

### US-001: 在固定位置维护跨 Builder 的共同规则

作为金融 Builder 维护者，我希望跨 Builder 的 Runtime、Session、Agent 行为与 L1 设计规则只在
`builder/base` 维护一份，以便 Intent、Scheduling 及后续 Builder 引用同一份公共定义，各场景
只描述自己的差异。

### US-002: 安全地处理会话工作区材料

作为 Builder 开发者，我希望复用一致的工作区路径解析与目录复制规则，以便处理产物时不会越出当前
会话的受信目录、覆盖已有结果或复制临时状态。

### US-003: 使用可演进的公共契约与 Builder Core

作为当前或未来的 Builder 作者，我希望公共核心提供经过严格校验的通用模型，包括 Builder Task、
Workspace Context、Manifest、产物关系和候选下一步动作，同时允许具体 Builder 通过场景扩展接口
补充业务信息，以便新增 Builder 不必复制公共实现，也不会破坏既有输出数据结构。

### US-004: 组合 DataFactory 处理单元而不受固定流程约束

作为 DataFactory 场景开发者或 Orchestrator 开发者，我希望复用生成规格、交付结构和输入输出
明确的处理单元接口，并按场景声明或在运行时组合处理单元，以便仓库提供的默认组合和由
Orchestrator 决定执行步骤的动态编排都能成立。

### US-005: 通过确定的 Evaluation 流程获得可信结论

作为 Evaluation 场景开发者，我希望复用固定的评测流程和结论判定规则，只实现调用、指标、
预期结果（expected）类型与 Bad Case 扩展，以便不同场景在测试输入、进度、取消、部分结果和
达标判断上保持一致。

### US-006: 用两个真实 Builder 验证公共能力

作为公共基座维护者，我希望 Intent 与 Scheduling Builder 都通过明确、可复现的仓库依赖方式
使用公共能力，以便确认公共设计确实可被不同 Builder 复用，并通过实际使用方的兼容性测试阻止
不兼容修改。

## Verification

- `VER-001`: `builder/base/README.md` 明确公共资产纳入规则、依赖方向、非职责，以及 Base
  `0.1.x` 与各 Builder 版本线相互独立。
- `VER-002`: 公共 Runtime L1、Builder Session、Agent 行为契约和通用 L1 模板具有唯一公共
  路径；仓库相对链接检查通过，Intent 与 Scheduling 均能从 README 导航到公共入口。
- `VER-003`: 公共 Runtime L1 不把固定数量、名称或场景类型的 Agent 规定为所有 Builder 的
  必选组成；Feature 完成时 `shared_contracts` 和其它元数据已更新到新的公共路径。
- `VER-004`: 合法工作区内路径可解析；`..` 越界、越界符号链接、不存在或类型错误的必要目录
  均以统一且可识别的异常类型拒绝。
- `VER-005`: 目录复制在源目标相同、互为父子或目标已存在时失败且不覆盖；Git 元数据、虚拟
  环境、测试缓存和 Python 字节码不会进入复制结果。
- `VER-006`: 公共核心模型拒绝未声明字段并在赋值时继续校验；错误按约定字段返回，且不泄露
  服务端绝对路径或凭据。
- `VER-007`: Intent、Scheduling 及一个用于测试的未来 Builder 可通过同一类场景扩展接口接入；
  新增扩展不需要修改既有公共核心，也不改变既有扩展的输出数据结构。
- `VER-008`: Builder Core 能表达构建任务生命周期、工作区信息、产物清单、产物关系和供
  Orchestrator 选择下一步动作的结构化候选结果；两种选择策略可基于同一状态得到不同的下一步
  动作，公共核心不包含固定 Agent 顺序。
- `VER-009`: DataFactory 的生成规格、交付结构，以及无背景直生、先背景后样本、结构化规则生成、
  文本模型生成等选择规则和背景生成、样本合成、质检、划分、打包处理单元，均具有明确且稳定的
  输入输出类型；仓库提供的默认组合可通过测试扩展完成完整交付。
- `VER-010`: 另一项组合测试可重新组合相同 DataFactory 处理单元，而无需覆盖由父类预先固定的
  流程；缺少必要处理单元时返回明确 `unsupported`，不静默回退。
- `VER-011`: Evaluation 基类通过测试扩展完成“固定测试输入 → 逐样本执行 → 指标 → 验收规则 →
  报告”的确定性循环，并输出进度和可追溯结果。
- `VER-012`: Evaluation 在取消时保留部分结果；证据不足返回无法判定而非达标；封闭式（closed）
  与开放式（open）判定按约定分别处理。
- `VER-013`: Intent 与 Scheduling 各自通过真实场景适配实现或针对公共接口的契约测试，验证
  公共 Core、Runtime、DataFactory、Evaluation 中适用于自身的接口，并保持各自面向用户的
  业务错误含义。
- `VER-014`: 两个 Builder 通过可重复安装或构建的仓库内依赖方式引用同一公共实现；具体形式经
  L2/Plan 评审，不依赖源码复制或临时运行路径注入。
- `VER-015`: 公共基座及两个 Builder 中受影响模块的针对性测试通过；公共实现扫描不包含完整
  Intent、Schedule、RelationshipGraph 等场景业务模型。

## Dependencies

无。

## Shared Contracts

本 Feature 将现有 Builder Agent 行为契约与 Builder Session 契约迁移到统一的公共维护位置。
新增代码接口先作为待验证的公共接口，在两个 Builder 完成验证并通过架构评审后再确定长期维护路径。

## Decisions

- 公共文档与 Python 源码分层存放，避免 `runtime/`、`contracts/` 同时承担文档目录和包目录。
- 公共基座承诺稳定引用，并且公共实现只维护一份；Plan 评审后确定使用仓库内可安装的
  `finance-builder-base` distribution 与 uv workspace，不要求发布到外部包仓库。
- Scheduling `security.py` 中的安全行为按语义重构进入公共 Runtime；`models.py` 只提炼两个
  Builder 已验证的领域无关核心、接口校验规则和场景扩展接口，不按文件整体搬迁，也不把两个现状模型
  的字段交集当作公共能力上限。
- 公共核心允许随新增 Builder 和实际复用情况演进；将场景扩展迁入公共定义时，必须重新确认该定义
  由谁维护、如何保持兼容以及哪些使用方会受影响，不能以移动现有模型文件代替抽象评审。
- Evaluation 与 DataFactory 基类属于本 Feature 的实际交付，不是后续占位目录；完整场景逻辑
  留在 Intent、Scheduling 扩展中，DataFactory 的最终行为边界继续服从 Issue #11 的接受结论。
- DataFactory 的默认流程只是可替换的组合实现，不属于子类必须继承的固定执行流程；Evaluation
  为保证结果可复现，并统一取消和部分结果的处理规则，保留职责有限且顺序固定的评测流程。
- 由 Orchestrator 根据当前状态决定下一步 Agent 的能力保留在 Builder 层；`base/core` 只提供
  决策所需的状态和候选结果模型，不负责实现 Orchestrator 的选择策略。
- Scheduling 内部的 `ResourceRef`（`kind=filesystem`）保留为包内运行时路径句柄，不作为公共
  引用模型；跨进 base 契约前统一经 `to_base_resource()` 转换为工作区相对引用，越界即拒绝。
  这是经评审确认的偏离记录：公共可移植引用以 base `ResourceRef` 为唯一语义。

## Delivery History

- 2026-08-18：由 Issue #13 拆分为候选 Feature。
- 2026-08-18：Euphoria_Yan 接受 Feature 范围；具体行为仍待 Specify 阶段确认。
- 2026-08-18：根据审阅意见明确 Evaluation 与 DataFactory 可执行公共基类属于交付范围，排除的
  是空占位实现和完整场景业务上移。
- 2026-08-18：根据 Issue #13 讨论进一步明确基类职责：DataFactory 定义接口与处理单元但不固定
  编排流程；Evaluation 保留固定评测流程；`base/core` 定义结构化状态但不接管 Orchestrator 决策。
- 2026-08-19：完成 Plan/Task 评审，采用 Base-first：先交付 Base、Intent 适配和 AI Coding 知识入口，
  Scheduling 通过后续关联 PR 接入；架构影响由 L1 更新为 L2，进入 `tasks-ready`。
- 2026-08-19：Base-first 本地质量回路完成，结论为 GO-WITH-RISK；Feature 整体仍处于 `implementing`，
  `review_passed` 保持 false，等待 Scheduling T008 与最终 T009。
- 2026-08-19：Scheduling T008 已实现 Base workspace、公共路径安全和带版本场景扩展适配，领域请求与
  结果序列化保持兼容；因当前 Scheduling 分支晚于原 Plan 源码基线，待刷新范围门禁后正式验收。
- 2026-08-19：以包含 Base、Intent 与 Scheduling 的 WIP 修订刷新 Plan；Euphoria_Yan 接受单一最终 PR、
  统一验收与整体回滚边界。重建后的任务先确认 T008 证据，再执行 T009 跨消费者集成与证据汇总。
- 2026-08-19：T008 安装、定向适配和非 E2E 回归通过；T009 新增 Intent、Scheduling 与未来 Builder
  跨消费者契约测试并完成 CodeGraph、静态扫描和实施证据刷新，等待独立 Review。
- 2026-08-19：本地 diff Review 第 1 轮修复 Base-only 测试收集对场景包的强制依赖；第 2 轮结论为
  GO-WITH-RISK，Feature 进入 `ready-to-merge`，保留环境相关风险供最终合入决策。
