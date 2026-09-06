# 干净 Runtime + Harness + Builder：交付边界 V0.1

## 目标

让开发者选择能力与场景资产，由 Builder 装配一个基于通用 Agent Runtime 的金融 Agent，
不用复制整套手机银行实现，也不用重新写身份边界、确认、查证恢复、事实语义与执行证据。
手机银行关键特性逐项接入；“一次生成完整可上线手机银行”不是当前交付承诺。

```text
Builder：选择模板 / 解析与核验资产 / 生成 / 编译 / 运行与验收
     |
原生 Runtime：AgentLoop / 模型 / Tool / Session
     |
Harness Integrator：安装能力与专业方法 / 消费可信宿主上下文
     |
Harness：公共金融合同 / 调用边界 / 确认与恢复语义 / 事实处理 / Evidence
     |
Provider Adapter -> 银行 IT：权威身份、客户授权、适当性、风控及交易终态
```

UI、登录会话及可信确认事件属于宿主。Builder 不能从 requiredScopes 生成实授，模型不能授予权限，
Harness 不能代替银行决定适当性或资金结果。“干净 Runtime”指不修改 SDK、不强制依赖手机银行
Intent/Task-centric 三引擎或 UI；不是删除必要的 Host Adapter。

## 本批实施

同事 Builder MR #118 的 `66d2767557cf4fd4890d55fe8729aaeafd30de97` 新建本地评审分支
`feat/harness-clean-runtime-readonly-v01`；Builder 仍归原维护者，不并入 Harness。
Harness 从 `aad5a537a069ce55e9be8c99e330cae1aeca7b6e` 继续，仍包含未合并 PR #6 的依赖。
未修改手机银行/模拟器/EA 源码，未推送同事主线，也未部署。

新增 `shared-read-only` 的显式 recipe 和单模块 Java 模板，生成：

1. 一个薄启动类与固定依赖 POM；
2. Harness 的只读 Domain/Capability Pack、配置和受控数据；
3. 安装的原生 SDK 与 Harness JAR；
4. 由 Harness 唯一维护的十案例矩阵、业务验证器和每次装配/调用报告。

**当前使用 `finance-example-wealth-facts-kit` 受控测试宿主。** 原生 ReActAgent/工具/Provider 客户端
确实运行，但身份、授权、HTTP 数据及模型选择为受控测试源。生成目录不含 Harness Java 公共实现，
也没有复制手机银行 Fork；金融规则通过同一份 JAR 调用。
这一结果证明独立装配和调用路径，不证明真实模型专业表现、生产宿主授权或完整产品已生成。

运行时使用分离的 SDK/依赖 JAR；Assembler 核验实际 Runtime 版本，Builder 记录全部依赖摘要并对照
本次构建的六个 Harness JAR。模板显式导入 Jackson BOM，避免依赖原手机银行父 POM 才能启动。
`--no-verify` 只生成，状态为 NOT_RUN；任何构建或验证失败保留失败报告。

## 首批验证矩阵

| 案例 | 必须看到的结果 |
| --- | --- |
| 正常查询 | 已知资产金额、币种、范围、测评记录、来源与时间；适当性 NOT_EVALUATED |
| 同 CUSTOMER 换受控 Snapshot | 只改 binding，资产/风险业务字段与共享描述一致 |
| 无权限 / 授权过期 / 错源客户 / 模型传客户选择器 | 原生工具拒绝，业务读取 0 次、无客户事实 |
| 测评缺失 / 语义依据缺失 | 接口可完成，业务描述 PARTIAL，不默认评级、资产范围或适当性 |
| 明确零资产 | 输出真实零，不能解释成缺失 |
| 坏响应 | 不返回虚构事实；允许记录已发生的读取尝试，但没有写入 |

金额与币种由源合同和语义依据决定。手机银行样例已在响应里给出的 CNY 不能因映射依据缺失而被
删除；未证实的是资产范围/风险体系时，应精确标明这些缺口。

## 接入入口

取得两个固定 Git 提交后，在 Builder 的 `community/finance/builder/finance-solution-builder` 执行：

```bash
python3 -m venv .venv
.venv/bin/pip install -e ../base -e . -r <HARNESS_ROOT>/engineering/requirements-conformance.txt pytest
.venv/bin/finance-solution-builder build examples/wealth-facts-readonly/solution-build-input.v1.json \
  --harness-repo <HARNESS_ROOT> --output build/clean-readonly-001
```

Builder 需要 Python 3.11.4 至 3.13；Harness 的独立 CLI 仍支持 Python 3.10。
依赖安装和 Maven 首次下载需网络；本业务运行不需要模型 Key、银行账号或 EA Token。
使用专用 Maven 环境，避免其他版本并行覆盖 SNAPSHOT。
新增输出目录，不覆盖旧结果；报告、Lock 与受控案例全部在 `build/clean-readonly-001`。

生成后的 Candidate 可复制到另一目录，按内置 README 使用 JDK 和 Python 运行；运行不需要
Builder/Harness 源码目录或手机银行。AI 在同机复制复跑只能证明路径可迁移，不能称为同事独立接入。

## 下一步按业务能力验收

| 工作 | 进入条件 | 当前状态 |
| --- | --- | --- |
| Builder CLI 生成干净 Runtime 只读参考 Agent | 固定模板与共享矩阵 | 本批受控实现及验证 |
| Builder 维护者接纳模板，WebUI 选择/生成 | 局部补丁评审，生成同一配置及同一门禁 | 待协作验收 |
| 手机银行/标准宿主真实会话及只读 | 权威 Session、客户绑定、可撤销 Grant、明确数据口径 | 待接线；不复制公共规则 |
| 完整模拟银行只读 | 隔离模拟器、固定数据和调用记录 | 待联调；不是本批 loopback Fixture |
| 账户余额等更多 QUERY | 明确参数/输出语义与授权，配置能力选择 | 逐项开放；当前仅 wealth.facts.describe |
| 模拟转账反诈与恢复 | 可信 UI 确认、冻结参数、原任务查证、银行风控结果 | 后续；不得由模型确认文本提交 |
| 真实同条件 Provider 替换 | 同能力/主体/用途/等价授权和口径 | 待真实接入；跨角色演示不替代 |
| 非原作者接入 | 同事在干净环境亲自操作，记录耗时/求助/修改 | 待组织验收 |

不新增第二 Runtime、完整金融本体、权限平台或通用工作流。技术架构组维护 Harness/Integrator；
灯塔 Owner 确认业务与权威授权；Builder Owner 维护生成器；场景工具组做独立接入及复制。
验收以每条实际调用路径为准，不按模板数量、生成代码行数或推算提效比例宣布产品成立。
