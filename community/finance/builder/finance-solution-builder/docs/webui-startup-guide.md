# Agent Builder WebUI 启动指南

本文说明如何在本机启动 Finance Solution Builder 的实际构建工作台和构建引导页面。两者采用
一致的视觉语言，但承担不同职责，启动方式和依赖也不同。

启动后按 [业务模块、能力与专业方法操作说明](private-method-user-guide.md) 完成方法导入、保存、
受控检查、真实模型预览、发布及选择版本构建。该说明保留现有组件架构，区分计划装配与实际安装证据。

## 先分清：两套服务、三类页面

| 入口 | 用来做什么 | 是否执行构建 | 默认地址 | 代码位置 |
|---|---|---:|---|---|
| 实际构建工作台 | 选择能力与模型，创建构建任务，查看真实阶段，启动 Candidate 并进行对话 | 是 | `http://127.0.0.1:18118/` | `examples/local-workbench/` |
| 构建过程引导页 | 按六步 Storyline 解释 Solution、资产锁定、装配、启动、Evaluation 和交付裁决 | 否 | `http://127.0.0.1:18117/` | `examples/build-visualizer/workbench.html` |
| 概念说明页 | 介绍业务需求、Solution、Builder 与 Finance Harness 的关系 | 否 | `http://127.0.0.1:18117/guide.html` | `examples/build-visualizer/index.html` |

实际构建工作台面向 Agent 构造者。页面操作会调用 Builder 后端，写入
`build/workbench/<build-id>/`，并在条件齐备时启动真实 Candidate。构建过程引导页和概念说明页
面向讲解、复盘与评审，只读取已有的 Lock、Report 和评测证据，不会在浏览器里重新执行构建。

两套服务的数据目录也不同：实际构建工作台管理 `build/workbench/` 下的构建任务；引导页默认读取
两个固定示例目录 `build/wealth-manager-previsit-v1.1/` 和
`build/wealth-customer-diagnosis-v1.1/`。实际工作台新建的任务不会自动替换引导页的参考数据。

选择入口时可以按下面的规则判断：

- 要亲自选择组件、执行构建、启动 Agent 或对话：启动**实际构建工作台**；
- 要向业务人员逐步讲解一次 Agent 如何形成：启动**构建过程引导页**；
- 要先解释 Solution 和 Harness 分别解决什么问题：打开**概念说明页**。

## 实际构建工作台：完整启动

实际构建工作台需要 Builder、Finance Harness 和银行模拟服务共同就绪。推荐使用仓库提供的
`start_local_stack.py` 同时管理银行模拟服务与工作台后端，避免只打开网页、内部服务却没有启动。

### 安装 Builder 和 Harness 校验依赖

从 `community/finance/builder/` 执行：

```powershell
uv sync --package finance-solution-builder
uv pip install `
  --python .\.venv\Scripts\python.exe `
  -r D:\repo\finance-agent-harness\engineering\requirements-conformance.txt
```

将 Harness 路径替换成实际位置。工作台使用仓库中已打包的前端文件，普通启动不要求重新运行
Node.js 构建。
当前实际工作台要求 Harness 版本同时包含本地只读 Profile、账户余额 Pack、Host Session 支持和校验依赖清单。先检查：

```powershell
Test-Path D:\repo\finance-agent-harness\engineering\requirements-conformance.txt
```

返回 `False` 表示当前 Harness 分支不能启动这套实际工作台。PR !122 验证使用的 Harness 基线为
`b55d072`；应使用包含上述能力的已批准分支，或从该版本创建独立 worktree，再把后续命令中的
Harness 路径改为该 worktree。不要为了启动工作台直接切换或覆盖一份带有未提交改动的 Harness
工作区。

### 准备银行模拟服务 JAR

取得或构建 `non-ai-banking` 模拟服务 JAR，并计算它的真实 SHA-256：

```powershell
$simulatorJar = 'D:\path\to\non-ai-banking-simulator.jar'
$simulatorSha = (Get-FileHash -Algorithm SHA256 $simulatorJar).Hash.ToLowerInvariant()
```

启动器会先核对 JAR 摘要，再启动模拟服务并等待其会话接口就绪；摘要不一致、端口占用或服务未
就绪时，工作台不会继续启动。

