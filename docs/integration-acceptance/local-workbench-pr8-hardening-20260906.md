# PR #8: Workbench Boundary Corrections

Date: 2026-09-06. Status: **Four Corrections Verified / New Artifacts Controlled Acceptance Passed /
Live Model Workflow Exercised / Professional Output Reliability Pending**. Keep PR #8 draft for review.
This record supersedes the acceptance claims in the [original delivery](local-workbench-v01-delivery.md),
whose evidence is retained. It does not certify real bank authorization, business suitability or human onboarding.

## Fixed components

| Component | Executed revision |
| --- | --- |
| Harness | `7804c145bc4f9a12bbbf611d2ce7e8a06b2ec5cc`; subsequent PR update is documentation only |
| Builder implementation | `72e0ac35e592483a32321f9f463dbe0b3853448d`; clean checkout used for all final generated artifacts |
| Builder delivery | `728e339`; only verification documentation differs from the executed implementation |
| Builder upstream | MR #118 `cb749fab5fa06090b954e99a68993d2135b20ca9`; maintainer acceptance remains pending |
| Simulator | `162ef4e587c9e9b781bab60bf89591cf4255e579`, unchanged; isolated synthetic H2 database |
| Simulator artifact | SHA256 `e40f43df54b4f54b86cf552554c3e4cd65728073a6fa71cc71cab2108681807e` |
| Runtime | Stock OpenJiuwen Java `0.1.13`, SDK source unchanged |
| Model | Server-configured `deepseek-v4-flash`; no key or model connection in generated artifacts |
| EA / old mobile bank | Not participants in these runs; no deployment changes |

All implementation corrections belong to the existing Builder host, workbench and verifier. No new
Harness financial contract, permission platform or Runtime was introduced. The exported Builder branch
is not a claim that GitCode has accepted the patch. Its bundle requires upstream `66d2767` and `cb749fab`.

## Corrections and regression evidence

| Finding | Implementation | Behavioral verification |
| --- | --- | --- |
| P1 delayed financial results reach the model after revocation | Native `BEFORE_MODEL_CALL` checks current source session and host authorization; denied/unavailable checks request SDK force-finish. A sticky per-turn rejection becomes HTTP 403 and Runtime session release. Existing tool and final response checks remain. | Old generated balance artifact: one post-revocation model request containing financial data. Final nine probes: zero post-revocation model requests, HTTP 403, no extra business reads, cleared sessions that cannot be revived by restoring policy. |
| P2 old completions mutate replacement instances | Capture Popen object identity plus token, request port and expected session. Compare and mutate under the same lock; perform HTTP and process waits outside it. Cover creation, chat/revoke/status, start success/failure, stop success/failure and shutdown cleanup. | Paused old completions cannot kill the new real child or overwrite its session/status. Current-instance failure still terminates its own child. Start/stop stale HTTP completions expose 409 `INSTANCE_CHANGED`, without leaking arbitrary exceptions. |
| P2 full simulator denial checks inspect the wrong counter | One business observation function selects the actual recorder in full-simulator mode; IAM introspection is excluded. Denial checks compare the actual before/after observations. | Mutating the recorder with an extra financial GET while returning 403 now fails; the old predicate passed. Disabled capability and write checks use the same observation source. |
| P2 message count is not a multi-turn proof | Second turn's first model request must contain the first-turn unique user marker and prior nonempty tool messages. Another customer's requests must contain neither. | Two independent single-turn tool exchanges, a marker without the tool result, and history only in a later second-turn request are rejected. Actual generated Runtime history and customer isolation pass. |

New regressions were exercised before correction: 9 failed / 5 passed on the old lifecycle and original
assertion semantics; two additional stale HTTP status cases failed on a clean `42525af` checkout.
The old generated-host P1 probe also failed independently. Original assertion expressions were first
extracted unchanged into testable helpers; they were not replaced before recording those failures.

Wealth reads aggregate and risk data concurrently. The timing barrier waits until both requests have
arrived at the controlled bank, with neither response released, then revokes. This avoids misclassifying
an already-dispatched request as newly issued. A preliminary one-request barrier failed the read-count
assertion and is preserved as an intermediate test synchronization error, not quietly removed.

## Final verification

Environment: Ubuntu x86_64, JDK 21.0.12, Maven 3.8.7, Python 3.12.3. These local results do not claim a
new Python 3.10 Builder test run; Harness CI has its own supported-version matrix.

| Verification | Actual result | Scope |
| --- | --- | --- |
| Builder suite | 85 passed | Lifecycle, HTTP mapping, verifier negative controls and previous tests |
| Harness clean Maven verify | 172 tests: 165 passed, 7 explicit real-environment skips, zero failures/errors | 13 modules; no real bank or EA |
| Harness Python | 21 passed | Local Python 3.12 |
| Schema/assets | 9 schemas, 55 fixtures/assets/probes passed | Real validator, no compliance certification |
| Final balance / wealth / both builds | All compiled from clean `72e0ac3` + `7804c145` | Actual manifest files and running health lock compared |
| Controlled service | 26 / 29 / 29 checks passed | Explicit test model; real native SDK and controlled HTTP |
| Full simulator service | 18 / 19 / 19 checks passed | Same three artifacts, actual recorder; no financial write attempts |
| Delayed-bank authorization | 9/9 passed | Three compositions x policy revoke / source revoke / unavailable policy |
| Portable lifecycle | 11 checks passed | Actual start/stop/restart, occupied port, modified artifact, portable archive; no external model |
| Original controlled recipe | Ten cases and evidence verifier passed | Actual CLI rerun exited zero |
| Patch handoff | Implementation patches applied on clean upstream, tree equals `72e0ac3`; 85 tests passed | AI replay, not an independent human |
| Actual model, final-baseline first attempt | First turn completed; second turn failed the required tool-use check | Model incorrectly requested optional accountId; retained as failure |
| Actual model, complete repeat | 27 checks passed; 3 model turns: 12.809s / 11.320s / 11.382s | Two normal turns, revoke/clear, rebuild without balance, direct-route rejection, desktop/mobile screenshots |

