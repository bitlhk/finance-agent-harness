# Builder Agent 行为契约

## 1. 适用范围

本契约适用于由 Builder Orchestrator 编排的专业 Agent。具体 Builder 可以声明 Agent 清单和场景差异，但不得
重新定义消息、Session、取消、恢复和公开错误的共同语义。

## 2. 消息与资源

- Agent 接收自然语言目标、可信运行元数据以及工作区相对路径、HTTP/HTTPS URL 或 A2A File Part；
- Agent 间调用使用 openJiuwen A2A Message/Part 和原生 Task 生命周期，不增加领域 RPC 信封；
- 资料不足时返回结构化反问，由 Orchestrator 转达用户；
- 输出区分消息、进度、反问、状态、文件/数据和错误；普通响应不包含内部 work_id、服务端绝对路径或凭据。

## 3. Session、恢复与取消

每个 Agent 使用隔离、持久化的 Checkpointer，并以 Builder Session 稳定关联自己的 Agent Session。恢复失败
必须明确返回错误，不能伪装成空会话恢复成功。Session State 只保存小型结构化状态和资源引用，不保存完整
文件、代码包或大体量日志。

AgentApp 客户端断开和 A2A Task 取消必须传播到实际执行。取消不自动删除工作区、Checkpoint 或已完成部分
结果；Orchestrator 取消上游任务时继续取消仍运行的下游 Task。

## 4. 流式与可观测性

分钟级任务必须提供真实阶段和数量进度。优先复用 agent-core 的事件与 Task 状态，不建立平行事件协议。
专业 Agent 的事件由 Orchestrator 汇总给 WebUI；WebUI 不直接理解领域内部状态。

公开事件、普通日志和报告不得包含完整 Prompt、模型内部推理、凭据、客户身份、完整敏感原文或宿主绝对路径。

## 5. 对外入口

Agent 复用 openJiuwen AgentApp 的会话式入口和 A2A 原生能力。本契约不新增固定 URL、DTO、端口、数据库表或
事件 JSON；精确映射进入具体接口契约和 L2。

## 6. 关联文档

- [Builder Session 契约](builder-session.md)
- [Builder Runtime L1](../runtime/L1-architecture.md)
- [Base L2](../L2-architecture.md)
