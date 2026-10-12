# Workbench API V0.2: Backend Batch 1 Verification

Date: 2026-09-06. Status: **Backend implemented / controlled API verified / UI wiring pending**.

## Fixed Sources and Scope

- Harness main: `b55d0722cac2905388f57dc4a17b85d346a0e470`, PR #8 merge confirmed and local main fast-forwarded.
- Builder baseline: `728e339c347faa0e32986f2973c87cfed53e0237`.
- Builder implementation: `139cb3682a22a9aea9e72aa8735658d714e34972` plus completion-view hardening
  `422b6bff35cb8bcbcc02dec9e2f2d405e78639ee`, branch `feat/workbench-api-v02`.
- The delivery documentation commit is subsequent; generated artifacts identify `422b6bf`, not a later documentation SHA.
- No Base API, Harness implementation, native SDK, existing UI file or Java host template changed.
- The active 18128 workbench still runs its prior isolated checkout; it was not restarted or replaced.
- No private model configuration file was read, paid model called, real bank accessed, or historical evidence deleted.

## Delivered Interfaces

The [API contract](workbench-api-v02.md) and [generated samples](../examples/workbench-api-v02/README.md)
cover the finite catalog, strict selection, local-only connection checks/preflight, existing real build
queue, build/manifest/instance/session separation, safe stage/error views, generation-bound host calls
and original whitelisted artifact downloads. Required controls cannot be disabled by selection.

This is not a second Builder engine. `Jobs`, `solution_for`, existing Maven stages and Java host
remain the implementation. A compiled artifact remains available after a failed launch; retries
must still pass identity, lock and owned-process checks. Optional controlled test mode is explicitly
server-configured, never a response fallback. A new per-request run ID is correlation, not authority.

## Actual Checks

| Check | Result and boundary |
| --- | --- |
| Baseline Builder | 85 tests passed before changes |
| Final Builder | 120 passed, including 35 new contract/API/negative tests |
| New facade completion race | Two tests failed on `139cb36`, then passed after `422b6bf`; red XML retained |
| Real HTTP build workflow | 51 checks passed, three real generated/compiled candidates, explicit local synthetic endpoints |
| Native host regression | Balance 26, wealth 29, both 29 controlled checks passed on final candidates |
| In-flight revocation | Nine cases passed: three compositions times policy/source/policy-unavailable; zero post-revocation model HTTP requests |
| Harness Maven reactor | 13 modules built; 172 tests: 165 passed, 7 explicit real-environment Smoke skips, 0 failures/errors |
| Contract/asset stage | Actual `harness_contracts` stage passed for each build; 9 Schema / 55 checks |
| UI and AG-UI | No UI replacement or AG-UI implementation; prototype-to-API wiring is next |
| Real model / full simulator / real authorization | Not run this batch; local test model and synthetic HTTP bank explicitly labelled |
| Human onboarding / Builder upstream | Not performed / not accepted by this delivery |

The completion race was found in this batch's new response facade: a replacement could be STARTING
while its predecessor's instance ID was still stored. Final projection now checks expected status
and instance under the same lock. This supplements, and does not replace, PR #8's process/token
generation protection. Both the earlier successful workflow and the red regression remain retained.

Final generated versions:

| Composition | Build ID | Service-lock SHA256 |
| --- | --- | --- |
| Balance | `agent-4677e14bd6704508` | `c233982da712e067681df0ac52855538c567ec59d9faff1017540328b18e38f2` |
| Wealth | `agent-6135a4d6fc61492a` | `fd9141757254e5d9144866b7cf1f9fac05293a0006288b54718d617ba61a6850` |
| Both | `agent-73da49f7581948c0` | `770122c5ca69534390e28ee01b7e23ad3b1ee350871980e80447490c563b189e` |

