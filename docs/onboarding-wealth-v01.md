# 财富只读：独立接入手册

对象：场景工具组未参与本仓开发的一位同事。目标是按文档复跑受控场景，再在获准后接真实只读源。
当前自动入口不会联系真实银行/EA，不需要任何生产 Key；依赖安装需互联网，测试业务 HTTP 仅 loopback。
这是测试配置，不是网络沙箱；使用无生产凭据的开发环境。暂不测模型质量、真实授权或办理。

## 从干净工作副本开始

需要 Git、JDK 21、Maven 3.8+、Python 3.10+、Bash，允许绑定临时 loopback 端口。
由私仓管理员授予仓库读取权限；使用自己已登录的 Git/SSH 凭据，不把 Token 写进 URL。

```bash
git clone git@github.com:bitlhk/finance-agent-harness.git finance-harness-onboarding
cd finance-harness-onboarding
git switch --detach <已确认包含接入脚本的完整提交SHA>
python3 -m venv .venv
.venv/bin/pip install -r engineering/requirements-conformance.txt
.venv/bin/python engineering/run_onboarding.py
```

交接人必须提供本轮可获取的完整 SHA，不使用不断变化的 main 代替固定基线。
真实接入脚本所在新提交尚未合并时，应取对应评审分支提交；`647b41c` 是前置基线，尚不含本入口。
从 clone 前记录人工起始时间，包含权限申请、安装、下载、排障和求助。已有 JDK/Maven/依赖缓存时如实注明。

脚本要求 Git 工作区干净。修改专属代码后先提交个人分支再运行，不会 stash/reset 或删除你的改动。
使用未提交的源码或脚本在编辑期间自测，应手动运行原门禁，不能冒称干净提交复现。
启用了任何 `*_LIVE=true` 时拒绝启动；继承的 JVM/Maven/Python 优化开关不传入，避免跳测试。
脚本只编排现有门禁，遇到错误立即停止。没有改变 CI、Provider、业务或授权逻辑。

## 会运行什么

1. 记录实际 Java/Maven/Python 版本和 Git commit/tree。
2. Schema/Fixture 校验。
3. 全模块 `mvn -B -ntp verify`，真实 Smoke 默认跳过。
4. Python 回归，消费 Maven 实际生成的 Java 时间样本。
5. 五份受控 CLI，经过 Assembler、原生工具、Bound Provider、映射和共享消费者。
6. 校验报告、Lock/Trace 摘要及拒绝路径；收集受控结果。

每次使用独立 `target/onboarding/<运行ID>/`，其中 `report.json` 记录步骤、退出码、命令耗时和日志摘要，
`controlled-runs/` 保存本次受控证据及其摘要。只收集 Fixture 结果，不收集 `live-smoke`。
自动 PASS 只代表门禁通过；`liveAuthorization=NOT_RUN`、`realProviderSubstitution=NOT_RUN`、
`independentHumanOnboarding=PENDING_REVIEW` 不会被脚本提升。
失败时根据 report 指定的步骤日志排查；依赖/权限错误不靠跳过模块解决。

核对三个正常 COMPLETED、两个 REJECTED，拒绝源读取为零、修改尝试为零。
正常案例金额 `1000000.12345678 CNY` 是虚构输入；观察事实来源、业务日期及 `NOT_EVALUATED` 适当性。
受控 BANK_HTTP/SNAPSHOT 替换不代表第二家真实银行已接入。

## 本人填写的接入记录

另存到受保护交接目录，提交给场景工具组复核；不要把真实客户或密钥放入记录。
不能由 AI 自动填姓名、求助次数、人工耗时或“独立通过”。

| 项目 | 本人实填 |
| --- | --- |
| 执行人、是否参与过本仓实现、确认人 | 待填 |
| 完整 SHA、操作系统/CPU、JDK/Maven/Python | 待填 |
| 全新环境还是已有依赖缓存 | 待填 |
| 人工开始/结束时间，暂停与等待时间 | 待填 |
| 自动报告路径、失败步骤/最终结果 | 待填 |
| 仓库权限、配置、专属代码改动列表/commit | 待填 |
| 每次求助：时间、对象、问题与解决方式 | 待填，未记录不能算零 |
| 主要卡点、必须由原作者解释的步骤 | 待填 |
| 业务结果及拒绝样例由谁核对 | 待填 |
| 独立接入结论与范围 | 待复核，不等于真实授权/Provider 替换 |

受控入口预期不需修改业务代码；实际有改动仍按事实记录。
真实接入时专属部分是：宿主当前授权读取、adoption-owner 对应、只读端点/Token、字段口径依据。
公共部分是：装配、要求/实授权限校验、主体隔离、映射与缺数、只读消费者、Evidence。
没有旧流程对照不计算“提效百分比”，脚本秒数不能代替人工接入时间。

## 转入真实只读的门槛

必须先按[双线验收计划](wealth-readonly-acceptance-v01.md)确认员工、客户范围、权威身份、当前实授和日志投影。
不要将固定 Fixture Grant、`scopeVerified=true` 或自己填写的绑定 JSON 改个名字复制为生产授权。
旧 `EMPLOYEE_AGENT_LIVE` Smoke 不是本轮正式验收入口。没有这些材料就保留受控接入结果，继续列出外部依赖。
