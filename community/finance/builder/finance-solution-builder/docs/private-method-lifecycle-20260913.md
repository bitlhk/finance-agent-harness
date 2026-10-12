# Private method lifecycle revision

Baseline: finance `04b953eb`, including the upstream generic Python Backend.
This is a Studio extension using the existing private MethodAssets and Jobs
reference lock. Base, Runtime, Harness contracts, Packager, observation collectors,
bank authorization and installed financial capabilities are unchanged.

Implemented:
- New and example editors remain local until explicit save.
- Imported SKILL.md/ZIP uses the same bounded parser for preview and save; original
  bytes are retained only on save. Cancel leaves no empty asset.
- Delete only owner-owned, revision-matching, unreferenced unpublished drafts
  with no trial/preview evidence. Otherwise archive; never delete immutable releases.
- Archive/restore preserve versions and history. New Java/Python builds recheck
  active status under Jobs.lock; old admitted builds keep their frozen release.
- Active trials block lifecycle changes. Archive/restore invalidates the editable
  revision's trial, not historical trial files or release receipts.

## Verification

```bash
npm ci --ignore-scripts
node browser/build-ui.mjs
PYTHONPATH=src:../base/src python -m pytest tests -q
```

After the final frontend wording refinement, full tests: 738 passed, one explicit
live test skipped. The initial run had one failure because an old Python test
mocked a release which had no stored draft; it now creates/publishes an actual
fixture asset, preserving the original snapshot/reference assertions.

The opt-in `engineering/verify_method_lifecycle_browser.py` runs against an
isolated empty loopback workbench. Desktop/mobile completed ten checks: cancel,
example save, archive/restore/delete, edited import after reload, browser errors.
Screenshots were inspected for wrapping/overflow. It makes no model/bank calls;
fixtures do not represent independently operated human acceptance.

## Component details follow-up

The module detail drawer renders the existing server-resolved component IDs,
versions, Capability/Domain Pack references, Provider, Profile and tool declarations.
It shows the currently selected Runtime and private method separately. Default
Domain Pack declarations are not represented as final installation after a method
override; the artifact's eventual Lock remains the final record. No independent
Domain Pack is explicitly marked absent, not replaced by an invented Skill.

The plan summary no longer invents `0.1.0` when a capability version is missing.
Components, capability packs and individual tools are different identities.
The 26 upstream business modules still do not imply 26 installed capabilities.
The private asset editor handles SOP text, not arbitrary Capability/Provider
uploads or executable package management.

Final regression remains 738 passed / one explicit skip, with four additional
component-presentation assertions inside the existing Node state test. Final
browser run has 12 desktop/mobile checks, including the real catalog drawer.

Not included: full Capability/Provider package upload, a general catalog platform,
simulated transfer confirmation UI, real model requalification or Shanghai release.
Those must not be inferred from this private SOP lifecycle change. Keep the prior
release and private data when staging; do not overwrite user assets with test data.

## Business-first cashflow presentation

Keep the original `agent.finance_assistant` module ID and the legacy Chinese name
as a search alias; display the module as cashflow analysis. Its single component
still installs both summary and spending-analysis QUERY tools. The UI exposes one
group checkbox, not two unsupported independent installation choices.

Capability names and descriptions come from the server catalog `capabilities`
metadata, shared by the module drawer and private SOP editor. Component IDs must
not be used to look up individual capability names. Technical details retain tool
IDs, function names, operation, required scopes and versioned asset references;
required scopes remain declarations, never grants.

The source mapper exposes monthly income/expense/net, classifications and six
months of source data. Its explicit `TREND_COMPARABILITY_NOT_VERIFIED` gap means
the business label describes monthly data, not a verified trend judgment. This
change neither modifies mapper semantics nor adds a new business module.

The browser regression now checks 14 desktop/mobile cases, including the single
cashflow choice and collapsed/expanded technical details. Five additional Node
assertions cover metadata-driven labels and technical scope visibility. The first
full run found the old module-name assertion; the compatibility test now checks
the new label, preserved alias and unchanged component ID. No production release,
real model call or human independent acceptance is implied by these checks.
