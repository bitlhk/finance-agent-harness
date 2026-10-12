# Personal Loan Dynamic Subagents Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the desktop personal-loan demo execute all four installed audit capabilities from one complete test-material bundle and render their real-time execution as a Codex-style Subagents list.

**Architecture:** The material schema and controlled test-policy fixture describe all required evidence and versioned parameters. The runtime emits a normalized `finance.subagents` lifecycle event before, during, and after each real capability execution. The AG-UI browser client renders only those events into compact running/completed groups and keeps verbose evidence collapsed.

**Tech Stack:** Python 3, Java/OpenJiuwen candidate runtime, AG-UI SSE, vanilla ES modules, Node assertions, pytest, Playwright CLI.

---

### Task 1: Define complete material and policy fixtures

**Files:**
- Modify: `browser/smart-audit-bundle.js`
- Modify: `examples/agent-experience/demo-materials/personal-loan-demo.json`
- Create: `examples/agent-experience/demo-materials/personal-loan-test-policy.v1.json`
- Modify: `engineering/test_smart_audit_experience.mjs`

- [ ] **Step 1: Write failing assertions for complete material categories and controlled policy provenance.**

```js
assert.deepEqual(personal.materials.map(({ category }) => category), [
  "身份证", "户口本", "结婚证", "贷款申请表", "收入证明", "流水明细",
  "个人所得税完税证明", "社会保险参保证明", "不动产权证书",
  "不动产信息查询结果", "贷款结清证明",
]);
assert.equal(personal.policy.policyId, "PPL-DEMO-2026-01");
assert.equal(personal.policy.approvalStatus, "CONTROLLED_TEST_APPROVED");
assert.equal(personal.policy.productionUse, false);
```

- [ ] **Step 2: Run the test before implementation.**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: fail because the four-material demo has no policy and no income, asset, or repayment evidence.

- [ ] **Step 3: Add the 11 allowed personal-loan categories, complete fixture data, and a versioned controlled policy.**

```js
export const MATERIAL_REQUIREMENTS = {
  "personal-loan-audit": ["身份证", "户口本", "结婚证", "贷款申请表", "收入证明", "流水明细", "个人所得税完税证明", "社会保险参保证明", "不动产权证书", "不动产信息查询结果", "贷款结清证明"],
};
```

Policy validation must require ID, version, effective period, approval status, applicable product, parameter object, and `productionUse: false`. `buildAuditPrompt` must pass the controlled policy with the material bundle.

- [ ] **Step 4: Re-run the fixture test.**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: `smart-audit-experience fixtures: PASS`.

### Task 2: Emit observed Subagent lifecycle events

**Files:**
- Modify: `src/finance_solution_builder/experience.py`
- Modify: `tests/test_experience_stream.py`

- [ ] **Step 1: Write a failing test for event ordering and all four terminal capability results.**

```python
subagent_events = [event["value"] for event in events if event.get("name") == "finance.subagents"]
assert subagent_events[0]["members"][0]["status"] == "RUNNING"
completed = subagent_events[-1]["members"]
assert {m["capabilityId"] for m in completed if m.get("capabilityId")} == {
    "smartaudit.personal.borrower-qualification.query",
    "smartaudit.personal.income-verification.query",
    "smartaudit.personal.asset-liability.query",
    "smartaudit.personal.repayment-capacity.query",
}
assert all(m["status"] == "COMPLETED" for m in completed)
```

- [ ] **Step 2: Run the focused test.**

Run: `uv run --package finance-solution-builder --extra test python -m pytest tests/test_experience_stream.py -q`

Expected: fail because `finance.subagents` is not emitted.

- [ ] **Step 3: Add one backend event-schema helper and emit only observed states.**

Each member must contain `id`, `name`, `capabilityId`, `status`, `summary`, `startedAt`, `finishedAt`, and `elapsedMs`. A capability is completed only if it is present in the runtime execution observation. Missing or rejected calls remain `FAILED` or `NEEDS_HUMAN_REVIEW` with the returned reason.

- [ ] **Step 4: Re-run the focused test.**

Run: `uv run --package finance-solution-builder --extra test python -m pytest tests/test_experience_stream.py -q`

Expected: pass with running and terminal lifecycle events.

### Task 3: Execute the complete personal-loan capability set

