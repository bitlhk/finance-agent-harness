# 收支只读切片 V0.1 开发与口径

定位：在不修改 Core、Integrator 或 OpenJiuwen 的条件下，沉淀第三类只读场景。
不是复制手机银行，也不是把每个 API 变成独立包。

## 组件与贡献入口

| 内容 | 本轮交付 | 客户扩展位置 |
|---|---|---|
| 公共基础 | 沿用 Context/Request/Result/Evidence、BoundFinanceProvider 和原生工具安装 | 本轮不改 Core |
| 业务工具清单 | 一个 `cashflow-readonly` Pack，摘要、分类趋势两个 QUERY | 新事实边界才新增 Capability |
| 专业工作方法 | 一个 `cashflow-review` Skill：账期、范围、分类、趋势、缺口 | Domain Pack 正文及案例 |
| 统一业务数据定义 | `finance-cashflow-facts.v0.1`，金额十进制字符串 | `packages/java/cashflow`，候选版本评审 |
| 企业适配 | 源身份绑定复用、政策读取、确定性字段映射 | Provider 中的 `CashflowMapper` |
| 场景使用边界 | `cashflow-read-only` Profile | 配置，不是实授权限 |
| 生成入口 | 配套 Builder 的有限只读配方与原生持续宿主 | Builder 原仓短分支，不复制 Base |

## 字段定义与依据表

来源证据：模拟器 `162ef4e587c9e9b781bab60bf89591cf4255e579` 的 Java 合同、Facade、
SQL 和迁移；手机银行 `4652b285a0bff72dd88aaf00f6acae196b8951e0` 的 finance-assistant
能力声明/Skill。`conformance/fixtures/cashflow/source` 为按合同构造的合成数据，**不是真实返回**。
没有逐字段核实标准条款，不宣称 ISO 20022 或其他标准符合性。

| 业务含义 | 源字段/接口 | 转换与适用范围 | 验证与确认状态 |
|---|---|---|---|
| 查询账期 | 两接口 `period` | 显式 YYYY-MM，响应必须同月；缺省询问，不能落到固定演示月 | 非法/月缺失负例；Owner 待确认 |
| 主体 | bank session 的 customerRef | 可信宿主绑定到 Context.subjectRef；带 non-ai-banking 命名空间，不猜跨系统 ID | 错主体/源会话/撤权负例 |
| 收入、支出、净额 | `totalIncome/totalExpense/netCashflow` | 十进制字符串、两位小数、不舍入；净额=收入-支出 | 大额精度、零、缺数、矛盾负例 |
| 币种、入账状态声明 | 两个 `/readiness` 的当前政策 | 只接受 CNY/BOOKED 声明；主响应无币种，不静默补默认；声明不等于 SQL 已过滤 | 非 CNY/版本/写策略不支持时业务读取为零 |
| 摘要范围 | BankingFacade.financialTransactionSummary / SimulatorBankingStore.statement | 本行账户查询前 100 项的月度账单；SQL 未过滤状态，不称“已核实的已入账流水” | `ACCOUNT_LIST_LIMIT_100` + `SOURCE_BOOKED_STATUS_NOT_VERIFIED`，PARTIAL；Owner 待裁决 |
| 分类范围 | SimulatorBankingStore.spendingAnalysis | BOOKED + CNY 流水与分类表左连接，保留 UNCLASSIFIED 及其金额 | 缺少分类标签显式报告；分类合计与自身总额核对 |
| 趋势 | `trend` + 政策 `trendMonths=6` | 包括未分类的已入账流水；六个月顺序，末月为请求账期 | 同分析末月总额冲突标 PARTIAL；不解释为正常差异，不推断修复 |
| 信用卡账单 | `creditCardStatementBalance` 等 | 保留原源能力；不合并进共同账户收支总额，不复制私有卡号 | 2000 元账单不加入 1250.50 元账户支出 |
| 数据时间 | `data.asOf`、政策 asOf、观察时间 | 独立记录来源/政策/观察时间；缺时间为 PARTIAL，未来时间拒绝 | 时间缺失/未来负例 |
| 来源版本 | summaryVersion / analysisVersion | 当前 financial-summary-v2 / spending-analysis-v1；版本变更先评审映射 | 未知版本在客户读取前拒绝 |
| 缺失与完整度 | totals、categories、trend、asOf | COMPLETED 是调用完成；PARTIAL 是事实不足，UNKNOWN 仍留给办理不确定 | 接口完成但缺数、真实零独立用例 |
| 异常提示 | anomalies / assumptions | 本轮不映射自由文本及风险结论，避免当成权威反诈决策 | 原始异常客户字段不进入模型 |

