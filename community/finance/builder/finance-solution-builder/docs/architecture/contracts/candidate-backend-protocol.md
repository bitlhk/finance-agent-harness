# Candidate Backend Protocol v1 兼容入口

Candidate Backend v1 的权威契约已经提升到 Finance Builder Base：

- [Candidate Backend Protocol v1](../../../../base/docs/architecture/contracts/candidate-backend-protocol.md)
- 权威 Python API：`finance_builder_base.candidate_backend`
- 权威机器 Schema：`community/finance/builder/base/contracts/json-schema/candidate-backend.v1.schema.json`

Finance Solution Builder 保留 `finance_solution_builder.candidate_backend` 作为向后兼容导入路径，并继续持有 OpenJiuwen Java/Python、Maven、Harness 等具体 Backend。兼容路径只允许 re-export Base 权威模型、SPI、Registry 和通用 Python 受管运行，不能复制类型定义或摘要算法。

仓内旧 Schema 文件作为兼容镜像保留，并由测试逐字比较 Base 权威 Schema。任何差异都阻止合入。
