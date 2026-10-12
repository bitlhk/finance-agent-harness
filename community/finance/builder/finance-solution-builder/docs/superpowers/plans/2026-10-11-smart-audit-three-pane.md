# Smart Audit 三栏审核工作台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Smart Audit 以案件资料、流式 AI 审核对话和可展开 Subagents 三栏展示真实受控审核过程。

**Architecture:** 保留 AG-UI 事件和真实模型调用。`experience.js` 将审核材料、阶段、Agent 映射和结果摘要渲染为业务化组件；样式覆盖桌面三栏网格并为窄屏保留抽屉。Node 断言守护关键结构，Playwright 验证真实审核路径。

**Tech Stack:** Vanilla JavaScript、CSS、Node assert、Playwright CLI、AG-UI。

---

### Task 1: 锁定审核 UI 的可验证契约

**Files:**
- Modify: `engineering/test_smart_audit_experience.mjs`
- Test: `engineering/test_smart_audit_experience.mjs`

- [ ] **Step 1: 编写失败断言**

为 `experience.js` 增加断言，要求存在 `audit-material-summary`、`audit-live-brief`、`subagent-detail` 中的“材料数”“规则数”“耗时”“证据”，并要求样式存在 `.audit-three-pane` 和三栏 `grid-template-columns`。

- [ ] **Step 2: 运行测试并确认失败**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: assertion failure because the new semantic hooks are absent.

- [ ] **Step 3: 实现通过测试的最小 UI 契约**

在 `browser/experience.js` 增加语义化的材料摘要、审核进行态和 Agent 详情字段；在样式中加入三栏选择器。

- [ ] **Step 4: 重跑测试**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: `smart-audit-experience fixtures: PASS`.

### Task 2: 将审核状态转成业务化三栏数据

**Files:**
- Modify: `browser/experience.js`
- Test: `engineering/test_smart_audit_experience.mjs`

- [ ] **Step 1: 为状态转换添加失败断言**

断言源文件包含“系统核验已完成”“待人工复核交接”和“正在分派审核任务”，防止把工具回执状态直接作为审批结论。

- [ ] **Step 2: 运行测试并确认失败**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: assertion failure for at least one新的业务状态文字。

- [ ] **Step 3: 实现最小状态映射**

增加材料类别到业务材料组的映射、Agent 的材料数/规则数/依赖关系映射、以及完成后“待人工复核交接”的状态。将详细原文置入 `<details>`。

- [ ] **Step 4: 重跑测试**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: PASS.

### Task 3: 实现响应式三栏布局和信息层级

**Files:**
- Modify: `examples/agent-experience/style.css`
- Test: `engineering/test_smart_audit_experience.mjs`

- [ ] **Step 1: 编写失败布局断言**

要求 CSS 为 `.audit-three-pane` 定义 `300px minmax(0, 1fr) 360px`，并定义窄屏抽屉回退。

- [ ] **Step 2: 运行测试并确认失败**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: assertion failure because selector absent.

- [ ] **Step 3: 实现 CSS**

让左栏和右栏占固定轨道、中栏滚动，消除左栏重复 Agent 卡片；以状态色和彩色时间线强化运行态，原始证据维持折叠。

- [ ] **Step 4: 重跑测试**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: PASS.

### Task 4: 浏览器端到端验收

**Files:**
- Test artifact: `.playwright-cli/*.png`

- [ ] **Step 1: 构建并启动候选体验**

Run the existing Smart Audit build command for the selected candidate and open its local workbench URL.

- [ ] **Step 2: 用 Playwright 执行真实交互**

上传 `examples/agent-experience/demo-materials/personal-loan-demo.json`，点击“发起智能审核”，在运行中截屏，等待真实流式调用完成，展开“收入核验专员”。

- [ ] **Step 3: 验证最终状态并截屏**

在 1920px 截图中确认三栏同屏；核实 `系统核验已完成`、`待人工复核交接`、Agent 详情中的材料数/规则数/耗时/证据入口均可见。

- [ ] **Step 4: 运行回归验证**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: `smart-audit-experience fixtures: PASS`.
