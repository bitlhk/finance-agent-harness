# Codex Construction Agent 接入指南

本指南用于把运行环境已有的 Codex CLI 接入 Finance Solution Builder。Builder 不下载、安装或分发
Codex，也不读取或保存账户凭据。

## 1. 适用边界

Codex Adapter 与 OpenCode Adapter 实现同一份 `ConstructionAgentBackend` 协议。Optimization
Controller 仍提交 Finding、父 Lock、工作区、允许写入路径、验证命令和预算；适配器负责把这些内容
映射为 Codex thread/turn，并在完成后以实际文件变化和 Builder 验证结果形成公共终态。

当前版本：

- 要求 `codex-cli 0.153.4`，通过本地 `app-server --stdio` 通信；
- 每个任务创建独立进程和 ephemeral thread；
- 支持当前请求的一次性审批、取消、超时和终态幂等读取；
- 不支持活动任务恢复；
- 不把完整 Prompt、推理、账户信息、宿主绝对路径或 Codex 私有 DTO 写入公共记录。

## 2. 启动前检查

运行环境先完成 Codex 登录和模型配置，再执行：

```powershell
codex --version
codex app-server generate-json-schema --out .tmp/codex-schema-check
```

第一条命令必须输出 `codex-cli 0.153.4`。第二条命令必须成功生成 v2 Schema。Adapter 每次启动任务
仍会独立执行相同的版本与 Schema 门禁，并在工作区发生修改前完成 initialize、账户和模型检查。

## 3. Python 装配

```python
from pathlib import Path

from finance_solution_builder.construction_agent_backend.codex import (
    CodexAdapterConfig,
    CodexConstructionAgentBackend,
)

backend = CodexConstructionAgentBackend(
    CodexAdapterConfig(
        workspace_root=Path("D:/builder-workspaces"),
        executable="codex",
        model="gpt-5.6-terra",
        input_cost_microunits_per_million_tokens=0,
        output_cost_microunits_per_million_tokens=0,
    )
)
```

`workspace_root` 是 Builder 管理的任务工作区父目录。`executable` 可以是 PATH 中的命令，也可以是
部署方固定的绝对路径。`model` 省略时使用 Codex 当前默认模型。

两项 token 费率单位均为“每一百万 token 的微单位成本”，必须成对配置。上例的零费率只适用于部署方
明确把当前订阅额度按零增量成本核算的环境；按 API 或内部配额计费时应填写对应核算值。缺少费率或
App Server 用量事件时，任务以 `construction.budget.usage_unknown` 失败，不能绕过成本预算。

## 4. 执行与审批

Adapter 对每个任务执行以下顺序：

1. 校验工作区和允许写入路径，记录修改前摘要；
2. 核验 CLI、Schema、账户与模型；
3. 启动 app-server，建立 thread 和 turn；
4. 把匹配当前 thread/turn 的通知投影为公共事件；
5. 把命令、网络访问或文件范围扩张转换成公共 `ApprovalRequest`；
6. turn 完成后重新扫描工作区，校验实际差异、链接边界和预算；
7. Builder 逐条执行任务中冻结的验证命令；
8. 全部通过后返回唯一 `ConstructionResult`，随后停止自有进程。

批准命令或文件变更时，Adapter 只返回 Codex 的单次 `accept`；权限型请求最多使用 `scope=turn`。
它不会使用 `acceptForSession`，也不会写入长期 exec 或网络策略。

## 5. 测试

确定性协议、适配器、一致性和 Optimization 回归：

```powershell
$env:PYTHONPATH = "..\base\src;src"
python -m pytest -p no:cacheprovider tests/construction_agent_backend tests/optimization/test_revision.py
```

真实 smoke 只操作 pytest 创建的脱敏临时 Candidate，不启动浏览器。运行前显式提供：

```powershell
$env:FINANCE_BUILDER_RUN_CODEX_LIVE = "1"
$env:FINANCE_BUILDER_CODEX_EXECUTABLE = "C:\path\to\codex.exe"
$env:FINANCE_BUILDER_CODEX_INPUT_RATE = "0"
$env:FINANCE_BUILDER_CODEX_OUTPUT_RATE = "0"
python -m pytest -p no:cacheprovider tests/construction_agent_backend/test_codex_live.py
```

如需固定非默认模型，再设置 `FINANCE_BUILDER_CODEX_MODEL`。真实 smoke 必须由运行环境负责人确认账户、
模型、数据和费率口径；没有这些条件时应保持 `NOT_RUN`，不能用协议替身冒充真实模型结果。

## 6. 失败处理与升级

- CLI、Schema、账户或模型不兼容：修复部署环境后重新创建任务，Builder 不回退到另一个构造 Agent。
- 连接中断、审批投递失败、越界修改或用量未知：任务失败并停止自有进程；检查公共错误码和运行环境日志。
- 取消或预算耗尽：Adapter 调用 `turn/interrupt`，停止 app-server，并保留唯一取消或超时终态。
- 活动任务无法恢复：当前任务失败关闭；以新的任务 ID、父 Lock 和工作区重新执行。

升级 Codex 时，先在独立分支更新精确版本和最小 Schema 标记，再依次运行协议测试、公共 conformance、
Optimization 回归、隐私检查和获批的真实 smoke。全部通过后才能调整部署配置。
