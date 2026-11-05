# 原算法计算 Java 接线

此组件使用真实 openJiuwen Java 0.1.15，通过既有 Scenario/Provider SPI 安装方法与七类有限能力。
当前私有绑定固定一个资产和输入；debt/entity 是不同原资产。输入不来自模型生成代码，数值来自固定原函数。

宿主私有状态目录中的 `calculation-authority.json` 保存当前授权和私有绑定引用。
`FH_CALCULATION_BINDINGS` / `FH_CALCULATION_BINDINGS_SHA256` 用于受管安装规划；规划只读元数据，不调用原源服务或取得授权。
私有配置沿用现有 256 KiB 上限；原工具自身的 4 MiB 输入上限不代表此内联绑定可接受同样大的配置。

`chat` / `calculate` 执行原生 Agent；`status` / `result` 复核当前授权，完成后的结果读取也复查源端状态。
`history` 返回本会话成功记录的标识；`result` 可通过显式 `runId` 回查。每次读取重验授权，失败/取消的默认结果不会回落旧成功。
`cancel` 先关闭本次结果披露并关闭 Provider 的后续调用，再请求源端停止实际进程；只中断 HTTP Future 不算取消通过。
源停止无法确认时明确失败。重算使用新代次/新源运行标识，旧结果不能覆盖新状态。Core、Execution 和原算法均不改。

工程自测使用本地固定模型和源故障注入，验证安装/模型/工具流程，不能代替原算法资格。
真实原算法、七类操作、两类 Logistic 与两个独立消费者由专门资格入口验证。
接线、数值等价、专业模型有效性和生产资格分别记录，不把模型答复结束当作工具成功。

实际原进程联测由 `engineering/verify_original_calculation.py --config <固定配置> --report <报告>` 调用。
缺少固定工具配置时测试失败，不跳过。会话历史上限为64条，源索引和会话历史在当前进程内维护，重启恢复另行建设。
