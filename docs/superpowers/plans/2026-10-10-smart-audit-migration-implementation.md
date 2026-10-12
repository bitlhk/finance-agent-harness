# Smart Audit 场景迁入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将个人贷款审核辅助和企业授信审核辅助作为两个可验证、只读、内部审核场景迁入 Finance Harness，并在新版 Builder 的“信贷授信”业务板块中构建。

**Architecture:** 从 Smart-Audit 来源选择性迁入 Domain Pack、Capability Pack、规则、Java Provider 和示例 Solution；用当前 Harness 资产目录及受管装配声明替代旧分支缺失的目录接线。Builder 为每个场景固定一个内部审核 Host、一个 Task Blueprint 和一组 Pack Binding；解析层在入队前拒绝交叉组合与越权输入。

**Tech Stack:** Java 21、Maven、OpenJiuwen Java 0.1.15、Python 3.11+、Pydantic、PyYAML、pytest、vanilla JavaScript、Playwright CLI。

---

## 文件职责

| 区域 | 文件 | 职责 |
| --- | --- | --- |
| Harness | `pom.xml` | 注册 Smart Audit Provider 和两个 Solution 模块 |
| Harness | `domain-packs/smart-audit/**` | 审核方法、固定规则、输入和人工复核边界 |
| Harness | `capability-packs/smart-audit-*/pack.yaml` | 9 个 `QUERY` 工具合同与最小 scope |
| Harness | `providers/smart-audit/**` | 可信 Context 校验、确定性核验、结果映射和 Provider 测试 |
| Harness | `catalog/finance-assets.v1.json` | 受管资产、Profile、运行时与 Provider 兼容性 |
| Harness | `docs/public/developer-guide.md` | 面向场景作者的真实场景迁入指南 |
| Builder | `src/finance_solution_builder/template-modules.v1.json` | 信贷授信下两个 Module/Task Blueprint 的页面投影 |
| Builder | `src/finance_solution_builder/{unified_catalog.py,managed_selection.py}` | 检查固定 Pack Binding、Profile、Host 和能力集合 |
| Builder | `templates/**` 与 `candidate_backend/**` | 内部审核 OpenJiuwen Host 的 Provider 注册与可信 Context 接线 |
| Builder | `tests/**` 与 `engineering/**` | 目录/API/浏览器/受控运行验证 |

### Task 1: 建立迁入前的失败用例和资产清单

**Files:**
- Create: `finance-agent-harness-main 2/providers/smart-audit/src/test/java/.../SmartAuditMigrationContractTest.java`
- Modify: `finance-agent-harness-main 2/pom.xml`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/tests/test_template_modules.py`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/tests/test_unified_catalog.py`

- [ ] **Step 1: 写 Harness 的迁入前失败测试。**

  测试应要求 Provider descriptor 恰含下列九个 capability ID，且全部为 `QUERY`；并要求 `executeTask` 返回 `TASK_NOT_SUPPORTED`：

  ```java
  assertThat(provider.descriptor().capabilities())
      .extracting(ProviderDescriptorV1.CapabilitySupport::capabilityId)
      .containsExactlyInAnyOrder(
          "smart-audit.personal.borrower-qualification.query",
          "smart-audit.personal.income-verification.query",
          "smart-audit.personal.asset-liability.query",
          "smart-audit.personal.repayment-capacity.query",
          "smart-audit.enterprise.financial-report.query",
          "smart-audit.enterprise.financial-summary.query",
          "smart-audit.enterprise.transaction-analysis.query",
          "smart-audit.enterprise.transaction-report.query",
          "smart-audit.enterprise.due-diligence.query");
  ```

- [ ] **Step 2: 写 Builder 的目录失败测试。**

  断言 `credit-approval` 下恰有 `personal-loan-audit-assistance` 与 `enterprise-credit-audit-assistance` 两个 Task Blueprint；两者的 `packIds` 分别是 `smart-audit-personal-loan-audit` 和 `smart-audit-enterprise-credit-audit`，不得交叉。

