# Studio usability round 2

Scope: Builder UI and generated read-only experience templates only. No changes to
Runtime, Harness contracts, authorization, model credentials, or bank adapters.

## Behavior

- Model picker enables only connections marked CONFIGURED_UNTESTED. Metadata-only,
  disabled, missing-configuration and unsupported connections remain unavailable.
  Configured does not assert a successful inference. Existing offline-build API
  semantics remain unchanged; start still checks the frozen connection.
- My Agents supports background-card click, native checkboxes, keyboard selection,
  select-all eligible records and a destructive confirmation dialog. Batch deletion
  sequentially uses the existing owner-checked delete API. It never stops a running
  instance. Partial failures retain their records/selection and report both counts.
- Message views preserve their reading position across DOM replacement. Sending a
  new question follows the latest content; receiving a reply follows the bottom
  only if the user has not scrolled back to read history.
- Generated experiences offer Home (end/clear current session) and, when opened
  through the workbench, Workbench (end/clear, then navigate to `/`). Standalone
  artifacts do not invent a workbench URL. Existing ATM service-home behavior stays.
- Composer focus uses a quiet outer border and light accent shadow, following EA's
  container-focus pattern; the textarea has no separate dark outline. Keyboard
  focus remains visible on buttons and selectable cards.

## Compatibility and verification

Existing generated artifacts are immutable. Updating Studio changes the picker and
record management immediately, but experience changes require a newly generated
version using "修改配置并重新构建". Do not replace assets inside an old locked candidate.

Run from this module (install existing development dependencies first):

```sh
npm ci
npm run build:ui
PYTHONPATH=src:../base/src python -m pytest tests -q
PYTHONPATH=src:../base/src python engineering/verify_usability_ui.py \
  --harness /absolute/path/to/finance-agent-harness \
  --output /absolute/path/to/ui-evidence \
  --chromium /absolute/path/to/chromium
```

The browser regression uses actual compiled browser bundles with explicitly
synthetic HTTP/SSE responses. It checks model gating, batch selection/partial
failure, all three terminal layouts, scroll, navigation and focus. It does not call
a real model or bank, delete real Agent records, or prove source authorization.
Backend deletion and authorization regressions remain in the Python suite.
