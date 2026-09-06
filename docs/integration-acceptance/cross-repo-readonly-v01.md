# 跨仓只读接入 V0.1：首批实施与交接

本轮以手机银行上游 **main 的固定提交** 为基线，不把旧 release 的验证直接搬过来。
演示增强与套件接入共享代码，但配置、实际参与组件和验收结果分开。
不改同事主线，不部署生产，不执行真实转账，不把 AI 自测记为人工独立接入。

## 仓库与历史

| 仓库 | 基线/现状 | 本轮角色 |
| --- | --- | --- |
| wuxiaojingjing/mobile-banking-3.0 | main `f89c9fb2215dc4ebca169f676f96fac2d18b0e8c` | 选定上游 |
| bitlhk/mobile-banking-3.0 | 既有私有 Fork；适配分支从 `4c1e13585c2da84aea0425595a958179b63216ba` 继续 | 手机银行宿主薄桥 |
| wuxiaojingjing/non-ai-banking | main `f48fe89ecc56c79dbcd48fbb69124fc54b429f7a` | 模拟器上游 |
| bitlhk/non-ai-banking | 新建私有 Fork；保留反诈分支 `a69b33f68c959e96101d219b47334aa03b1950ca` | 后续完整模拟器联调；本批测试未启动该服务 |
| bitlhk/finance-agent-harness | 从 PR #6 `c8ed56592ba36b803ed099b57ce2ae16e0cc1802` 继续，包含已合并 `647b41c` | 唯一公共实现 |
| bitlhk/employee-agent-internal | 源码检查 `5706428bfd9b3d1708fa3e9e710a212a48aa229b` | 接入准备，未参与本批运行 |
| Builder MR #118 | `66d2767557cf4fd4890d55fe8729aaeafd30de97` | 待同事复跑/产品迁移，未参与本批运行 |

`4c1e135` 已包含所选 `f89c9fb`，`a69b33f` 已包含 `f48fe89`，已用 Git ancestry 核验，
无需重复 cherry-pick。手机银行旧 release `4676a62` 到选定 main 的六个提交涉及供给查询/任务、
HITL interactionId 和知识边界等，必须在新组合下回归。
原 Task-centric、标准 UI 与旧反诈分支均保留，不强制合入/删除，不把全量旧改动回馈上游。
手机银行 Fork 既有 main `5599b2b` 未被覆盖。当前使用短期分支 `feat/harness-readonly-v01`。
Harness 使用 `feat/cross-repo-readonly-v01`，依赖尚未合并的 PR #6；本轮不宣称 PR #6 已合并。

## 任务清单

- [x] 核实 Fork/远端和工作区，保留历史。
- [x] 核对 main/release 差异及适配分支 ancestry。
- [x] Harness 基线回归；复现旧手机薄桥四项不兼容测试。
- [x] 实现宿主会话构造入口和显式实授的只读装配；复用公共财富 Mapper/Consumer。
- [x] 新增 18 项受控 HTTP 边界案例。
- [x] 提供跨仓复跑脚本和独立交接说明。
- [x] EA 接入入口、身份来源和缺口盘点。
- [ ] 手机银行真实会话接线、页面到 Agent 对话的端到端调用。
- [ ] 启动完整模拟器，核对源 Session/事实和出站调用。
- [ ] 非原作者独立复跑，记录人工时间/求助。
- [ ] Builder 自动生成同一个应用并装配运行。
- [ ] 真实授权只读、同条件真实 Provider 替换，分别验收。
- [ ] 将模拟反诈转账接上可信宿主确认及恢复路径。

## 第一批代码实际做了什么

手机银行的 `FinanceHarnessReadOnlySupport.wealth()` 接受受信宿主创建的 `HostBinding`：
principal、subject、session、purpose 和当前 Grant Supplier。它不是 Tool 参数、用户 JSON 或通用权限平台。
每个登录会话独立装配，禁止把某个用户的 binding 做成全应用单例。

公共机制仅在 Harness：

```text
手机银行 RoutableCapabilityAdapter
  -> OpenJiuwenFinanceBridge（显式宿主 Session、实时 scopes）
  -> BoundFinanceProvider（外层 wealth:facts:read）
  -> WealthFactsProvider
  -> BoundFinanceProvider（源 wealth:customer:read）
  -> NonAiBankingProvider（核对源 Session.customerRef）
  -> 既有共同财富映射、缺数处理和只读消费者
  -> 手机银行 ExecutionResult / Evidence
```

本批测试直接调用手机银行原生 Adapter SPI，使用真实 Provider HTTP 客户端和临时 loopback Fixture；
**没有启动完整手机银行、没有模型对话、没有启动完整银行模拟器**。
旧环境配置入口 `FinanceHarnessOpenJiuwenSupport` 尚未迁移到真实登录/实授 Supplier，
本批新增入口也尚未接入 DomainRuntimeAssembly；不能把它宣布为已发布/真实授权。
不要运行旧 live-demo 脚本来替代本批验收。