- [ ] **Step 3: 运行失败测试。**

  Run:
  ```bash
  mvn -pl providers/smart-audit test
  cd '/Users/apple/Documents/工作资料/代码助手/agent-store-builder-dev-wo-fork-finance 2/community/finance/builder'
  uv run --package finance-solution-builder pytest finance-solution-builder/tests/test_template_modules.py -k smart_audit -q
  ```

  Expected: Harness 报 `Could not find the selected project`，Builder 断言失败，因为资产和任务场景尚未迁入。

- [ ] **Step 4: 提交测试基线。**

  ```bash
  git add 'finance-agent-harness-main 2/providers/smart-audit/src/test/java' \
    'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/tests/test_template_modules.py' \
    'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/tests/test_unified_catalog.py'
  git commit -m 'test: define smart audit migration contract'
  ```

### Task 2: 迁入并适配 Harness 审核资产

**Files:**
- Create: `finance-agent-harness-main 2/domain-packs/smart-audit/personal-loan-audit/**`
- Create: `finance-agent-harness-main 2/domain-packs/smart-audit/enterprise-credit-audit/**`
- Create: `finance-agent-harness-main 2/capability-packs/smart-audit-personal-loan-audit/{pack.yaml,assembly.v0.1.json}`
- Create: `finance-agent-harness-main 2/capability-packs/smart-audit-enterprise-credit-audit/{pack.yaml,assembly.v0.1.json}`
- Create: `finance-agent-harness-main 2/providers/smart-audit/**`
- Create: `finance-agent-harness-main 2/examples/solutions/openjiuwen-personal-loan-audit/**`
- Create: `finance-agent-harness-main 2/examples/solutions/openjiuwen-enterprise-credit-audit/**`
- Modify: `finance-agent-harness-main 2/pom.xml`

- [ ] **Step 1: 选择性复制来源资产。**

  从来源只复制上述六个目录下的规则、Skill、Provider 与测试；不复制来源的根 `pom.xml`、旧 OpenJiuwen 0.1.13 属性、无关 Provider 或生成输出。

- [ ] **Step 2: 更新两份 Solution 的运行时引用。**

  两个 `solution.yaml` 均使用：

  ```yaml
  runtime:
    family: openjiuwen
    language: java
    templateRef: maven://com.openjiuwen:agent-core-java:0.1.15
    integrationRef: maven://io.github.bitlhk.finance:finance-harness-openjiuwen-java:0.1.0-SNAPSHOT
  profileRef:
    id: internal-control
    version: 0.1.0
    source: repo://profiles
  ```

  个人和企业 Solution 各自只能引用一个同名 Domain Pack、一个同名 Capability Pack 及 `smart-audit-model-provider`。

- [ ] **Step 3: 注册 Maven 模块并跑 Provider 测试。**

  在根 `pom.xml` 的 Provider 区增加 `providers/smart-audit`，在 Solution 区增加两个 `examples/solutions/openjiuwen-*-audit` 模块。修复当前 API 差异，但不得降低来源测试对 `EMPLOYEE`、purpose、scope、材料类别、规则漂移和禁止 TASK 的断言。

- [ ] **Step 4: 运行 Green 验证。**

  Run:
  ```bash
  mvn -pl providers/smart-audit -am test
  mvn -pl examples/solutions/openjiuwen-personal-loan-audit,examples/solutions/openjiuwen-enterprise-credit-audit -am test
  ```

  Expected: Provider 与两个 Solution 均通过；没有产生自动审批、写回或 TASK 支持。

- [ ] **Step 5: 提交 Harness 资产。**

  ```bash
  git add 'finance-agent-harness-main 2/pom.xml' \
    'finance-agent-harness-main 2/domain-packs/smart-audit' \
    'finance-agent-harness-main 2/capability-packs/smart-audit-personal-loan-audit' \
    'finance-agent-harness-main 2/capability-packs/smart-audit-enterprise-credit-audit' \
    'finance-agent-harness-main 2/providers/smart-audit' \
    'finance-agent-harness-main 2/examples/solutions/openjiuwen-personal-loan-audit' \
    'finance-agent-harness-main 2/examples/solutions/openjiuwen-enterprise-credit-audit'
  git commit -m 'feat: add smart audit harness scenarios'
  ```

### Task 3: 将 Smart Audit 纳入当前资产目录和受管装配

