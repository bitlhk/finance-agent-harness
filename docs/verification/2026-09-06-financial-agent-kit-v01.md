# 可复用金融 Agent 接入套件 V0.1：分项验证报告

日期：2026-09-06（Asia/Shanghai）。状态：**Candidate V0.1 / Controlled Integration Verified / Live and Independent Acceptance Pending**。

## 基线与交付范围

开工承接 `f8bb6156fd6167ad4b39a961aad98798b2e60ff4`，PR #2 仍是依赖，未代用户合并。原目标分支 `feat/wealth-cross-application-builder-v1`；新增实现分支 `feat/financial-agent-kit-v01`。未改同事 Task-centric 仓库，未部署环境，未读取真实客户数据或执行真实办理。

本轮交付独立财富模块、确定性双源映射、共享只读消费者、候选 Schema/Fixture、Pack/目录入口、原生 AgentLoop CLI、报告校验器和 CI。原四合同、原能力 ID、客户/员工原响应及宿主最终授权责任保留。兼容迁移与完整入口见 [快速接入](../../examples/solutions/wealth-facts-kit/README.md)。

## 执行底座复核

| 问题 | 本轮动作与行为证据 | 界限 |
| --- | --- | --- |
| 续办自动升级为确认 | 继承 PR #2 的 HostTaskConfirmation；回归空文本、模型肯定文本、旧确认、重复/过期、跨任务及真实宿主测试事件 | 模型参数不能批准；未连接真实 UI 确认，不开放真实办理 |
| requiredScopes 变实授 | Bridge 检查当前 scopes，BoundFinanceProvider 独立检查能力要求与宿主 Grant；不足时 Provider 调用 0 次 | Grant 的真实性依赖可信宿主接线，不是权限服务器 |
| 固定银行账号与 Context 不一致 | 验证银行 Session.customerRef，不假设 loginName 等于 principalId；错主体在业务查询前拒绝 | 测试身份来自模拟银行，不代表真实账号已核实 |
| EA adoption/客户绑定 | 新增宿主提供的 adoption-owner 绑定；输入与返回客户不一致拒绝；错误信息不回传原数据 | 本轮未取得真实授权 EA READY |
| 缺参无法续办 | Adapter 明示本地收集阶段、可补字段，Controller/Bridge 同步接受补齐；冻结后的变化拒绝 | 不是通用参数编辑器，不替用户取消银行任务 |
| 创建 ACK 丢失或不合法 | 坏 JSON、合法 JSON 坏 data、缺 taskId 均保留不确定创建记录；改参不新建、恢复不盲重试 | 无上游幂等查询接口时需人工查证，不虚构 taskRef |
| 已提交状态的暂时观察失败 | 继承 PR #2 状态映射及单机文件 Store，回归 UNKNOWN 后查询断网、重复查证与明确终态 | 文件新实例/双控制器同 JVM 测试，不宣称多进程多节点 |
| 缓存恢复绕过新授权 | 恢复、确认及读取终态缓存均重查；撤权后原私有结果不回流 | 宿主需提供动态 Supplier，固定旧 scopes 不是撤权接线 |
| 声明摘要与运行资产 | 继承声明 digest 比较，增加包装器内层 Provider 的运行制品 inventory | 无声明摘要仅观察内容；本地 Adapter 摘要不等于银行服务证明 |
| 验证“假绿” | 空 Conformance/缺任务状态回归；真实 Schema 2020-12 校验；日期校验依赖缺失直接失败 | 模型质量、真实授权、人工接入不填 true |

回归过程中曾发现创建响应 `data={}` 仍被当成确定失败，补了 taskId 缺失的行为测试与异常映射。另补极端十进制指数/错误 stale 类型，以及缺口列表为空但必要事实缺失的负例。没有放宽标准或修改上游固定数据迎合测试。

## 本机自动验证

环境：Ubuntu Linux x86_64、OpenJDK 21.0.12、Maven 3.8.7、Python 3.12。Runtime 依赖 `agent-core-java:0.1.13`，代码/依赖从本分支 pom 构建。

| 实际入口 | 结果 |
| --- | --- |
| `mvn -B -ntp clean verify`，07:03 | 13 模块成功，25.178 秒；清洁构建后又补了 1 条 Readiness 负例 |
| `mvn -B -ntp verify`，07:07 | 13 模块成功，21.571 秒；26 个测试类，140 项，133 通过、0 失败、0 错误、7 跳过 |
| `engineering/verify_contracts.py` | 9 个 Schema、53 项 Fixture/资产/状态探针通过；共享财富正反例同时被 Java 消费 |
| `run-controlled.sh` | 构建依赖后运行默认 5 个独立 JVM 配置；无外部模型 Key，无生产业务端点 |
| `engineering/verify_wealth_kit.py` | 核对运行输出 Schema、Trace/Lock/构建输入 SHA、原生回调、同条件受控替换及无权限拒绝 |
| `git diff --check` | 无空白错误 |

