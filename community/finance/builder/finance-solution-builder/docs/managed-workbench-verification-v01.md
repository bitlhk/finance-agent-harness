# Managed workbench selection: candidate verification

Date: 2026-09-14. This is stage 2, not shared TASK or deployment acceptance.

## Fixed-source rerun

After committing, the same browser build/start/two-turn/revoke/stop path passed
again with Builder `db32673ba77b0462bd0473928f1c3084190f6933` and Harness
`35f9be3e51ed03e2ad9b978dc8ac7b2b990e49f2`, both recorded clean. The resulting
Agent is `agent-36051fc86bda4d1c`. Evidence is in
`/home/ubuntu/outputs/capability-extension-v01/managed-browser-fixed-01`:
`browser-build.json`, `start.json`, `session.json`, `turns.json`,
`source-evidence.json`, `stop.json`, `result.json` and desktop/mobile screenshots.
The isolated workbench and generated instance were stopped after verification.
Earlier worktree/failure records below remain unchanged and are not relabeled.

## Changes and compatibility

The workbench exposes an explicit component-selection entry beside the existing
scenario entry. Managed selection 0.8 resolves Pack/Provider identities from the
server catalog and reaches the real build pipeline. A browser test discovered
that the pipeline's initial validator still invoked the legacy recipe; managed
input now uses component admission there too. Legacy input retains its original
validation. A targeted dispatch regression covers this boundary.

The managed Java template declares its supported runtime and three presentations.
Unsupported Python selection blocks this entry. The original scenario entry still
supports Python queries and private method 0.7; switching entries does not silently
discard a selected private method or replace capabilities. Existing generated
Agents and Locks are not rewritten. ATM voice remains rejected.

## Executed checks

- Builder Python: 853 passed, 1 skipped (`builder-managed-ui-final.xml`).
- JavaScript bundle generated from source; syntax and diff checks passed.
- Desktop 1440x1000 and mobile 390x844 screenshots inspected. An early mobile
  select overflow was fixed; the automated horizontal-overflow check passes.
- `managed-browser-07`: real page selected `wealth-customer-context`, submitted
  version 0.8, and generated/compiled a new native Java Agent. It then used the
  owner-scoped workbench start/session/chat/stop APIs with the frozen model binding.
- Two turns reached the controlled HTTP model and bank. Requests included the
  selected tool and source amount `1000000.12`; the unselected balance tool was
  absent. Source business reads were observed and business writes were zero.
- After revocation, chat was rejected with conversation clearing, with no new
  model or business read. Workbench session invalidation returns 409
  `INSTANCE_CHANGED`; direct Host authorization denial may instead return 403.
- Successful instance stop was recorded; test processes were reclaimed.

These are developer worktree results based on e77be2ed plus the reviewed working
changes, not an immutable release or live-model result. Generated manifests retain
the actual dirty-worktree indication and asset digests. Do not relabel them clean.
Evidence root: `/home/ubuntu/outputs/capability-extension-v01/managed-browser-07`.

## Failures retained

01/02: test model registry used invalid environment variable names; corrected to
the existing `FH_CONN_*` contract. 03: mobile overflow. 04: legacy recipe rejected
the new selection; its polling probe was stopped and cleaned after diagnosis.
05: the older standalone verifier monitored its own model endpoint while the
artifact correctly used its frozen model binding; the browser verifier now tests
the actual workbench connection. 06: expected 403 but got the existing cleared
session's 409. Assertions now require the specific denial, conversation clearing,
and zero additional model/business requests. Failures are not deleted or PASS.

## Still separate

Shared TASK confirmation/recovery, channel-preference persistence, real model
acceptance, independent human SOP/Provider onboarding and controlled production
deployment remain unaccepted. This change does not publish a transfer tool or
replace the standalone pilot. The original seven-query matrix is not rerun merely
for the new UI; earlier fixed factory matrix evidence remains separately versioned.
