---
schema: speckit-shared-contract/v1
contract_id: FINANCE-MARKET-RISK-CAPITAL-001
title: 原市场风险标准法资本的有限只读适配
status: accepted
owner: Finance Harness market-risk-capital domain
collaborators: []
review:
  decided_by: Yan
  decided_at: '2026-11-03T05:17:53.495769+00:00'
  decision_source: conversation
  statement: 继续拆任务（continue-to-tasks）：接受设计，细化任务与实施边界
related_features:
- FEAT-001
compatibility: backward-compatible
feature_namespace: https://gitcode.com/Euphoria_Yan/agent-store-builder-dev-wo-fork/issues/54
related_feature_records:
- community/finance/docs/features/scenario-industrialization/FEAT-001.md
---

# FINANCE-MARKET-RISK-CAPITAL-001：原标准法资本只读适配

## Purpose And Boundary

适配 Agent Store 固定原 mktrisk_engine 的标准法资本计算，只转换有限传输与结果结构；不重写公式，不运行完整 VaR/压力测试/报告流程，不提供任意代码执行。关联的 FEAT-001 属于 Agent Store 的 Issue #54，不能与本 Harness 仓的报价 FEAT-001 混同。合同接受不代表 Provider、Host 或发布资格已具备。

## Consumers

原 bank-mktrisk-kit 流水线和 risk-llm-coach 市场风险分支的最小探针；Builder 受管 Java 0.1.15 首接线及可选结果视图。完整风险能力和场景装配另行交付。

## Inputs

QUERY 的当前受信主体、任务/会话、有效授权和绑定的固定头寸/汇率/参数快照。模型只触发已装配有限操作，不得选择主体、任务、快照路径、服务地址、Python 模块、命令或任意监管系数。原配置/依赖/源码均有固定身份和摘要。

## Outputs

原 interest_rate、fx、equity、commodity、total、param_version、原明细/返回及可回查出处；来源业务日期可缺失，计算/观测时间另记。以现有 CapabilityResultV1 表达完成/失败，不新增 Core 通用状态；结果数值与原函数/原测试相同口径。

## Semantics And Invariants

复用 FinanceContextV1、CapabilityRequestV1/ResultV1、BoundFinanceProvider、ManagedProviderFactoryV01 与 Assembler。原 Python 算法本身没有客户授权语义，Host/Provider/服务会话边界必须核对当前主体、任务、有效期和范围，不信任模型或未绑定请求。请求令牌由宿主当前授权提供，不能进入资产/日志/公开记录。

服务是原工具的本地只读适配出口，地址受控并有超时、正文大小和响应校验，不是全行框架服务。固定输入从受信绑定取得；方法文件不携带可执行实现。原引擎及其配置原样打包为可独立取得的固定工具依赖，保留 Apache-2.0 和教学参数说明；不具备真实监管报送资格。

## Errors And Boundary Cases

无权、过期、错主体/任务/快照、未选能力不得披露；缺输入与合法空头寸零结果分开。异常、超时、不可用服务、版本/Schema 错误均不能声明“成功取得计算事实”。Agent 正常结束解释错误不等于业务计算成功。原日期缺失不能以当前时间补齐。

## Compatibility

新增 opt-in 能力/领域/Provider，不修改 Core SPI 和旧业务字段。安装要求真实制品/配置摘要与 Host 支持，组件通过不代表 Runtime 接线通过。Builder 显式消费已资格化固定版本，不用 current main 替换历史资格。跨主机生产部署、真实银行数据与外部授权不在本决定内。

## Verification

原函数直接结果与适配结果分项/合计/参数/明细对照；合法零与缺数据；授权否定；双主体/任务交错；异常/超时；真实 Runtime 工具循环；脱离 Builder 的独立制品；原返回与视图核对。执行证据全部另存，本文无 PASS 声明。

## Decisions

由 Yan 对 Agent Store FEAT-001 的 continue-to-tasks 决定接受；确切字段和实现用例在该工作项 Tasks/Schema 中落实，不可扩大有限只读语义。Core、新依赖族或额外权限变更需另行决定。

## 已实现的有限映射

领域 `finance-market-risk-capital.v1` 的 Schema 位于
`contracts/json-schema/market-risk-capital/result.v1.schema.json`。原数值名称、参数版本及明细保持原义，
`source.asOf` 必须存在且允许为 null。通用结果合同要求的外层 `asOf` 使用计算时间，
同时明确标记 `asOfSemantics=CALCULATION_TIME_NOT_SOURCE_DATE`；查看业务日期必须读取 `capital.source.asOf`。

Harness 能力名为 `market.risk.capital.query`，模型可见工具名为 `market_risk_capital_query`，
参数为空对象。原工具制品内部的有限操作标识为 `market-risk.capital.query`，两者由专用 Provider 明确适配，
不是任意操作转发。Provider 只连接 127.0.0.1，禁止跳转，限制超时与响应大小，重新核对当前来源会话后才披露结果。
503、超时、无权、版本或结果结构不匹配均为失败；公开错误不包含响应正文、凭据或主机路径。

Provider 使用现有 ManagedProviderFactoryV01，配置身份按该 SPI 的裸十六进制 SHA-256 返回；
部署方环境变量采用裸摘要；私有绑定文件中的 `sha256:` 前缀形式也会归一化校验。配置不携带授权令牌。原始返回保存在本次结果中，
不会从模型文字反向提取资本数值。

Builder 生成制品的 `service-lock.json/providerConfiguration` 锁定受管配置摘要。
`MarketRiskSession` 启动时将实际工厂配置与该锁逐项核对；缺失或替换时拒绝启动，
不能仅凭宿主私有配置自述重新选择快照。直接组件测试可由受控 Context 自行拥有授权配置，
这种组件证据不替代 Builder 制品的配置准入。
