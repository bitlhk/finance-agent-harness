# Builder Runtime L1 架构

## 1. 定位

公共 Runtime 说明所有金融 Builder 在装配、Session、Workspace 和待测产物运行上的共同规则。具体 Builder
只补充自己的 Agent 清单、目录和业务差异。

Builder 直接复用 Python openJiuwen agent-runtime/agent-core 的 AgentApp、A2A、Session、Checkpoint 和部署
能力。Base 不修改这些依赖，只提供 Builder 额外需要的公共契约与本地工作区安全支撑。

## 2. 输入输出

输入包括部署方提供的 YAML 配置、可信 Builder Session、Workspace Context、Agent 业务实例和已授权的待测
产物引用。输出包括装配约定、健康状态、工作区相对引用，以及范围受限的待测实例句柄与网络入口。

## 3. 共同规则

- WebUI 后端创建 Builder Session 和单机会话工作区；Runtime 不提供用户文件浏览接口；
- 各 Agent 维护独立 Session，可使用同一 Builder Session ID 关联，但不共享 Checkpoint；
- Agent 服务进程不随 Session 关闭；关闭会话只结束活动任务和会话运行资源；
- Agent 以工作区相对路径、HTTP/HTTPS URL 或 A2A File Part 交换资料；
- 本地路径必须由受信根解析，拒绝越界、越界符号链接、目录重叠和已有复制目标；
- 对外消息、普通日志和报告不包含宿主绝对路径、凭据或完整敏感原文；
- 多机 Workspace Service、远程文件数据面和通用资源调度另立需求。

## 4. 待测产物

Orchestrator 请求范围受限的运行控制按一次 Evaluation Run 拉起一个待测 Agent；一个 Run 的多个测试集可以
复用实例，不跨 Run 复用。健康检查通过后才把入口交给 Evaluation，完成、取消或失败后执行幂等停止和最小
清理。Manifest/hash 用于固定实际运行身份和标记用户修改漂移，不是产物准入门槛；路径授权仍是硬边界。

完整候选进程管理由相应 Runtime Feature 交付，不属于 Base 文件安全函数的职责。

## 5. 依赖与接口

- [Builder Agent 行为契约](../contracts/agent-behavior.md)
- [Builder Session 契约](../contracts/builder-session.md)
- [Base L2](../L2-architecture.md)

精确 HTTP DTO、A2A 字段、配置 Schema、进程句柄和存储实现进入具体接口契约或 L2，不在 L1 固定。
