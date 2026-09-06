# OpenJiuwen Capability Pack Live Smoke

验证日期：2026-09-03

## 目的

验证一个未预置金融工具的 OpenJiuwen Java Agent，能否仅根据 `solution.yaml` 安装 Finance Harness Capability Pack，并通过自然语言自主选择金融能力。

## 环境

- Runtime：OpenJiuwen Java `0.1.13`
- Model：`glm-5.2`，OpenAI 兼容接口
- Capability Pack：`retail-banking-basics@0.1.0`
- Provider：`non-ai-banking-reference@0.1.0-SNAPSHOT`
- Bank simulator：部署态 Non-AI Banking

密钥和登录凭据仅通过环境变量注入，未写入 Solution、Lock 或运行报告。

## 验证结果

### 余额查询

1. Runtime 安装 `account_balance_query -> account.balance.query`。
2. 模型根据“查询余额”自然语言请求自主选择该工具。
3. Provider 返回模拟银行账户事实。
4. 最终回答包含账户掩码、币种、可用余额、数据时间、来源系统和证据回执。

结果：`PASS`

### 行内转账

1. Runtime 安装 `transfer_internal_create -> transfer.internal.create`。
2. 模型从自然语言提取付款账户、已保存收款人和金额并发起预检。
3. 首次调用返回 `NEED_USER`、转账摘要和一次性 `financeTaskHandle`，未直接提交。
4. 用户明确回复“确认提交”后，模型携带原句柄继续同一任务。
5. Provider 返回 `COMPLETED`、交易编号和第二份证据回执。

结果：`PASS`

## 结论

该验证证明 Capability Pack 不是文档提示，而是可安装的 Runtime 资产：它把标准 Finance Capability 映射为 OpenJiuwen 原生工具，并通过 Finance Contract 调用银行 Provider。更换银行时替换 Provider；更换 Agent Runtime 时替换 Integration；Pack 的能力语义和 Solution 装配关系保持不变。

本次结果不代表生产银行交易验收。当前 Provider 和银行系统均为模拟环境，模型输出也仍需由冻结评测集持续验证。
