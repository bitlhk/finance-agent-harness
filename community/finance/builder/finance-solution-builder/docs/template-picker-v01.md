# Compact Template Picker V0.1

This is a frontend-only correction to the versioned template/module creation page.
It changes neither Backend selection contracts, capabilities, Profile/authorization nor runtime support.

## Behavior

- More Templates is a separate header command, not a scenario card.
- The first three scenarios stay visible. A selected additional scenario becomes a fourth ordinary radio card.
- Re-selecting the current scenario is a no-op: module removals, models, presentation and edit provenance remain.
- The picker has its own scoped styles: 420px maximum desktop width, content-sized height (288px for three options),
  64px rows, 16px viewport margins, and internal overflow for a longer list or short screen.
- The popup is a listbox: arrows/Home/End move focus only; Enter/Space/click commit and close. Escape, Close and
  the backdrop cancel without changing the scenario. Main-page radios retain selection-following-arrow behavior.
- Commit focuses the selected main card; cancel focuses More Templates. Dialog close is queued, so restoration
  checks the current page/Agent, connected opener and any newly opened dialog. Model-save rerender can restore
  the replacement button; a stale close must not erase a newer dialog's draft or steal focus.
- Pending scenarios still contain no invented modules and cannot build. Python financial integration remains pending.

## Verification

From this module, with the normal local Workbench and Playwright configured:

```bash
npm run build:ui
node engineering/verify_template_picker.mjs <workbench-url> <new-evidence-directory>
node engineering/verify_template_modules_ui.mjs <workbench-url> <new-metadata-evidence>
python -m pytest tests ../base/tests -q
```

Authenticated browser runs read STUDIO_TEST_USER / STUDIO_TEST_PASSWORD from the process environment.
The picker verifier does not submit builds, invoke models or mutate stored Agents. Its existing-record editing
check requires a record belonging to that operator and is explicitly skipped when none exists.
The long-list check clones rows in the browser DOM solely to test layout overflow; it does not add catalog entries.
Screenshots cover open desktop/mobile pickers and the selected extra scenario at 1440/1024/390/320px.
The touch context additionally verifies tap selection/reselection/backdrop cancel. No model quality conclusion follows.

The previous implementation fails compact sizing, real selected-card semantics, idempotent module selection,
backdrop cancel, focus-after-model-save and viewport margin checks. Preserve baseline failures separately from
the successful repaired run. Activation uses the established release/rollback process after verifying no active
build or generated Agent; historical records and private configuration remain unchanged.
