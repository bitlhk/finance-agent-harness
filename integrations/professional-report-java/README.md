# 原方法与报告：openJiuwen Java 接线

本组件供 Builder 的独立 Host 按显式 Profile 安装。执行环境为 `agent-core-java:0.1.15`，复用原生 `ReActAgent`、现有 Provider SPI 及 `InstalledPreparationMethod`，不另建 Agent Loop。

- 财富 Profile 加载已校验的原 Skill 正文，保留 `wealth_assistant_context_probe`、`wealth_assistant_customer_list`、`wealth_assistant_customer_detail` 三个工具名。数据由原财富 MCP 处理；当前客户和授权由 Host 固定。
- 市场风险 Profile 加载独立登记的派生操作方法，调用 `market_risk_report`。原报告引擎保留原算法、章节、表图和附件，模型只补充有引用的说明。
- 格式及证据指令单独计算摘要，不改原 Skill。报告成功表示本次执行和证据关联完成；专业复核及生产资格单列。

## 部署与使用

组件依赖报告领域、只读 Provider 和已有 openJiuwen 集成模块。通过既有 ServiceLoader 安装；私有 `report-authority.json`、Provider 绑定文件和原材料服务由部署方准备，均置于制品目录之外。绑定文件摘要须与 `service-lock.json` 一致。客户端和模型不能提交端点、凭据或客户权限。

Host 的 `chat` / `generate` 动作接收 `query`；可用 `previousReportId`、`previousRevision` 显式关联重生成。`result`、`versions`、`note`、`artifact` 按报告ID和版本读取，`history` 返回当前仍有权访问的报告。人工备注新增版本并保留原事实；不会自动变为已复核。`cancel` 会向源服务发出取消并检查源进程停止，未确认停止时返回失败。

每次读取历史或附件都重新核对当前授权及原来源状态。源失败、模型格式或引用错误、超时和取消均不能沿用旧成功报告。模型用量按实际 SDK 返回累计；未取得的指标保持空值，不能当成零。

## 本地验证

在仓库根目录运行 `mvn -B -ntp -pl integrations/professional-report-java -am test`。集成测试实际调用原生 SDK 和本地固定响应模型；该结果验证工程接线，不代表专业报告质量。真实原工具和两个独立消费者的验证分别登记在组件 conformance 与消费者材料中。
