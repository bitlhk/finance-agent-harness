---
schema: speckit-shared-contract/v1
contract_id: QUOTATION-READONLY-V01
title: 只读报价与优惠咨询语义
status: accepted
owner: finance-harness-maintainers
collaborators: []
review:
  decided_by: Euphoria_Yan
  decided_at: '2026-10-19T08:54:18Z'
  decision_source: conversation
related_features: [FEAT-001]
compatibility: backward-compatible
upstream_maintainer_review: pending
release_status: candidate-not-released
---



# 只读报价与优惠咨询包：公共语义合同

Euphoria_Yan于2026-10-19T08:54:18Z接受本文的项目公共接口语义及职责方向，于09:06:04Z接受H1八任务实施批次。本文及新资产仍是未发布候选，不冒充维护者已接纳的上游合同。Owner是语义维护路由，不是维护者已经批准的证明。

## 1. 要交付什么

新增一个版本化报价领域包和一个合成参考Provider，复用Core QUERY、受信Host权限、受管工厂、组件装配与现有证据。第一版仅查询一个合成服务产品的一份适用参考报价，不做真实金融产品推荐、资金操作、发券、领取优惠、订单、预留库存、锁价或营销写入。

对外可以表达“来源当前提供的适用参考价及条件”，不能表达“已经锁定优惠”“保证成交”“全部费用免除”。只读不意味着无风险：错误价格承诺仍必须在公开前阻断。

比较后的选择：

- 不直接重用财富/保险产品介绍作为报价：这些合同没有完整的报价归属、期限、精度和允许承诺语义。
- 不新建通用Policy Engine、IAM或工作流DSL：超出当前闭环；已有公共执行机制不重写。
- 选择新增领域合同＋合成Provider；Runtime保持薄接线，Builder后续消费正式资产引用，不复制金融内核。

## 2. 边界与依赖

| 对象 | 负责 | 不负责 |
|---|---|---|
| 报价领域模块 | 严格解析、金额/优惠关系、事实绑定、有效性、可披露事实投影 | 登录鉴权、模型调度、UI页面、网络传输 |
| 合成参考Provider | 读受信固定合成快照，按当前Host主体和产品查事实，返回版本化数据 | 凭模型参数批准优惠、计算营销资格、签发真实客户权限 |
| Host / Integrator | 当前授权、请求来源、调用与披露前检查、出口控制、取消/清理 | 重新解释价格或优惠规则 |
| Domain Pack / Builder方法 | 业务SOP、审核后的标签/模板引用、方法版本和试跑证据 | 把提示词当强制策略、代替银行授权 |
| Builder | 兼容准入、装配、方法生命周期与最终Message/Result/流式呈现 | 在场景里实现另一套报价Provider |

依赖：Host → Integrator → 既有Core/Provider；Host和Provider均可调用报价领域验证；Provider → Core/报价领域模块。领域模块不引用SDK、Provider实现、Builder或UI。Core、Execution、现有Grant/SPI和原业务合同不变。

## 3. 能力与请求（项目设计已确认，上游发布前仍为候选）

| 项 | 候选值 |
|---|---|
| Capability Pack | `quotation-consultation-readonly` / `0.1.0` |
| Capability | `quotation.offer.query`，仅QUERY |
| Tool name | `quotation_offer_query` |
| Required scope | `quotation:read`；是权限要求，不是授予权限 |
| Result data schema | `quote-facts.v0.1` |
| Provider | `quotation-synthetic-reference` |

模型可提供的参数恰为 `productRef` 和 `currency`，两项必填，拒绝额外字段。第一版currency固定CNY；产品必须来自受信安装目录及当前主体可查询范围。无唯一产品时澄清，不取第一个产品兜底。

客户主体仅取 `FinanceContextV1.subjectRef`，不接受模型传customerRef或真实凭证。登录会话、用途、scope、当前时间、源文件、路径、Owner批准、价格、折扣、资格均不是工具参数。传入TASK、confirmation、continuation、taskRef或幂等办理字段，直接拒绝，不进入Provider业务查询。

引用标识限制为ASCII字母数字及 `._:-`、1～128字符；不接URL、文件路径或表达式。请求主体、会话和权限的真实性仍由Host提供，不能只靠字符串格式成立。