**Files:**
- Create: `finance-agent-harness-main 2/profiles/internal-control.md`
- Create: `finance-agent-harness-main 2/domain-packs/smart-audit/personal-loan-audit/assembly.v0.1.json`
- Create: `finance-agent-harness-main 2/domain-packs/smart-audit/enterprise-credit-audit/assembly.v0.1.json`
- Create: `finance-agent-harness-main 2/providers/smart-audit/assembly.v0.1.json`
- Modify: `finance-agent-harness-main 2/catalog/finance-assets.v1.json`
- Modify: `finance-agent-harness-main 2/contracts/json-schema/catalog/finance-asset-catalog.v1.schema.json` only if validation proves the existing schema cannot express the already-used compatibility fields
- Test: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/tests/asset_governance/test_catalog.py`

- [ ] **Step 1: 为 `internal-control` 创建实体 Profile 说明。**

  文档固定描述内部员工辅助、可信 subject/session、最小 scope、只读核验、证据保留与人工复核；禁止最终审批、客户自助身份、银行写回和 TASK。

- [ ] **Step 2: 添加两份 Capability Pack 的装配声明。**

  每份声明的 owner 与 `pack.yaml` 身份一致，`requiredFeatures` 为 `query.v1`，Provider 只允许 `smart-audit-model-provider@0.1.0-SNAPSHOT`，默认 Domain Pack 为场景同名 Pack，不声明 `task.v1`。

- [ ] **Step 3: 添加 Provider 装配声明并注册 catalog。**

  `providers/smart-audit/assembly.v0.1.json` 的 artifact 必须是
  `maven://io.github.bitlhk.finance:finance-provider-smart-audit:0.1.0-SNAPSHOT`；支持九个能力、仅 `QUERY`、仅 `internal-control` 和 OpenJiuwen Java 0.1.15。`catalog/finance-assets.v1.json` 为两个 Domain Pack、两个 Capability Pack、Provider 和两个 Eval Pack 引用登记同样的版本、source、capabilities 与 compatibility。

- [ ] **Step 4: 写并运行目录闭环测试。**

  断言 ComponentAssembly 对每个 Smart Audit Pack 只能选择一个 Smart Audit Provider，且对另一个场景的 Pack/Provider 或非 `internal-control` Profile 抛出稳定的 `ASSEMBLY_*` 错误。

  Run:
  ```bash
  cd '/Users/apple/Documents/工作资料/代码助手/agent-store-builder-dev-wo-fork-finance 2/community/finance/builder'
  uv run --package finance-solution-builder pytest finance-solution-builder/tests/asset_governance/test_catalog.py finance-solution-builder/tests/test_unified_catalog.py -k smart_audit -q
  ```

  Expected: 两份 Pack 都有唯一 Provider 绑定，未登记组合无法进入装配。

- [ ] **Step 5: 提交受管资产登记。**

  ```bash
  git add 'finance-agent-harness-main 2/profiles/internal-control.md' \
    'finance-agent-harness-main 2/catalog/finance-assets.v1.json' \
    'finance-agent-harness-main 2/domain-packs/smart-audit' \
    'finance-agent-harness-main 2/capability-packs/smart-audit-personal-loan-audit' \
    'finance-agent-harness-main 2/capability-packs/smart-audit-enterprise-credit-audit' \
    'finance-agent-harness-main 2/providers/smart-audit'
  git commit -m 'feat: register smart audit managed assets'
  ```

### Task 4: 为内部审核场景接通 Builder Host 与固定方案

**Files:**
- Create: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/templates/openjiuwen-v1.1-overlay/internal-control/FinanceHarnessProfileSupport.java`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/src/finance_solution_builder/builder.py`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/src/finance_solution_builder/managed_selection.py`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/src/finance_solution_builder/candidate_backend/openjiuwen_java.py`
- Test: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/tests/candidate_backend/test_openjiuwen_java.py`
- Test: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/tests/test_managed_runtime_selection.py`

- [ ] **Step 1: 写内部审核 Host 的失败用例。**

  用一个 `internal-control` Smart Audit Solution 断言 Candidate 的依赖包含 `finance-provider-smart-audit` 和 `finance-harness-openjiuwen-java`，Host 仅注册所选 capability ID，且构造的 Context 使用 `EMPLOYEE`、场景固定 purpose 和服务端授予的 scope。

