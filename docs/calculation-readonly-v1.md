---
schema: speckit-shared-contract/v1
status: accepted
owner: Harness calculation domain maintainers
collaborators:
- Builder maintainers
- original algorithm maintainers
review:
  decided_by: Yan
  decided_at: '2026-11-04T12:48:47.609016+00:00'
  decision_source: conversation
  statement: 接受方案，继续拆任务（continue-to-tasks）
related_features:
- FEAT-004
feature_namespace: 'Agent Store scenario-industrialization; Requirement #54'
related_feature_records:
- community/finance/docs/features/scenario-industrialization/FEAT-004.md
compatibility: backward-compatible
contract_id: FINANCE-ORIGINAL-CALCULATION-001
title: 受管原算法计算、原值与生命周期
---

# FINANCE-ORIGINAL-CALCULATION-001：受管原算法计算

## Purpose And Boundary

本合同记录已接受的职责及逻辑语义。实现、JSON Schema、依赖锁与验收证据须随关联变更交付；accepted 不表示已有代码、发布或生产资格。原公式不改，Core/Execution/既有 SPI 不改。

## Consumers

Agent Store 的共用原计算工具、Finance Solution Builder、FTP与风险编排器两个消费者；后续场景必须显式选择固定版本。

## Inputs

七类有限操作，各有独立输入 Schema；八个原算法资产按单实体 SourceReceipt 固定。可信部署绑定原资产、依赖、当前主体/任务、输入快照和参数；请求不得传命令、模块路径、表达式或代码。

## Outputs

受限保真原值、版本化结构化表/序列/指标及受控产物引用；每项可回查输入、参数、来源和运行身份。原 Python 对象只在原解释器中按预审类型序列化，不提供任意对象反序列化。

## Semantics And Invariants

### 1. 有限计算操作
拟增加七类只读能力，名称在任务细化时以Schema和Catalog核对后固定：FTP定价、流动性指标、市场风险计量、操作风险资本、Logistic拟合/预测、组合收益、固定因子面板回测。Logistic的debt/entity是不同算法资产和受控变体，不混用同名Python包。既有`market.risk.capital.query`不改名、不扩义。

每类能力有独立输入Schema、允许的参数、结果Schema、授权范围和原函数映射。部署方提供可信的Profile/工具制品/依赖身份。模型不能提交模块路径、可执行文件、命令、表达式、pickle或任意factor_code。

首批源绑定共八个算法资产：FTP、liqrisk、mktrisk、oprisk、debt、entity、stock组合、quant纯回测；风险编排器是这些能力的真实调用者。五维应用分级、贷后规则报告和推荐通道保留专业方法衔接，不冒称通用数值算法支持，也不在此实现完整场景。

### 2. 来源、输入与参数

- 每个原算法制品绑定独立SourceReceipt（仓库/完整提交/原实体/文件摘要），复用现有单实体约束；共享适配代码另外记录自身版本/摘要。发布副本是构建产物，不另维护原公式。
- 源服务保存客户/任务授权下的不可变输入快照及原字段、行序、列序、日期、币种/单位、缺值状态。公开引用是受控逻辑ID和摘要，不是宿主路径或任意URL。
- 参数保留显式输入、原默认值及实际采用值、参数Schema/版本与摘要；随机种子是否支持、是否已固定分别记录。默认值不能掩盖用户提供值或数据缺失。
- Host私有绑定将当前主体、会话、任务、快照与允许操作对应；客户端提供的引用必须重新授权。凭据不进入资产、方法、浏览器或公共错误。

### 3. 原始数值与展示

领域结果至少关联运行ID、原资产/工具/依赖身份、输入/参数身份、计算时间、数据时点或缺失说明、原始结果引用/摘要及结果Schema。

“原始结果”指在原解释器中对原函数返回做**已版本化的受限保真序列化**：保留未舍入数值、类型、行列/索引及缺失/非有限标记；不是声称原Python对象本来就是JSON，也不序列化任意模型对象供浏览器加载。仅允许预审结果类型。模型训练对象留在隔离进程；首批返回原系数/摘要/预测，不接收外部pickle。

表格/序列/指标展示是独立投影，有明确单位、日期和来源映射。舍入、截断或派生图必须说明，不改原始层。NaN、无穷和缺值不改成零；原函数的归一化/剔除政策明确呈现或在新Profile预检拒绝不满足条件的输入。原场景旧入口保持原义。

`calculatedAt`不是业务数据日期。外层既有`asOf`若继续表达计算时间，须保留明确的time semantics，数据日期单独取输入/原来源；缺日期不填当前时间。

### 4. 生命周期、权限和取消

新Profile只做有截止时间的计算调用，不引入银行办理TASK、中央任务服务、跨会话恢复或人工复核平台。实际源进程由工具服务管理；输入与派生输出按客户/任务隔离。

通过既有ManagedScenarioExtensionV01的actions/call/status/close扩展有限取消与结果查看动作，不修改SPI。新会话不持长时间会话锁阻塞取消；执行持有运行代次/取消状态，取消先封闭披露，再中断请求并终止对应受管工作进程。只取消HTTP等待不足以证明原计算停止。不能在取消后接受晚到结果或回填成功。

