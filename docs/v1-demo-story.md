# V1 跨渠道财富演示故事

## 1. 演示目标

现场证明 Finance Agent Harness 不是抽象公共库，也不是另一个 Agent Runtime，而是让内外两个金融 Agent 快速获得统一金融语义、差异化 Profile、受控动作和证据的工程底座。

## 2. 演示角色

- 客户：陈明；
- 财富经理：员工账号；
- 客户渠道：手机银行；
- 员工渠道：Employee Agent；
- Runtime：OpenJiuwen Java + OpenJiuwen 企业版；
- Domain：财富管理；
- 共享 Provider：Customer、Portfolio、Product、Policy。

## 3. 统一场景数据

```text
客户：陈明
待配置资金：75 万元
投资期限：3 年以上
流动性底线：至少 20%
风险测评：有效
产品快照：固定版本
政策快照：固定版本
```

## 4. 第一幕：手机银行客户自服务

客户在手机银行说：

> 我有 75 万元想配置，三年以上不用，但至少保留 20% 高流动性。先帮我看看怎么安排。

系统演示：

1. Mobile Application 调用 OpenJiuwen Java Runtime；
2. `customer-self-service` Profile 生效；
3. Harness 组装只包含本人可见字段的 FinanceContextPack；
4. Wealth Pack 形成客户可理解的配置方向；
5. 不展示客户归属、员工经营备注或内部机会标签；
6. 客户确认后创建一条 `CREATE_ADVISORY_REQUEST`；
7. ApprovalGrant 绑定客户、请求参数和 Policy Snapshot；
8. 生成 FinanceEvidenceReceipt。

领导应直观看到：同一 Harness 能服务面客智能体，而不是只服务 EA。

## 5. 第二幕：岗位智能体承接

财富经理打开 Employee Agent，看到新的客户咨询请求。

系统演示：

1. EA 调用 OpenJiuwen 企业版 Runtime；
2. `employee-assisted` Profile 生效；
3. Harness 在客户归属验证后投影员工工作所需 Context；
4. 在客户本人信息基础上增加 AUM、持仓变化、到期事项、服务记录和经营提示；
5. Wealth Pack 生成访前判断、关键问题和 A/B 配置建议；
6. 方案先形成流动层、稳健层和增值层，再映射当前 eligibleProducts；
7. 财富经理确认后创建一条 CRM Follow-up；
8. 生成同一 Schema 的 FinanceEvidenceReceipt。

领导应直观看到：Harness 不只是拦截动作，还帮助 Agent 获得财富专业判断、配置和沟通能力。

## 6. 第三幕：证据与对比

界面并排展示：

| 维度 | 手机银行 | 岗位智能体 |
|---|---|---|
| Principal | Customer | Employee acting for Customer |
| Profile | customer-self-service | employee-assisted |
| 客户主体 | 同一个陈明 | 同一个陈明 |
| Policy Snapshot | 同一版本 | 同一版本 |
| Context Projection | 本人可见 | 员工工作所需 |
| 动作 | Create Advisory Request | Create Follow-up |
| 确认主体 | 客户本人 | 财富经理 |
| Evidence | FinanceEvidenceReceiptV1 | FinanceEvidenceReceiptV1 |

然后打开 Skill Eval 结果，展示：

- 无 Wealth Skill；
- 旧 Wealth Skill；
- 新 Wealth Domain Pack；
- 同一模型、同一数据快照下的专业判断差异。

## 7. 现场要讲清楚的三句话

1. 同一个金融 Core，可以服务员工和客户两种应用；
2. 同一客户数据因为 Profile 不同，展示和允许的动作自然不同；
3. Builder 后续只需组合 Runtime、Profile、Domain Pack 和 Provider，就能快速装配财富、保险等新 Solution。

## 8. 演示成功标准

- 两个应用真实运行，不播放预录视频；
- 同一客户与 Policy Snapshot 可在 Evidence 中核对；
- 手机银行无法看到员工字段；
- 两次写动作分别有独立 ApprovalGrant；
- A/B 财富方案金额闭合、流动性分类正确、产品映射有依据；
- 任一步失败时能够展示明确降级和 Evidence，不出现无响应。

## 9. Secondary Demo

V1.2 可以把 Wealth Pack 替换为 Insurance Pack，并保持 Core 不变，证明 Harness 能帮助 Builder 快速构建保险顾问 Agent。该场景不应抢占 V1 跨渠道主线。
