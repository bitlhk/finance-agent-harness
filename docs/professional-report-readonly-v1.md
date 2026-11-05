# 原方法与报告的只读接线

本轮提供两个有限 Profile：`wealth-kyc-report-readonly` 使用原财富 Skill 与 context/list/detail 三个原 MCP 工具；`market-risk-report-readonly` 使用登记为派生方法的完整原报告流程。后者保留原九个章节、公式、教学假设、原表图及导出，不接外部行情或生成代码。

## 执行与数据归属

`professional-report-readonly` Provider 经受管连接读取原服务；`ReportMethodAgent` 使用 openJiuwen Java 0.1.15 的 ReActAgent 和 InstalledPreparationMethod，没有新增 Agent 循环。当前客户和任务由 Host 授权，不接受模型自述授权。`HOST_INSTANCE` 占位只在运行时替换为当前 Host 的实际实例号，原私有绑定文件摘要保持不变。

Workshop 源服务保存各次输入快照、原返回、原文件、精确版本和进程状态。报告领域校验 `finance-professional-report.v1`、JSON Pointer 和摘要；报告聚合多次来源，每个 `sourceResultId` 保留自己的依据。Core/Execution 不接收报告业务字段。

原工具内容、模型说明与人工备注分别保存。模型说明必须引用本次有效依据；不自动成为银行专业结论。`ReportVersions` 追加不可变版本，备注保留操作者和时间；重新生成产生新报告并显式关联旧版本，不覆盖历史。原文件读取逐次重新授权并校验摘要，限 Markdown/CSV/JSON/PNG/XLSX；不存在原日期或用量时保留缺失，不记零。

本报告适配汇总每次模型响应的输入/输出 Token；其中任一次缺失，整次汇总保持未记录。Java SDK 0.1.15 对缺失用量也生成全零整数对象，没有字段存在标记，所以全零情况保守标为未记录；缓存用量在此路径同样缺少可靠的存在标记，保持未记录，不推算为零。

失败、超时、取消和部分导出失败分别表示。取消必须传到实际源进程；进程停止未确认时不得返回成功。旧成功报告可经当前权限显式查历史，但不能充当本次失败结果。源服务重启仅恢复停止后的保留证据，不表示恢复运行或确定性回放。

## 本地验收步骤

1. 固定 Agent Store 提交与原场景来源 `0e158eee225c698774aa75671c944624cd36550b`，逐文件核对原发布物及依赖锁；两类 Python 环境独立安装精确哈希。
2. 使用 `engineering/prepare_professional_report_tool.py --workspace <新目录> --worker-python <Python3.12> --builder-root <固定源码> --source-root <原来源>` 准备输入。未发布的源身份明确失败，不下载同名替代库。
3. 用准备输出的 `execution-inputs.json` 传入 Maven：`-Dfh.reportConfig=<输入文件>`，可另传 `-Dfh.reportLifecycleEvidence=<输出文件>`。真实 MCP/引擎由本地合成案例运行，固定模型只验证 SDK/工具接线，不调用外部模型或银行。
4. 查看 `ReportSourceProcessIntegrationTest` 的实际进程停止、迟到结果拒绝及重试证据；`ReportAuthorizationIntegrationTest` 检查历史、备注、附件和当前权限。故障等待点仅在测试中注入，原算法及生产进程管理实现不变。

远端 CI 在两仓固定源尚未推送前为 NOT_RUN。工程、原引擎保真、真实模型专业质量、生产适用性、合并和发布分别记录；真实模型、专家判据、银行规则及客户数据授权不足时，专业与生产资格仍为 BLOCKED。