An earlier old-recipe build collided with a concurrently started Maven build using the same Harness
target directories. Its failed compilation log remains. The final run used `mvn clean verify` followed
by serial builds and passed without changing Harness source. Never run two builders' Maven stages
against that same checkout concurrently; these timings are individual warm-cache observations, not P95 or speedup.

## Artifacts

Local final evidence: `reports/local-workbench-pr8-final-20260906/` (ignored generated evidence).
Old failures and intermediate runs: `reports/local-workbench-pr8-hardening-20260906/`, plus the original
`reports/local-workbench-v01-20260906/`. Do not overwrite or relabel them as final acceptance.

| Candidate | Build ID | Frozen lock SHA256 |
| --- | --- | --- |
| Balance | `agent-pr8-balance-v2` | `966d0f869ab47cb54b5d785dec2fd9d09dd4efdc79adbacdb6fc4d73c57befa2` |
| Wealth | `agent-pr8-wealth-v2` | `e98008c17aa2671462ea74674a2a41dee75f4d21c4eb21f846fca3d5e94a6071` |
| Both | `agent-pr8-both-v2` | `84a15d00c4811026e51a43c9d18438a34ec356ddcb0718c17d5e9caae3a0debc` |

The three ZIP files contain only manifest-whitelisted files plus the lock: 124/126/127 entries in the
respective manifests, each with 98 JARs including the application and four Harness dependencies.
`candidate-inventory.json`, `execution-matrix.json`, per-composition reports, build logs, timing reports,
test XML, the source bundle/patches and `evidence-index.json` form the handoff. The index records actual
file digests. Private policy, model key and live host tokens are not exported.

Actual page builds: `agent-e0680e2a13a74d56` (both) and `agent-c6c8f12baf0a453b` (wealth only), also built
from clean `72e0ac3`. Their IDs, complete locks, installation records and outputs are in `live-model/report.json`.
The failed first final-baseline attempt remains in `live-model-first-attempt/`; it is not hidden by the repeat.

## Reproduce and use

1. Read Builder `docs/local-workbench-v01.md`; acquire the fixed upstream commits and apply the exported
   implementation patches or fetch its bundle into a review branch. Do not overwrite the maintainer's branch.
2. Configure JDK 21, Maven and Builder/Base Python environment. Regenerate a selected Candidate through
   the existing CLI using its `solution.json`, with a new output directory. Build serially.
3. Run `engineering/verify_local_service.py <candidate>`, then the same command with `--bank-url <isolated-bank>`.
4. Run `engineering/verify_revocation_race.py <candidate> --mode <mode> --output <report>` for `policy`,
   `source`, `policy_unavailable`. Run `engineering/verify_service_lifecycle.py <build> --harness <checkout>`.
5. Separately configure the real model only on the server and repeat the actual UI flow. The local
   automation and exact commands are retained under `reproduction/`; they are not human acceptance evidence.
6. A copied Candidate ZIP runs with `python run.py`, external connection/policy/host-token configuration
   and JDK 21; it does not require the original Builder/Harness source directories at runtime.

An isolated verification instance is available at `http://127.0.0.1:18128/`, simulator at `18129`.
User unit: `finance-workbench-pr8-verified.service`; stop/restart with `systemctl --user stop/restart`.
The prior `18118/18119` stack and its active user instance were not restarted. Use a separate browser
profile/private window for the new port: local cookies are host-scoped, not port-scoped. SSH-forward
the new loopback port when viewing this server remotely. No public bind or production deployment was made.
The independently provisioned synthetic read policy expires at `2026-09-07T12:00:00Z`; it is not silently renewed.

## Remaining acceptance

- Model/task reliability is not established by one successful repeat. Native model HTTP schema was
  captured and confirms accountId is optional; the failed attempt's contrary claim is an observed model error.
- Professional output remains unapproved: unsupported R3 interpretation and overgeneralization from a
  zero USD account balance occurred in retained samples. Source facts, missing risk scheme and asset scope
  must remain explicit. No prompt/skill tuning or standard weakening was used to close the four corrections.
- Source business metadata needs the scenario owner's confirmation; simulator success is not production IAM,
  actual same-condition Provider substitution or transaction acceptance.
- Builder maintainer acceptance and a real colleague's independent onboarding remain separate pending items.
  Patch replay, screenshots and model invocations here were performed by the coding agent.

The four engineering corrections can now be reviewed against new commits, new artifacts and new evidence.
Do not label this a stable professional banking Agent or a fully accepted cross-repository product.