取消/超时/进程异常/无权/无数据分别有公开安全错误与实际状态。必要工具未运行或失败时，Agent记录不得仅因模型回复结束而标为业务成功。该保证限新Profile自身接线，不宣称修复所有Runtime观测入口。

### 5. 对照与证据归属

原函数结果、适配传输结果、表图投影三层分别比较。对照层由Builder场景ExpectedEvaluator/MetricCalculator消费Base Evaluation，不由Provider决定“业务质量通过”。

相同输入、参数、种子、依赖与原测试判据固定后比较；对浮点项复用原用例的明确容差，不能用统一宽松阈值。没有原判据时先冻结候选并取得责任人认可，未获认可不宣称专业有效。接线通过、数值保真通过、模型质量通过、生产资格分别记录。

### 6. 版本与交付

新增计算领域/操作Schema、原工具协议及视图版本均opt-in；无Core、Execution、Base、通用RuntimeBuilder数据模型或旧资本合同变更。精确JSON字段、Schema及兼容用例在接受后随Tasks冻结，首次版本不承担任意新算法自动发现。

Harness发布领域/Provider/Integration与装配元数据，Builder显式更新固定引用；源工具、原算法和运行依赖都有独立发布身份。回滚关闭新Profile并恢复已验证闭包，保留历史输入/输出。跨仓先具备固定可用制品再更新消费方，不跟随浮动HEAD。

## Errors And Boundary Cases

缺依赖/许可/源文件/授权、超限、坏输入、非有限值、原函数异常、超时与取消分别记录，不填零、不回落模型文本、不用旧成功结果。取消先关闭披露，终止真实工作进程并拒绝晚到结果；未验证的平台不宣称通过。

## Compatibility

新 Profile opt-in。原资本合同、原场景入口及 SourceReceipt 单实体约束保留；不依赖 FEAT-002/003 尚未合入代码。原算法和适配层分别记录身份，不复制维护原公式。关联PR固定两仓可用提交，恢复旧闭包可回滚。

## Verification

八资产原函数/传输/投影三层对照、原用例容差、两个独立消费者、当前授权与跨任务隔离、异常/超时/真实取消和晚到拒绝。接线、数值等价、业务质量及生产资格分别记录；前置不足保持 BLOCKED，不用模拟原引擎替代。

## Decisions

Yan 已接受 HLD 和候选共享语义，来源为本次对话。具体代码、任务文件范围、依赖安装及实际执行仍需完整实施批次批准；本文件不授予这些权限。

## Candidate Realization

本次候选将上述语义实例化为 `contracts/json-schema/calculation/` 下的请求、原值、结果、来源与状态 Schema。领域模块为 `packages/java/calculation`，Provider 为 `providers/original-calculation`，原生扩展为 `integrations/original-calculation-java`。这项说明不代表上游合入或发布。

原服务在 loopback 上提供 `/v1/calculations/start/status/result/input/cancel`。Host 通过私有的 `original-calculation-bindings.v1` 固定 principal、subject、session、task、选中资产、原输入与来源/适配/依赖摘要。私有配置最大 256 KiB；源 HTTP 请求最大 4 MiB，原返回最大 8 MiB，单个原任务截止时间不超过 60 秒。大材料应设计显式快照引用，不能自行放宽这些限制。

Agent 通过已有 Scenario SPI 暴露 `chat/calculate/result/status/history/cancel`。一份公共包安装七类定义，私有绑定只授权一个资产；派生方法只需声明该资产对应的能力。允许修改有限参数，原输入和资产不由 Agent 改选。每次重算建立新 runId 和参数摘要，最多保留 64 条会话成功记录；当前失败或取消不会把旧结果作为本次成功。历史读取使用原 runId，重新核对 Host 与源服务当前权限，不再次执行算法。运行索引/会话历史当前是进程内状态，重启恢复尚未实现。

原值示例：下列缺失标记不会变为零，也不会用计算日期填补来源日期。

```json
{"kind":"missing","marker":"None"}
```

有限数值使用十进制字符串及浮点十六进制表示保留原精度；表格保存列、索引、类型和每个值。`rawDigest` 绑定规范化 JSON 原值。投影中的每个 `rawRef` 指向对应原值，展示截断与原值分开。`calculatedAt` 与 `dataAsOf` 独立。

Linux worker 配置 CPU 60 秒、地址空间 1 GiB、文件 8 MiB；禁止网络连接、派生进程和外部文件写入。取消先关闭披露，终止自有进程组并回收，不能仅以 HTTP 请求取消作为进程退出证据。这些本地边界仍需客户部署环境的操作系统权限与网络隔离配合。

验测入口为 `engineering/prepare_original_calculation_tool.py` 和 `engineering/verify_original_calculation.py`。真实固定模型端点仅确定路由，原函数实际执行；接线通过、原新数值相等、专业金融有效性和生产资格是四类独立结果。两名消费者的完整交付由 Agent Store 的独立构建/启动/浏览器验证补充，不能用 Harness 单元测试代替。
