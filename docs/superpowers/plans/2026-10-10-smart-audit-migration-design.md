# Smart Audit 真实场景迁入设计

## 已确认的目标

将本地来源 `/Users/apple/Documents/工作资料/代码助手/灵感/finance-agent-harness-Smart-Audit` 中的两个审核场景沉淀到 Harness，并在
`/Users/apple/Documents/工作资料/代码助手/agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder`
的新版创建页中，以“信贷授信”业务板块下的两个任务场景提供：

1. **个人贷款审核辅助**
2. **企业授信审核辅助**

新版 Builder 的信息架构保持不变：`业务板块 → 任务场景 → 能力模块 → 运行框架 → 应用终端`。不恢复旧版“场景模板”首页，也不将两个场景拼装成一个候选 Agent。

## 事实盘点

| 资产层 | 个人贷款审核 | 企业授信审核 | 迁入结论 |
| --- | --- | --- | --- |
| Domain Pack | 4 个 Skill、锁定个人贷款规则与监管基线 | 5 个 Skill | 迁入原始方法和规则文件，保留来源与版本 |
| Capability Pack | 4 个 `QUERY` 能力 | 5 个 `QUERY` 能力 | 每个场景只能引用自己的 Pack |
| Provider | `smart-audit-model-provider` | 同一个 Provider | 迁入确定性核验器、访问检查、结果映射与单测 |
| Solution | `openjiuwen-personal-loan-audit` | `openjiuwen-enterprise-credit-audit` | 升级到当前 OpenJiuwen Java 0.1.15 基线 |
| Builder 页面 | 信贷授信板块尚无任务场景 | 同左 | 新增两个独立 Task Blueprint 与可构建 Module |

来源分支尚未具备当前 Builder 的受管装配所需 `finance-assets.v1` 登记、`assembly.v0.1.json` 声明、内部审核 Host 接线和端到端合成材料验证。因此不能直接整体合并旧分支；必须选择性迁入并补全这些契约。

## 场景边界

两个场景均是**内部审核辅助**，而不是贷款审批或授信决策系统。

- 输入只允许带 `category`、`content`、`declaredFormat` 的 OCR 文本或 JSON 材料。
- 所有能力均为 `QUERY`；禁止 `TASK`、审批写回、授信额度调整、资金操作、规则上传或规则切换。
- Provider 强制要求 `EMPLOYEE` 主体、场景对应 purpose、会话引用、审核对象引用和对应最小 scope。
- 输出是事实、规则核验、证据定位、缺项与人工复核关注点；不得产生“批准／拒绝／自动授信”的最终结论。
- 个人贷款的银行阈值参数必须由受管配置提供；缺失或未批准时只能输出待复核，不能形成正向结论。

## 目标映射

```text
信贷授信
├── 个人贷款审核辅助
│   ├── smart-audit.personal-loan-audit (Domain Pack)
│   ├── smart-audit-personal-loan-audit (Capability Pack, 4 × QUERY)
│   └── smart-audit-model-provider
└── 企业授信审核辅助
    ├── smart-audit.enterprise-credit-audit (Domain Pack)
    ├── smart-audit-enterprise-credit-audit (Capability Pack, 5 × QUERY)
    └── smart-audit-model-provider
```

每个 Builder Task Blueprint 使用独立 Module、独立固定 Pack Binding 和独立 Solution。选择一个任务场景不会安装另一场景的工具；任意部分选择、跨场景 Pack Binding 或伪造 Capability ID 都必须在入队前拒绝。

## 必须补齐的受管装配链

1. Harness 根 `pom.xml` 注册 Provider 和两个示例 Solution Maven 模块。
2. `catalog/finance-assets.v1.json` 注册两个 Domain Pack、两个 Capability Pack、Smart Audit Provider 及对应兼容性；`internal-control` Profile 需有可读取的实体说明文件。
3. 所有由 Builder 读取的 Pack/Provider 具备符合当前 schema 的 `assembly.v0.1.json`，声明默认 Domain Pack、Provider artifact、支持能力、运行时、Profile、`QUERY` 和受管连接类型。
4. Builder 增加内部审核 OpenJiuwen Java Host 支持：注册 Provider，构造可信 `EMPLOYEE` Context，传递受管 scope／purpose，并只装配固定 Smart Audit 能力。
5. Builder 的目录投影、Task Blueprint 和前端只显示已通过上述登记与运行资格检查的两个场景。

## 验收证据

迁入完成不以“页面出现卡片”为准，必须同时具备：

- Smart Audit Provider 的规则、越权、缺材料、非法格式、禁止 TASK、企业/个人隔离单测；
- Maven 聚合构建通过，并验证两个 Solution 的 Pack、Provider、Profile 与能力绑定；
- Builder 目录和 API 测试证明两个场景各自生成固定且互不交叉的构建输入；
- 浏览器回归证明新版页面在“信贷授信”下展示且选择两个场景会更新独立能力集合；
- 受控本地运行只处理合成材料，记录人工复核、证据及阻断状态；没有模型、审核身份或银行阈值配置时必须失败关闭；
- 公开开发指导描述真实迁入流程、验证范围与禁止边界，不暴露凭据、真实材料或内部地址。

## 非目标

- 不改变既有手机银行、财富、转账或反诈能力的装配结果。
- 不对生产站点写入、发布或修改用户账号。
- 不将 OCR、文件上传、MCP、HTTP 外部 Skill 执行器作为本次 Smart Audit Provider 的能力。
- 不宣称完成真实银行审批或生产授信验收；本次交付仅验证合成材料和受管本地候选 Agent。
