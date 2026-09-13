# 只读方法来源与适配边界

原则：先复用上游原创业务方法，再做工具接线与必要约束适配。不得为了 Harness 包装默认重写 Skill；
简单工具可以没有独立 Skill。原作者、许可证、原文和历史不因改名、中文化或打包而消失。
本表说明来源，不是上游作者已评审/验证本候选的证明。

## 固定核查范围

- 手机银行仓：`wuxiaojingjing/mobile-banking-3.0`。
- **实际收支沉淀基线 U：`4652b285a0bff72dd88aaf00f6acae196b8951e0`**。
  本次 GitHub commit API 确认该提交为原仓 PR23 合并（finance skills + upstream adapters）；
  本地固定工作副本读取对应文件，不从目录名字推断出处。
- 补充对照 L：`e33be7c5c6339b3dd1206f85d9c3115ac0d718f4`。该树没有收支专属 Skill，
  **不能用它否定 U 中存在的 Skill**，更不能推广为全部历史/Nacos 都无 Skill。
- 同时核查 U/L 中 account、wealth-aggregate、finance-assistant 的 agent/tools/prompts，
  以及 account 后端接线。未核查在线 Nacos 和所有历史分支。余额/财富早期沉淀没有证据绑定到 U，
  因此其 U/L 项是语义对照，不倒填为最初开发输入。

## 精简来源表

以下路径相对对应仓库，U/L 定义见上。机器标识不因中文化改变。

| 单元 | 上游 repo / SHA / path | 资产类型 | 保留的原有内容 | 适配差异 / 我们新增的方法与原因 |
| --- | --- | --- | --- | --- |
| 余额 | mobile-banking U/L；`agents/account/assets/tools/account.yaml`、`assets/prompts/prompts.yaml` | TOOL `account.balance.query`；域意图/规划 Prompt | 本人账户余额、实际查询而非菜单、与信用卡/办理分开 | Harness 保留余额能力；`accountId` 可选筛选与 `{}` 查本人账户是当前 Provider 合同接线，不能宣称完整移植上游 `accountOrdinal` 指代流程。Builder 余额短方法用于纠正必填账户号误解，是项目新增 Host 方法，不另建 Domain Pack |
| 余额相关但非等价 | mobile-banking L；`agents/account/assets/skills/account-card-tips/SKILL.md` | Skill 0.5.1，Apache-2.0 | 原账户分类/限额咨询方法继续归上游 | 不是余额查询 SOP；不拿它改成余额 SOP，不把它的转账/明细工具装入只读切片 |
| 财富 | mobile-banking U/L；`agents/wealth-aggregate/assets/tools/wealth.yaml` | overview/aggregate TOOL，非同名 Skill | 本人财富事实；不把查询扩为购买/推荐 | `wealth.facts.describe` 是项目公共事实消费者，组合资产/测评/缺口，不等于把 `wealth.overview.query` 改名。Host 触发规则与 `wealth-facts-description` 是项目新增方法，解释跨源事实边界；并非上游同名 Skill 原样复制 |
| 财富相关但非等价 | mobile-banking L；`agents/wealth-product/assets/skills/wealth-portfolio-doctor/SKILL.md` | Skill 0.3.0，Apache-2.0 | 先确认基金代码与金额，再做组合诊断 | 基金诊断并非只读资产范围说明；本轮不重写、不装配它的 MCP 诊断能力 |
| 收支原方法 | mobile-banking **U**；`agents/finance-assistant/assets/skills/spending-analysis-journey/SKILL.md`、`assets/tools/skills.yaml` | **Skill 0.3.0 / SKILL 卡**，Apache-2.0 | 先确定月份 → 摘要 → 分类趋势；绝对月跳过对时；单项不凑组合；两查询同 period；固定六个月趋势；不办预算/明细/且慢收支 | `cashflow-review` 不是原样复制，现明确作为该原方法的适配。工具名换为 Harness 注册名；未安装且慢对时，使用可信 Host 日期，否则询问；不采用对时失败退回 2026-08 的演示兜底。来源口径/缺数/冲突约束为项目增量 |
| 收支工具 | mobile-banking U/L；`agents/finance-assistant/assets/tools/upstream-queries.yaml`、`scenarios.yaml` | 两个 TOOL / HTTP 路由配置 | summary 与 analysis 分工、GET 只读、period 月份 | 不搬运固定演示月份；不把 TOOL 误称 Skill。当前方法版本 0.1.3 显式保留原顺序及周期；0.1.2 候选与旧制品保留历史 |
| 财富事实依据 | `wuxiaojingjing/non-ai-banking` `a69b33f68c959e96101d219b47334aa03b1950ca`；`src/main/java/com/openjiuwen/banking/nonai/api/BankingContracts.java`、`domain/BankingFacade.java` | 数据合同 / 聚合代码 | 源金额、币种、范围、测评原值 | 与 EA 的共同表达是项目约定，见 wealth-field-basis-v01.md；不是上游专家 SOP，也不是同事已确认全部业务口径 |
| 收支事实依据 | non-ai-banking 集成提交 `162ef4e587c9e9b781bab60bf89591cf4255e579`；同上合同/Facade 及来源 SQL，见 cashflow-readonly-v01.md | 数据合同 / 代码 / SQL | 原始汇总、BOOKED 分类、UNCLASSIFIED、趋势 | 原汇总状态过滤与 policy 不一致，故新增未核实/趋势不可比披露。不能为保留原措辞而宣称数据已核实，不改上游真值迎合测试 |

## 保留与差异证据

原收支 Skill 原文保存在 [来源快照](source-evidence/spending-analysis-journey-4652b28.md)，
Git blob 为 `b0cf4ef7bf913d7480272bc5132d800ccc8d772c`；保留原 name/version/license/正文。
该文件仅用于复核，不安装到 Agent，不把原工具名或演示兜底混进执行上下文。
[原仓固定源码](https://github.com/wuxiaojingjing/mobile-banking-3.0/blob/4652b285a0bff72dd88aaf00f6acae196b8951e0/agents/finance-assistant/assets/skills/spending-analysis-journey/SKILL.md)。

| 原行为 | 适配行为 | 判定 |
| --- | --- | --- |
| 已给绝对月，不对时 | 同样跳过对时，两查询同 YYYY-MM | 保留 |
| 未指定/相对月先读 GetCurrentTime | 当前无该工具，读可信 Host 业务日期；不可得则询问 | 接线差异，不假造已装工具 |
| 对时失败退回 2026-08 | 不用固定月兜底 | 必要防错约束，显式差异 |
| 完整分析摘要后分类、单项不凑另一工具 | 同样顺序、按需调用 | 保留原方法 |
| TREND_MONTHS=6 | 保留六个月，且来源不可比时不计算环比 | 保留 + 来源约束 |
| 不调用 AnalyzeIncomeExpense、不办预算/明细 | 保留；工具安装白名单继续只读 | 保留 |

本轮中文化是对项目已有方法的语言修订与来源收敛，不是上游同名 Skill 翻译项目。
以后找到更匹配的原 SOP，应先评审复用/最小适配，不继续平行造一个同义方法。
方法正文遵循性仍需真实模型和业务 Owner 验证；39 项文字对照及受控调用回归不能代替该验收。
