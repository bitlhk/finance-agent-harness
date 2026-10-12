# OpenCode 构造 Agent 接入指南

本指南说明 Finance Solution Builder 如何通过 Construction Agent Backend v1 委托 OpenCode 修订一个
Candidate 工程。OpenCode 只承担受控编码执行；任务目标、父 Lock、工作区、可写路径、验证命令、预算、
审批和最终是否接受，仍由 Builder 决定。

## 固定版本与许可证

- 当前仅支持 OpenCode `1.18.29`。
- OpenCode 使用 MIT License；Builder 调用用户环境中已有的可执行文件，不复制、打包或分发 OpenCode。
- 安装和模型配置由使用者依据 OpenCode 官方文档完成。Builder 不自动下载、安装或升级 OpenCode。
- 启动任务前会执行 `opencode --version`；输出不是 `1.18.29` 时直接失败。服务启动后还会再次核对
  `/global/health` 返回的版本。

参考：[OpenCode Server API](https://dev.opencode.ai/docs/server/)、
[Configuration](https://dev.opencode.ai/docs/config)、
[Permissions](https://dev.opencode.ai/docs/permissions/)。

## 运行前置检查

1. `opencode --version` 输出 `1.18.29`。
2. 模型及供应商凭据已经按 OpenCode 的方式配置在运行环境中；不要写入 Solution、Lock、Report 或仓库。
3. Builder 已为任务创建一次性工作区，并生成合法的 `ConstructionTaskRequest`：
   - 工作区引用是 Builder 根目录下的相对路径；
   - `allowedWritePaths` 至少包含一个可写相对目录；
   - Finding 只使用已脱敏引用；
   - 验证命令使用固定 argv、相对 cwd 和超时；
   - 时间、轮次、工具调用和成本预算均已明确。
4. 操作系统允许 Builder 在 loopback 上启动本地子进程和端口。

## Builder 如何启动 OpenCode

适配器为每个构造任务启动一个独立的 headless server：

```text
opencode --pure serve --hostname 127.0.0.1 --port <随机空闲端口>
```

端口和 `OPENCODE_SERVER_PASSWORD` 由适配器随机生成，只保存在进程环境与内存中。服务仅监听
`127.0.0.1`，HTTP 请求使用 Basic Auth。适配器同时设置：

- `OPENCODE_DISABLE_AUTOUPDATE=true`；
- `OPENCODE_AUTO_SHARE=false`；
- `OPENCODE_CONFIG_CONTENT`：关闭自动共享、外部目录、Web fetch/search 和子 Agent；Shell 默认询问；
  编辑只允许任务声明的相对路径；敏感环境文件保持拒绝读取；未知工具默认要求审批。

每个任务还使用独立的临时 OpenCode 配置、缓存、数据和状态目录；任务结束并回收进程后清理，避免把
供应商 Session 当成 Builder 的跨进程恢复依据。

这些配置是第一层控制。OpenCode 完成后，Builder 会把 Session Diff 与任务开始前后的工作区内容摘要交叉
核对；当固定版本未返回 Session Diff 时，以实际工作区变化为准，仍逐项校验路径。两者同时存在但不一致
时失败关闭。随后 Builder 亲自执行任务中固定的验证命令。供应商权限即使被批准，也不能扩大 FEAT-003
的不可变写入边界。

## Python 装配示例

适配器是供应商私有实现，不从 `construction_agent_backend.__all__` 导出。装配代码显式选择它：

```python
from pathlib import Path

from finance_solution_builder.construction_agent_backend.opencode import (
    OpenCodeAdapterConfig,
    OpenCodeConstructionAgentBackend,
)

backend = OpenCodeConstructionAgentBackend(
    OpenCodeAdapterConfig(
        workspace_root=Path("builder-workspaces"),
        executable="opencode",
        expected_version="1.18.29",
        model="provider/model-id",
    )
)
```

随后由 Orchestrator 使用 FEAT-003 的 `start`、`events`、`approve`、`cancel` 和 `result`，无需理解
OpenCode Session、SSE Part 或 Permission 的私有结构。OpenCode Backend 在 0.2.0 声明
`supports_resume=false`；`resume` 仅能幂等返回已经结束的任务事实。

## 审批、取消与恢复

- OpenCode 的 Permission 事件会转换为 `approval-required`。批准只发送一次 `once`；从不发送 `always`。
- 等待审批期间保持同一条 SSE 事件流；批准不会另建竞争连接，事件流若在终态前异常结束则失败关闭。
- 拒绝会形成稳定失败终态，不继续相同动作。
- 取消或任一预算超限会调用 Session Abort，并回收受管进程树。
- 重复取消返回同一个终态结果；重复提交相同审批不产生第二次供应商副作用。
- 0.2.0 不恢复活动中的 OpenCode 任务。现有 `ResumeRequest` 没有携带调用时的父 Lock 和工作区摘要，
  因此适配器无法证明当前上下文仍与启动时一致；即使原进程和 Session 尚在，也会返回
  `construction.resume.unavailable` 并停止该任务，不会创建替代 Session。任务结束后调用 `resume` 只返回
  已有终态，不重新执行动作。活动任务应在同一控制流内继续使用 `events`、`approve`、`cancel` 和 `result`。

## 公开事件与证据

工作台只接收 FEAT-003 的 started、progress、file-change、tool-result、approval-required 和唯一终态。
公开对象可包含仓库相对路径、固定验证摘要、预算用量和证据引用；不包含：

- `OPENCODE_SERVER_PASSWORD`、模型密钥或 Authorization；
- PID、随机端口和宿主绝对路径；
- 完整 Prompt、SSE 原文、供应商私有 DTO 或模型内部推理；
- 未经授权的业务原文。

## 故障定位

| 公共错误 | 含义 | 处理 |
|---|---|---|
| `construction.backend.startup_failed` | 可执行文件、版本、健康或 Session 不可验证 | 检查安装版本和本地模型配置后重建任务 |
| `construction.backend.event_stream_failed` | 当前 Session 的事件流异常结束 | 查看受控本地日志；不要复制私有原文到 Report |
| `construction.path.outside_allowed_scope` | Diff 出现未授权路径 | 拒绝本轮结果，收窄任务或重新走人工范围决策 |
| construction.path.symlink_escape | 文件解析后越出允许写入根 | 拒绝本轮结果，清理重定向路径后重建任务 |
| construction.approval.delivery_failed | 审批结果未能安全送达 OpenCode | 本轮失败关闭，确认服务状态后新建任务 |
| `construction.validation.failed` | Builder 固定验证命令未通过 | 根据公开 Finding 重新修订，不降低验收门槛 |
| `construction.budget.exhausted` | 时间、轮次、工具调用或成本超限 | 调整任务粒度；预算变更由 Orchestrator/用户决定 |
| `construction.resume.unavailable` | 原进程、Session 或工作区事实无法证明 | 新建任务，不冒充恢复旧任务 |

## 测试与升级边界

仓库中的 `opencode_protocol_double.py` 只模拟固定 HTTP/SSE 形状，不访问外部网络或真实模型。它用于日常
回归，但不能代替固定版本 smoke。合入或升级前仍需在授权环境使用 OpenCode `1.18.29` 与脱敏临时工程
完成一次“启动、Session、修改、Diff、验证、停止”检查。

升级 OpenCode 时必须由单独 Feature 或修复重新核对版本、Server API、事件字段、Permission 语义和回归，
不能让适配器静默跟随最新版本。
