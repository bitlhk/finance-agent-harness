# Finance Builder Base

`builder/base` 是金融 Builder 共同架构、严格契约和已验证公共实现的唯一维护位置。它服务于
Intent、Scheduling 及后续 Builder，但不包含某个场景的生成规则、指标、图谱或业务流程。

## 公共能力

- `finance_builder_base.contracts`：严格模型、资源引用、公开安全错误和版本化场景扩展；
- `finance_builder_base.core`：Builder Task、Workspace Context、Manifest、产物关系和候选动作；
- `finance_builder_base.runtime`：本地会话工作区的安全路径解析与目录复制；
- `finance_builder_base.data_factory`：有类型处理单元和可替换的参考组合；
- `finance_builder_base.evaluation`：固定测试快照、逐样本执行、指标、验收规则和报告循环。

Base 不重新实现 openJiuwen AgentApp、A2A、Session 或 Checkpoint，不选择 Orchestrator 的下一步 Agent，
也不管理场景业务数据库。

## 依赖和版本

Base 使用独立的 `0.1.x` 版本线。各 Builder 通过 `community/finance/builder/` 的 uv workspace 引用同一
`finance-builder-base` distribution；Base 不要求发布到外部包仓库，也不与 Intent 或 Scheduling 的版本号绑定。

依赖方向始终是：

```text
Intent / Scheduling / Future Builder
                  ↓
        finance_builder_base
                  ↓
      Python 与显式基础依赖
```

## 纳入规则

公共能力必须同时满足：领域无关、语义稳定、具有清楚的所有者与兼容策略，并由至少两个真实 Builder 或一个
真实 Builder 加明确的未来扩展契约验证。未满足条件的字段和行为留在场景扩展；后续可以通过版本化接口评审提升，
而不是复制或整体搬迁现有模型文件。

## 文档入口

- [公共 L2 架构](docs/architecture/L2-architecture.md)
- [公共 Runtime L1](docs/architecture/runtime/L1-architecture.md)
- [Builder Agent 行为契约](docs/architecture/contracts/agent-behavior.md)
- [Builder Session 契约](docs/architecture/contracts/builder-session.md)
- [通用 Agent L1 模板](docs/architecture/agent-l1-template.md)
- [知识索引](docs/knowledge/README.md)
- [Base/场景扩展指南](docs/knowledge/base-extension-guide.md)

AI Coding 工具还应读取上级 [AGENTS.md](../AGENTS.md)。

## 验证

```powershell
uv run --project community/finance/builder/base --extra test pytest community/finance/builder/base/tests -p no:cacheprovider
```