7 项跳过为显式真实环境 Smoke：银行查询 2、银行 Task 1、转账 1、反诈 1、EA 财富 1、EA 保险 1。未跳过离线关键模块。CI 已加入同样合同、Java、CLI 和报告检查，并归档 XML 与受控运行文件；最终 CI 状态以本 PR 检查为准。

## 分项验收结论

| 命题 | 当前证据 | 状态/不能推出的结论 |
| --- | --- | --- |
| 共同语义 | 两种源形状映射同一金额、范围、估值时间、风险体系；零/缺失/过期/冲突/缺元信息有负例 | 受控通过；真实字段口径仍需业务确认 |
| 跨角色边界 | CUSTOMER/EMPLOYEE 分别走银行 Session/EA adoption 验证；共同任务排除名字、电话、内部笔记等私有字段 | 本地隔离通过；不是生产授权验收 |
| 同 Profile 换 Provider | CUSTOMER：BANK_HTTP 与 SNAPSHOT 仅改 binding，同一 Skill/消费者和等价 Fixture | 受控替换通过；EA 与银行跨角色不算实际可替换 |
| 开发者接入 | 从配置到真实 ReActAgent、工具、Provider、映射、结果与摘要校验可复跑 | AI 本机复现通过；非原作者独立接入待验收 |
| 真实模型表现 | 使用脚本 OpenAI 协议端点以固定工具选择 | 未执行，不提供胜率、Token 成本或专业质量结论 |
| 真实授权只读 | 没有新的银行身份核验或 EA READY 材料 | 未执行，不以旧 DEGRADED Smoke 替代 |

正常三份配置应完成、源读取分别为 2/1/1 次；拒绝两份配置为 REJECTED、源读取 0 次。全部写入 0 次，全部单元为 2 次脚本模型请求。工具结果来自原生 `ToolCallInputs`，不是 Harness 自造的成功事件。

完整受控报告在 `examples/solutions/wealth-facts-kit/target/runs/`，由 CLI 校验器重新计算摘要。CI 的同名制品保留该次运行身份和输出。报告目录复跑会覆盖；时间/随机会话/构建字节不同产生不同摘要是正常的，不能要求各次证据 hash 相等。

## 开发效率与复用记录

- 初次实现增加 1 个 Runtime 无关财富公共模块、6 个主代码类；开发者示例为 1 个入口类和 1 个受控 HTTP 服务类，不增加中央服务或第二 Runtime。
- 复用现有 Bridge、Task/Confirmation/Checkpoint、Provider SPI、Assembler 和 OpenJiuwen 原生 Tool；没有复制银行业务状态机或 EA 客户授权服务。
- 用已有源格式新增场景：主要写 Profile/Pack/solution 配置和可信宿主绑定，复用现有 mapper/消费者。接新字段形状：需要独立的确定性映射与语义案例，不承诺“任何银行只配 URL”。
- 本轮等价受控替换只改 `customer-bank.json` 中 `binding`；源 Adapter 独立，消费者和 Skill 未改。EA 真实接线仍需身份、字段字典、允许客户等材料。
- 本机缓存依赖下，一次默认 5 配置脚本运行曾测得 33.67 秒（包含 Maven 构建测试与进程启动）；单次报告约 0.7-0.9 秒，使用脚本模型。不是真实 LLM 时延，也不是人工接入耗时。
- 无旧流程可比基线，不给提效百分比。无真实同事参与，不把 AI 自测称为独立开发者验收。

## 外部验收及责任

1. 灯塔组/银行 Owner：确认 Session 对应主体、数据范围/币种/时点、风险体系和日期口径；提供脱敏真实只读样例。
2. EA/财富 Owner：提供合法 adoption-owner 映射、获授权客户和 READY 样例，确认开放 portfolio 字段的真实币种及估值含义。
3. 技术架构组：把真实身份解析接到 Grant/IdentityBinding；先跑错主体、撤权、实授不足负例，再显式只读 Smoke；本轮 CLI 不允许直接切 LIVE。
4. 场景工具组：在干净副本按快速接入复跑，记录专属修改点、实际耗时和失败现场；由人完成独立接入验收。
5. Runtime 通用问题由参谋部与 2012 承担；本轮记录 SDK 同步回调事件差异，不改其引擎。第二 Runtime、通用权限平台、金融本体和正式办理发布不自动展开。

共同财富表达为项目候选合同，不冒用 ISO 20022/FIBO/CDM 或国内标准认证；真实业务依据和未确认项见 [字段表](../wealth-field-basis-v01.md)。
