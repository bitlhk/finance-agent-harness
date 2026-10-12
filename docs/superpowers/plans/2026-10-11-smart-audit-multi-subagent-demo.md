# Smart Audit Multi-Subagent Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a concise, one-upload Smart Audit demonstration that uses the uploaded test bundle, visibly coordinates capability-backed review Subagents, and streams the governed review result into a ChatGPT-like working-paper UI.

**Architecture:** The browser owns local parsing of a single versioned TEXT/JSON bundle and derives a bounded review prompt from that uploaded content.  The Java host keeps authorization and tool invocation ownership, emits observable stage/Subagent events and splits only the already-authorized result into SSE deltas.  The Harness provider remains the authority for material categories, rules, evidence and the non-decision boundary.

**Tech Stack:** Vanilla browser ES modules bundled by esbuild, AG-UI SSE, Java `HttpServer`, OpenJiuwen, Finance Harness Smart Audit provider, pytest/JUnit, Playwright CLI.

---

## File structure

- Create: `examples/agent-experience/demo-materials/personal-loan-demo.json` — fictional, existing-fixture-derived personal material bundle.
- Create: `examples/agent-experience/demo-materials/enterprise-credit-demo.json` — fictional, existing-fixture-derived enterprise material bundle.
- Modify: `browser/experience.js` — one-file upload state, strict client validation, multi-Subagent display, progressive assistant bubble and evidence disclosure.
- Modify: `examples/agent-experience/style.css` — case rail, review-team cards, progress timeline, disclosure and responsive styles.
- Modify: `examples/agent-experience/icons.svg` through `browser/build-ui.mjs` — only add icons needed by the new controls.
- Modify: `templates/local-readonly-service/src/main/java/io/github/bitlhk/finance/builder/local/AgUiTransport.java` — bounded SSE text chunks and actual tool-result-to-review-team events.
- Create: `templates/local-readonly-service/src/test/java/io/github/bitlhk/finance/builder/local/AgUiTransportTest.java` — event ordering, chunk and review-team mapping unit coverage.
- Modify: `tests/test_ag_ui_transport.py` — assert portable AG-UI chunk stream semantics remain equivalent.
- Create: `engineering/test_smart_audit_experience.mjs` — browser-level contract checks for upload, collapsed evidence and progressive rendering.
- Modify: `docs/public/smart-audit-scenario-migration.md` — concise demo/operator instructions and non-decision warning.

### Task 1: Create safe, uploadable demo bundles

**Files:**
- Create: `examples/agent-experience/demo-materials/personal-loan-demo.json`
- Create: `examples/agent-experience/demo-materials/enterprise-credit-demo.json`
- Test: `engineering/test_smart_audit_experience.mjs`

- [ ] **Step 1: Write the browser fixture assertion first**

```js
const personal = await readJson("examples/agent-experience/demo-materials/personal-loan-demo.json");
assert.equal(personal.schemaVersion, "smart-audit-demo-materials.v1");
assert.equal(personal.scenario, "personal-loan-audit");
assert.deepEqual(personal.materials.map(({ category }) => category),
  ["身份证", "户口本", "结婚证", "贷款申请表"]);
```

- [ ] **Step 2: Run the assertion and confirm it fails because the files do not yet exist**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: failure reporting the missing personal demo bundle.

- [ ] **Step 3: Add the two versioned fictional bundles**

Use this envelope exactly; retain only synthetic, non-identifying values:

```json
{
  "schemaVersion": "smart-audit-demo-materials.v1",
  "scenario": "personal-loan-audit",
  "materials": [
    {"category":"身份证","declaredFormat":"TEXT","content":"测试借款人：张三；出生日期：1990-01-15；证件状态：示例有效。"},
    {"category":"户口本","declaredFormat":"TEXT","content":"测试借款人张三；与身份证姓名、出生日期一致。"},
    {"category":"结婚证","declaredFormat":"TEXT","content":"测试婚姻登记材料；配偶信息待人工核验。"},
    {"category":"贷款申请表","declaredFormat":"TEXT","content":"测试申请：个人经营贷款；用途为流动资金；金额和期限由人工复核。"}
  ]
}
```

The enterprise bundle uses `scenario: "enterprise-credit-audit"`, contains an `资产负债表` JSON material with a balanced synthetic equation, and includes only categories accepted by the enterprise capability pack.

- [ ] **Step 4: Run the fixture assertion again**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: bundle-schema checks pass; later UI checks may still fail until Task 4.

