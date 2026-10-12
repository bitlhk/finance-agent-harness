# 干净 Runtime 财富只读参考模板 V0.1

本页保留原十案例受控 recipe 的边界。新增的持续服务与真实构建页面见
[本地金融 Agent 装配台](local-workbench-v01.md)，两类入口及模型验收状态分别记录。

## 定位与边界

目标是验证 `Builder -> 原生 OpenJiuwen Java SDK + Harness -> 金融只读任务`，
不以复制整个手机银行 Fork 作为生成 Agent 的前提。原 Task-centric/UI/反诈增强仍留在应用仓。
本变更属于 Finance Solution Builder 场景扩展；不改变 Base 契约、不建设第二 Builder。
目录安全及命令执行使用 `finance_builder_base.runtime`，金融规则只通过 Harness 制品消费。

当前 recipe 明确选择 `finance-example-wealth-facts-kit` 作为**受控测试宿主**：它创建原生 ReActAgent、
安装 Domain/Capability Pack、执行原生工具，经过当前 Grant、源 Session、共同事实映射和 Evidence。
模型仅按脚本发起工具调用，身份与事实为虚构数据；不是模型质量或真实银行的证明。
不要把该测试宿主当作生产身份实现，也不能用 LIVE 环境变量切换到真实办理。

## 可重跑入口

先取得包含本变更的 Builder 和 Harness 正常 Git 副本，记录两份 HEAD。Builder 代码未合入同事仓时，
使用评审分支或由维护者应用对应补丁，不能声称 MR #118 已包含这些变化。
JDK 21、Maven 3.8+、Python 3.11.4 至 3.13。Harness 独立工具的 Python 3.10 支持不变。

在 `community/finance/builder/finance-solution-builder` 中执行：

```bash
python3 -m venv .venv
.venv/bin/pip install -e ../base -e . -r <HARNESS_ROOT>/engineering/requirements-conformance.txt pytest
.venv/bin/python -m pytest -q -p no:cacheprovider
.venv/bin/finance-solution-builder build examples/wealth-facts-readonly/solution-build-input.v1.json \
  --harness-repo <HARNESS_ROOT> --output build/clean-readonly-001
```

每次使用新的输出目录。该 recipe 不自动删除旧产物，不支持 fallback；不支持的工具组合、
Task、真实模型、错误 Runtime 版本或源引用会在生成前拒绝。声明 Pack digest 会原样传到 Assembler
核验，未声明则只记录观察摘要；Maven Provider 的运行制品单独核验。

完整流水线：Harness Schema -> Java 全量测试并安装 -> Harness Python 回归 -> 独立 Candidate 编译
-> 真实 AgentLoop 十案例 -> 共享报告验证器。首次 Maven/Python 下载需要网络；业务与模型流量仅在 loopback。
使用单独的开发环境，构建会更新本机 Maven 的 Harness SNAPSHOT，不要和其他版本并行安装。
`--no-verify` 只生成，报告状态为 `not_run`，不是 PASS。

## 输出与复用

- `candidate/`：一个 Java 薄启动类、POM、配置、只读 Skill/Pack/Fixture、共享验证器。
  不复制 Harness Java、手机银行、Intent/Task-centric、Controller 或模拟器业务代码。
- `candidate/target/dependency/`：独立 SDK/Harness 及传递依赖 JAR。模板固定 Jackson BOM 2.20.1，
  不依赖手机银行父 POM；不打未经核验的 fat JAR，避免隐藏 Runtime 来源。
- `solution-lock.json`：Builder/Harness commit 和 dirty 状态、模板/资产/验证器摘要、6 个 Harness JAR
  与本次构建的一致性、完整运行依赖摘要。实际 Runtime 装配记录另在每个案例的 Lock。
- `build-report.json`：命令阶段、相对日志引用/摘要、耗时、十案例结果和未验收项。
  `passed_with_risk` 只表示受控矩阵通过，不表示上线批准。

构建后按 Candidate 内 README 运行；可把 `candidate/` 复制到另一目录，JDK/Python 从这个目录直接
启动及核对结果，无需原 Builder、Harness 或手机银行源码路径。首次在另一机器重新 Maven 编译时仍需
能获取相同 Harness 制品及其父 POM。这两种分发方式不要混为一谈。

十案例由 Harness 的 `customer-readonly-cases.json` 定义，业务断言也由 Harness 唯一维护：
正常银行源、等价 Snapshot、无授权、授权过期、错客户、模型伪造选择器、缺测评、缺口径依据、零资产、坏响应。
“缺口径依据”不会删除上游已经提供的币种/金额；应保留事实并明确资产范围和风险体系尚未证实。
已安装的工具只有 `wealth.facts.describe` QUERY，源 Provider 虽声明办理能力，也不意味着对 Agent 开放它们。

## 兼容与下一步

旧 customer-self-service / employee-assisted 生成入口保留，当前变更不宣称已迁移它们的真实身份接线。
既有输入预检/安全/模板测试必须一起通过。新增 Base workspace 依赖后，直接 pip 安装须同时提供 `../base`；
uv workspace 会解析同一份 Base。不复制公共路径函数或金融规则。

真实宿主应提供按会话解析、可撤销的实授 Supplier，固定源客户对应关系，替换受控时钟/模型/数据。
这需要后续独立模板和真实只读验收，而不是把 fixture 里的 Grant 改个用户名。
接入 UI、账户余额、可信确认、转账反诈及 UNKNOWN 恢复按 Harness 现有合同逐项开放，当前不自动装入。
Builder WebUI 自动选择与生成此模板尚未接线；CLI 已参与运行与维护者人工独立使用必须分别记录。
