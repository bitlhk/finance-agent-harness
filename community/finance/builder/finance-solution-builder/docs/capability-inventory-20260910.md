# 首批金融能力源码盘点

本文是可提取资产清单，不是新增能力发布。26个模块是手机银行项目目录；本轮UI仍只安装余额和财富事实两项能力。

## 固定基线与证据级别

- 手机银行：`wuxiaojingjing/mobile-banking-3.0@db38c7f454b25f109a607308dbeb4f01de75e9d2`，通过固定提交读取工具声明。
- 模拟器：同事源项目的 `bitlhk/non-ai-banking` 受控集成分支 `feat/local-readonly-session-v01@162ef4e587c9e9b781bab60bf89591cf4255e579`，不宣称该提交已进入同事上游或是其最新版本。
- 当前套件对照：Harness `b55d0722cac2905388f57dc4a17b85d346a0e470`，Builder目录 `template-modules.v1.json`。
- 核对源码的Controller、Facade与测试用例存在性；本轮未独立复跑模拟器全量测试，也没有真实客户或上游生产验收。
- 展示目录来源仍保持之前的固定提交。本文的新源码盘点不自动改变目录来源、能力状态或依赖锁。

所有模拟器路由均以 `/api/v1` 开头。Controller文件为
[`src/main/java/com/openjiuwen/banking/nonai/app/BankingController.java`](https://github.com/bitlhk/non-ai-banking/blob/162ef4e587c9e9b781bab60bf89591cf4255e579/src/main/java/com/openjiuwen/banking/nonai/app/BankingController.java)。
下表按实际复用顺序分组，不要求增加对应数量的一级卡片或Pack。

| 批次／模块 | 原能力及业务类型 | 手机银行源码 | 模拟器实现及测试依据 | 可沉淀资产与现状 | 接入前缺口 |
| --- | --- | --- | --- | --- | --- |
| 1／金融助手 | `finance.transaction-summary.query`、`finance.spending-analysis.query`；QUERY | [upstream-queries.yaml](https://github.com/wuxiaojingjing/mobile-banking-3.0/blob/db38c7f454b25f109a607308dbeb4f01de75e9d2/agents/finance-assistant/assets/tools/upstream-queries.yaml)、[scenarios.yaml](https://github.com/wuxiaojingjing/mobile-banking-3.0/blob/db38c7f454b25f109a607308dbeb4f01de75e9d2/agents/finance-assistant/assets/tools/scenarios.yaml) | GET `/finance/transaction-summary`、`/finance/spending-analysis`；Controller823/829；`BankingApiIntegrationTest`2114-2145包含查询与策略检查 | 成套收支只读Pack、账期/金额/分类合同及解释方法；有候选实现，未纳入当前两能力配方 | 明确账期、币种、账户覆盖、分页和入账口径；保留未分类、缺数与不一致，不复制默认演示月份 |
| 2／账户服务 | `account.transaction.query`、`account.statement.query`；QUERY | [account.yaml](https://github.com/wuxiaojingjing/mobile-banking-3.0/blob/db38c7f454b25f109a607308dbeb4f01de75e9d2/agents/account/assets/tools/account.yaml)、[upstream-queries.yaml](https://github.com/wuxiaojingjing/mobile-banking-3.0/blob/db38c7f454b25f109a607308dbeb4f01de75e9d2/agents/account/assets/tools/upstream-queries.yaml) | GET `/accounts/{accountId}/transaction-page`、`/accounts/{accountId}/statement`；Controller431/442，425是另一个非分页transactions入口 | 明细事实、月结汇总能力及账户引用解析；当前只有余额安装 | 账户授权、月份、返回规模、分页完整性；无交易不同于无法取得数据；不能靠同名账户猜身份 |
| 3／金融助手 | `finance.risk-assessment.query`；QUERY | 上述finance-assistant `upstream-queries.yaml` | GET `/finance/risk-assessment`；Controller854；IntegrationTest2193-2199覆盖有效及过期 | 测评事实合同可复用；当前财富事实已消费测评来源，尚非独立安装能力 | 保留体系、版本、时效与来源；不把测评有效当适当性通过，问卷提交另属办理 |
| 4／权益运营 | `customer.points.query`、`customer.benefit.query`；QUERY | [points.yaml](https://github.com/wuxiaojingjing/mobile-banking-3.0/blob/db38c7f454b25f109a607308dbeb4f01de75e9d2/agents/benefits-ops/assets/tools/points.yaml)、[scenarios.yaml](https://github.com/wuxiaojingjing/mobile-banking-3.0/blob/db38c7f454b25f109a607308dbeb4f01de75e9d2/agents/benefits-ops/assets/tools/scenarios.yaml) | GET `/customer-points`、`/customer-benefits`；Controller909及customerBenefits方法；`/benefit-workspace`914是聚合入口，不与单项权益查询混同；IntegrationTest2266及8460附近 | 积分和权益事实合同、客户术语映射；尚未接入Harness目录 | 区分客户积分与信用卡积分、单位/到期/资格/余次；兑换、领取、核销与报名均不可随只读工具安装 |
| 5／转账服务 | `beneficiary.query`、`transfer.rule.query`、`transfer.quote.query`；业务QUERY | [upstream-queries.yaml](https://github.com/wuxiaojingjing/mobile-banking-3.0/blob/db38c7f454b25f109a607308dbeb4f01de75e9d2/agents/transfer/assets/tools/upstream-queries.yaml)及该目录报价工具声明 | GET `/beneficiaries`、`/transfer-rules`，POST `/transfer-quotes`；Controller960；IntegrationTest6581/6599/6602报价正常与负例 | 转账准备的只读能力；未接入当前配方 | 报价绑定账户/收款人/金额及有效规则，不保留额度、不授予执行许可；不能仅按HTTP POST判断为办理 |
| 6／转账服务 | `transfer.status.query`；QUERY | 上述transfer `upstream-queries.yaml` | GET `/transfers/{transferId}/status`；Controller959，存储按客户与转账引用查询 | 已有任务查证/来源状态表达；尚未安装独立状态工具 | 保留原任务和银行状态；UNKNOWN先查证不重提；人工查证申请可能产生新任务，须单列 |

测试路径统一为模拟器 `src/test/java/com/openjiuwen/banking/nonai/BankingApiIntegrationTest.java`。
源码ACTIVE或测试存在，只证明实现线索，不能替代Harness合同、映射、权限、生成Agent消费及负向验收。

## 现有资产与候选的边界

账户服务里的 `account.balance.query` 与财富聚合服务里的 `wealth.facts.describe` 是当前实际目录的两项能力。
财富事实描述不是照搬上游“财富聚合”的全部功能。持仓/产品事实后续应按实际来源、估值口径与授权单独盘点，
本表不因模块名称存在就将它们标为已提取。

金融助手除收支外还有日历、FAQ、比较及测评，因此不改名为“收支分析”。上游Skill可作为专业方法素材，
但默认演示时间、缺失填零、月/年单位转换和外部工具依赖必须重新核验，不直接导入为已验证公共方法。
转账执行、账户冻结、积分兑换等办理，继续独立处理可信确认、参数冻结、幂等与UNKNOWN恢复。

每项进入可构建目录前需具备：版本化合同与映射、只读/办理分类、权限和缺数案例、真实安装与取消安装证据，
以及生成Agent消费检查。复用专业方法不等于复制原应用的Runtime或意图路由。
标准参考及未核实范围见[业界标准参考页](industry-standards-v01.md)，本轮不强行填写BIAN服务域对应表。