- [ ] **Step 5: Commit the isolated fixture change when the target repository has its own Git root**

```bash
git add examples/agent-experience/demo-materials
git commit -m "feat: add smart audit demo material bundles"
```

Do not commit through a parent repository that treats this project as an untracked directory.

### Task 2: Preserve actual review-team execution in the Java SSE boundary

**Files:**
- Modify: `templates/local-readonly-service/src/main/java/io/github/bitlhk/finance/builder/local/AgUiTransport.java`
- Create: `templates/local-readonly-service/src/test/java/io/github/bitlhk/finance/builder/local/AgUiTransportTest.java`

- [ ] **Step 1: Add failing Java tests for chunking and capability-backed review members**

```java
@Test public void chunksPreserveTheAuthorizedAnswerWithoutEmptyDeltas() {
    var chunks = AgUiTransport.textChunks("材料已收齐，进入人工复核。", 8);
    assertEquals("材料已收齐，进入人工复核。", String.join("", chunks));
    assertTrue(chunks.size() > 1);
    assertTrue(chunks.stream().noneMatch(String::isBlank));
}

@Test public void reviewTeamMarksOnlyInstalledAuditCapabilities() {
    var team = AgUiTransport.reviewTeam(Set.of("smartaudit.personal.borrower-qualification.query"), List.of());
    assertEquals("case-orchestrator", team.getFirst().get("id"));
    assertEquals("READY", team.get(1).get("status"));
    assertFalse(team.stream().anyMatch(member -> "enterprise-financial-report".equals(member.get("id"))));
}
```

- [ ] **Step 2: Run the test and confirm compilation fails until helper methods are added**

Run: `mvn -q -f templates/local-readonly-service/pom.xml -Dtest=AgUiTransportTest test`

Expected: compilation failure because `textChunks` and `reviewTeam` do not exist.

- [ ] **Step 3: Implement bounded, observable event helpers**

Add package-visible helpers that:

```java
static List<String> textChunks(String answer, int maxChars) {
    if (answer == null || answer.isBlank()) return List.of();
    var chunks = new ArrayList<String>();
    for (int offset = 0; offset < answer.length(); offset += maxChars)
        chunks.add(answer.substring(offset, Math.min(answer.length(), offset + maxChars)));
    return List.copyOf(chunks);
}
```

Create `reviewTeam(Set<String> capabilities, List<Object> toolResults)` from the admitted Smart Audit capability ids only.  Include a `case-orchestrator` entry and specialist entries whose status is `READY`, `RUNNING`, `COMPLETED` or `NOT_INVOKED` based solely on observed capability/tool output.  Do not add names supplied by the browser or model.

In `run`, preserve authorization before any financial text; emit `finance.reviewTeam` after the tool run; emit one `TEXT_MESSAGE_CONTENT` event per `textChunks(answer, 120)` entry; then emit `finance.evidence` and `RUN_FINISHED`.  Preserve terminal order:

```text
RUN_STARTED → STEP_STARTED → finance.reviewTeam → TEXT_MESSAGE_START
→ TEXT_MESSAGE_CONTENT+ → TEXT_MESSAGE_END → finance.evidence → RUN_FINISHED
```

- [ ] **Step 4: Run focused Java tests**

Run: `mvn -q -f templates/local-readonly-service/pom.xml -Dtest=AgUiTransportTest test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit the service change in its actual repository**

```bash
git add templates/local-readonly-service/src/main/java/io/github/bitlhk/finance/builder/local/AgUiTransport.java \
  templates/local-readonly-service/src/test/java/io/github/bitlhk/finance/builder/local/AgUiTransportTest.java
git commit -m "feat: stream governed smart audit review events"
```

### Task 3: Keep the portable transport contract in sync

**Files:**
- Modify: `src/finance_solution_builder/ag_ui_transport.py`
- Modify: `tests/test_ag_ui_transport.py`

- [ ] **Step 1: Extend the existing transport test with the expected progressive contract**

```python
def test_authorized_answer_is_split_into_ordered_nonempty_deltas():
    _, parsed = events(FakeApi())
    deltas = [event["delta"] for event in parsed if event["type"] == "TEXT_MESSAGE_CONTENT"]
    assert len(deltas) > 1
    assert "".join(deltas) == "synthetic-12345.67"
    assert all(delta for delta in deltas)
    assert next(event for event in parsed if event["type"] == "CUSTOM")["name"] == "finance.reviewTeam"
