# Finance Asset Catalog

`finance-assets.v1.json` 是 Builder 可直接读取的静态资产目录。它列出可装配的 Runtime Integration、Profile、Domain Pack、Capability Pack、Provider 和 Eval Pack，并提供三类构建期事实：

- `dependencies`：该资产要求同时安装的其他 Harness 资产；
- `compatibility`：适用的 Runtime、语言或 Profile；
- `governanceRef`：资产 Owner、允许建议的修改范围、审批策略与验证入口。

`contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json` 是 Catalog v1 的权威结构。Builder 必须在固定的 Harness Git revision 上读取并计算 Catalog 内容摘要；依赖缺失、循环、版本冲突或治理信息不完整时停止装配。

目录负责发现、解析和治理投影，不负责远程发布、权限管理或动态注册。`solution.yaml` 仍是装配输入，`solution-lock.json` 记录一次构建实际解析出的完整资产闭包。Builder 对 Harness 资产只有提案权，没有修改或发布权。