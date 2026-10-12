# 智能审核实时 SSE 透传设计

## 目标

让电脑端个人贷款审核在不泄露内部推理、原始工具事实或审批结论的前提下，实时呈现主对话文本和 Subagent 编排状态。

## 当前问题

候选 Agent 的 `AgUiTransport` 会在受控审核过程中发出 `finance.subagents` 事件，但工作台 `Experience.stream` 调用同步 `/chat` 接口，等调用结束后才将自己合成的事件和文本块一次性写给浏览器。因此浏览器只能看到“开始”和“完成”，不是实际事件流。

## 选定架构

工作台的 `Experience.stream` 改为向当前候选实例的 `/sessions/{id}/ag-ui` 建立受控 SSE 代理。代理在连接前完成原有身份、实例、会话和授权检查；在流中只接受 AG-UI 白名单事件，并逐条刷新下游响应。

候选 Agent 的 AG-UI transport 是唯一生成 Subagent 生命周期的来源：`AUDIT_STARTED`、每项 `CAPABILITY_STARTED/COMPLETED` 和最终完成态。浏览器现有 `HttpAgent` 按事件渲染，因此中间回答 `TEXT_MESSAGE_CONTENT` 与右侧 `finance.subagents` 都会即时更新。

## 事件与安全边界

允许透传的事件为运行生命周期、步骤生命周期、`TEXT_MESSAGE_*`、`finance.subagents`、`finance.reviewTeam`、`finance.display`、`finance.evidence` 和错误事件。工作台仍拒绝未知事件、跨实例事件、非当前 run ID 的事件和断连后继续写入的事件。

不新增或展示 chain-of-thought。主对话只渲染模型最终对用户可见的增量文本；原始材料、OCR、受控回执和规则证据继续按既有折叠/授权模型展示。

## 验证

单元测试验证工作台逐条转发且不聚合事件。Playwright 使用真实候选 Agent 上传 `personal-loan-demo.json` 后捕获：编排器先运行、四位专员逐个变化、中间消息分段增长、最终进入人工复核交接。
