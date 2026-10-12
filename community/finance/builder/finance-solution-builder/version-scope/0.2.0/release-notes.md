# Finance Solution Builder 0.2.0 发布说明

> 状态：已发布；版本标签为 `finance-solution-builder-v0.2.0`。

## 版本目标

0.2.0 面向 Agent 上线前冷启动。Builder 根据固定的 Solution 与 Finance Harness 资产完成确定性装配，
在同一 Candidate 身份下执行受控构造、Sandbox Evaluation 和限预算优化，并保存可追溯的 Lock、报告与
过程证据。

## 主要能力

- 语言无关的 Candidate Backend 契约，以及 OpenJiuwen Java、Finance Studio Python、标准 Python
  ZipApp 三条边界明确的实现路径。
- 统一的 Construction Agent Backend 契约，以及 OpenCode、Codex 两种受控构造适配。
- 冻结数据边界的 Sandbox Evaluation，包含生命周期管理、逐样本证据和结构化 Finding。
- 受预算约束的优化闭环：依据 Finding 选择修订对象，重新构建并执行回归与一次性 Holdout 验证。
- Harness 资产 Catalog、修订提案、发布后复验绑定，以及只读的优化过程工作台。

## 验证口径

2026-09-15 在基于 `finance@dc881d5f44de28a4d8dc29c944ad29b0ea756ed8` 的隔离工作树中完成代码回归：

- Linux / Python 3.12.3 / Maven 3.9.9 / Node.js 22.14.0：`983 passed, 14 skipped`；
- 14 项跳过包括 1 项需显式授权外部 Codex 调用的可选 smoke，以及 13 项等待 Finance Harness
  发布 Unified Catalog 所需固定资产后的跨仓契约验证；
- 锁定的前端依赖安装、UI 与 AG-UI bundle 构建、Python 编译均通过；
- Ruff 和差异完整性检查在各 Feature PR 中均已通过；本次收口只修改发布文档，不改动源码；
- 本次收口不执行浏览器回放，不把历史证据回放描述为真实银行在线业务评测。

Windows 本地工作台与 Linux 认证服务端使用不同运行前提。Windows 启动前需安装 Maven；Linux 服务端的
私有文件权限、进程锁和符号链接隔离依赖 POSIX 语义。发布验证以完整 Linux 回归为准，Windows 启动说明见
[WebUI 启动指南](../../docs/webui-startup-guide.md)。

## 发布结果

- 发布证据 PR [!173](https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/pull/173)
  已合入 `finance@388dbc099119a060c69551cefdc6e1c78acbfbfe`；
- `finance-solution-builder-v0.2.0` 标签指向上述合入提交；
- FEAT-001～010 均绑定该标签作为发布证据；本次记录回填合入后关闭 Requirement #31。

## 已知边界

- 真实银行在线业务评测依赖获批的数据、系统与 Provider 环境，不属于本次代码回归结果；
- OpenCode、Codex 的外部模型调用与操作系统级隔离能力由部署环境决定；
- 标准 Python Candidate 仍要求 Harness Catalog 发布对应的固定模板与 Runtime Integration 资产。
