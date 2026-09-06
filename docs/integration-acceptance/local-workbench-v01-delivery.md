# Local Visual Financial Agent Workbench V0.1: Delivery Record

Date: 2026-09-06. Status: **Implemented / Controlled Native Service Verified / Actual Model and Human Acceptance Pending**.
This is a local read-only reference Agent, not a complete mobile bank or production authorization acceptance.

## Component baseline

| Component | Fixed revision and use |
| --- | --- |
| Harness main | `296ada013363e32d2bf0fe558c2a154ea84346fd`: PR #6 then #7 merged in dependency order |
| Harness implementation | `de183ae3387bc7c113da53dede07d4048ae91636`, new `feat/local-agent-workbench-v01` |
| Builder upstream | MR #118 `cb749fab5fa06090b954e99a68993d2135b20ca9`; newer visualizer/storyline changes preserved |
| Builder implementation | `105a40de153c4fc83e3639e8597c1bdc8d7f0ff9`; three portable candidates built from this clean source |
| Builder delivery | `42525afbcee15eb8d528488a1f20bc25f9da1794`: adds lifecycle/transport/revocation verification, launcher port-preflight fix and docs |
| Page-generated candidate | `agent-a1ed6619ccdc4c34`, Builder `a2c6e3a14c1e948387ecfd8046570437e38d9280`; real browser-triggered build |
| Simulator | `162ef4e587c9e9b781bab60bf89591cf4255e579`, from existing `a69b33f`; new session introspection only |
| Simulator JAR | SHA256 `e40f43df54b4f54b86cf552554c3e4cd65728073a6fa71cc71cab2108681807e` |
| Runtime | Stock OpenJiuwen Java `0.1.13`; SDK source unchanged |
| Database | Full simulator, isolated H2 memory, synthetic migrations through V511; no existing file database changed |
| EA / old mobile application | Not part of this execution; no deployment or application changes |
| Model | Not configured for live use. Native regression explicitly uses `CONTROLLED_TEST` |

Builder remains its maintainer's project. Local development and export do not imply GitCode acceptance.
The three CLI candidates contain 97 dependency JARs, including four actual Harness JARs, each compared
against this build. Locks bind selected capabilities, generated files, dependencies and source revisions.
No model key, bank secret, access policy, session file or customer output is included in the download.

## What is implemented

- Three finite choices: wealth facts, balance, both. Explicit bindings, independent balance-only Pack,
  and QUERY-only native installation. Removing balance makes forged balance invocation unavailable.
- One portable Java persistent host with native Agent/Tool/Session use. No Harness Java implementation
  copied into Builder, no SDK patch, no dependency on the full mobile banking application.
- Actual simulator session introspection plus separately provisioned, revocable local read grants.
  Capabilities do not grant scopes. Context/source mismatch is denied before source reads.
- Authorization before model use, at each source/tool boundary and after inference; revocation clears
  the native session and suppresses in-flight results. A revoked conversation cannot be revived.
- Loopback Builder API, owner-bound local cookies, CSRF/Origin/Host checks, finite configuration,
  real queued builds, stage reports, owned child start/stop/restart and immutable artifact download.
- Red/white/gray UI with phone-style preview, actual build and instance identifiers, native tool results,
  source observations and artifact/Runtime locks. Model configuration missing is an explicit launch error.

## Actual verification

Environment: Ubuntu x86_64, JDK 21.0.12, Maven 3.8.7, Python 3.12.3. Linux only in this round.

| Verification | Observed result | Limit |
| --- | --- | --- |
| Harness Maven install/verify lifecycle | 13 modules; 172 tests: 165 pass, 7 explicit live-smoke skips, zero failures/errors | No production bank/EA |
| Harness JSON Schema/assets | 9 schemas, 55 fixtures/assets/probes pass | No standards-compliance claim |
| Harness Python | 21 pass | Local Python 3.12; CI has its own version matrix |
| Builder Python | 62 pass | Includes old Base/recipe/visualizer behavior |
| Original controlled CLI | Ten cases and shared evidence verifier pass; rerun actual CLI exit 0 | Scripted model, controlled source |
| Wealth-only native service | 28 checks pass | Explicit test model |
| Balance-only native service | 25 checks pass | Explicit test model |
| Combined native service | 28 checks pass; both tools reach their source paths | Explicit test model |
| Full simulator through native service | 18 checks pass; forwarded business requests all GET, zero business-write attempts | Explicit test model; identity operations recorded separately |
| Process/artifact lifecycle | 11 checks pass: real start/stop/restart, old-session rejection, occupied port, modified artifact, download/independent-directory execution | No model call in lifecycle portion; separate portable native test uses test model |
| Playwright actual page | 12 checks: real build, refresh, owner/CSRF denial, download, missing-model launch rejection; 390/768/1440 widths, no JS errors/overflow | Actual-model page conversation not executed |
| Builder handoff patches | Six patches applied to clean upstream `cb749fab`; resulting tree equals delivery `42525af` | AI-operated replay, not independent human onboarding |
| Simulator full suite | 420 tests: 419 pass, one existing fund workflow failure | Full suite is NOT green |