Every final candidate records clean Builder `422b6bf` and Harness `b55d072` sources. The HTTP
verifier checks every manifest file hash and actual session installation. It exercised two native
tool turns, current authorization denial with zero new source/model calls, stop/restart, new instance
and empty session, and rejection of the old tab's stop request. Nine separate timing probes delayed
actual source responses and counted requests at the local model HTTP endpoint.

## Measurements and Evidence

Final serial build times were 28.169 s / 30.804 s / 29.686 s (balance / wealth / both), including
contract checks, complete Harness Maven install/tests and candidate packaging. Maven dependencies
were cached. These are machine build observations, not human onboarding time, speedup ratios,
fresh-cache guarantees or business conversation latency.

Relative to the Builder project:

- `build/api-v02-tests-final.xml`: final 120-test report.
- `build/api-v02-projection-red.xml`: two failing predecessor checks.
- `build/api-v02-verification-20260906/`: initial `139cb36` HTTP workflow, retained as earlier evidence.
- `build/api-v02-verification-final-20260906/`: final HTTP report, build views, native/race results and logs.
- `build/workbench/<final-build-id>/`: actual inputs, logs, portable sources/JARs and immutable service locks.

Run the commands in the [API documentation](workbench-api-v02.md). Tests use a separate Harness
checkout to avoid concurrent Maven target mutation. Each pipeline completed serially; all temporary
API/host/fixture processes were stopped. Generated artifacts remain available for review; none of
this batch's candidates was promoted to the running 18128 service.

## UI Wiring Handoff

The supplied clickable prototype was read as an integration input, not used as backend evidence.
Its reported 30 browser checks belong to the UI author; they were not independently rerun here.

| Prototype behavior | Backend mapping / remaining work |
| --- | --- |
| `caps=balance/wealth` | Map to `account.balance.query` / `wealth.facts.describe`; `/catalog` is authoritative |
| `template=mobile/desktop` | UI appearance only; both use `banking-readonly-web-v1`, CUSTOMER_SELF; do not send these values as separate Runtime/profile IDs |
| Boolean model/bank settings | Replace with `/connections` state; this batch has no credential editing or active connectivity-test API |
| `startBuild()` timer / `finishBuild()` local record | Replace with preflight -> POST builds -> poll actual three stages; never carry the timer into production success logic |
| Default build-and-experience | After build SUCCEEDED, use artifactId to start, wait READY, then create session with instanceId; stop at actual failure |
| Missing model disables main demo button | Keep a separate artifact-only build action when canBuild is true; missing model blocks starting, not generation |
| Open existing Agent | GET build, check current instance/session, check authorization; do not create another build or infer permission from a local record |
| Scripted chat and source data | Use generation/session-bound chat. Text is `result.response.output`; facts come from authorized `result.toolResults`, not mock answers |
| Balance rendering | `toolResults[*].data.capabilityId=account.balance.query`, then `.data.result.accounts`; preserve currency, `.data.asOf`, `.data.sourceSystem` |
| Wealth rendering | Use `.data.facts` and `.data.description`; retain scope, gaps, source risk system and missing information |
| Cancel/replay animation | No build cancellation API this batch; returning to config must not claim a running build was cancelled. A real rebuild creates a new ID |
| Delete prototype record | No delete/archive endpoint; do not translate this to filesystem removal |
| Model settings in customer UI | Metadata view or maintenance navigation only; no Host token/API key or institution connection editing |
| Independent page and AG-UI | UI route still to wire; synchronous same-origin API available, AG-UI only a documented future mapping |

The prototype's model/bank booleans, fixed balances, timers and page-local records are deliberately
not execution inputs. View preferences can remain nonsensitive UI state, but must not store keys,
customer facts or authorization claims. Refreshing a page does not grant access or recreate history.
UI must retain clearConversation and late-result checks against its currently active instance/session.

Public mechanism additions stay in Builder; no new finance policy engine, Runtime, native app package,
marketplace, central service or filesystem cleanup was introduced. Actual UI wiring, paid-model
quality, complete-simulator regression, upstream acceptance and human use remain separate work.