## 4. 结果结构

沿用 `CapabilityResultV1` 外壳，不增Core状态。正常完成的查询使用COMPLETED；它不代表获批、成交或行为质量通过。只读业务状态写在严格的data中。data及所有嵌套对象拒绝未知字段和重复JSON键。

| data字段 | 类型与语义 |
|---|---|
| schemaVersion | 常量 `quote-facts.v0.1` |
| subjectRef / productRef / currency | 绑定本轮可信主体、确切查询产品和CNY，不能按相同金额替代绑定 |
| state | AVAILABLE / NOT_APPLICABLE / MISSING / UNAVAILABLE / EXPIRED / CONFLICT |
| quote | 只有AVAILABLE时为完整对象，其余状态必须为null，禁止携带可回退使用的旧价 |
| gaps | 有界有限reasonCode数组；AVAILABLE必须为空，其他状态必须有理由，不含自由错误正文 |
| source | sourceKind、sourceId、sourceVersion、recordRef、observedAt、snapshotSha256 |

sourceKind首版固定CONTROLLED_FIXTURE。sourceVersion/recordRef定位来源快照中的唯一记录；snapshotSha256为已审核配置文件**原始字节**的SHA256，不是签名、不证明真实银行来源或业务正确。不能把它解释成授权票据。

AVAILABLE.quote完整字段：

| 字段 | 规则 |
|---|---|
| quoteId | 同一来源版本内唯一，不能作为bearer token或授权凭据 |
| listPrice / referencePrice | `{value, currency}`；value为无指数、无符号、固定两位小数的非负十进制字符串，整数位最多16位；currency=CNY |
| priceFactorBps | 0～10000整数；表示参考价相对原价的万分比，9000表示九折，**不是减免9000个基点** |
| priceBasis | 固定PER_UNIT；不宣称总应付、税费全包或免除全部费用 |
| validFrom / validUntil | UTC的RFC3339秒精度时间，合法区间 `[validFrom, validUntil)`，起点必须早于终点 |
| eligibility | 首版AVAILABLE固定ELIGIBLE，来自受信源事实，不能采信用户自述或模型推断 |
| conditions | 1～8项 `{code, state}`；code来自版本化有限合成条件目录，AVAILABLE每项state必须SATISFIED |
| claimCodes | 首版允许REFERENCE_UNIT_PRICE、CONDITIONAL_PRICE，固定有限集，不能携带任意营销正文 |
| effect | 固定INFORMATION_ONLY，排除已锁价/已领取/已办理/保证成交 |

金额和折扣均来自同一来源记录，Provider不接收模型提出的折扣，也不负责按自然语言重新定价。语义验证必须满足 `referencePrice × 10000 = listPrice × priceFactorBps`，以整数分或精确十进制验证；不隐式舍入。超精度、科学计数法、float、跨币种、矛盾关系直接拒绝，不能由消费者修正。

原价为0时，仅允许referencePrice=0且priceFactorBps=10000，不能利用0乘任意比例都相等来制造折扣事实。

例：来源给出的原价1000.00、referencePrice900.00、priceFactorBps9000，且当前主体适用所有条件、处于有效期内，才可表达“当前适用参考价900元，条件……，有效至……”。同一条数据不能被改写成“已经为您锁定九折”。零参考价只能展示该单位参考价格为0，不能外推出“所有费用免费”。

非AVAILABLE时没有部分报价可供公开。资格明确不满足为NOT_APPLICABLE；资格/关键事实缺失为MISSING；来源故障为UNAVAILABLE；过期为EXPIRED；事实关系冲突为CONFLICT。错误主体/请求/撤权等控制拒绝使用原FAILED语义和固定安全错误；不通过业务NOT_APPLICABLE泄露未授权资源是否存在。

## 5. 披露控制：为什么不是普通QUERY就足够

领域包提供纯数据验证与可披露事实投影，逻辑调用为：`validateForDisclosure(facts, expectedBinding, now)`。expectedBinding含可信subject/product/currency、已安装合同/来源版本和快照摘要；now由受信Host时钟提供。失败只给有限诊断，不返回部分价格；成功只返回有限事实/claimCodes，不返回任意HTML或模型文本。具体Java签名在该语义接受后的Tasks中落定。

