# Studio V0.2 Integration Verification

Date: 2026-09-06. Status: **Implementation Complete / Controlled Integration
Verified / Real Model and Human Acceptance Pending**.

## Fixed Components

| Component | Actual version / participation |
| --- | --- |
| Builder baseline | d3622916781d6fa847903c003b8f5dc17a103a32, contains upstream MR118 cb749fab |
| Model registry, generated host and live Studio | 25c14c1064a2a3afbbea0600607c1a97e57c371b |
| Portable instructions / viewport capture | 40802595b41aa0b7a97889b4c60e4fa81e2501b7 |
| Final connection-label correction | 4d70d7c01f67523db2d188edd4db01781b5816a4 |
| Harness | b55d0722cac2905388f57dc4a17b85d346a0e470, unchanged |
| Runtime | OpenJiuwen Java 0.1.13, unchanged SDK |
| EA discovery | 12 whitelisted entries observed from 5706428bfd9b3d1708fa3e9e710a212a48aa229b; metadata only |
| Model / bank validation | Separate synthetic bank and controlled A/B/C model HTTP endpoints |
| Full simulator, real EA runtime, real models | NOT_RUN in this batch |

Multi-model and browser Candidates were regenerated from clean 4080259. Legacy
Candidates were regenerated from clean 4d70d7c. The only difference between those
commits is UI connection/category wording; `templates/` and `src/` are identical.
Both use clean Harness b55d072. This report is a later documentation-only change,
not a claim that older artifacts were silently updated to a new source version.

## Executed Checks

Environment: Linux x86_64, JDK 21.0.12, Maven 3.8.7, Python 3.12.3,
Playwright 1.62.1/Chromium. Existing Maven cache was reused. No developer-efficiency
percentage, paid-model latency or human onboarding time is inferred.

| Check | Result and scope |
| --- | --- |
| Builder Python suite | 142 passed, 0 failed; JUnit XML retained |
| Patch replay | All three implementation patches apply to clean d362291; resulting tracked tree identical to 4d70d7c; 142 tests passed using replay source |
| Harness Maven install, invoked by real builds | 13 modules; 172 tests: 165 passed, 7 explicit live Smoke skips, 0 failures/errors |
| Harness Schema/fixtures | 9 schemas, 55 fixtures/assets/state probes passed |
| Three multi-model CLI compositions | 58 checks with browser endpoint assertions; real generation, compilation and native host invocation |
| Actual A/B/C destinations | A received 8 requests; B 11; unselected C 0; these counts include controlled CLI and browser calls |
| Browser live flow | 33 checks: actual build/start/chat, fresh model switch, retained authorized history, revoke, stop, narrower new build and forbidden model rejection |
| EA metadata browser | 17 checks on final UI; 12 entries, multiselect/default, search/cancel/empty state, 390/320px bounds, no JS errors |
| Legacy single-model API | 51 checks; three new builds, two-turn native history, selected tools, revoke, restart and stale-instance rejection |
| Existing authorization races | 9/9 passed: three compositions x policy revoke/source-session revoke/policy unavailable; zero post-revoke model requests and extra business reads |
| Business writes | Zero in the controlled model/legacy checks; login and session revocation are separate IAM operations |

The backend suite also covers old chat/switch success/failure completing after
model/session/instance replacement. Unknown, duplicate, empty, disabled,
unsupported and default-outside-allowlist choices are rejected. Frozen model
configuration drift is detected; secret values are not embedded in locks.

## Artifact Index

Candidate roots are `build/workbench/<buildId>/candidate/` under this Builder.
Each contains a locked portable README, host JAR, dependency JARs, selected Packs
and optional model-selection.json. Every locked file hash was checked by the
verifiers; the generated application reports the same manifest at startup.

| Build ID | Role in verification |
| --- | --- |
| agent-0b4680f5fcab4987 | Multi-model balance |
| agent-8d933f9d55204c75 | Multi-model wealth |
| agent-5f72c1ab19b947a5 | Multi-model both |
| agent-7a7601ba9f634409 | Browser-created both, selected A/B, default B |
| agent-d049d664d7f443b3 | Browser-created new version, wealth only, model B only |
| agent-b62292a7ddb24524 | Legacy balance and three authorization races |
| agent-51f420602d0d4f6f | Legacy wealth and three authorization races |
| agent-bcbc9f00dc654e43 | Legacy both and three authorization races |

The narrowed version has no balance installation; forged selection of removed
model A returns 403. The previous build's allowlist remains unchanged. All test
instances were stopped; only the separately managed preview service remains.

## Commands and Evidence

```sh
.venv/bin/python -m pytest tests -q
NODE_PATH=<playwright-node-modules> .venv/bin/python engineering/verify_multi_model.py \
  --harness <clean-isolated-Harness> --output build/model-ui-v02-delivery-20260906 --browser
.venv/bin/python engineering/verify_workbench_api.py \
  --harness <clean-isolated-Harness> --output build/model-ui-legacy-delivery-20260906
.venv/bin/python engineering/verify_revocation_race.py <legacy-candidate> \
  --mode policy --output <unique-report.json>
```

Repeat the last command with source and policy_unavailable for each of the three
legacy Candidates. Do not run simultaneous Maven builds against the same Harness
checkout. Browser-only metadata checks use `verify_studio_ui.cjs <URL> <output>
metadata`; this does not run a model. Use unique output directories on rerun.

Evidence roots: `build/model-ui-v02-delivery-20260906`,
`build/model-ui-legacy-delivery-20260906`, `build/model-ui-races-delivery-20260906`,
and `build/ui-model-catalog-delivery-20260906`. The separate handoff bundle includes
copies of reports, screenshots, stage logs, locks and an integrity manifest, not
private binding files, keys or customer transcript archives. Candidate JARs stay
in their original generated directories to avoid another duplicate dependency set.

An initial UI probe failed because the generic button handler consumed the send
button before form submission; its captured API observations contain no chat
request. That failure is retained separately. The corrected browser flow asserts
an actual successful chat HTTP response, not merely rendered assistant text.
Early full-page screenshots of modal dialogs included off-viewport document
content; final modal screenshots capture the visible viewport and assert top,
bottom and horizontal bounds. No business assertion was weakened.

## Preview and Remaining Acceptance

The independent local preview listens on loopback port 18138. Existing 18128 was
not replaced or restarted. The preview shows the actual EA metadata snapshot but
the twelve live model bindings are UNBOUND. It may inspect configuration/build;
starting requires an administrator's explicit private binding and simulator
authorization. A configured EA entry alone is never treated as runnable.

- Real-model tool calling, quality, latency and per-model compatibility: NOT_RUN;
  host administrator must approve a specific binding and run a separate live test.
- Full non-ai-banking simulator and real bank/EA authorization: NOT_RUN here;
  respective environment and scenario Owners retain that acceptance.
- Builder upstream acceptance / push: PENDING; local patches are not upstream merge evidence.
- Human independent use: PENDING; clean-worktree replay above was AI-executed
  using an existing Python environment, not a fresh human installation.
- Python 3.10 and remote CI: NOT_RUN for these unpushed patches; local Python is 3.12.
- AG-UI, token streaming, native app packaging, storage deletion and new Runtime:
  outside this batch; no compatibility or cleanup claim is made.

The accepted design mapping and five source asset hashes are recorded in
`studio-ui-v02-design-provenance.md`. This is an adaptation of those assets inside
the colleague's existing Builder, not a new Builder platform or independent UI repository.