**Files:**
- Modify: `templates/local-readonly-service/src/main/java/io/github/bitlhk/finance/builder/local/NativeConversation.java`
- Modify: `templates/local-readonly-service/src/main/java/io/github/bitlhk/finance/builder/local/AgUiTransport.java`
- Modify: `templates/local-readonly-service/src/test/java/io/github/bitlhk/finance/builder/local/AgUiTransportTest.java`

- [ ] **Step 1: Write a failing Java test for four observed capability receipts.**

```java
var team = AgUiTransport.reviewTeam(PERSONAL_CAPABILITIES, toolResultsForAllFourCapabilities());
assertEquals(5, team.size());
assertTrue(team.stream().skip(1).allMatch(member -> "COMPLETED".equals(member.get("status"))));
```

- [ ] **Step 2: Run the module test before implementation.**

Run: `mvn -q -f templates/local-readonly-service/pom.xml -Dtest=AgUiTransportTest test`

Expected: failure when only the model-selected capability is observed.

- [ ] **Step 3: Add governed fan-out for installed personal-audit capabilities.**

Use the existing bound provider grant and identical uploaded material/policy input for every specialist. Execute borrower, income, and asset-liability before repayment capacity. Preserve each receipt/error under `executionObservation`; never make an external write or an approval decision.

- [ ] **Step 4: Re-run the Java test.**

Run: `mvn -q -f templates/local-readonly-service/pom.xml -Dtest=AgUiTransportTest test`

Expected: pass with four observed personal-audit receipts.

### Task 4: Render a Codex-style desktop Subagents list

**Files:**
- Modify: `browser/experience.js`
- Modify: `examples/agent-experience/style.css`
- Modify: `engineering/test_smart_audit_experience.mjs`

- [ ] **Step 1: Write a failing UI assertion for grouped task markup.**

```js
assert.match(renderedHtml, /已开启 · 1/);
assert.match(renderedHtml, /完成 · 4/);
assert.match(renderedHtml, /收入交叉验证专员/);
assert.match(renderedHtml, /data-subagent="smartaudit\.personal\.income-verification\.query"/);
```

- [ ] **Step 2: Run the UI fixture test before implementation.**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: fail because the current UI only renders review-member cards.

- [ ] **Step 3: Replace desktop review cards with event-backed compact task groups.**

Render `已开启` and `完成` groups. Running tasks show the backend summary and elapsed time from actual `startedAt`; terminal tasks show returned `elapsedMs`. Clicking one row expands material, policy, evidence, conclusion, and human-review details. Long receipts remain collapsed. Non-desktop terminals retain the compact audit rail.

- [ ] **Step 4: Re-run the UI fixture test.**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: `smart-audit-experience fixtures: PASS`.

### Task 5: Verify the complete desktop journey

**Files:**
- Create: `output/playwright/personal-loan-subagents-e2e.png` (verification artifact; do not commit)

- [ ] **Step 1: Build the UI.**

Run: `npm run build:ui`

Expected: successful UI build.

- [ ] **Step 2: In Playwright, create a `电脑端` personal-loan Agent, start it, and upload the 11-material bundle.**

Expected: the UI reports 11 materials and enables the audit button.

- [ ] **Step 3: Trigger audit and observe actual lifecycle transitions.**

Expected: tasks enter running state, all four specialists reach an observed terminal state, and compilation begins only after the specialist tasks are terminal. The answer references the controlled policy and preserves human-review boundaries.

- [ ] **Step 4: Capture the desktop screenshot and execute regression checks.**

Run:

```bash
uv run --package finance-solution-builder --extra test python -m pytest tests/test_experience_stream.py tests/test_ag_ui_transport.py tests/test_sse_http_charset.py -q
npm run build:ui && node engineering/test_smart_audit_experience.mjs
git -C '/Users/apple/Documents/工作资料' diff --check
```

Expected: all targeted Python tests, UI fixtures, build, and whitespace checks pass.

## Plan self-review

- Tasks 1–2 cover the complete one-upload bundle and policy governance.
- Task 3 ensures the Subagents represent actual capability execution.
- Task 4 implements the requested Codex-style list without fake progress.
- Task 5 validates the desktop Web journey.
- This workspace remains uncommitted because the user requested direct local changes without a commit.
