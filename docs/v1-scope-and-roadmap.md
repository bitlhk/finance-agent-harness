# V1 范围与实施路线

状态：`Proposed`

## 1. V1 目标

用 3 至 4 周完成一个可以演示、可以验证、可以继续扩展的 Finance Agent Harness V1：

1. 同一套 Core Contract 被岗位智能体和手机银行消费；
2. 同一客户与政策快照在员工辅助和客户自服务 Profile 下返回不同合法结果；
3. TypeScript 和 Java 对关键合同产生一致判断和摘要；
4. 现场能够展示手机银行发起客户需求，岗位智能体承接并形成专业建议和跟进；
5. 所有公共能力都有 Fixture、Conformance 和 Evidence，不靠口头证明。

## 2. V1 范围

### Runtime

- OpenJiuwen 企业版远程形态；
- OpenJiuwen Java 内嵌形态。

### Applications

- Employee Agent；
- Mobile Banking Agent。

### Profiles

- `employee-assisted`；
- `customer-self-service`。

### Domain

- Wealth Management。

### Core Contracts

- FinancePrincipalV1；
- FinanceContextPackV1；
- FinanceActionV1；
- FinancePolicyDecisionV1；
- FinanceApprovalGrantV1；
- FinanceEvidenceReceiptV1；
- RuntimeCapabilityProfileV1。

### Providers

- Customer；
- Portfolio；
- Product；
- Policy / Knowledge；
- Advisory Request；
- Follow-up。

### Control

- Context Projection；
- Before Side Effect；
- Allow / Deny / Require Approval / Require Step Up；
- Approval 参数绑定、过期和一次性使用；
- Evidence Receipt。

## 3. V1 不做

- 第二个 Agent Runtime；
- 中央 Harness 服务；
- Builder 自动装配；
- 保险、投研和风控完整 Domain Pack；
- 自动收集 Bad Case 并修改 Skill；
- 自动发布；
- 全量迁移两个应用；
- 通用知识平台和通用安全平台。

## 4. 四周节奏

### Week 1：Walking Skeleton

目标：合同可解析，TS/Java 可以消费同一 Fixture。

交付：

- Monorepo 和 CI；
- 六个 Core Contract 的最小 Schema；
- RuntimeCapabilityProfile Schema；
- JCS + SHA-256 测试向量；
- TypeScript Reference Validator；
- Java Validator Spike；
- 第一组正向和负向 Fixture；
- EA / Mobile 字段映射草图。

验收：

- TS 和 Java 对同一 Fixture 给出相同校验结果和摘要；
- 不依赖 EA 或手机银行即可运行 Conformance。

### Week 2：Context、Knowledge、Action 和 Approval

目标：两个真实应用开始 Shadow 接入。

并行工作：

- Knowledge Owner：Current / Expired / Conflict / Missing Policy Fixture；
- Security Owner：Action / Approval / Replay / Tamper Fixture；
- EA：将现有 Context 和 Receipt 映射到 Contract；
- Mobile：将用户 Context、Capability、确认和幂等映射到 Contract；
- Integration Lead：冻结 OpenJiuwen 生命周期事件。

验收：

- 两个应用不改变现有用户行为即可输出 Shadow Evidence；
- Employee / Customer Context Projection 可对比；
- Approval 过期、篡改和重放被一致拒绝。

### Week 3：跨渠道财富闭环

目标：跑通 V1 演示故事。

交付：

- Mobile `CREATE_ADVISORY_REQUEST`；
- EA 获取咨询请求与员工侧客户 Context；
- Wealth Domain Pack 最小版本；
- Follow-up 写入确认；
- 两端 FinanceEvidenceReceipt；
- Provider 超时和降级处理。

验收：

- 同一客户、同一政策快照、不同 Profile 的字段与动作差异符合预期；
- 两端 Evidence 使用同一 Schema；
- 手机银行不泄漏员工侧字段；
- EA 不绕过客户归属和写入确认。

### Week 4：评测、加固与冻结

目标：把“能跑”变成“可证明”。

交付：

- finance-skill-eval 对接示例；
- Cross-language Conformance 完整报告；
- OpenJiuwen Runtime Capability 实测结果；
- Bad Case 和安全回归；
- 演示脚本和 Architecture V1 Final；
- V1 Release Candidate。

验收：

- 演示可重复；
- 合同、Fixture、实现和 Evidence 可追溯；
- 无 Blocker 级跨 Profile 泄漏或 Approval 绕过；
- V1 非目标没有被偷偷加入范围。

## 5. 前十个工作日任务

| 日期 | Architecture / Integration | Knowledge | Security | Builder | Applications |
|---|---|---|---|---|---|
| D1-D2 | Repo、ADR、Schema Skeleton | Review Context Schema | Review Action Schema | Review AssetRef 需求 | EA/Mobile 映射清单 |
| D3-D5 | Contract + Fixture Runner | Knowledge Fixtures | Approval Fixtures | 压测 Manifest 最小字段 | EA Context Shadow |
| D6-D8 | OpenJiuwen Lifecycle | Provider Contract | Action Interceptor | 反馈 Composition Gap | Mobile Java Mapping |
| D9-D10 | Cross-language Conformance | Policy Snapshot | Replay/Tamper | V1.2 Proposal | 第一条双应用链路 |

## 6. V1 Definition of Done

1. 六个 Core Contract 有 Strict Schema、文档和版本；
2. TypeScript 和 Java 消费同一 Fixture 并得到一致结果；
3. OpenJiuwen 远程和 Java 内嵌两种形态各通过最低能力验证；
4. EA 与手机银行跑通同一客户的跨渠道财富故事；
5. Employee / Customer Profile 无字段越界；
6. 一个写操作经过 Decision、Approval、Execute、Evidence 完整链路；
7. finance-skill-eval 能读取 Evidence 并完成一次对照评测；
8. Builder 能解释如何消费 V1 合同，V1.2 范围已冻结；
9. 所有 Blocker 已关闭，剩余风险进入 ADR 或 Roadmap；
10. 文档、代码和演示均可由非原作者重复运行。

## 7. 后续路线

### V1.2：Assembly Proof

- AssetRef；
- SolutionManifest；
- SolutionLock；
- Builder 接入；
- 财富 Solution 可重复装配；
- 保险 Domain Pack 作为第二个装配样例。

### V1.5：Engineering Plane

- Provider Toolkit；
- Pack Authoring；
- Policy Simulator；
- Sandbox；
- Finance Engineering Agent 的自然语言入口候选。

### V2：Cross-Runtime Proof

- 选择 AgentScope、DeepAgents 或银行自研 Runtime 之一；
- 实现第二个 Runtime Integration；
- 用同一 Contract、Profile、Domain Pack 和 Conformance 证明 Runtime-neutral。
