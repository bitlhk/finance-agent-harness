---
schema: speckit-shared-contract/v1
contract_id: CONTRACT-CANDIDATE-BACKEND-V1
title: Candidate Backend Protocol v1
status: accepted
owner: Finance Builder Base
collaborators:
  - Finance Solution Builder
  - Dialogue Management Builder
review:
  decided_by: Euphoria_Yan
  decided_at: '2026-09-20T03:23:05Z'
related_features:
  - finance-solution-builder/FEAT-001
  - dialogue-management-builder/FEAT-008
compatibility: backward-compatible
---

# CONTRACT-CANDIDATE-BACKEND-V1: Candidate Backend Protocol v1

## Purpose And Boundary

本契约统一开发态 Agent Candidate 的静态身份、产物验证和受管生命周期，使不同金融 Builder 可以在不理解具体语言、构建工具或 Runtime 私有细节的前提下完成构建、启动、健康检查、调用、证据采集和停止。

Base 只持有领域无关模型、SPI、确定性选择、产物校验和通用受管运行构件。它不选择业务资产，不实现 OpenJiuwen、Maven、Finance Harness、外呼流程或任何银行 Provider。

权威 Python 实现位于 `finance_builder_base.candidate_backend`；`finance_solution_builder.candidate_backend` 保留旧路径兼容导出。

## Consumers

- Finance Solution Builder 的 Java、Python 与 OpenJiuwen Candidate Backend；
- Finance Solution Builder 的 Evaluation、Optimization 和 Workbench；
- Dialogue Management Builder 的 Candidate 装配与基础运行验证；
- 后续需要同一 Candidate 生命周期的金融 Builder。

## Inputs

- 稳定构建任务身份与 Candidate 目标；
- 已解析、已锁定并实际参与装配的资产引用；
- 目标语言、Runtime、产物类型、调用协议和必需生命周期动作；
- 已声明的能力、工具、Provider 操作与 Host Wiring 约束；
- 启动前对交付目录实际产物完成的路径和 SHA-256 校验记录；
- 只包含公开、脱敏内容的调用载荷或受控资源引用。

具体 Backend 的专有信息只能进入带类型和 Schema 版本的扩展区，不能成为上层流程必须理解的公共字段。

## Outputs

- 与实际产物一致的 Candidate Descriptor；
- 各生命周期操作的标准结果、稳定状态和公共错误；
- 与任务、Candidate、Descriptor、Lock 和 Backend 身份一致的 Run Handle；
- 受控公共端点与证据引用；
- 可幂等执行的停止结果。

## Semantics And Invariants

1. 生命周期顺序为预检、装配、构建、启动、健康检查、调用、证据采集和停止；不适用动作必须显式返回 `skipped`。
2. Registry 只能按显式 Backend 身份或唯一完整匹配选择实现；零匹配、多匹配和不兼容均失败关闭，不静默回退语言、Runtime、Provider 或场景。
3. Candidate Descriptor 必须绑定任务、Candidate、Backend、Runtime、实际产物及摘要、入口、调用与健康协议、Lock 摘要以及实际安装资产和能力。
4. 启动前必须重新校验交付目录内实际产物。目录越界、文件缺失、摘要或 Descriptor 不一致时不得启动。
5. 成功启动只能返回 `RUNNING` Handle 和至少一个公共端点；调用、证据与停止必须验证同一生命周期身份。
6. 停止操作必须幂等。构建、启动、健康检查、调用失败或调用方取消后，不得遗留本轮 Backend 管理的活动实例。
7. 所有公共结果只包含稳定阶段、状态、公开摘要、错误和证据引用，不公开进程号、宿主绝对路径、凭据、真实业务原文或模型内部推理。
8. Base 公共模型是唯一权威类型；兼容路径只能 re-export，不能复制定义或形成第二套摘要算法。

## Errors And Boundary Cases

- Backend 不存在、匹配歧义、工具或资产缺失、能力覆盖不足时，在有副作用前返回稳定的 blocked/failed 结果。
- Descriptor、Lock、产物、Run Handle 或请求身份不一致时拒绝后续操作，不合并跨轮证据。
- 部分启动后失败仍先采集可用证据，再执行清理；未执行阶段不得标记为成功。
- 外部服务 Backend 可以保留自己的异步传输，但必须映射到相同动作顺序、结果语义和清理保证。
- 公共内容发现凭据、生产地址、客户信息或宿主路径时失败关闭且不回显原值。

## Compatibility

- 本次迁移不改变 Candidate Backend v1 的 wire schema、枚举、规范化序列化、摘要、Backend ID 或生命周期语义。
- `finance_solution_builder.candidate_backend` 在迁移期保留全部既有公开导入，返回的对象必须与 Base 权威类型具有相同类型身份。
- 通用 Python 受管运行使用领域无关证据 Schema；具体 Backend 可显式保留自己的历史证据标识，确保既有运行链迁移前后行为一致。
- Java、OpenJiuwen Python、Python ZipApp 等具体 Backend 继续由 Finance Solution Builder 持有；Base 不新增这些依赖。
- v1 可以增加具有默认值的可选字段；删除、重命名、收窄状态或增加语言专有公共必填字段需要新契约版本。
- 回滚时可恢复 Finance Solution Builder 原实现，但不得让两个权威模型同时对外可用。

## Verification

- Base conformance 覆盖合法/非法 Descriptor、唯一选择、无匹配、歧义、摘要绑定、篡改产物、完整生命周期、阶段失败截断、身份关联、重复停止和敏感信息反例；
- 兼容测试断言迁移前后的 wire schema、canonical bytes、摘要和旧导入路径行为一致；
- Finance Solution Builder 的既有 Java/Python Backend、Evaluation、Optimization 和 Workbench 回归保持通过；
- Dialogue Builder 通过相同契约完成一个真实本地 Candidate 的装配、启动、健康检查、脱敏调用、证据采集和停止；
- Base 依赖检查确认未引入 OpenJiuwen、Maven、Finance Harness 或场景模块。

## Decisions

- 2026-09-20：Euphoria_Yan 接受 Candidate Backend v1 的领域无关部分提升到 Finance Builder Base；Finance Solution Builder 保留兼容导出和具体 Backend，Dialogue Management Builder 通过 linked PR 消费公共契约。决策证据：https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/32#comment_190618784