### 配置模型（需要启动 Candidate 并对话时）

模型配置只放在启动工作台的终端环境中，不填写到浏览器或提交到仓库：

```powershell
$env:FH_MODEL_URL = 'https://your-openai-compatible-endpoint/v1'
$env:FH_MODEL_NAME = 'your-model-name'
$env:FH_MODEL_KEY = '<LOCAL_SECRET>'
```

没有配置模型时，工作台仍可展示组件并执行允许的构建步骤，但 Candidate 启动、对话和对应验收会
保持阻断，不会自动改用其他模型。

### 首次启动完整本地栈

仍在 `community/finance/builder/` 下执行：

```powershell
uv run --package finance-solution-builder python `
  .\finance-solution-builder\engineering\start_local_stack.py `
  --harness-repo D:\repo\finance-agent-harness `
  --simulator-jar $simulatorJar `
  --simulator-sha256 $simulatorSha `
  --provision-demo-policy
```

`--provision-demo-policy` 只用于首次创建一天有效的合成测试授权。后续启动时应去掉该参数；已有授权
不会被静默覆盖。启动器默认使用：

- 实际构建工作台：`http://127.0.0.1:18118/`；
- 银行模拟服务：`http://127.0.0.1:18119/`；
- 本地运行记录：`build/local-environment/`。

### 检查内部服务是否真的就绪

另开一个 PowerShell 窗口执行：

```powershell
Invoke-RestMethod 'http://127.0.0.1:18118/api/bootstrap' |
  Select-Object runtime, modelConfigured, bankConfigured, deploymentMode
```

本地完整演示至少应看到 `bankConfigured=True`。需要启动 Candidate 并对话时，还必须看到
`modelConfigured=True`。然后再打开 `http://127.0.0.1:18118/`，选择组件并创建一项新的构建任务。

在启动器所在窗口按 `Ctrl+C` 会同时停止它创建的工作台和银行模拟服务，不会结束其他进程。

## 1. 引导页面：启动前准备

### 1.1 基础环境

- Python 3.11 或更高版本；
- uv；
- Maven，并确保 `mvn.cmd -version` 可以执行；
- 使用宽屏浏览器访问。当前版本优先保证宽屏体验。

仅查看已有构建结果时，WebUI 使用 Python 标准库，不需要安装 Node.js 或单独构建前端。

### 1.2 准备两个本地仓库

这套工作台只来自一个 Agent Store Builder 仓库，但首次生成展示数据时还要读取一个独立的
Finance Harness 仓库。它们不是两份 Harness：

| 本地仓库 | 作用 | 本文示例路径 |
|---|---|---|
| `Euphoria_Yan/agent-store-builder-dev-wo-fork` | 提供 Builder、两份 Solution 示例和 WebUI | `D:\repo\agent-store-builder-dev-wo-fork` |
| `bitlhk/finance-agent-harness` | 提供金融合同、Pack、Provider SPI、验证规则和 Java 构件 | `D:\repo\finance-agent-harness` |

先在 Builder 仓库切到最新 `finance`：

```powershell
git switch finance
git pull origin finance
```

确认当前源码包含启动预检：

```powershell
python .\community\finance\builder\finance-solution-builder\examples\build-visualizer\server.py --help |
  Select-String -- '--check-data'
```

如果没有输出 `--check-data`，当前源码仍早于本指南对应版本，请先更新分支。

### 1.3 确认固定外部资产可访问

两份示例 Solution 会按固定提交解析 OpenJiuwen Java 模板和 Evaluation Pack：

- `wuxiaojingjing/mobile-banking-3.0` 中的 OpenJiuwen Java Integration；
- `bitlhk/finance-skill-eval` 中的财富 Evaluation Pack。

这些是构建输入，不会作为数据文件提交到 Builder 仓库。若当前账号访问返回 404，应先取得对应
仓库权限；Builder 会在资产解析阶段失败关闭，不会用其他模板静默替代。

### 1.4 确认展示数据已经存在

进入 Builder 目录：

```powershell
cd community\finance\builder\finance-solution-builder
```

工作台默认读取以下两套构建结果：

