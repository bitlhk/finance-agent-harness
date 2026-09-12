# Historical execution playback V0.1: controlled delivery

Date: 2026-09-12. Candidate contract, not a production banking certification.

## Baseline and ownership

Main was `9d8fb89683cdc60da8167f95294237104030d01b`; PR10 remained open at
`64bce5fd22d63964c843466a270bc483559273df`. The review branch preserves its
cashflow changes and the additional deployed fix rather than merging historical PRs.
Previous deployed Harness was `aaf46abee5d4aab0a97ac288c906beaa04d3cd3e`,
Builder `2a72ec2a4c694bcbc19c2d200cc49921e0b5c16a`.

Actual deployed implementation:

| Component | Fixed source |
| --- | --- |
| Harness | `a7b0b1401d66a05280695f10b75c39060e6b8761` |
| Builder | `76b9c1a784d55097ecb7fe8569b286a2093a05f3` |
| Runtime | OpenJiuwen Java 0.1.13, unmodified |
| Simulator JAR SHA256 | `e40f43df54b4f54b86cf552554c3e4cd65728073a6fa71cc71cab2108681807e` |

Both source trees were clean when the deployed workbench built the new pilot.
This report is a subsequent documentation-only change, not a different runtime build.
Harness PR12 remains draft; Builder's `feat/execution-history-v01` is pushed and
maintainer acceptance remains pending. No repositories or historical PRs were merged.

## Implemented scope

Harness supplies the candidate event contract/SPI, callback adapter, Provider decorator
and reference HTTP-attempt instrumentation. The generated Host supplies trusted root
correlation, lock/queue intervals, authorization observations and incremental storage.
The workbench supplies owner-scoped historical queries, filters, timelines and versions.

Events distinguish attempts, observable send entry, blocks, execution exceptions and
returned business status. SDK callbacks do not prove model HTTP delivery; internal model
client retries remain unobserved. Existing authorization order, introspection, nativeLock,
SDK source and mandatory financial receipts are unchanged.

The archive is append-only JSONL with immutable per-request installation/build snapshots.
There is no SQLite dual-write. Recovery records when interruption was detected, not a
fabricated death time. Default storage excludes prompts, customer text, raw tool bodies,
credentials and hidden reasoning. Historical retrieval has no business execution path.

See ADR0010 and Builder `docs/execution-history-v01.md` for extension instructions,
limits, flushing, retention, owner access, route allowlists and supported evidence.
New Packs reuse the mechanism only where their Host/Provider boundaries are instrumented.

## Actual validation

| Validation | Observed result |
| --- | --- |
| Harness Maven reactor | 14 modules; 207 tests, 200 passed, 7 explicit live Smoke skips |
| Schema/contract probes | 13 schemas, 92 probes passed |
| Builder Python, final release tree | 451 passed |
| Seven generated capability combinations | 7 passed; four operations across balance/wealth/cashflow |
| Generated Java project regression | 10 tests per final composition, including archive lock-order test |
| Controlled fault/lifecycle probe | 51 checks passed on final cashflow artifact |
| Desktop/mobile history UI | 7 checks; timeline widths, interruption, owner isolation and no overflow |
| Actual configured model + full synthetic bank | 14 checks; two turns with tools, revocation and read-only history |
| Deployed workbench build/start/chat/stop | 5 checks; new build source and lock verified |
| Deployed authenticated browser | History detail, desktop/mobile, no JS errors; actual browser timing persisted |
| GitHub push and PR CI | Python 3.10 and 3.12 jobs passed for implementation SHA |

Fault coverage includes slow source/model, two sessions and nativeLock waiting,
revocation while a source response is pending, cancellation request versus effect,
isolated process termination/restart, owner denial, model error, unavailable archive,
quotas and disabled observation. Unit tests additionally cover explicit HTTP retry,
timeout, callback force-finish, thrown exceptions and returned business failure.
No test killed a production Agent or filled a production disk.

A lock inversion found before release was reproduced by adding the same test to the
prior generated implementation (failed), then passed after the fix. Early pilot and
composition failures remain in the evidence directory; they are not counted as passes.
These included descriptor preservation, a terminal-flush observation race in a verifier,
and rejection of a source tree changed during building. Final artifacts were regenerated.

## Performance observation

Alternating paired controlled requests, 12 measured requests per mode after two warmups:
median observation ON 189.306ms, OFF 68.899ms. This is approximately 120.407ms additional
time in this small local experiment, not a production latency prediction. Synchronous
per-event flush has a measurable cost. Nearest-rank P95 is the maximum with this sample
size and is not a stable production P95.

Fourteen recorded requests occupied 843093 bytes including snapshots/sidecars; event
files accounted for 539335 bytes. Quotas are bounded; retention is operator-controlled
and dry-run by default. Zero overhead and unlimited retention are not claimed.

## Deployment and rollback

Authenticated workbench: https://finance-harness.demo.linggan.top/

Release directory on the existing Shanghai host:
`/opt/finance-studio/releases/20260912-history-v01`.
The service was stopped, existing build records/artifacts backed up, source symlinks
switched and service restarted. Model, runtime, speech, proxy and simulator authorization
configuration hashes remained identical. Anonymous access still returns HTTP401.
No old Agent artifact was rebuilt or upgraded. Its previously running process was stopped.

New operator-isolated pilot: `agent-f23574d0b5e74911`, stopped after verification.
Its build Lock SHA256 is
`01a01e82c9aeef45766c4d8b5de2c2e63f8c02b953b410d49fae1b1fbf2799fc`.
It is not inserted into another user's workspace. Users create a new Agent with their
own login to see their own records. Existing pre-release Agents do not acquire history
instrumentation automatically.

Rollback by a host operator: stop `finance-studio`, restore the `builder` and `harness`
symlinks to the paths recorded in `previous-builder.txt` and `previous-harness.txt`,
then start the service and check its authenticated health. Keep new history intact;
do not overwrite current records with the backup merely to roll back code. The backup
`pre-release-builds.tar.gz` is disaster-recovery material. New pilot artifacts remain
version-pinned and should not be assumed compatible with an older workbench.

## Evidence and remaining acceptance

Operator evidence root: `/home/ubuntu/outputs/execution-history-v01/`:
`compositions-final`, `history-final`, `browser-03`, `overhead-final`,
`live-final-report.json`, `deployed-browser`, and `workbench-final-report.json`.
Remote actual-model evidence is under
`/opt/finance-studio/pilots/history-20260912/`.
The reusable scripts live in Builder's `engineering` directory. Reports contain hashes
and metadata, not complete actual-model prompts or customer responses.

The final isolated story retains a slow request and an interrupted request after a
process restart; historical viewing makes no model/bank request. This does NOT claim
an actual full day of retention. Production gateway Basic-login entry was not exercised
by the automated browser; it used the trusted operator proxy path, with browser and
Host owner/session controls still active.

Not accepted here: human independent onboarding, Builder maintainer acceptance,
actual-day retention, real-bank authorization, model answer quality, model-client
internal retries, multi-host persistence, automatic replay, token streaming or Task
business execution. No claim that all future Packages automatically support this scope.