```

- [ ] **Step 2: Run the focused Python test and confirm it fails on the single-delta implementation**

Run: `uv run --package finance-solution-builder pytest tests/test_ag_ui_transport.py -q`

Expected: failure because the old implementation emits one delta and no review-team custom event.

- [ ] **Step 3: Add a shared bounded splitter and review-team payload**

Implement a private `_chunks(text, size=120)` helper that returns nonempty ordered slices.  In `stream_run`, send `CustomEvent(name="finance.reviewTeam", value={"members": [...]})` using only server-known selected capability data, then create one `TextMessageContentEvent` per chunk.  Continue authorizing before the first chunk and protect all financial payloads on revocation.

- [ ] **Step 4: Run the complete Python transport suite**

Run: `uv run --package finance-solution-builder pytest tests/test_ag_ui_transport.py tests/test_sse_http_charset.py -q`

Expected: all tests pass.

- [ ] **Step 5: Commit the portable transport contract**

```bash
git add src/finance_solution_builder/ag_ui_transport.py tests/test_ag_ui_transport.py
git commit -m "feat: expose smart audit review team over ag-ui"
```

### Task 4: Implement the one-upload, ChatGPT-like review experience

**Files:**
- Modify: `browser/experience.js`
- Modify: `examples/agent-experience/style.css`
- Modify: `browser/build-ui.mjs`
- Modify: `examples/agent-experience/icons.svg`
- Modify: `examples/agent-experience/app.js` (generated by the build command; do not hand-edit)

- [ ] **Step 1: Add browser validation tests before UI code**

The test script must import/exercise exported pure helpers from `browser/experience.js` or a new `browser/smart-audit-bundle.js`:

```js
assert.deepEqual(validateBundle(personal, "personal-loan-audit"), {ok: true, missing: []});
assert.equal(validateBundle({...personal, scenario: "enterprise-credit-audit"}, "personal-loan-audit").ok, false);
assert.equal(validateBundle({...personal, materials: personal.materials.slice(0, 3)}, "personal-loan-audit").missing[0], "贷款申请表");
assert.equal(buildAuditPrompt(personal).includes(personal.materials[0].content), true);
```

- [ ] **Step 2: Run the browser validation test and confirm it fails**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: failure because `validateBundle` and `buildAuditPrompt` do not yet exist.

- [ ] **Step 3: Implement bundle ownership and scenario binding**

Add a dedicated small module, `browser/smart-audit-bundle.js`, with:

```js
export const MATERIAL_REQUIREMENTS = {
  "personal-loan-audit": ["身份证", "户口本", "结婚证", "贷款申请表"],
  "enterprise-credit-audit": ["资产负债表"],
};
export function scenarioFromCapabilities(capabilities) { /* only all-personal or all-enterprise prefixes */ }
export function validateBundle(bundle, scenario) { /* schema, scenario, categories, format TEXT|JSON, nonempty unique content, size <= 3000 */ }
export function buildAuditPrompt(bundle) { /* fixed Chinese review instruction plus JSON.stringify(bundle.materials) */ }
```

In `experience.js`, parse exactly one selected `.json` file with `File.text()`, save only the parsed bundle and filename in page memory, and clear it on session end.  Display **下载演示材料** links but do not auto-run them.  Enable **发起智能审核** only after validation.  Pass `buildAuditPrompt(bundle)` to the existing `chat` function so the governed tool receives the uploaded content, not a static sample prompt.

- [ ] **Step 4: Render the review-team and progressive conversation honestly**

Add local state `auditBundle`, `reviewTeam`, and `streamingMessage`.  On `finance.reviewTeam`, update only the capability-backed rail.  On `TEXT_MESSAGE_START`, append an empty assistant draft; on every `TEXT_MESSAGE_CONTENT`, append `event.delta`; on `TEXT_MESSAGE_END`, mark that bubble complete.  Do not show invented reasoning.

Render this semantic structure:

```html
<aside class="audit-case-rail">
  <ol class="audit-stages"><li class="complete">材料收件</li><li class="active">智能审核</li></ol>
  <section class="review-team"><h2>审核协作组</h2><!-- observed members --></section>
</aside>
<article class="assistant-message">
  <div class="review-summary"></div>
  <details><summary>查看已上传材料</summary></details>
  <details><summary>查看规则与证据</summary></details>