```text
build/
├── wealth-manager-previsit-v1.1/
│   ├── solution-lock.json
│   ├── build-report.json
│   ├── candidate/
│   └── evaluation/report.json        # 有评测记录时存在
└── wealth-customer-diagnosis-v1.1/
    ├── solution-lock.json
    ├── build-report.json
    ├── candidate/
    └── evaluation/report.json        # 有评测记录时存在
```

当前工作台左侧会同时提供两套场景，因此启动前必须保证两套场景的 Solution、
`solution-lock.json` 和 `build-report.json` 都可读取。服务会在监听端口前完成强制预检；任一场景
缺少文件或数据结构无法解析时，启动命令会直接失败并列出问题，不会再打开一个只能返回 503 的
网页空壳。

可以在 PowerShell 中检查：

```powershell
Test-Path .\build\wealth-manager-previsit-v1.1\solution-lock.json
Test-Path .\build\wealth-manager-previsit-v1.1\build-report.json
Test-Path .\build\wealth-customer-diagnosis-v1.1\solution-lock.json
Test-Path .\build\wealth-customer-diagnosis-v1.1\build-report.json
```

返回 `True` 表示对应文件已经准备好。四个文件都不存在并不代表仓库不完整：
`build/` 是本地生成且被 Git 忽略的构建结果，请按第 4 节生成，不能期待 `git pull` 下载得到。

## 2. 引导页面：最短启动流程

### 2.1 先独立检查数据

在 Finance Solution Builder 目录执行：

```powershell
python .\examples\build-visualizer\server.py --check-data
```

看到以下提示才能继续：

```text
Finance Solution Builder WebUI data preflight passed.
```

若检查失败，终端会逐项列出缺少或无法解析的场景文件。先按第 4 节生成构建结果，或按第 3 节
指定正确的 `--data-root`。

### 2.2 启动服务

预检通过后执行：

```powershell
python .\examples\build-visualizer\server.py --port 18117
```

启动命令本身也会再次执行同一项预检。即使跳过 2.1，只要数据不完整，HTTP 服务也不会监听
18117 端口。

看到以下提示即表示服务已启动：

```text
Finance Solution Builder WebUI: http://127.0.0.1:18117
```

浏览器打开 Agent 构造工作台：

```text
http://127.0.0.1:18117/?demo=wealth-manager-previsit-v1.1
```

还可以直接打开另一套场景：

```text
http://127.0.0.1:18117/?demo=wealth-customer-diagnosis-v1.1
```

Solution、Harness 与 Builder 的概念讲解页位于：

```text
http://127.0.0.1:18117/guide.html
```

## 3. 引导页面：构建数据位于其他目录时

如果网页代码与构建结果不在同一个工作目录，可用 `--data-root` 指向实际 Builder 数据根目录：

```powershell
python .\examples\build-visualizer\server.py `
  --check-data `
  --data-root D:\path\to\finance-solution-builder
```

检查通过后再启动：

```powershell
python .\examples\build-visualizer\server.py `
  --port 18117 `
  --data-root D:\path\to\finance-solution-builder
```

该目录应同时包含：

```text
examples/wealth-manager-previsit/solution-build-input.v1.json
examples/wealth-customer-diagnosis/solution-build-input.v1.json
build/wealth-manager-previsit-v1.1/
build/wealth-customer-diagnosis-v1.1/
```

`--data-root` 只改变读取数据的位置，不会复制或修改其中的文件。

## 4. 引导页面首次运行：先生成两套构建结果

如果本地还没有上述 `build/` 数据，需要先安装 Builder 并分别构建两个示例。

从 `community/finance/builder/` 执行：

```powershell
uv sync --package finance-solution-builder
```

仍在 `community/finance/builder/` 下执行：

```powershell
uv run --package finance-solution-builder finance-solution-builder build `
  .\finance-solution-builder\examples\wealth-manager-previsit\solution-build-input.v1.json `
  --harness-repo D:\path\to\finance-agent-harness `
  --output .\finance-solution-builder\build\wealth-manager-previsit-v1.1 `
  --maven mvn.cmd
```

```powershell
uv run --package finance-solution-builder finance-solution-builder build `
  .\finance-solution-builder\examples\wealth-customer-diagnosis\solution-build-input.v1.json `
  --harness-repo D:\path\to\finance-agent-harness `
  --output .\finance-solution-builder\build\wealth-customer-diagnosis-v1.1 `
  --maven mvn.cmd
```

