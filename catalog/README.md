# Finance Asset Catalog

`finance-assets.v1.json` 是 Builder 可直接读取的最小静态资产目录。它列出可装配的 Runtime Integration、Profile、Domain Pack、Capability Pack、Provider 和 Eval Pack。

目录只用于发现和选择资产，不负责远程发布、权限管理或动态注册。`solution.yaml` 仍是装配输入，`solution-lock.json` 记录一次构建实际解析出的版本和能力绑定。