</article>
```

Make both `<details>` controls closed by default; raw content appears only in an explicit expanded block.  Include the fixed notice `仅为审核工作底稿，须人工复核，不构成授信审批或放款结论。`.

- [ ] **Step 5: Add style and rebuild generated UI**

Use CSS grid for the desktop case rail and one-column mobile fallback.  Give pending/active/completed Subagent cards distinct non-semantic colors and status text.  Use an accessible `role="status"` on the live review line and retain keyboard operation for upload and disclosures.

Run: `npm run build:ui`

Expected: regenerated `examples/agent-experience/app.js` and icon sprite with no build errors.

- [ ] **Step 6: Run browser unit checks**

Run: `node engineering/test_smart_audit_experience.mjs`

Expected: all material validation and prompt-source checks pass.

- [ ] **Step 7: Commit the experience change**

```bash
git add browser/smart-audit-bundle.js browser/experience.js browser/build-ui.mjs \
  examples/agent-experience/style.css examples/agent-experience/icons.svg \
  examples/agent-experience/app.js engineering/test_smart_audit_experience.mjs
git commit -m "feat: add multi-subagent smart audit experience"
```

### Task 5: Document and prove both governed demo paths end to end

**Files:**
- Modify: `docs/public/smart-audit-scenario-migration.md`
- Test: `engineering/verify_native_agui.py`
- Test: Playwright against the locally launched Builder experience

- [ ] **Step 1: Extend native AG-UI verification expectations**

Add assertions after the first native event sequence:

```python
content = [event["delta"] for event in events if event["type"] == "TEXT_MESSAGE_CONTENT"]
check("native_progressive_deltas", len(content) > 1 and all(content))
team = next(event for event in events if event["type"] == "CUSTOM" and event["name"] == "finance.reviewTeam")
check("native_capability_backed_review_team", team["value"]["members"][0]["id"] == "case-orchestrator")
```

- [ ] **Step 2: Run verification before documentation updates**

Run: `uv run --package finance-solution-builder python engineering/verify_native_agui.py`

Expected: all native event checks pass after Tasks 2–3.

- [ ] **Step 3: Document the concise operator demo**

Add a short section to `docs/public/smart-audit-scenario-migration.md`:

```markdown
1. Select Personal loan audit or Enterprise credit audit.
2. Download one fictional demo material bundle and upload that one file.
3. Confirm the material checklist, then select 发起智能审核.
4. Read the visible working-paper summary; expand materials/evidence only when needed.
5. Treat every result as a manual-review input, never as a credit decision.
```

- [ ] **Step 4: Build a fresh candidate and execute two Playwright journeys**

Build both scenario candidates with the configured model connection.  For each candidate use the Playwright CLI to:

```text
open experience → start session → upload the matching demo JSON once
→ verify checklist → start review → observe a live assistant draft
→ wait for final manual-review notice → expand each disclosure once
→ assert only relevant review-team members are shown
```

Run: `bash /Users/apple/.codex/skills/playwright/scripts/playwright_cli.sh --help`

Then run the existing project launch/build command for each scenario and save screenshots plus the event transcript under the candidate's verification directory.  Expected: both builds are `BUILT`, both browser runs complete, neither result contains approval/disbursement wording.

- [ ] **Step 5: Run regression suites**

Run:

```bash
uv run --package finance-solution-builder pytest tests/test_ag_ui_transport.py tests/test_sse_http_charset.py -q
mvn -q -pl providers/smart-audit -am test
```

Expected: all selected Python and Smart Audit provider tests pass.

- [ ] **Step 6: Commit documentation and verification updates only in the correct repositories**

```bash
git add docs/public/smart-audit-scenario-migration.md engineering/verify_native_agui.py
git commit -m "docs: explain smart audit one-upload demo"
```

## Plan self-review

- Spec coverage: Task 1 makes the existing synthetic fixture material uploadable once; Task 4 uses that upload as review input; Tasks 2–3 expose actual Subagent/capability activity and progressive events; Task 5 validates both banking flows with Playwright.
- Safety coverage: every task retains provider category checks, host authorization, no hidden reasoning, collapsed raw material/evidence, and non-decision language.
- Placeholder scan: all creation/modification paths, commands, expected outcomes and the core data/event shapes are explicit.
- Type consistency: `smart-audit-demo-materials.v1`, scenario IDs, `finance.reviewTeam`, `members`, `validateBundle`, and `buildAuditPrompt` use the same names throughout.