- [ ] **Step 2: 实现独立的内部审核 Profile Support。**

  新 Java Support 不读取浏览器传来的 principal、scope、purpose 或 rule path；仅从服务端环境接收经验证的审核身份、当前会话、对象引用和授予范围。缺少任一值时不安装工具。个人审核规则路径和银行阈值只能由服务端配置读取。

- [ ] **Step 3: 将 Builder 的装配分支限定为三个已支持 Profile。**

  `builder.py` 对 `internal-control` 选择 Smart Audit Provider，不复用客户模拟银行或 Employee Agent MCP Provider；未知 Profile 继续失败关闭。`managed_selection.py` 只在 Task Blueprint 选择与 Profile/Host 一致时生成 Solution。

- [ ] **Step 4: 运行 Host 和选择回归。**

  Run:
  ```bash
  cd '/Users/apple/Documents/工作资料/代码助手/agent-store-builder-dev-wo-fork-finance 2/community/finance/builder'
  uv run --package finance-solution-builder pytest \
    finance-solution-builder/tests/candidate_backend/test_openjiuwen_java.py \
    finance-solution-builder/tests/test_managed_runtime_selection.py -k 'internal_control or smart_audit' -q
  ```

  Expected: Smart Audit Host 只在受管内部审核配置完整时可启动，其他 Profile 与 Provider 不可串用。

- [ ] **Step 5: 提交 Host 接线。**

  ```bash
  git add 'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/templates/openjiuwen-v1.1-overlay/internal-control' \
    'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/src/finance_solution_builder/builder.py' \
    'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/src/finance_solution_builder/managed_selection.py' \
    'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/src/finance_solution_builder/candidate_backend/openjiuwen_java.py'
  git commit -m 'feat: add internal control smart audit host'
  ```

### Task 5: 在新版 Builder 增加两个信贷授信任务场景

**Files:**
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/src/finance_solution_builder/template-modules.v1.json`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/src/finance_solution_builder/unified_catalog.py`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/browser/workbench.js`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/tests/test_template_modules.py`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/tests/test_unified_catalog.py`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/engineering/test_unified_creation.mjs`

- [ ] **Step 1: 添加两个 Module 与两个 Task Blueprint。**

  `personal-loan-audit-assistance` 和 `enterprise-credit-audit-assistance` 均以 `credit-approval` 为 `businessDomainId`、以 `operations` 为关联板块、以内部审核模板为 `legacyTemplateId`。每个 Blueprint 含恰好一个 Module、一个 Pack ID 和固定 `internal-control` Profile；不在“自定义组合”中公开九项能力的任意交叉组合。

- [ ] **Step 2: 扩展目录投影的准入规则。**

  `unified_catalog.py` 只有在目标 Harness 的 catalog、assembly declaration、Provider artifact 和兼容性全部有效时，才把两个 Module 标记为 `AVAILABLE`。任一资源缺失时隐藏相应 Task Blueprint 或显示不可构建状态，不能回退至相似 Provider。

- [ ] **Step 3: 更新页面文案，不改变新版布局。**

  在现有“信贷授信”选中后，任务场景卡显示“个人贷款审核辅助”“企业授信审核辅助”；能力模块卡显示各自 4/5 项只读审核能力；运行框架显示仅支持 OpenJiuwen Java；页脚明确“材料核验与人工复核辅助，不生成审批决定”。

- [ ] **Step 4: 添加 API 与浏览器断言。**

  对每个场景提交选择后断言 `capabilityIds`、`packBindings`、`profileRef` 与生成 Solution 完全匹配；把一个个人 Pack Binding 与企业 capability 混合时，预检必须返回 `MODULE_SELECTION_MISMATCH` 或 `COMPONENT_SELECTION_MISMATCH`。浏览器脚本依次点击信贷授信和两个场景，验证卡片、固定能力数、Java runtime 限制和构建阻断提示。

