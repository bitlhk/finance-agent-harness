# Clean OpenJiuwen Retail Banking Example

这个示例证明一个未预置金融工具的 OpenJiuwen Java Agent 可以通过声明式资产获得金融能力：

1. `solution.yaml` 引用 `retail-banking-basics` Capability Pack；
2. OpenJiuwen Integration 把 Pack 转换为 Runtime 原生 `ToolCard` 和 `LocalFunction`；
3. Query 和 Task 经统一 Finance Contract 调用 Provider；
4. Task 首次调用返回确认信息与续办句柄，明确确认后使用同一任务和幂等键继续；
5. 安装回执记录 Pack、Integration、Provider 和能力版本；卸载后工具从 Agent 与 Runtime 消失。

当前自动化测试使用确定性的 Reference Provider，不依赖模型质量或外部银行环境。接入真实应用时，只替换 Provider、身份上下文和模型配置，Capability Pack 与 Finance Contract 不变。

## 真实模型演示

准备一个 OpenAI 兼容模型和 Non-AI Banking 环境：

```bash
export FINANCE_AGENT_MODEL_API_KEY='<model-api-key>'
export FINANCE_AGENT_MODEL_BASE_URL='<openai-compatible-base-url>'
export FINANCE_AGENT_MODEL='glm-5.2'
export NON_AI_BANKING_BASE_URL='http://116.204.36.83:8086'
```

余额查询不会产生业务副作用：

```bash
./examples/solutions/openjiuwen-retail-banking/run-live-demo.sh
```

转账演示会在配置的模拟银行中创建一笔 `CNY 0.01` 的模拟交易，并验证预检、等待确认和恢复提交：

```bash
./examples/solutions/openjiuwen-retail-banking/run-live-demo.sh demo/transfer-confirmation.txt
```

每次执行都会重新解析 `solution.yaml`、安装 Capability Pack，并生成：

```text
target/solution-lock.json
target/live-demo/run-report.json
```

`solution-lock.json` 证明本次装配了哪些 Pack、能力、Integration 和 Provider；`run-report.json` 记录模型、脚本及逐轮对话，但不保存模型密钥或银行登录凭据。