Java入口为 `QuoteDisclosureV01.validateForDisclosure(QuoteFactsV01, ExpectedBinding, Instant)`，返回不可变Decision，失败projection=null。这个纯函数**不鉴权**。Host必须：

1. 查询前以现有Host Grant及Provider边界检查当前主体、用途、scope、会话和所选产品。
2. Provider返回后核对外壳requestId/traceId/sourceSystem、完整领域结果和受信源引用。
3. 公开前重新取当前授权，并用当前绑定/当前时间验证；不能只在调用开始检查一次。
4. 仅由获准事实与经审核标签/模板形成公开内容。Core的content、Provider自由文本、模型前后缀、用户输入回显、异常栈不能直接面客。
5. 首个公开内容及每个后续分片发送前重新检查当前权限与有效期。未通过时只发送固定无价格提示或终止，不先发送再撤回。已发送信息不可追回，不宣称消除网络在途撤权竞态。

首片试点不开放自由模型token直通，也不新增恢复/缓存协议。未来缓存/重放必须经过同样披露验证。Host只知道“支持QUERY”不足以满足上述要求。

Pack的assembly requiredFeatures除既有query.v1/trusted-grants.v1/observation.v0.1，还要求候选 `quotation-disclosure.v0.1`。这是**新要求名**，不是已存在特性；必须在具体Host实现控制点并完成验收后才可声明支持。未知要求应被拒绝，不能为让目录通过而手工添加支持字符串。H1不会据此自动开放原生构建；Builder FEAT-017的实际Host试跑门禁仍需单独闭合。

## 6. 合成Provider与可信配置

通过既有ManagedProviderFactoryV01/ServiceLoader和Provider companion安装，不接受包或网页提供任意类名、脚本或程序入口。候选connectionKind为 `quotation-reference.v0.1`，是新受管参考来源，不假扮真实银行连接。

参考源使用操作者提供的只读合成快照配置，包含显式客户/产品范围、条件事实、记录和绝对有效期。工厂在首次被选中配置/创建时严格读取并冻结，不在每次查询中自动延长报价有效期、不静默换用最新源。未选中ServiceLoader工厂不读取配置。采用既有configurationFiles/摘要接线：环境变量 `FH_QUOTATION_REFERENCE_CONFIG` 必须是绝对普通文件路径，`FH_QUOTATION_REFERENCE_CONFIG_SHA256` 必须是原始字节SHA256；非链接父路径、最多256KiB，拒绝重复/未知键，Host同时保护文件和预期摘要；`configurationDigests()`报告实际字节摘要。连接仅允许本地标识 `reference://quotation`，不是网络地址。模型/浏览器不能选择路径、改摘要或注入授权。

所有引用客户必须是合成标识。Provider内仍核对可信主体与选中产品映射，不因只读放行越权；Host Grant/current authority是权限真源，配置只限制可读参考数据，不能产生银行权限。第一片Provider不发HTTP、不复制IAM、不持有真实源凭据。

快照变更必须新建版本和摘要、新安装/实例并重验；重启不能沿用旧Lock冒充同一来源。测试时可由受信测试代码生成固定绝对有效区间后冻结配置；测试时钟仅用于单测，实际运行使用Host真实时钟，不能用模型传入日期放行过期数据。

## 7. 装配、观测和阶段资格

新能力仅声明QUERY；Provider的executeTask总是安全拒绝且写调用数为0。目录、companion、实际Pack、Provider descriptor及工厂requirements必须一致。初期新增目录项只能是候选，不修改旧资产版本/摘要，不自动放行Java/Python/AgentScope。

复用REQUIRED capability.result元数据观测；必要字段沿用capability/provider/request/status/time等，不采集内部思维链。公开承诺验证还必须绑定quote/source/config/contract/condition目录/Host控制实现及用例版本。新披露事件语义不能冒充已安装collector或原有capability.result足以证明它；实际Host支持与证据格式在Builder后续接口中审核。

三层分开：

