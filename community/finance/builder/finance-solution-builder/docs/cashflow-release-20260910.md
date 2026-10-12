# Cashflow First-batch Release - 2026-09-10

## Outcome and Exact Versions

The synthetic-bank cashflow integration is deployed at `https://finance-harness.demo.linggan.top/`.
It adds two QUERY capabilities as one installation unit inside the existing finance-assistant module.
The account and wealth units remain available: four business Capabilities, three units, seven finite nonempty combinations.
The 26-module inventory is unchanged; this is not an implementation of the other modules or candidate groups.

| Component | Actually used version |
| --- | --- |
| Builder release and generated Host/UI | `a509e723ae5c3d2efc8003c3e4b7e6866d685fb9` |
| Harness | `aaf46abee5d4aab0a97ac288c906beaa04d3cd3e` |
| Browser acceptance driver | `60e944fe` (verification-only change after release code) |
| OpenJiuwen Java SDK | `0.1.13`, verified loaded Maven artifact |
| Full synthetic bank integration fork | `162ef4e587c9e9b781bab60bf89591cf4255e579` |
| Simulator JAR SHA256 | `e40f43df54b4f54b86cf552554c3e4cd65728073a6fa71cc71cab2108681807e` |
| Default cashflow Domain Pack | `0.1.1` |
| Reviewed upstream cashflow source | `db38c7f454b25f109a607308dbeb4f01de75e9d2` |
| Public guide | V0.8; same two architecture diagrams as V0.7 |

Both implementation branches were pushed. Harness PR #10 remains an open dependency at `64bce5f`; this release does not claim a mainline merge or Builder maintainer acceptance. Later verification/report commits are not silently substituted for the installed release SHA.

## Verification Results

| Check | Observed result | What it does not prove |
| --- | --- | --- |
| Harness reactor | 193 tests: 186 passed, seven live Smoke skips, no failures/errors | Real-bank authorization |
| Harness contracts | Ten schemas, 69 fixture/asset/state probes passed | Full industry-standard compliance |
| Builder and Base on release server | 511 passed, two skips | Builder upstream CI/acceptance |
| Fixed-source generation | Seven combinations, 959 locked files checked, 16 actual controlled tool cases | Arbitrary combinations |
| Private summary-only method | Actual controlled baseline/candidate run and installed method verified | Expert method quality or per-Skill tool authorization |
| Generated cashflow service | 28 controlled boundary checks passed | Uncontrolled real-world fault coverage |
| Full simulator with controlled model | Four capability cases, seven business GETs, zero business-write attempts | Real-model quality |
| Domain-site real-model UI | 50 checks; mobile two turns, desktop three, ATM two; seven distinct runs | Human device/independent onboarding acceptance |
| Source-card reconciliation | Six cashflow cards across three terminals matched fixed synthetic facts, scope, version and gaps | Equivalent real Provider replacement |
| Wealth regression | Additional real-model query returned CNY 360824.12 with source and gaps | Household total assets or suitability |
| New online artifacts | Three stopped instances; 147/151/147 locked files verified; both source worktrees clean | Automatic upgrade of old Agents |
| Public guide | Nine content/diagram tests; 55 local and 56 anonymous online reader checks | Complete Provider/Runtime tutorial coverage |

The real model was the configured `deepseek-v4-flash` connection. Facts came from the full synthetic simulator, not an actual bank or customer.
Harness CI for the fixed SHA passed both Python 3.10 and 3.12 jobs: <https://github.com/bitlhk/finance-agent-harness/actions/runs/34463630828>.
Builder remote push hooks passed; no unobserved GitCode full CI is claimed.

## Business and Authorization Boundaries

For August 2026 the summary source reports income 15000.00, expense 232.00 and net 14768.00 CNY.
The BOOKED/CNY classified source reports income 15000.00, expense 166.00 and net 14834.00 CNY.
The different scopes are retained; the two figures are not added or forced into one total.
Summary UTC/first-100-account limits, unverified BOOKED filtering, analysis timezone, historical coverage and trend comparability remain explicit gaps.
No percentage growth, FX conversion, forward-zero completeness, purchase, transfer or budget-write capability is introduced.

