# V1 现场演示故事

## 1. 演示目标

用十分钟证明 Finance Agent Harness 不是架构概念，而是一套可以连接银行业务环境并复用金融专业能力的轻量底座。

## 2. 第一幕：真实业务查询

打开手机银行助手：

`http://116.204.36.83:8090`

用户输入：

> 查询我的账户余额。

界面展示：

```text
Mobile Banking
→ OpenJiuwen
→ Finance Harness
→ non-ai-banking
→ account.balance.query
```

结果必须包含：

- 模拟账户事实；
- `sourceSystem`；
- 数据时间；
- `requestId / traceId`；
- `EvidenceReceipt` 摘要。

讲解重点：模型负责理解问题，银行环境负责提供事实，Harness 负责稳定连接和证明来源。

## 3. 第二幕：需要确认的办理

进入渠道设置，修改语言或大字模式。当前 V1 使用统一能力
`channel.preferences.update`。

用户先提出办理请求，系统返回待确认摘要；用户确认后再提交。

界面展示：

```text
preflight
→ NEED_CONFIRMATION
→ 用户确认
→ submit once（重复确认不重复提交）
→ COMPLETED
→ EvidenceReceipt
```

讲解重点：Harness 没有重新发明银行规则，而是正确消费银行后端已有任务协议。

## 4. 第三幕：财富专业能力复用

加载从 Employee Agent 提取的 Wealth Domain Pack，使用同一模拟银行客户、资产和产品事实。

输入：

> 客户有 75 万元待配置，投资期限三年以上，至少保留 20% 高流动性，请形成两套可沟通方案。

输出重点：

- 客户目标和约束；
- 流动性底线；
- 当前组合问题；
- 先形成组合方向，再映射产品；
- A/B 方案取舍；
- 自然面谈话术与下一步。

打开 `finance-skill-eval` 对照结果，展示无 Skill、旧 Skill 和 Wealth Domain Pack 的专业差异。

讲解重点：Harness 解决“拿得到可信金融能力”，Domain Pack 解决“财富经理应该怎么专业地做”。

## 5. 领导应看到的结果

```text
同一套 Harness
├── 支撑手机银行客户查询与办理
└── 支撑财富助手专业分析与推荐
```

这证明它不是手机银行内部重构，也不是只会治理的框架。

## 6. 演示成功标准

- 全部使用真实运行环境，不播放预录结果；
- 查询能看到业务来源和数据时间；
- 办理在确认前不提交、确认后只执行一次；
- 财富输出有专业分析和推荐价值；
- 发生失败时显示业务错误，不用模型编造结果；
- 明确标注 `non-ai-banking` 为 Simulator。

## 7. 暂不展示

- Builder 自动生成 Agent；
- 多 Runtime；
- Profile/Policy 配置中心；
- 自动 Skill 优化；
- 生产审计认证。

这些能力在 V1 证明有复用需求后再逐步增加。
