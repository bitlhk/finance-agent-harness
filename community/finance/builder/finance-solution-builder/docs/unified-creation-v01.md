# Unified Agent creation V0.1

Status: fixed-release qualification and controlled demonstration deployment passed.
See [release verification](unified-creation-release-v01.md) for the exact deployed
sources, independent verification gates and remaining acceptance boundaries.
Base: Builder `f306c8bac6c1a25e8660791579cb2c9493d3d540`.

## Product boundary

There is one creation page. A scenario template preselects a starting set; business
modules group supported packages; versioned Capability Pack/Provider bindings
determine what is actually installed. A blank customer template uses the same
page and build path. Neither a template nor a module grants source permissions.

The existing transfer-service module contains internal transfer. It opens the
existing generated chat and shared confirmation/recovery controls, not a second
transfer application. Bank precheck, confirmation, submission and authoritative
task status remain in the existing Provider and bank interfaces.

## Catalog and compatibility

The new projection derives available package identities and versions from the
managed assembly directory. Presentation metadata supplies Chinese labels and
module organization, not business execution rules. Creation and Business Assets
consume this same projection. Package versions and technical dependencies remain
inspectable; package selection is atomic.

The additive `finance-workbench.selection` version `0.9` records explicit package
bindings and template/module provenance. The server re-resolves identities,
compatibility and the directory revision before queueing work. The interface does
not select a replacement Runtime when a combination is unsupported.

Existing selection versions remain readable. Editing an old Agent creates a new
draft and then a new artifact; it does not mutate its original Lock, permissions,
method version or running instance. Unavailable original package versions must be
reported, not silently substituted.

Python retains its qualified read-only support boundary. TASK is rejected. Private
SOP derivation retains the existing owner-scoped, versioned method workflow;
unqualified method/package combinations cannot be enabled by changing a label.

## Ownership

- Harness supplies package contracts, Provider implementations and execution rules.
- Builder consumes assembly declarations and supplies the presentation taxonomy.
- The generated Host calls the existing Assembler and financial execution bridge.
- Business Assets exposes supported customization, not arbitrary package code upload.

No change to Finance Builder Base or the Runtime SDK is required by this feature.

## Acceptance gates

1. One page, no independent component-mode switch; transfer is selectable inside
   its existing business module.
2. A blank template plus selected packages produces a real version-locked build.
3. Creation and asset detail show identical package/version bindings.
4. Unsupported Runtime selections remain selected and block building explicitly.
5. Unknown, stale, duplicate or inconsistent bindings are rejected before queueing.
6. Existing query selections and private SOP references remain compatible.
7. A newly generated native Agent passes the shared chat/task tests against an
   isolated synthetic bank; old Agent artifacts are unchanged.
8. A new deployed source revision requires fresh observation qualification, a
   rollback release and browser verification. Old qualification is not relabeled.

Real bank authorization, independent human onboarding and broad model quality are
not inferred from controlled browser and simulator tests.

## Local verification (2026-09-14)

- Final Python regression: 919 passed, one explicit skip. JUnit output is retained in
  the release evidence directory; counts are not added to overlapping focused runs.
- Browser helper checks: 10 unit assertions and 59 controlled page checks,
  including 1440/1024/390/320 widths, stale references, partial module selection,
  provider alternatives and an unavailable unified directory.
- New native query build from the page: generated, installed and started; two
  controlled-model turns and revocation passed with the selected wealth context
  package and no unselected balance tool.
- New shared TASK build from the page: 34 checks passed against an isolated
  persistent synthetic bank, including transfer, preferences, explicit confirmation,
  uncertain response lookup and process restart without duplicate submission.

These two native runs used a development snapshot. They are not relabeled as
fixed-commit release qualification, a production transfer or live-model quality
acceptance. The final release must retain its own source revisions, artifact
digests, observation qualification and rollback evidence.

Commands from this project directory (set the documented Python/JDK/Node setup):

```sh
PYTHONPATH=src:../base/src python -m pytest tests -q
npm run build:ui
node engineering/test_unified_creation.mjs
python engineering/verify_managed_workbench.py --help
python engineering/verify_shared_task_workbench.py --help
```

The browser test accepts `PLAYWRIGHT_MODULE`, `CHROMIUM_PATH` and `TEST_OUTPUT`.
Native verifiers require explicit Harness, new output directory, Maven and Chromium
paths; the TASK verifier additionally requires an isolated simulator JAR. Its
database is created under the test output, not the existing demonstration bank.