Cashflow instances use a separately and explicitly provisioned synthetic authorization file.
It preserves the configured customer's identity, bank endpoint, enabled state and expiry, with the extra cashflow scope only in this new file.
The old three-scope file is byte-identical. Capability selection itself does not create a grant.

A private method may declare only summary, while the actual tool installation expands to the cashflow pair.
Replacement is an entire Domain Pack: a summary-only private method replaces the default cashflow method, not just one subsection.
Unrelated wealth methods remain. This is neither a new workflow engine nor a per-Skill permission sandbox.

## Deployment, Preservation and Rollback

The first switch attempt aborted because two preexisting Agent instances were running. It did not stop the service or alter the release links.
After explicit user approval both were stopped through the management API, then backup and release switching proceeded.
The two old Agent records remain unchanged relative to the post-stop backup; seven old locks and their existing assets were preserved.
564 present locked files were checked. 392 already absent files under old method-trial artifacts were also absent in the predeployment archive; they are not a new loss and are not counted as validated present files.
Model, speech and runtime environment files and the old speech systemd drop-in remain byte-identical.

Operational paths, accessible only to maintainers:

- Active release: `/opt/finance-studio/releases/20260910-cashflow-a509e723`.
- Backup: `/opt/finance-studio/rollback/20260910-cashflow` (including `builds.tar.gz`, configuration and original authorization).
- Rollback script: `/opt/finance-studio/incoming-cashflow-20260910/rollback.sh`; first ensure no active user builds/instances.
- Guide: `/srv/finance-studio/developer-guide/20260910-public-v08-a509e723`; roll its `public-current` link back to `20260910-public-v07-e0ce3997` if required.

Rollback changes release references, not customer records or old artifacts. Do not restore an old whole data archive over new user work.
All acceptance instances were stopped; the temporary local simulator on port 18189 was stopped after its interrupted launcher was identified.
No unrelated local service was stopped. The old long-term demo records and test evidence are retained, not purged.

## Reproduction and Evidence

Use the commands and scope in `cashflow-kit-integration-v01.md`. Freeze the release SHA for Candidate generation;
the later browser verification file can run against that fixed service without changing its Java/UI bytes.
The site path is: select the mobile-banking template, select finance assistant, configure model and bank connection,
build, start, choose an explicit month, then query summary and categories. Add account/wealth to build the four-capability combination.

Maintainer evidence root: `/home/ubuntu/outputs/cashflow-kit-20260910`.
Key entries are `frozen-verification.json`, `frozen-assembly/assembly-verification.json`,
`frozen-assembly/combination-4/candidate/cashflow-controlled-report.json`, `harness-ci.json`,
`server-python-tests.log`, `online-verified/`, `online-wealth-replay/`, `online-artifacts.json`,
`preserved-final.json`, and `public-guide-v08/`.
The guide's four-file delta is `public-guide-v08-scoped.patch`; existing unrelated changes in the documentation worktree were not committed or reverted.

## Failed Attempts and Remaining Limits

- The initial live browser driver could mistake first-turn categories for a completed second turn. The fixed driver requires an additional captured response and distinct run ID. A cached-fact explanation is recorded as reuse, not another source read.
- Source selection previously assumed the second response had its own new tool facts. The model sometimes explicitly explains the preceding turn's facts instead. Verification now selects an actual recorded source; the application does not copy stale cards into a new balance response.
- A preliminary browser process received SIGTERM during the host disruption; no cause or model verdict is inferred. One supplemental wealth probe encountered Chromium response-body retrieval failure; rerun with cloned response capture passed. These failed records remain separate from accepted evidence.
- Cached-fact explanations may render as prose without a new fact card; users can inspect the original source message. This is not a fresh query result.
- Screenshots preserve an observation of blank generic icon positions in the generated terminals. The icon response/render cause and baseline comparison were not independently established in this batch; the functional checks do not claim all visual assets passed pixel-level validation.
- Real-bank authorization, business Owner confirmation of source scope, equivalent real Provider replacement, expert model quality, upstream Builder acceptance and independent human onboarding remain unaccepted.
- GitCode warned that the upstream repository LFS usage exceeds its quota; this push succeeded. No unrelated LFS assets were deleted.

No second candidate group, Skill migration program, new Runtime or Core redesign was started. No efficiency percentage or independent-human completion is claimed.