The simulator failure is `BankingApiIntegrationTest.fundServiceCoversProductsOrdersRedemptionConversionPlansDividendHealthAndNotices`,
expected HTTP 200, actual 422. A separate pristine `a69b33f` worktree reproduces exactly that failure:
419 tests, 418 pass, one failure. The new introspection test passes. The full simulator was packaged
with the explicit targeted test command after preserving the failed full-suite logs; no silent whole-suite PASS.
No unrelated fund workflow modification was made.

Initial failed probes are preserved: missing explicit multi-Provider bindings, non-atomic instance JSON,
and a TIME_WAIT port preflight false positive. These were corrected and rerun. An earlier old-CLI tool
session ended with status 143 after generating a passing report; the final repeat explicitly recorded
`ACTUAL_CLI_EXIT 0`. Do not use the interrupted wrapper as the successful CLI exit evidence.

## Measured effort and evidence

With dependencies cached, the three CLI builds took 29.5s / 29.1s / 29.5s, including the full Harness
test/install stage. These are single observations, not P95, first-install time, human effort or speedup.
There is no comparable old-process baseline. Actual model latency and professional output quality were not measured.

Public financial logic changes: one Provider plus its tests, one read-only Pack and one profile.
Simulator changes: one identity-only controller plus its test. Builder-specific work is the service
recipe, thin application template, local job/ownership lifecycle, UI and verification scripts. A second
real bank still requires an actual Adapter/identity contract and same-condition substitution acceptance.

Local evidence directory: `reports/local-workbench-v01-20260906/` (ignored build artifacts, not committed secrets).
It includes copied build/lock/native reports, browser evidence, build logs, the downloadable candidate,
Builder patches/bundle and an automatically generated digest index. Original build outputs also remain
under the Builder's `build/`. Financial facts in these reports are synthetic only.

## Reproduce and hand off

Read the Builder's `docs/local-workbench-v01.md` and generated candidate README. Install JDK 21,
Maven, the same Python venv with Builder Base/extension, and Harness schema dependencies. Configure
`FH_MODEL_URL`, `FH_MODEL_NAME`, `FH_MODEL_KEY` only in the host environment for actual-model use.
Never put credentials in Solution, Lock, the browser or Git. The launcher verifies the simulator JAR
digest, starts fresh synthetic data, and requires explicit first-time local policy provisioning.

Current server UI: `http://127.0.0.1:18118/`; simulator: `http://127.0.0.1:18119/`.
The loopback-only stack is managed by the user unit `finance-workbench-local-v01.service`.
Stop/restart it with `systemctl --user stop/restart finance-workbench-local-v01.service`.
These transient units are a local convenience, not an installed production service or reboot-persistence claim.
On a different computer, use the documented launcher; SSH-forward the port when viewing this server remotely.

The Builder bundle requires upstream `66d2767` and `cb749fab`; fetch those first, then fetch the bundle
into a review branch. Alternatively apply the six numbered patches on `cb749fab`. Both include the
previous unaccepted `8229b3c` patch. Maintainer review is still required; do not push over their branch.

## Pending acceptance

| Item | Needed to close | Owner |
| --- | --- | --- |
| Actual-model dialogue / page end-to-end | Available server-side model configuration; two real turns, tool/results/source comparison, revoke then rebuild without balance | Local operator + technical team |
| Business semantics | Confirm supported asset scope, valuation time and risk-system metadata; preserve current explicit gaps until confirmed | Simulator / wealth scenario owner |
| Builder upstream acceptance | Review/apply exported patches and run the same checks in maintained Builder | Builder maintainer |
| Independent use | A colleague operates from a clean environment; records time, cache state, edits and assistance | Builder / scenario tools team |
| Production IAM / real Provider substitution | Separate equivalent-authorization and actual-source acceptance | Bank / EA owners |

The full simulator exposes one documented login customer. Two-customer isolation is tested with two
controlled identities, not claimed as full simulator multi-customer IAM support. The current grants are
operator-provisioned test grants, not permission inferred from a front-end checkbox or production bank IAM.
No transactions, confirmation UI, anti-fraud transfer, UNKNOWN extension or production deployment in this round.