具体依据：`BankingContracts.java` 的 FinancialTransactionSummaryView/SpendingAnalysisView；
`BankingFacade.java` 的 financialTransactionSummary；`SimulatorBankingStore.java` 的 spendingAnalysis；
`V410__spending_analysis_governance.sql`、`V411__financial_transaction_summary_governance.sql`。
这些是模拟器实现依据，不等于目标银行业务确认，也不证明真实银行授权。

## 直接接入

1. 依赖 `finance-provider-non-ai-banking` 与 `finance-harness-openjiuwen-java`，使用同一固定 Harness 制品集。
2. 从可信宿主取得当前 bank session 与独立实授 `cashflow:read`，构造已有 `Config.hostSession` 和动态 Grant Supplier。
3. 使用 `BoundFinanceProvider` 包装源 Provider，两个能力的 required scopes 均为 `cashflow:read`。
4. 通过原 Assembler 引用 `capability-packs/cashflow-readonly` 和 `domain-packs/cashflow/customer-self-service`。
5. 宿主在每次模型调用前重查当前授权，撤权清除受限记忆。Provider 的读取检查不替代这个模型边界。
6. 只传 `period`，不允许模型传客户选择、Profile 或授权字段。调用结果在 `data.facts` 和 `data.description`。

生成宿主的完整接线与回归由配套 Builder 分支提供。沿用旧的三种账户/财富组合；新收支配方暂不与旧组合交叉，
也不因此开放转账或预算修改。只读业务查询可能产生银行自己的审计日志，不将其宣称为数据库完全零写入。

## 验证入口

```bash
python -m pip install -r engineering/requirements-conformance.txt
python engineering/verify_contracts.py
mvn -B -ntp verify
mvn -B -ntp -pl packages/java/cashflow,providers/non-ai-banking -am test \
  -Dtest=CashflowContractTest,CashflowProviderTest -Dsurefire.failIfNoSpecifiedTests=false
```

Schema 和 Java 消费同一组 12 个合同正反例；金额代数关系、趋势顺序等跨字段语义另由 Java 检查，
不宣称 JSON Schema 自动证明算术正确。Provider 测试实际启动隔离 HTTP 服务并断言读取/修改次数。

## 兼容与待验收

- 原查询/办理响应、Core 四合同与 Runtime SDK 不变；新公共合同单独版本化。
- 新 Provider 依赖收支 JAR，使用严格依赖白名单的 Builder 须配套更新并重建 Lock，不手改旧 Lock。
- 本轮参考源只有模拟器。另一银行是否同语义可替换，须有同角色、同授权和等价口径的真实接线。
- 技术架构组维护合同/映射；灯塔/手机银行 Owner 确认上述业务口径；Builder Owner 接纳生成补丁；场景工具组独立复跑。
- 真实模型质量、完整模拟器的本次运行、真实客户授权及非原作者接入分别待验收，不能用本地 Fixture 代替。

开发量按新增文件/公共修改范围和实际运行步骤记录，不编造提效比例。后续新增方法优先改 Skill，
新增源优先改 Provider，新增独立事实才改 Capability；不因多两个接口扩展金融原语名单。
