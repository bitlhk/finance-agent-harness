# 首批跨仓只读接入验证

状态：`Controlled Host Adapter Verified / Application Wiring Pending / Independent Human Pending`。
本轮尚未达到“现有应用完整接入并由同事成功复现”的整轮完成标志。

## 已完成

- 复用既有手机银行私有 Fork；新建 `bitlhk/non-ai-banking` 私有 Fork，保留原反诈分支。
- 选择手机银行 main `f89c9fb`，核验旧适配提交已经包含它，不重复迁移六个 main/release 差异提交。
- 保留所有旧 UI、Task-centric、反诈历史；没有改动任何 main、真实客户数据或服务部署。
- 手机银行增加显式 HostBinding 的只读装配 API，调用公共 Bound Provider/财富映射/消费者。
- Harness 给外部薄桥增加宿主 Session 构造重载，模型参数和 taskId 不能替代该 Session。
- 跨仓脚本核验源提交、案例、结果和四个实际加载 JAR 与本次构建的 SHA。
- EA 源码盘点与缺口见 [接入记录](../integration-acceptance/cross-repo-readonly-v01.md)，本地已留准备分支，原工作区客户资料未改。

## 实际测试与范围

Linux x86_64，JDK 21.0.12、Maven 3.8.7、Python 3.12.3，已有 Maven/Pip 缓存。
`9d403ee3663abb51bfc225509c3a14961016644c` + 手机银行
`8d830b44756a05c125c4a813188a3dfff41437ab` 的干净提交组合：

| 命令/范围 | 实际结果 |
| --- | --- |
| Harness `mvn -B -ntp install` | 13 模块；169 项，162 通过、7 个显式真实 Smoke 跳过 |
| 手机银行 `mvn -B -ntp -pl framework/finance-harness-openjiuwen -am clean test` | 5 模块；113 项，111 通过、2 个显式真实 Smoke 跳过 |
| 新增只读场景 | 18/18，真实 Provider HTTP 客户端 + 临时受控端点，不是完整银行模拟器 |
| `.venv/bin/python -m unittest discover -s engineering -p 'test_*.py' -v` | 19/19；本机未运行完整 Python 3.10 解释器 |
| `.venv/bin/python engineering/verify_contracts.py` | 9 Schema、54 Fixture/资产探针通过 |
| 跨仓复跑脚本 | PASS，5 个命令步骤 40.843 秒；不是人工接入或 Agent 时延 |

完整矩阵见 [baseline JSON](../integration-acceptance/baseline-20260906.v01.json)。
运行结果和日志位于 `target/mobile-readonly/fd9275b82cfa4521ba57d95f3ddf4ec8/`，
未把临时路径当成 Git 中已上传的证据附件；脚本可从固定版本重新生成。

先用系统 Python 运行时缺少 `referencing`，两个模块导入失败；按现有 requirements 建立专用 `.venv`
后全部通过。没有跳过 Schema/Python 验证。旧手机桥升级前存在 4 项失败：Query 的 3 项测试仍假设
需求即实授，Task 的 1 项仍假设模型文本即确认。修正测试 Fixture/预期并增加负例，没有降低公共门禁。

## 明确未完成

- 新 HostBinding 尚未接入手机银行的登录/客户选择、DomainRuntimeAssembly、完整页面/Agent 会话。
- 完整银行模拟器尚未启动联调，反诈转账尚未升级到可信 UI 确认/恢复路径。
- EA 只完成入口和身份授权来源检查；未接真实授权、未获取客户 READY。
- Builder 同事未参与，人工接入时间/求助次数为 null，不记零、不代签。
- Builder 产品模板未在本轮修改或生成；手工复跑不替代产品集成。
- 不证明同条件真实 Provider 替换、真实业务、完整手机银行全仓测试或部署验收。

代码本轮先保存在独立本地提交；未向同事仓提交 PR。实际交接前需将选定提交推到自有私仓分支，
并确认接入者有读取权限。未推送不能要求同事通过远端取得这些提交。

## 下一项可执行工作

将 HostBinding 接到手机银行真正的服务端会话入口，核实 principal/customer/session 与实授来源。
若该入口没有当前 Grant 权威读取能力，明确以受控宿主模式联调模拟器，真实授权保留待接。
随后运行页面/Agent 到完整模拟器的只读正反例；Builder 同事可先独立复跑现有受控入口，
不要等待其产品模板全部迁移。按各自结果决定是否扩展业务，不再扩原语或建设第二 Runtime。
