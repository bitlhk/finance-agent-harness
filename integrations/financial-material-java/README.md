# 原生材料接线 0.1.0 候选

复用 openJiuwen Java 0.1.15 的 Agent/Runner、原生工具安装和 Harness Provider，不实现新的 Runtime。受管 Host 通过 ServiceLoader 选择 `financial-material-processing.v1`。

Host私有状态 `material-host-authority.json` 固定owner、当前主体/客户/任务/会话、代次、期限、文档引用和Provider绑定摘要；凭据不进入模型参数。调用前、模型调用前、工具返回和结果披露复核当前授权。撤权、换代、取消和关闭不会显示旧成功。

工程测试使用真实Runtime、原PDF工具及回环固定模型。视觉固定响应只验证协议，不能证明真实图片质量。目录许可和真实视觉条件保留独立BLOCKED。