Harness 的新增构造重载只接收宿主 sessionRef，旧构造器及安装器行为不变。
未改变四个 Core 合同或交易规则。当前手机桥仍不能从模型确认文本产生可信确认；旧测试改为验证
冻结参数保留、第二轮仍待确认。未接确认 UI 的旧办理能力仍不得宣布可用。

## 可重跑入口

开发者取得两个私仓读取权限。用正常 Git/SSH 凭据，不把 Token 写进命令或版本清单。
交接人提供 **本批 Harness 与手机银行两个完整提交 SHA**，禁止用不断变化的 main 替代。
本轮源代码可从各自 `feat/cross-repo-readonly-v01`、`feat/harness-readonly-v01` 分支获取后固定 SHA。

```bash
git clone git@github.com:bitlhk/finance-agent-harness.git finance-agent-harness
git clone git@github.com:bitlhk/mobile-banking-3.0.git mobile-banking-readonly
git -C finance-agent-harness switch --detach <HARNESS_SHA>
git -C mobile-banking-readonly switch --detach <MOBILE_SHA>
cd finance-agent-harness
python3 -m venv .venv
.venv/bin/pip install -r engineering/requirements-conformance.txt
.venv/bin/python engineering/run_mobile_readonly.py --mobile-root ../mobile-banking-readonly
```

JDK 21、Maven 3.8+、Python 3.10+、Bash；无生产凭据、LIVE 开关关闭。
脚本要求两个工作区干净，先构建/测试/安装本份 Harness，再 clean/test 手机银行薄桥及其依赖。
安装会更新本地 Maven 的四个 Harness SNAPSHOT，建议使用专用开发环境，避免并行构建其他版本。
每条受控结果记录实际加载的四个 JAR SHA，脚本核对它们等于本次 Harness 构建产物，
不能仅凭同名 SNAPSHOT 宣称版本一致。旧应用未升级的单机常驻服务不自动重启。

结果在 `target/mobile-readonly/<run-id>/report.json`：实际 commit/tree、每步命令/退出码/秒数，
日志及产物 SHA、18 项案例。错主体/会话/权限的源业务读取为零；撤权用例先成功读取两次，
撤权后累计仍为两次，不能把累计值错写为零。坏响应允许有读取但不返回事实。
这是自动命令耗时，不是人工接入耗时或业务推理时延。

## 两次 Builder 验收

1. **非原作者手工复跑**：Builder 同事只消费现有入口，无需先改 Builder。
   按 [独立接入记录](../onboarding-wealth-v01.md)本人填写开始/结束、缓存环境、求助次数、卡点及专属改动。
2. **Builder 产品集成**：修改其模板/合同解析，生成相同能力及可信宿主接线，执行相同负例。
   手工 Maven 通过不代表 Builder 能生成；生成/健康检查也不代表真实授权完成。

目前尚无同事参与记录，两项均未验收；不由 AI 填“独立通过”或默认零求助。

## EA 接入准备

| 问题 | 现有证据 | 最小接入/缺口 |
| --- | --- | --- |
| 入口 | `server/_core/platform-tools-mcp.ts` 注册 `prepare_wealth_previsit_context`；`wealth-previsit-tool-handler.ts` 执行 | Java 消费 EA 源入口已存在；这不是 EA TS 已消费 Harness |
| Runtime 身份 | `internal-runtime-request.ts` 验签、核对 active adoption、消费一次性 jti | 用权威 adoption/user 映射导出有期限绑定，不用模型 header/配置用户名自证 |
| 员工与授权快照 | `governance/principal.ts` 从 adoption/user 与 DB 解析 `RuntimePrincipalV2` | 需明确当前授权撤销、有效期、客户集合、purpose/scopes 到 Harness Grant 的映射；不存在自动等价关系 |
| 客户范围与事实 | Handler 调用身份 probe、客户画像/持仓 MCP；源适配负责客户一致性 | `scopeVerified` 是业务返回证据，不是可重用授权凭证；必须确认来源检查和撤权链路 |
| 金融规则在哪 | EA 现有治理负责员工业务授权与源访问；Java Harness 负责其调用侧边界和共同财富投影 | 不在 TS 复制 Java 规则，不用 HTTP 中央服务来凑同一 JAR |
| EA 作为消费者 | 本次未发现已接通的 EA TS -> 当前四合同/Java 财富消费者调用入口 | 需 Owner 确认宿主承载方式再做薄适配；暂不计入运行成果，不扩第二 Runtime |

EA 已留本地准备分支 `feat/finance-harness-wealth-readonly`，不切换其生产工作区、不改用户的未提交资料。
当前只完成代码路径盘点，**没有接真实 EA、没有获得授权客户 READY 样例**。

## 责任与下一门槛

技术架构组交付薄适配/版本清单/公共回归；灯塔 Owner 确认身份、客户范围、字段与真实只读窗口；
Builder Owner 负责模板迁移；场景工具组安排未参与实现者复跑并承担后续复制。
维护 Fork 不等于接管手机银行完整产品。跨角色演示不能替代同 Profile、等价授权的 Provider 替换。
真实授权、完整模拟器/页面链路、Builder 产品集成、人工接入各自保留状态，不压成总 PASS。