- [ ] **Step 5: 运行 Builder 回归。**

  Run:
  ```bash
  cd '/Users/apple/Documents/工作资料/代码助手/agent-store-builder-dev-wo-fork-finance 2/community/finance/builder'
  uv run --package finance-solution-builder pytest finance-solution-builder/tests/test_template_modules.py finance-solution-builder/tests/test_unified_catalog.py -q
  node finance-solution-builder/engineering/test_unified_creation.mjs http://127.0.0.1:18160
  ```

  Expected: 两个场景各自产生唯一固定选择；旧手机银行场景不变；页面不展示自动审批、写回或 TASK。

- [ ] **Step 6: 提交 Builder 场景。**

  ```bash
  git add 'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/src/finance_solution_builder' \
    'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/browser/workbench.js' \
    'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/tests/test_template_modules.py' \
    'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/tests/test_unified_catalog.py' \
    'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/engineering/test_unified_creation.mjs'
  git commit -m 'feat: expose smart audit credit scenarios'
  ```

### Task 6: 交付真实场景迁入开发指导与最终证据

**Files:**
- Modify: `finance-agent-harness-main 2/docs/public/developer-guide.md`
- Create: `finance-agent-harness-main 2/docs/public/smart-audit-scenario-migration.md`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/docs/business-domain-catalog-v01.md`
- Modify: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/docs/developer-guide-entry-v01.md`
- Create: `agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/engineering/verify_smart_audit_runtime.py`

- [ ] **Step 1: 写公开迁入指南。**

  指南按以下固定流程给出每一步的输入、代码位置、拒绝条件和证据：

  ```text
  业务边界和人工复核 → 材料/事实合同 → Domain Pack 与固定规则
  → Capability Pack 与最小 scope → Provider/Host 可信 Context
  → Asset Catalog/Assembly → Solution → Builder Task Blueprint
  → 单元/目录/API/浏览器/合成运行验证 → 发布版本与运行记录
  ```

  明确“页面可选”“构建通过”“合成运行通过”“真实银行生产验收”是四个不同状态。

- [ ] **Step 2: 生成受控运行验证器。**

  验证器使用合成 OCR 文本或 JSON 材料，分别启动两个 Candidate，核对只调用该场景能力、返回证据/缺项/人工复核字段并拒绝 TASK、跨场景能力和缺少审核授权。它不得读取真实客户文件、调用线上地址或输出材料原文。

- [ ] **Step 3: 执行最终验证。**

  Run:
  ```bash
  cd '/Users/apple/Documents/工作资料/代码助手/finance-agent-harness-main 2'
  mvn test
  cd '/Users/apple/Documents/工作资料/代码助手/agent-store-builder-dev-wo-fork-finance 2/community/finance/builder'
  uv run --package finance-solution-builder pytest finance-solution-builder/tests -q
  ```

  如已配置受控本地模型和内部审核 Host，再运行：

  ```bash
  uv run --package finance-solution-builder python finance-solution-builder/engineering/verify_smart_audit_runtime.py \
    --harness-repo '/Users/apple/Documents/工作资料/代码助手/finance-agent-harness-main 2' \
    --output finance-solution-builder/build/smart-audit-runtime-evidence
  ```

  Expected: 没有受控模型或审核身份时记录明确阻断；仅在完整本地配置下记录合成材料运行成功；两种情况均不宣称真实银行审批验收。

- [ ] **Step 4: 提交文档与验证器。**

  ```bash
  git add 'finance-agent-harness-main 2/docs/public' \
    'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/docs' \
    'agent-store-builder-dev-wo-fork-finance 2/community/finance/builder/finance-solution-builder/engineering/verify_smart_audit_runtime.py'
  git commit -m 'docs: add smart audit migration guide'
  ```

## 自检

- **需求覆盖：**Task 2 迁入两套真实审核资产；Task 3 补齐目录/装配契约；Task 4 接通可信内部审核 Host；Task 5 将其沉淀为信贷授信下两个任务场景；Task 6 交付迁入指南和端到端证据。
- **边界覆盖：**所有任务都只允许 `QUERY`、固定 Pack、固定 Profile、受管 scope、合成材料和人工复核；没有步骤扩展到审批、授信写回或真实生产数据。
- **兼容性覆盖：**Task 5 的回归包含现有手机银行场景；目录投影不因新卡片而把未登记资产变成可构建能力。
