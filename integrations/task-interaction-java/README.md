# 受管原任务 Java 接线（候选）

openJiuwen Java 0.1.15 使用已安装方法和只读任务工具解释原任务现状。人工操作由 Host 通过现有 TaskCoordination、HostTaskState 和持久 Journal 发起；模型不获得操作、复核或确认工具。

固定扩展 `original-task-review-managed.v1` 的 `openWithTaskJournal(Context, TaskCoordination.Journal)` 由支持新 Profile 的 Host 显式调用；通用 `open(Context)` 拒绝缺失 Host Journal 的接入。不改变 Core/Execution SPI，不复制其日志或确认实现。

Host 保管当前授权、一次性确认与资料绑定。原服务保管原业务对象和操作回执。结果不明后只读核查同一任务；复核通过与执行确认分开。本候选只支持受管单宿主边界，不代表外部并发、原完整邮件/PDF流程或生产资格。
