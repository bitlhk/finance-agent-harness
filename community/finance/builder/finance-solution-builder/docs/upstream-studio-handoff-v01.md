# Finance Studio 上游源码交接 V0.1

2026-09-12 补充：下文 PR #122 与 Python 待接入描述是历史交接基线。
当前 A4 以 MR #154 对比实际 `finance`，合并保留其 Codex 构造与优化页面，
支持原生 Java/Python 三单元七组合；Python 仍依赖 Java 金融桥。
职责、真实复用与模板保留边界见 [A4 职责审查](python-studio-a4-ownership.md)。
MR 打开、展示部署及维护者接纳是独立状态，不据此改变 Accepted Feature 状态。

更新日期：2026-09-09。状态：**PR #122 Draft / Maintainer Acceptance Pending**。

## PR #122 增量整理

- 继续使用 [PR #122](https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/merge_requests/122)，
  源分支 `feat/harness-studio-webui-upstream-v01`，目标 `finance`；不另建 PR，不自动合入主线。
- 原 PR 提交为 `ca71f04b`，本次纳入 Studio `dc9f2cff` 及其目录、模板业务模块、显式 Runtime
  选择和紧凑模板选择器增量，并合入上游 `b43614673da9db290e2d8f2033eb5eecaabe9f54`。
- 保留上游 Candidate Backend、Construction Agent Backend、契约、特性记录和构造讲解页。
  Base 使用上游 `>=0.1.0,<0.2` 范围；Studio 的 AG-UI 依赖及其 Python 版本约束继续保留。
- 本次贡献相对目标主线仍只涉及本模块；其他 Builder 的提交只是同步上游，不属于本 PR 新增功能。
- 已有展示部署与本次集成基线分别记录；不因更新 PR 宣称展示服务已升级，
  也不把旧版真实模型结果当作新合并基线验收。

### 当前 Backend 边界

上游 0.2.0 已实现 FEAT-001/002 Candidate Backend 与 FEAT-003 Construction Agent Backend
公共边界。传统 CLI Candidate 使用上游实现。本 PR 的 `local-read-only` 持续宿主及
`shared-read-only` 回归入口仍委托 `service.py` / `readonly.py`，没有迁移到新 Backend SPI。
因此它们是显式隔离、待维护者接纳的原型入口，不是另一条已认可的 Java 生产路径。
页面对 OpenJiuwen Python 的展示也只表示待接入，不代表 FEAT-010 已完成；不回退成 Java。

本批不修改 Accepted 架构/契约以适配原型，不把模板数量等同于已接通金融能力。
仅财富事实、余额、两者组合可构建，仍待接入的模块、角色和 Runtime 必须在构建前拒绝。
后续 Backend、标准构建事件与 WebUI 的迁移须由维护者按 0.2.0 Feature 明确安排。

本次同步及回归记录见 [PR #122 集成验证](pr122-integration-20260909.md)。

## 首次交付基线（历史记录）

- GitCode MR #118 已合并，源提交 `0e81b6ada202e891317d5b9a03b20cb2ce032119`。
- 本批基于当日主线 `finance`：`c04be801f8452adb10a90633aee8fddb8a6b8e77`。
- 建议源分支：`feat/harness-studio-webui-upstream-v01`；目标分支：`finance`。
- 原型来源为本地 Builder `107657dd` 及其前序实现；以净增量整理，不将私下部署历史并入上游。
- 原型不是只含前端的补丁。持续体验页面依赖构建 API、模型绑定、实例管理和 Java 薄宿主。

本批全部变更限于 `community/finance/builder/finance-solution-builder/`。
不修改 Base、其他 Builder、已接受契约、Feature 完成状态、同事的构造讲解页或其测试。
没有新建 Builder Fork。源码贡献与展示站部署分别验收；旧 Agent 保留原构建身份，
不能在升级 Builder 后将旧产物重新标记为新版本。

首次整理基线为 `83ac9351`；本轮同步其后的调度知识样例更新，并重新运行
Builder 193 项、Base 72 项及浏览器 34 项回归。Base 两项跳过分别是可选 Intent
消费者未安装和 Windows 专用路径检查。生成服务、展示站及真实模型的实际结果
单独记录，不由这些回归数字推导。

## 代码在哪里

| 目录/文件 | 内容 |
| --- | --- |
| `browser/workbench.js` | Studio 选择、真实构建、实例操作、模型切换、对话和指导入口 |
| `browser/markdown.js`、`browser/ag-ui.js` | 安全 Markdown 与官方 AG-UI 客户端接线 |
| `examples/local-workbench/` | 页面、样式、Lucide 资产及已打包 JS；不是孤立图片或运行目录 |
| `src/finance_solution_builder/workbench*.py` | HTTP 接线、有限组合、构建/实例/会话状态与公开视图 |
| `model_registry.py`、`server_access.py` | 服务端模型绑定、已认证操作者及数据归属 |
| `readonly.py`、`service.py` | 受控 CLI Candidate 与持续服务的装配入口 |
| `templates/local-readonly-service/` | 标准 OpenJiuwen Java 与 Harness 上的可携带薄宿主 |
| `tests/`、`engineering/verify_*.py`、`engineering/verify_*.cjs` | 单元、并发、原生服务、浏览器和显式外部验收入口 |
| `deploy/server/` | 通用部署模板、公开精简文档与登录保护路由 |

`examples/build-visualizer/` 是同事的构造说明/历史报告页面，完整保留；
`examples/local-workbench/` 是本批实际构建、启动和持续体验入口。两者不互相覆盖。
后续是否统一导航，由维护者评审确定，不通过复制第二套 Builder Base 实现来整合。

## 与新标准流程的关系

上游包含已接受的 `CONTRACT-SOLUTION-BUILD-V1` 及 0.2.0 Backend 等建设计划与实现。
本批仍使用既有 `SolutionBuilder` 和只读服务报告/HTTP 投影，不宣称已经消费全部新公共事件。

| 已有原型可交接的实现 | 仍需维护者确认或后续实现 |
| --- | --- |
| 三种只读组合、真实进程、实际装配和依赖锁 | 接到 Base Solution Build Coordinator/Stage Runner，统一事件和失败分类 |
| 当前任务的构建状态、日志和结果视图 | 接到标准 Build/Dryrun 事件；补齐 Feature 规定的逐 Case 事件与断言视图 |
| 已安装能力、模型绑定、启动停止、会话及安全修复 | 是否按新版 Backend/UI 分批接纳，以及原型生命周期如何迁移 |
| 受控验证与显式真实模型入口 | 本次新主线组合的完整模拟器与真实模型重验、维护者独立复跑 |

公共标准的“存量逐步适配”原则继续适用；不降低它的阶段、唯一终态、取消恢复、脱敏要求。
**在维护者确认迁移边界前，本批只作为待评审原型贡献，不作为 0.2.0 标准特性已完成的证据。**
此次没有为了迁就原型去修改 Accepted 契约，也没有默认启用办理或反诈转账。

## 复现与接入前提

在本模块目录准备 Python 3.11.4 至 3.13、Node 22、Git；实际 Candidate 另需 JDK 21、Maven。
维护者须取得允许使用的 Finance Harness 固定版本、Pack 和依赖制品，不包含在前端源码中。

```bash
python3 -m venv .venv
.venv/bin/pip install -e ../base -e . pytest
.venv/bin/python -m pytest tests -q
npm ci --ignore-scripts
npm run build:ui
```

可选浏览器检查需安装 Playwright 和 Chromium，再按
[Markdown 回归](markdown-rendering-v01.md)、[Studio 操作说明](local-workbench-v01.md)
和 [API 验证](workbench-api-v02.md) 执行。前端 bundle 从 `browser/` 生成，不能只修改压缩 JS。
模型配置、模拟银行 introspection 与授权策略按说明单独提供。缺配置必须明确拒绝，不能用脚本答案冒充模型。
默认只读的财富/余额/组合均不应注册办理工具；有副作用能力仍另行验收。

原生时序验收保留：源查询已发出、返回前撤权后，下一次模型 HTTP 调用必须被阻断。
普通浏览器渲染通过不能替代它。归档删除、旧实例异步回包、会话隔离也需沿真实运行入口检查。

## 发布边界

- 排除站点专属 release JSON、公网部署记录、内部完整指南、实际凭据和运行产物。
- 通用部署目录、模板和受控测试保留；历史验证数字只属于原版本，不作为本次新测试。
- 指导入口的 HTML/PDF 由部署 Owner 单独发布，经审阅的公开版和受保护内部版严格分开。
- 修改密码或模型配置不属于代码提交，不能进入 Git、截图或 PR 正文。
- GitCode 发布需有效凭据及相应仓库权限。匿名读取成功不等于可推送或创建 PR。

## 首次交付验收（历史记录）

本次仅做本地源码整理和回归，实际结果见交付补丁旁的验证报告。
GitCode PR URL、远端 CI、维护者接纳、真实模型/完整模拟器重验及真人接入均不补填为 PASS。
下一步先评审标准协议迁移边界；经确认后再以同一组固定组件进行完整生成与体验验收。
