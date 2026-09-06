# 共同财富事实 V0.1：字段定义与依据

状态：候选合同 / 上游代码已盘点 / 业务 Owner 与真实授权验收待完成。

## 来源与边界

本轮只读检查了本机 Git 副本：

- non-ai-banking `a69b33f68c959e96101d219b47334aa03b1950ca`：`src/main/java/com/openjiuwen/banking/nonai/api/BankingContracts.java` 的 SessionView、WealthOverviewView、WealthAggregateView、RiskAssessmentProfileView；`domain/BankingFacade.java` 的资产聚合。
- employee-agent `5706428bfd9b3d1708fa3e9e710a212a48aa229b`：`server/_core/wealth-previsit-context.ts`、`wealth-customer-context.ts` 及其受控测试。
- Harness 原手机侧 `wealth.customer-context.query` 返回 `wealthAggregate/riskAssessment/sourceSnapshots`；员工侧 `wealth.previsit-context.query` 返回 `previsitContext`。二者保留，权限前提不变。

以上属于上游代码/合同依据，不是本轮真实授权返回。EA 旧真实 Smoke 仅验证 DEGRADED，未获得新的客户 READY 证据。本轮 Fixture 均为虚构数据，不能证明数据源授权或业务口径已签认。没有引用未阅读的国内/国际标准条款；以下公共表达是明确的项目约定，不宣称 ISO 20022 或适当性合规认证。

## 字段定义与映射

| 业务含义 | 手机银行源字段 | EA 源字段 | 转换与适用范围 | 验证/确认状态 |
| --- | --- | --- | --- | --- |
| 当前授权客户 | SessionView.customerRef；聚合本身不含客户 ID | previsitContext.customer.customerId，evidence.customerId | namespace + ID；银行必须先核对 Session 返回；EA 必须核对 adoption 所属身份与返回客户。跨源对应只接受宿主明确映射，不因 ID 同名视为同客户 | 身份负例与受控映射；真实授权待验收 |
| 来源报告的资产总额 | wealthAggregate.overview.totalAssets | previsitContext.customer.portfolio.summary.totalAssets | 使用十进制原值，非 netAssets；只表述明确来源覆盖的资产，不扩大为家庭总资产；两者相同统计范围需额外依据 | 手机明确字段；EA 是开放 JsonObject 中现有测试路径，不保证所有连接器支持 |
| 币种、精度 | overview.currency；JSON 数值须按 BigDecimal 解码 | 上游通用合同未保证币种字段 | EA 必须有宿主提供的源数据字典 MappingBasis 才能赋单位；缺失则不输出误导金额。0 与缺失不同；不换汇、不加总多币种；最多18位整数8位小数，超出拒绝不舍入 | 精度/零/缺币种负例；EA真实依据待 Owner 确认 |
| 资产范围 | overview.coveredSourceSystems、missingSourceSystems；实际模拟聚合包含现金、存款及投资 | portfolio 的语义由实际连接器决定 | MappingBasis.scope 明确范围；未明确时范围为 SOURCE_REPORTED_UNSPECIFIED，并产生缺口；不得称全行/家庭 | 范围差异负例；范围文本为项目约定 |
| 估值时间 | overview.asOf / dataQuality.valuationAt | evidence.portfolioDataAsOf（数据时间不必等于估值时间） | EA 不自动把数据时间当估值时间；映射依据必须明示该时间是否可作估值时间。不以调用时间填补历史事实时间 | 缺时间测试；EA估值含义待确认 |
| 来源未覆盖/不可用 | overview.missingSourceSystems / sourceSnapshots 的 UNAVAILABLE | degraded/status 与实际 portfolio 缺失 | 执行完成与事实不完整分离；缺字段=MISSING，已知未提供=NOT_COVERED，调用失败=UNAVAILABLE；不补0 | 降级/未覆盖/不可用案例 |
| 风险等级 | riskAssessment.riskLevel | customer.riskLevel | 保留原级别文本；不把 C3 转 R3，不据此推荐产品 | 两源保真测试 |
| 风险体系、版本 | sourceSystem 是来源，不自动等同评级体系；assessmentVersion 为测评记录版本 | 通用合同未提供完整体系/版本 | 体系来自 MappingBasis 的明确依据；记录版本保留；体系未知形成缺口，绝不自动跨体系匹配 | 项目约定；真实字典/Owner待确认 |
| 风险有效期 | assessedOn、expiresOn（日期） | assessmentExpiresAt（可能日期/带偏移时间） | 保留日期精度；日期到期按配置的来源时区当日结束解释，必须记录依据；未知时区时不可把日期映射为时刻 | 到期边界测试；时区为宿主源合同配置 |
| 员工专属字段 | 无对应 | name、maskedPhone、confirmedNeeds、recentContactSummary、knowledgeBasis、governedProfile 等 | 本轮共享任务不需要，映射时白名单排除，不进入模型上下文或错误报告 | 泄漏负例 |
| 输出可用性 | 调用状态和事实状态分开 | ready/degraded 不是授权或适当性结论 | 共同消费者只描述资产范围、风险状态及缺口；没有正式推荐、适当性或交易动作 | 只读边界自动验证 |

## 待确认决定

EA numeric totalAssets 的币种、统计范围、估值时点和评级体系未由通用接口保证。默认 EA 映射不假定 CNY，不推断家庭财富，不把 ready 当作完整。受控等价案例显式使用 `fixture:*` 的字典依据，只证明转换行为。真实合同不足时，不启用相应数值映射。

领域 Owner（灯塔组）确认字段口径与样例；技术架构组实现映射、拒绝不一致及装配；场景工具组后续独立接入。真实同 Profile 的第二 Provider 和非原作者验收均另列，不由跨角色案例替代。
