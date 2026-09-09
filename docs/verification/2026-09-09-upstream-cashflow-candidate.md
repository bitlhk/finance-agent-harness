# 上游同步与收支只读候选交付

日期：2026-09-09。结论：固定上游、保留薄桥、独立沉淀收支只读切片，已完成受控生成和原生运行验证。
**不是生产验收；不修改 Core 或原生 Runtime，不部署线上服务。**

## 固定版本

| 组件 | 实际基线/参与方式 |
|---|---|
| 手机银行上游 | main `4652b285a0bff72dd88aaf00f6acae196b8951e0` |
| 我方 Fork main | 从 `5599b2b` 快进至上述上游 SHA，无增强覆盖 |
| 薄桥集成支线 | `cce6c336a36f58a4e6b82a41da4195e5813cfe6f`；[我方 Draft PR #1](https://github.com/bitlhk/mobile-banking-3.0/pull/1) |
| Harness 实现 | `07d173dfb7f533a9c9d815360e29d492de6465bb`，基于 `b55d0722` |
| Builder 实现 | `cf94d12f0527e1f2fb98f534056e4cf3014de6b7`，独立分支 `feat/harness-cashflow-builder-v01`，基于 PR #122 集成 `cc315956` |
| 模拟器 | `162ef4e5`，只读取合同/SQL；本轮不启动完整模拟器 |
| 实际生成 Runtime | OpenJiuwen Java 0.1.13；没有依赖手机银行整套工程 |

完整 SHA、制品摘要和分项状态见[跨仓版本清单](../integration-acceptance/cashflow-20260909.versions.json)。
后续报告提交只增加文档，不冒充 Candidate 使用了报告之后的源码。

## 设计原则落实

- 一个 Capability Pack 包含两个 QUERY：月度摘要、分类趋势；不是一个接口一个 Maven 模块。
- 一个 Domain Pack/Skill 描述专业步骤与输出；没有新增规划器/意图树或复制 SDK。
- 收支领域模块保留合同与共享消费者；银行字段映射留在 NonAiBanking Provider。
- Core 四合同、BoundFinanceProvider、Integrator 源码相对主线均无修改；复用原有权限、证据和装配机制。
- 模型不能产生主体/权限/默认演示月；未安装余额工具的收支 Agent 无法调用余额或办理能力。
- Builder 没有复制公共金融规则；新增有限配方和宿主能力/实授白名单。不是任意第三方插件自动发现器。
- 原手机银行演示增强不回灌 Harness；Fork 集成支线尚未合入 main，也未向原作者直接合并。

## 源口径复核与修正

详细字段表见[收支 V0.1](../cashflow-readonly-v01.md)。本轮第二次逐条核对 SQL 后修正了初稿口径：

1. 摘要的 policy 声明 BOOKED，但 `statement -> transactions` 的 SQL 没有状态过滤。
   因而输出范围是 `OWN_BANK_ACCOUNT_STATEMENT`，包含 `SOURCE_BOOKED_STATUS_NOT_VERIFIED`，描述为 PARTIAL。
   不凭 policy 声明升级成“已核实的已入账收支”。摘要最多读取 100 个账户也显式保留。
2. 分类查询使用 LEFT JOIN，会保留 UNCLASSIFIED；其金额不能丢弃，分类标签缺失要披露。
3. 分类与趋势末月冲突时保留两份数字并报告 `TREND_TOTALS_CONFLICT`，不当作普通范围差异，也不让模型补平。

这些是源实现与政策的待确认点，不修改模拟器业务真值来迎合测试。
首个 `cf788ea` Candidate 是修正前记录，已归档，不能作为最终收支语义交付。

## 实际验证

环境：Linux x86_64、Java 21、Maven 3.8.7、Python 3.12；已有 Maven 缓存。

| 检查 | 本轮结果 |
|---|---|
| Harness 全量 Maven（最终 Builder 构建内执行 install） | 14 模块；191 项，184 通过，7 项真实 Smoke 跳过；0 失败/错误 |
| JSON Schema/Fixture/资产 | 10 个 Schema、69 项通过；其中收支合同 12 个正反例由 Java/Schema 共用 |
| Harness Python | 21 项通过 |
| Builder 项目 Python | 374 项通过；不冒称包含 Base 全部或人工操作 |
| 最终收支生成 Candidate | 21 项受控原生检查通过；模型请求 9 次、客户业务读取 4 次、政策读取 4 次 |
| 最终旧财富+余额 Candidate | 35 项受控检查通过 |
| 原三种生成组合 | 修正前集成版本分别 33/32/35 项通过；最终版本另重建并验证组合型 35 项 |
| 原五配置财富 CLI | 3 COMPLETED / 2 REJECTED；拒绝业务读取均为零 |
| 手机银行薄桥定向 | 40 项，38 通过、2 个真实 Smoke 跳过；全仓未宣称通过 |

手机银行扩大回归的 7 个失败均在干净 `4652b28` 复现：缺少 snapshot manifest、
3 项 PromptCatalog 类型期望、EnrichmentGoldSet Top-1、组合选项、AgentCard examples。
没有删除这些测试；详情见薄桥分支的 `docs/finance-harness/upstream-sync-20260909.md`。

Harness 实现 `07d173d` 的 [Python 3.10/3.12 CI](https://github.com/bitlhk/finance-agent-harness/actions/runs/34366769993) 已成功。
该 CI 不代替 Builder 跨仓原生生成验收，后者见本地独立记录。

## 复现与资产保留

- Harness：`mvn -B -ntp verify`、`python engineering/verify_contracts.py`、`python -m unittest discover -s engineering -p 'test_*.py'`。
- Builder：`docs/cashflow-readonly-v01.md` 给出 CLI 构建、持续宿主和 `verify_cashflow_service.py` 命令。
- 最终制品：Builder 的 `build/cashflow-readonly-final/candidate`，含 98 个依赖 JAR，其中 5 个 Harness/Provider JAR；共约 158 MiB。
- 本机带缓存构建耗时 31.339 秒，含 Harness 测试安装；不是冷启动基准，也不是提效比例。
- 最终 Lock、构建报告、21 项检查报告及 SHA-256 见版本清单。所有测试 Host 已停止，没有后台演示服务留存。
- 5 份早期/兼容产物的源码、报告与日志归档为约 172 KiB 的 `build/cashflow-prior-runs-evidence.tar.gz`，
  不含 target/依赖 JAR/资产副本，不是可运行交付；对应临时目录已删除。只清理本轮自己生成的产物。

## 仍待验收

| 待办 | 责任/入口 |
|---|---|
| 摘要状态过滤、分类/趋势口径确认 | 手机银行/模拟器 Owner 审阅字段表与 SQL，确认后用完整模拟器复跑 |
| 真实模型是否遵循方法、合理选工具 | 使用最终 Candidate 与显式 LIVE 模型另跑任务质量集；当前脚本模型不证明专业收益 |
| 完整模拟器和真实授权 | 单独配置受控身份，验证合法/错主体/撤权；不借 Fixture 宣称真实 IAM 通过 |
| Builder 维护者接纳 | 独立短分支已推送，不合并或覆盖 PR #122，不宣称上游已接纳 |
| UI 目录与线上部署 | 本轮未开放新菜单、未更新生产服务；验收通过后再启用 |
| 同条件第二 Provider / 非原作者接入 | 分别安排，记录实际修改点/耗时/求助；当前未完成 |

新增场景仍需维护者注册一个有限 Builder 配方；客户方法和源差异可以独立扩展，但不能把这次 AI 自测
写成客户已能零代码任意扩展或真人独立接入成功。
