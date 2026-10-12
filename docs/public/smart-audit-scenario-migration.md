# 智能审核场景迁入指南

本文记录“个人贷款审核辅助”和“企业授信审核辅助”如何作为真实金融场景迁入 Finance Harness，而不是仅添加一个前端卡片。

## 已迁入内容

| 场景 | Domain Pack | Capability Pack | 能力数 | Provider | 输出边界 |
| --- | --- | --- | ---: | --- | --- |
| 个人贷款审核辅助 | `domain-packs/smart-audit/personal-loan-audit` | `capability-packs/smart-audit-personal-loan-audit` | 4 | `providers/smart-audit` | 主体资格、收入、资产负债、偿债能力的核验与人工复核材料 |
| 企业授信审核辅助 | `domain-packs/smart-audit/enterprise-credit-audit` | `capability-packs/smart-audit-enterprise-credit-audit` | 5 | 同上 | 财报、财务概述、流水、流水报告、尽调的核验与人工复核材料 |

两包使用不同的技术 Capability ID：`smartaudit.personal.*` 与 `smartaudit.enterprise.*`。这是为适配现有 Harness ID 合同；展示名称仍为“智能审核”。每个候选工程只能安装其中一个包，不能把个人材料和企业结论混为同一授权事实。

## 一个真实场景必须带来的内容

1. **业务方法**：Domain Pack 的 `pack.yaml`、技能步骤、输入材料类别、输出章节、范围与明确的 out-of-scope。
2. **能力合同**：Capability Pack 声明每项工具的输入 Schema、QUERY/TASK 语义和所需 scope。审核场景仅允许 QUERY。
3. **确定性实现**：Provider 实现 Harness SPI，校验身份、用途、session、scope、材料格式和缺项；返回回执可关联的结果，不能以提示词代替检查。
4. **固定业务依据**：个人审核规则基线及监管基线随 Domain Pack 保存；候选 Host 复制固定版本并由 Provider 校验，而非让页面上传或切换规则。
5. **受管装配声明**：`catalog/finance-assets.v1.json`、每个 Capability Pack 的 `assembly.v0.1.json` 和 Provider `assembly.v0.1.json` 固定版本、能力归属、制品和连接类型。
6. **Host/Profile 接线**：`profiles/internal-control.md` 规定员工内部审核边界；Builder Host 使用 `in-process-smart-audit.v1`，只把可信 Host 固定的 Context 投影给 Provider。
7. **目录与场景投影**：Builder 将两个 Pack 分别映射到“信贷授信”的两个任务场景，只有装配目录验证通过时显示为可用。
8. **验证证据**：Provider 单元测试、目录/装配测试、负向授权与跨场景隔离测试，以及候选工程的构建和运行验收。目录存在或页面可见本身不构成运行验收。

## 迁入流程

```text
业务范围与人工复核边界
  -> Domain Pack + 输入/输出合同
  -> Capability Pack + Provider 实现和测试
  -> Catalog + assembly 准入
  -> Profile/Host 的可信 Context 接线
  -> Builder 业务板块/任务场景投影
  -> 候选构建、负向授权、合成材料端到端验证
  -> 发布版本与运行证据
```

### 1. 先定义不可做什么

写清最终业务决定由谁做，哪些输入是权威材料、哪些只是 OCR 转写，输出是否只能供人工复核。若场景会审批、写回或资金操作，必须单列 TASK 生命周期、确认、幂等与查证；不能复用本审核场景的 QUERY Provider。

### 2. 再拆分能力和方法

当一个输入/输出/授权边界可以独立复用时，定义一个 Capability；共同交付且必须原子安装的一组 Capability 定义一个 Capability Pack；说明“怎样审、缺什么、输出如何组织”的内容放 Domain Pack/Skill。不要把接口地址、密钥或页面流程写进 Skill。

### 3. 将授权放到可信边界

Capability 的 `requiredScopes` 只是要求，不是授权。Host 必须在每次调用前绑定员工、主体、会话、用途和实授 scope；Provider 再验证这些值。请求参数、浏览器选择和模型文本不能决定 purpose、scope、规则路径、Provider 或安装的能力集。

### 4. 用目录连接页面与运行物

在 Catalog 中登记 Domain Pack、Capability Pack、Provider 与兼容 Profile；在 assembly 中声明所需 Host Feature、默认 Domain Pack、Provider artifact 和能力归属。Builder 的业务板块、任务场景和模块映射只引用这些身份，不能重复定义一套工具或跳过 assembly。

### 5. 用最小合成材料验收

准备不含真实客户信息的 TEXT/JSON 案例。至少验证：每个场景可构建；只安装该场景能力；缺材料返回待补项；无 scope/错误 purpose/错误 session 被拒绝；跨场景 capability 被拒绝；TASK 被拒绝；结果含证据和人工复核事项。生产数据接入、银行 IAM 和最终审批规则必须另行验收。

## 交付检查表

- [ ] 有业务 Owner 确认的范围、材料口径与人工复核动作。
- [ ] Domain Pack、Capability Pack、Provider 和 tests 同版本提交。
- [ ] Capability ID、输入 Schema、operation、scope 与 Provider 描述一致。
- [ ] Profile 和 Host 不把前端或模型输入当授权。
- [ ] Catalog/assembly 可解析，且只能选到声明的 Provider/Pack 组合。
- [ ] Builder 页面显示真实任务场景，而非“已接入”占位卡片。
- [ ] 候选工程的安装 Lock、构建报告与合成材料验收已留档。
- [ ] 未把可选页面、单元测试或目录声明描述成生产授信审批能力。
