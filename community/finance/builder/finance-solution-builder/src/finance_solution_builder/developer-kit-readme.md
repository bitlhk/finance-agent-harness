# Finance Harness 开发样例 V0.2

这是三个小型练习共用的固定源码底座，不是可直接导入工作台的业务组件 ZIP。
完整 SHA 和逐文件摘要见 source-lock.json；不依赖 Builder 或原作者目录。
SDK、Runtime 和构建插件版本见 harness/pom.xml；候选 SDK 的 SNAPSHOT 以本包源码 SHA 固定，不能用远端同名快照替代。
下载包不含运行配置、密钥、真实客户数据或依赖缓存。首次构建需联网获取 Maven/Python 依赖。

## 第一次运行

需要 JDK 21、Maven 3.8+、Python 3.10+、Bash，以及可绑定 loopback 端口的独立开发环境。
禁止设置任何真实环境 *_LIVE 开关。以下为受控脚本模型和合成银行，不证明真实模型专业质量。

```bash
cd harness
python3 -m venv .venv
.venv/bin/pip install -r engineering/requirements-conformance.txt
.venv/bin/python engineering/verify_contracts.py
mvn -B -ntp verify
bash examples/solutions/wealth-facts-kit/run-controlled.sh
.venv/bin/python engineering/verify_wealth_kit.py
```

真实环境 Smoke 默认跳过，其余失败不能跳过。结果在 examples/solutions/wealth-facts-kit/target/runs/，
包括 report.json、solution-lock.json 和受控模型调用记录。正常三例 COMPLETED，拒绝两例 REJECTED，拒绝业务读取为零。
第二次运行会覆盖同名报告，先自行归档。每个 Agent 的安装 Lock 独立生成。

## A：定制方法，不改 Provider

修改 domain-packs/wealth/shared-facts/skills/wealth-facts-description/SKILL.md 的解释结构，保留工具边界、缺数及授权要求。
练习：增加“已确认事实 / 尚缺资料”两个输出段。增加对应案例，不能用只改标题证明输出遵循方法。
在工作台业务资产选择“导入 Skill”，上传此 SKILL.md；绑定已有财富事实能力，保存草稿。
依次检查、受控试跑、真实模型预览（需受管模型）、发布新版本、下载业务 ZIP、使用该版本创建 Agent。
不要将本源码开发包上传为业务 ZIP。专业质量需人工确认；结构及脚本模型通过不替代人工验收。

本地方法与安装回归：
```bash
mvn -B -ntp -pl integrations/openjiuwen-java -am test -Dtest=OpenJiuwenDomainPackInstallerTest -Dsurefire.failIfNoSpecifiedTests=false
```
仅未拥有此测试的上游依赖模块允许无匹配；目标模块必须有非零执行数。
发布后在“我的草稿与版本”打开固定版本，核对包内 SKILL.md。改草稿不能改变已发布文件。

## B：组合已有来源与能力

从 examples/solutions/wealth-facts-kit/solution.yaml 和 customer-bank.json 开始。
查看 capabilityPackRefs、domainPackRefs、providerRefs 与 capabilityBindings；这是装配引用，不是新的执行引擎。
切换至同目录 customer-snapshot.json，复用原方法与消费者，仅更改受控来源 binding。
```bash
bash examples/solutions/wealth-facts-kit/run-controlled.sh
.venv/bin/python engineering/verify_wealth_kit.py
```
核对两个 CUSTOMER 来源的结果、来源范围与安装资产；EMPLOYEE 示例是跨角色边界，不作为同条件来源替换证据。
工作台选择多个已准入能力可组成新 Agent；不需要修改 Builder 公共代码。
新增目录组合由维护者登记版本与兼容要求；文件存在不等于准入。

## C：Provider 接口与受控实现

接口：packages/java/core/src/main/java/io/github/bitlhk/finance/harness/core/spi/FinanceProvider.java。
参考实现：providers/non-ai-banking/src/main/java/io/github/bitlhk/finance/harness/providers/nonaibanking/。
修改本行字段映射应在 Provider 工程中进行，不改 Core，不复制权限或恢复机制。
从 NonAiBankingProviderTest.java 的本地 HTTP 模拟响应新增一个本行格式正常例，再新增错主体、坏响应或缺数反例。
```bash
mvn -B -ntp -pl providers/non-ai-banking -am test -Dtest=NonAiBankingProviderTest,CashflowProviderTest -Dsurefire.failIfNoSpecifiedTests=false
```
新增实现使用 ManagedProviderFactoryV01，并在执行制品的 META-INF/services 中注册。
能力声明在 capability-packs/*/pack.yaml，执行要求在对应 assembly.v0.1.json，源调用观测参考 ReferenceObservationLabels.java。
先编译、正常及负向验证、固定制品版本摘要，再由维护者登记 catalog 和受管装配目录。
网页不执行上传的 Java/Python 代码。银行连接与密钥在目标环境绑定，requiredScopes 不是实授。
新协议或 Python TASK 不属于此示例支持范围。

## 故障定位和提交清单

- 未知工具：核对 allowed-tools 与已安装 toolName，不能只修改提示词绕过。
- Provider 缺失：先提交受管制品与能力实现映射，不能编造 providerId。
- 版本冲突：核对清单双方版本；不能替换成当前 latest 冒充旧引用。
- 环境不支持：选择已验证 Runtime，保留执行、授权、确认及观测要求。
- 验证失败：查看 Maven surefire-reports 或 CLI report.json；保存命令与实际测试数量。

交付修改文件清单、合同与映射依据、测试命令及结果、制品摘要、目录引用和维护人。
公共观测自动覆盖的范围与 Provider 自行补充的来源观测分别说明。声明事件不等于已埋点。
真人记录实际耗时、环境缓存、求助与卡点；AI 复跑不能代填独立接入验收。
