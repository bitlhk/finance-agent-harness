# 双线验收准备：工程复跑记录

状态：**Controlled Onboarding Verified / Live Authorization Pending / Independent Human Acceptance Pending**。
这是下一阶段启动与接入材料交付，不是两条真实验收线已完成。

## 本轮改动

- 基于已合并 `647b41c`，实现提交 `07e2b874e5ef6ab18374455aec25f631dc5b194d`。
- 新增 `engineering/run_onboarding.py`，编排现有门禁、记录步骤与退出码/耗时、独立保留受控证据。
- 新增 8 项 wrapper 负向/语义测试；连同原 6 项 Python 测试共 14 项。
- 提供 [两线验收计划](../wealth-readonly-acceptance-v01.md)及[独立接入手册](../onboarding-wealth-v01.md)。
- 未修改 Core、Provider、授权、确认、财富合同、业务数据或 CI 流程，不新增第二套执行引擎。
- 明确旧 EA Smoke 直接填 scopes、落盘原始结果的诊断边界，不把它作为新真实验收入口。

## 实际工程验证

执行者：本轮 AI 编码工具。**不是未参与开发的同事，不能称人工独立接入完成。**
工作副本：从本机正常 Git 仓 `--no-hardlinks --single-branch` 克隆至
`/tmp/finance-harness-onboarding-20260906`，前后 Git 干净，固定源码提交 `07e2b87`。
不是直接复用旧 target；该克隆起始无构建结果。
使用 Linux amd64、JDK 21、Maven 3.8.7、全新 `.venv` Python 3.12.3。
JDK/Maven 已安装，Maven/Pip 使用已有缓存。未测试私仓首次权限开通或全冷环境下载。

命令：

```bash
python3 -m venv .venv
.venv/bin/pip install -r engineering/requirements-conformance.txt
.venv/bin/python engineering/run_onboarding.py
```

| 验证 | 本次观测 |
| --- | --- |
| Java 全量 reactor | 13 模块、29 类、168 项：161 通过，7 项显式真实 Smoke 跳过，无失败/错误 |
| Schema/Fixture | 9 个 Schema、54 项通过 |
| Python | 3.12.3 与独立 3.10.20 环境各 14 项通过 |
| 受控 CLI | 3 COMPLETED / 2 REJECTED；拒绝源读取 0，全部修改尝试 0 |
| 自动入口 | 7 个步骤通过，受控产物收集通过 |
| 误开真实 Smoke | 实际设置 EMPLOYEE_AGENT_LIVE=true 后入口退出 2，预检拒绝，不运行门禁 |
| 本轮自动命令耗时 | 63.446 秒，含 Java 回归、受控 CLI 再构建与报告校验，不含 clone/环境准备、人工操作与证据复制 |
| 模型与数据 | 本地脚本模型、loopback 虚构 Fixture；无真实客户请求 |

报告位置：
`/tmp/finance-harness-onboarding-20260906/target/onboarding/19667b550d5d4500bbf7d5b65e461b63/report.json`。
同目录保存步骤日志、15 个受控结果/Lock/Trace 文件及摘要。
报告分别保留 `liveAuthorization=NOT_RUN`、`realProviderSubstitution=NOT_RUN`、
`independentHumanOnboarding=PENDING_REVIEW`，人工耗时与求助次数为 null。
报告在本机受控目录，未将原始日志或生成文件提交到仓库；异机复现须重新运行。

## 独立接入记录与后续条件

本次没有修改场景专属业务代码或源映射来跑通；仅使用已有五配置和公共机制。
这说明入口在 AI 控制的新克隆可运行，**不证明另一名同事已经完成接入或具备同等效率**。
没有前后可比基线，不给提效百分比。

| 待完成 | 负责人 | 解除条件与入口 |
| --- | --- | --- |
| 确认成熟只读任务、员工及客户范围 | 灯塔财富场景负责人 | 以双线计划为清单提供授权窗口及业务核对依据 |
| 可信当前 Grant 和 adoption-owner 接线 | 技术架构组与 EA 身份/权限 Owner | 取得权威接口/导出规则，补真实受管 wiring 后执行合法、错主体、撤权、过期案例 |
| READY 客户事实/金额口径 | 灯塔组与数据源 Owner | 受保护的脱敏样例及源证据，不能用旧 DEGRADED 或 Fixture 替代 |
| 非原作者操作 | 场景工具组 | 指定同事按手册固定提交复跑，亲自记录耗时、改动、求助和卡点并确认结果 |
| 同条件真实第二 Provider | 技术架构组与源 Owner | 单独准备等价身份/权限/口径；本阶段跨角色演示不验收该项 |

本次未部署、未改真实权限、未读取真实客户、未执行办理；后续材料和人员未确认前不自动越过这些边界。