将 `D:\path\to\finance-agent-harness` 替换为第 1.2 节准备的第二个仓库绝对路径，例如
`D:\repo\finance-agent-harness`。如果 Maven 不在 `PATH` 中，将 `mvn.cmd` 替换为
Maven 可执行文件的绝对路径。

构建会执行资产解析、版本锁定、Java Candidate 装配、Harness 检查、Maven 编译、Candidate
启动与健康检查，并生成网页需要的 Lock 和 Report。真实银行测试环境中的在线业务 Evaluation
需要另行配置；历史证据回放只用于验证评测流程可运行。

## 5. 引导页面怎么使用

1. 在左侧选择“财富经理访前助手”或“客户财富体检助手”。
2. 按顶部 Storyline 从左到右查看六个阶段。
3. 点击“选择并锁定组件”中的资产，查看共享资产、场景资产、版本和摘要。
4. 在“装配 Candidate”中点击组件或连线，查看组件怎样被装入工程以及接口传递的数据。
5. 在“构建并启动”中查看 Orchestrator、Developer 和 Evaluator 的循环，以及每轮调整对象。
6. 在“执行 Evaluation”中区分工程验证、冻结证据回放和真实业务验收。
7. 在“形成交付”中查看 Candidate、Lock、Report 和尚未关闭的交付门禁。

网页展示的“工程门禁通过”只说明 Candidate 可以装配、编译、启动并完成协议检查；只有真实业务
Evaluation 达到既定精度、时延和业务规则后，才能作出“合格 Agent”交付裁决。

## 6. 快速检查引导服务是否正常

另开一个 PowerShell 窗口执行：

```powershell
Invoke-RestMethod `
  'http://127.0.0.1:18117/api/demo?id=wealth-manager-previsit-v1.1'
```

- 返回包含 `solution`、`lock`、`report`、`stages` 的 JSON：服务与数据均正常；
- 返回 `503`：数据可能在服务启动后被移动、删除或改坏；正常启动流程会在监听端口前拦截初始问题；
- 无法连接：服务未启动、已经退出或端口被占用。

## 7. 引导页面常见问题

### 页面打开后内容区空白

新版本不会在初始数据不完整时启动 HTTP 服务。如果页面仍出现 `503`，说明数据在服务启动后被
移动、删除或改坏。停止服务，重新运行：

```powershell
python .\examples\build-visualizer\server.py --check-data
```

然后检查终端列出的具体文件，并确认构建目录严格使用：

```text
wealth-manager-previsit-v1.1
wealth-customer-diagnosis-v1.1
```

如果构建数据在另一份工作树中，使用 `--data-root` 指向那份数据，而不是复制构建产物进代码仓。

### 18117 端口已被占用

改用其他本机端口：

```powershell
python .\examples\build-visualizer\server.py --port 18118
```

随后访问 `http://127.0.0.1:18118/`。

也可以先确认占用端口的进程：

```powershell
Get-NetTCPConnection -LocalPort 18117 -ErrorAction SilentlyContinue
```

确认进程确实是旧的 Builder WebUI 后，再停止该进程。

### 修改网页后浏览器仍显示旧内容

服务已设置 `Cache-Control: no-store`。通常刷新页面即可；仍异常时使用 `Ctrl+F5` 强制刷新，或停止
服务后重新启动。

### 浏览器无法从其他电脑访问

当前服务固定监听 `127.0.0.1`，只允许本机访问。这是演示环境的安全边界，不应直接作为共享或
生产 Web 服务暴露。需要多人访问时，应通过正式 Web 后端、身份认证和受控部署提供服务。

## 8. 停止服务

回到运行服务的 PowerShell 窗口，按：

```text
Ctrl+C
```

看到命令提示符重新出现后，服务已停止。WebUI 是只读进程，停止它不会删除 Candidate、Lock、
Report 或 Evaluation 证据。

## 9. 不应提交的本地内容

以下内容用于本地构建或演示，不进入代码提交：

- `build/`、`output/`、`.tmp/`；
- 构建日志和运行日志；
- 截图、录屏、配音文件；
- API Key、Token、私钥和银行测试环境配置；
- 未脱敏的客户资料或真实业务输入。
