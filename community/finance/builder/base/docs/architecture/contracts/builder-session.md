# Builder Session 契约

## 1. 标识与所有权

| 对象 | 所有者 | 语义 |
|---|---|---|
| Builder Session | WebUI 后端 | 一次用户可恢复的 Builder 多轮工作会话及其本地工作区 |
| Agent Session | 每个 Agent | 该 Agent 在 Builder Session 下独立保存的上下文和执行状态 |
| A2A Task | 承接 Agent / A2A 协议 | 一次可运行、完成、失败或取消的 Agent 间任务 |
| Evaluation Run | Evaluation 领域 | 在固定测试输入上完成的一次候选评测 |

四类标识不能互相替代；A2A `taskId` 不是 Builder Session、Agent Session 或工作区标识。

## 2. 关联规则

逻辑会话键是 `(agent_id, builder_session_id)`。各 Agent 使用隔离的 Checkpointer，因此可以使用相同的
`builder_session_id` 作为自己的会话标识而不共享状态。如果框架或部署需要不同标识，调用适配层可以维护
映射；Runtime 不创建、保存或解析该映射。

WebUI 后端创建全局唯一、不可由浏览器任意指定的 Builder Session，并经可信运行元数据传给 Orchestrator；
Orchestrator 调用专业 Agent 时继续传播关联信息。A2A `contextId` 只需稳定关联 Builder Session，精确字段映射
由接口契约决定。

## 3. 生命周期

- 各 Agent 可以独立恢复、重置自己的上下文；一个 Agent 重置不影响其它 Agent、工作区或用户历史；
- 关闭 Builder Session 结束活动连接和任务，但不停止 Agent 服务进程、不删除持久化 Checkpoint 或工作区；
- 删除用户可见会话、重置 Agent Session 和删除工作区是三个独立动作，不自动级联；
- 当前公共契约不提供跨 Builder Session 的隐式记忆。

## 4. Workspace 与 Evaluation Run

一个 Builder Session 对应一个受信本地工作区；Agent 通过 Workspace Context 使用相对路径。一个 Builder
Session 可以包含多个 Evaluation Run，每个 Run 的待测实例和运行句柄独立于常驻 Agent Session。

### 受信工作区的传输通道（自定义字段映射）

受信调用方随每次调用提供会话工作区的宿主绝对路径，Runtime 与 Agent 均不保存
`session_id → workspace_root` 映射：

| 入口 | 通道 | 说明 |
|---|---|---|
| AgentApp `POST /query` | `QueryRequest.space_id` | 框架模型唯一的工作区语义字段；缺省值 `"default"` 视为未提供 |
| A2A `message/send` | `message.metadata.workspace_root` | 经 openjiuwen 0.1.16 `a2a_transformer` 合并进业务载荷顶层；`contextId` 仅作 Builder Session 绑定，不承载路径 |

- 缺失、非绝对路径、不存在或不是目录的工作区一律显式拒绝；Agent 不创建用户工作区；
- 来源标识由接收方按入口固定标记（`/query` → `webui-backend`，A2A → `orchestrator`），不是调用方自报；
  浏览器或普通 Agent 消息携带的工作区覆盖不是可信输入，一律拒绝；
- 宿主绝对路径禁令只管**公共业务交换**（业务消息、探针证据、日志、报告）；上表两个受信开通字段
  （`space_id`、`metadata.workspace_root`）是**声明例外**，仅传输用，由调用适配层从已校验的可信
  Context 生成，接收方进程内消费后绝不回显到业务消息、日志或报告；对外业务交换一律使用 Workspace
  Context 的相对 locator `.`。

### 信任边界（受信通道的鉴权）

入口标记本身只是字符串，不能证明调用方身份；受信通道以部署级控制面保护：

- Agent 端点（AgentApp 与 A2A 服务端）强制绑定 `127.0.0.1`（loopback），非 loopback 配置拒绝启动；
- Runtime 拉起 Agent 进程时经环境变量注入部署级共享 provisioning token；`/query`、A2A JSON-RPC 与
  `/reset_conversation` 等受信入口必须携带 `Authorization: Bearer <token>`，缺失或错误一律拒绝
  （401）；token 未配置时受信通道 fail-closed——视为未就绪（`/health` 非 200），携带受信字段的请求
  一律拒绝；
- `/health`（聚合就绪状态）与 A2A AgentCard（`/.well-known/agent-card.json`，协议发现）保持无鉴权；
- token 只经请求头传输，绝不进入业务消息、探针证据、日志、报告或错误消息。

## 5. 关联文档

- [Builder Agent 行为契约](agent-behavior.md)
- [Builder Runtime L1](../runtime/L1-architecture.md)
- [Base L2](../L2-architecture.md)
