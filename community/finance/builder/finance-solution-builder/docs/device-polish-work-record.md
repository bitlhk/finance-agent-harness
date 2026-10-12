# Device Preview and Card Polish V1

## Active Task (2026-09-10)

Implement the user-approved device preview and card polish, verify, commit/push,
and deploy the protected Finance Studio demo. Do not resume historical cashflow
packaging discussions. This task changes UI only, not Core, Provider or Runtime.

Baseline: Builder `f2d4e5ce`, production `a8064117`, Harness `b55d0722`.
Approved handoff: `device-polish-v1`, manifest verified (14 files).
Preserve the decimal display fix, authorization/session/ASR behavior, all 26 module
entries and the existing allowed capability combinations. Old Agent locks stay immutable.

- [x] Verify source, production and design manifest.
- [x] Implement device shells, one terminal selector and enlarged preview.
- [x] Polish catalog and shared result cards without replacing production wiring.
- [x] Verify responsive views, configuration binding and core regressions.
- [x] Generate fresh artifacts and verify the actual terminal interfaces.
- [x] Commit/push, deploy with rollback and verify the live site.
- [x] Record evidence, old-record preservation and remaining human acceptance.
- [ ] Independent human acceptance (not replaced by AI-driven browser checks).

## Release Completed (2026-09-10)

The user authorized the release after the frontend stage. Deployed Builder
`283e6d52c813394d99a5e3806287ace9af798629` with unchanged Harness `b55d0722`.
Fresh portable Candidates passed 118 controlled checks; the server Python suite
passed 389 tests. Protected live preview passed 304 checks, and a new page-built
Agent passed 19 actual-model/ASR checks using the full synthetic bank simulator.
All eight old records and seven old locks remained unchanged. See the
[release record](device-polish-release-20260910.md) for evidence and boundaries.

The final follow-up changes only documentation, evidence and the screenshot
verifier's icon-ready gate. It is not an additional runtime deployment. Earlier
sections below retain the states observed at those times.

## Frontend Stage Completed (2026-09-10)

The follow-up explicitly limited this stage to frontend implementation, local
checks and a local commit, without push or deployment. See
[implementation and re-run instructions](device-polish-v1.md).

- UI assets rebuilt from the modified source and included in the local commit.
- Existing Python suite: 389 passed (final run 11.13s), no failures or skips.
- Shared-renderer checks: 16 passed. Builder shells are absent from the actual
  Agent JS/CSS; exact decimal strings and zero/missing distinctions are retained.
- Browser: 162 checks passed, no page errors, at 1440/1024/390 across three
  terminals. Includes stable desktop preview height, enlargement, source access,
  keyboard/focus, independent theme/card choices and the real 26-module catalog.
- 45 screenshots plus `verification.json` saved under the local evidence root:
  `/home/ubuntu/finance-device-polish-evidence-20260910/frontend-stage/screenshots`.
  Representative images and the verification JSON are included in this commit.
- No build, bank or model requests were issued during browser preview checks.
- Local isolated workbench used port 18168; no existing local service was stopped.
- New locked Candidates, 736/320 checks, integration acceptance, push and release
  remain pending. The Shanghai service and all old Agent locks were untouched.

The next section is the historical pre-implementation observation, not the
current implementation status.

## Short Status Check (2026-09-10 01:26 UTC)

Device polish is NOT DEPLOYED. This check did not implement, build, test, commit,
push, restart or deploy the pending UI changes.

### Observed Versions

- Local branch: `feat/studio-terminal-ui-v01`.
- Local HEAD: `f2d4e5ce708e2b5f98a6e800627a6ab26706faa5`.
- Three tracked files are modified: `browser/build-ui.mjs`,
  `browser/presentation.js`, and `browser/workbench.js` (33 insertions,
  13 deletions at inspection). `browser/device-preview.js` and this record
  are untracked. No device-polish commit exists at local HEAD.
- Direct SSH to the Shanghai host succeeded with the existing key. The
  `sg-server` alias was not tested; another caller's connection failure was
  not reproduced using the direct address.
- Active release symlink: `/opt/finance-studio/releases/20260910-terminal-a8064117/builder`.
- Deployed checkout HEAD: `a8064117996cd7ebb591550959d018ce9607a933`;
  remote `git status --short` was empty; `finance-studio.service` was active.
- Release `examples/local-workbench/app.js` SHA-256:
  `d7cbb231313a1e8495e831a875d871f680ccab3d5e56c4d954c4c51a56576048`.
- Public workbench returned HTTP 401 without authentication. Direct local
  asset requests returned HTTP 403 under host access protection. No access
  checks were bypassed; authenticated browser rendering and the HTTP-served
  bundle digest were not verified in this short check.

### Pending Implementation Already Present

- Device shell markup for phone, laptop and ATM, plus a ResizeObserver helper.
- A single terminal selector above the preview, an enlarge-dialog entry,
  compact module/scenario markup and independently selected theme/card style.
- Shared balance card markup with currency, account label, availability,
  scope and source time; fixed sample content remains preview-only.
- Additional Lucide icon names in the UI build script.

These are partial source edits, not a finished or verified visual delivery.
Device shell CSS is absent; the ordinary `render()` path does not invoke
`resizeDevicePreviews()` (only the enlarge dialog does). Generated JS/CSS and
icon assets have not changed, and no new browser/test/deployment evidence was
produced for these edits.

### Next Bounded Steps

1. Finish device/card styles and the ordinary preview resize lifecycle.
2. Build assets; test terminal selection, enlargement, configuration binding,
   precision and responsive layouts at 1024/736/390/320 pixels.
3. Re-run relevant regressions and generate fresh candidate artifacts; retain
   identity, session, ASR and immutable old-Agent lock behavior.
4. Commit/push the reviewed changes, deploy a new rollback-capable release,
   then verify authenticated live pages and record the actual deployed SHA.