- H1：领域合同、合成Provider、组件资产和正反例通过。仅说明组件候选可消费。
- H2：目标Host实际安装、原生SDK调用、披露门禁及失败清理通过。才声明该固定组合可试跑。
- H3：Builder方法审批、完整行为矩阵、交付和迁移分别通过。不得由H1/H2直接推导。

当前实现计划是先H1，H2由既定FEAT-017试跑接线衔接；若H2必须修改Harness Integrator，先记录实际缺口/影响，不在新Provider中绕过。

## 8. 预期交付位置与兼容性

H1组件目录如下；执行计划由Harness FEAT-001的已检查本地工作包维护：

- `contracts/json-schema/quotation/`：新quote-facts.v0.1 Schema。
- `packages/java/quotation/`：Runtime无关领域DTO、严格校验与有限事实投影及测试。
- `providers/quotation-reference/`：合成Provider、受管Factory、ServiceLoader、companion和测试。
- `capability-packs/quotation-consultation-readonly/`：QUERY Pack及companion。
- `conformance/fixtures/quotation/v0.1/`：独立正反例、合成来源/条件样本及版本引用。
- `observation-packs/readonly/`：新能力所需的既有元数据观测声明。
- `docs/`、根pom聚合模块、候选目录元数据与相应工程验证：正式合同说明、阶段证据和拒绝旧Host的例子。

使用既有Java21、Core、Jackson和JUnit锁定版本；不引入新第三方坐标/SDK。Core/SPI/Grant不改，Execution不改，既有产品资料和旧Agent的Lock不改。金额语义与Wealth精确十进制方向一致，但不修改或复用其领域类型来混同业务意义。

Harness与Builder保持关联交付、分别审核/发布/回滚。新包停用/撤下目录不修改旧制品；未经兼容控制验收的Host继续拒绝新包。H1基线a8fd7808f5da4742506c16344e208c26506240ec，隔离分支codex/quotation-readonly-h1；未提交的实现状态不冒充源仓提交、PR或发布。

## 9. 必须覆盖的验证

在实现前冻结独立断言，不以实现返回值产生期望。至少覆盖：正常价格/条件；九折与一折语义；不同客户/产品/币种同金额；非法金额/舍入/未知字段；未知条件/资格缺失/明确不适用；区间起止及中途过期；旧源/错误摘要/版本；模型与工具内容诱导；未经许可的非数字承诺；TASK/续办字段拒绝；零写派发；配置链接/重复键/超限；未知Host要求拒绝；撤权不阻断可信清理；单次结果不获得重复执行/永久活动资格。

领域/Provider单测可使用受控替身，但模型首片段/真实授权刷新/原生SDK/真实公开出口只能由H2/H3实测证明。组件验证入口为 `python engineering/verify_quotation_reference.py --report .specify/FEAT-001/evidence/component-report.json`；需已有Java21、PATH中的Maven和H1_MAVEN_REPO指定的离线缓存。它执行固定reactor、逐例检查新鲜JUnit结果，并绑定源文件、冻结用例与两个JAR摘要；缺工具、跳过、篡改或缺例均失败。报告是本地验证记录，不是签名或对敌对操作者的远程证明。

用例时钟固定2026-10-19T12:00:00Z；示例源有效区间为当天11:00至13:00 UTC。实际工厂使用真实时钟，过期不自动延长；复制示例并不能获得持续可用报价。更换源需新版本、新摘要和重新验证。

## 10. 决策与下一门禁

已确认语义：一个只读QUERY，CNY精确两位小数，来源给定价格及万分比，客户仅取可信上下文，完整事实/条件/期限绑定，披露前动态检查，不支持锁价/领取/写入。

H1记录见[FEAT-001](features/FEAT-001.md)，Feature位置docs/features已接受。用户的设计接受不冒充CODEOWNERS维护者审阅或上游合并。实质接口差异必须回审。当前未授权提交、推送、PR、部署或合并。H2/H3、生产认证、真实银行和真实模型调用保持NOT_RUN。



## H1 实施范围记录

上述接受是项目公共语义接受，非维护者合并/发布。H1任务以docs/features/FEAT-001.md及本地工作包为准。可披露纯验证函数不执行鉴权/发送；H2才验证具体Host/SDK的真实出口。
