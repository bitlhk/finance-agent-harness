# Model snapshot P2 correctness closeout

Scope: two snapshot correctness issues only. PR #14 remains draft and depends
on draft PR #13, which depends on the unmerged execution-history work. No remote
PR merge, production deployment, SDK modification or streaming feature.

## Fixed implementation and candidate

| Component | Actual input |
| --- | --- |
| Harness used by the build | a2428ad1fc43cb23913e6bd25f43daf8b4f78bec, clean |
| Builder template, reader and UI | d756a4c0c3a6fda5c74d13792425af3c95c2a591, clean |
| Runtime | OpenJiuwen Java 0.1.15 |
| Generated directory | /home/ubuntu/outputs/model-snapshot-v01/p2-generated-all/candidate |
| Service Lock SHA-256 | 077b547b468170e7f87fc4191e749f3986fc963da5d09bf86cdfba1d61300ad4 |
| Generated Host JAR SHA-256 | fc28687709249fe5f3fc843312dced48d6628d1522b0c903e776213783259c01 |

Later commits update evidence/qualification documentation only. The actual
source SHAs in this lock must not be replaced with the latest documentation SHA.
The normal Builder generated, compiled and locked this candidate; test-only
copied red/green trees were never launched or represented as valid artifacts.

## Fixes

1. Structural projection separates requests, messages, functions and schemas.
   Dynamic property/definition names are preserved with recursive schema
   handling. The controlled endpoint's entire tool definitions match persisted
   definitions on all eight observed request calls, including period/accountId.
   Unknown fields and unsupported objects are still filtered, with explicit
   reasons. This does not claim arbitrary JSON Schema dialect fidelity.
2. Collection/property, depth, node and byte guards report losses rather than
   silently retaining CAPTURED. `changes` records reason/path and available
   counts; `changesOmitted` bounds detailed diagnostics. TRUNCATED denotes limits,
   FILTERED denotes other projection changes for normally captured calls.
   Byte counts refer to projected UTF-8 content, not original HTTP bytes.
   API and UI present these details; older bodies explicitly lack loss metadata.

Only the frozen diagnostic copy is projected. Model requests, authorization
callbacks, locking and business execution are unchanged. No captured context is
labelled HTTP_SENT; parsed responses are not raw HTTP or hidden reasoning.

## New verification

Evidence root: `/home/ubuntu/outputs/model-snapshot-v01/`.

| Evidence | Actual result |
| --- | --- |
| p2-red-candidate targeted tests against old template | Two tests fail: dynamic properties removed; 129 messages remain CAPTURED |
| p2-generated-all normal Builder stages | All three stages pass; total build elapsed 33,876 ms, not a development-efficiency comparison |
| Generated Host JUnit | 15 passed, 0 failures/errors/skips; three new structural/loss tests |
| Harness reactor in that build | 218 tests: 211 passed, 7 explicit real-environment smoke skips, 0 failures/errors |
| Schema validation in that build | 13 schemas, 92 fixture/asset/state probes pass |
| p2-builder-pytest.xml | 456 passed, includes permission, legacy-body, summary and history tests |
| p2-controlled/report.json | 134 checks; four capabilities, 16 bodies; received tool definitions match; post-restart reads produce zero model/bank HTTP |
| p2-history/report.json | 51 checks with capture enabled; revocation, faults, concurrency, interruption and storage degradation |
| p2-browser/report.json | Seven browser/API checks; loss details visible, owner gate enforced, no HTML execution, desktop/mobile no overflow |

Generated tests cover nested properties and `$defs`, 129 messages, 129 schema
properties, 129 enum values, excess depth, 60,000-character content, redaction,
detail overflow and existing write/queue/quota failures. Browser loss metadata
and malicious HTML are explicitly injected into an owner-remapped synthetic
copy to exercise presentation. The source lock is recorded in its report;
original candidate records remain untouched. This browser test is not evidence
that a real model call exceeded a storage limit.

Report SHA-256 values:

- Controlled: `80199e61342881118ab7b39b00394f7d6e84f6bc3b9edbc49d986aad62896b72`.
- Browser: `6fe2f19758d599a35b05797df2f643ee877bcd3b7f421f60b63e32eafa2fa855`.
- History faults: `2c134f810b4a3c1c7922d8818040b48d7c7bb1ad26baba9a553357c154dbd1a8`.

## Historical evidence is not upgraded

| Earlier evidence | Preserve this qualification |
| --- | --- |
| review-all / review-controlled | Builder f2731a71, lock d5c78649..., 88 old checks, pre-P2 filter |
| Builder 9e1906cd | Reader reference-state correction only; not a regenerated f2731a71 Host |
| live-1 | 22 configured-model/full-synthetic-bank checks at lock 3f7a9192..., not rerun with the P2 filter; quality NOT_SCORED |
| overhead-1 | Same older lock; 12 samples per mode, medians off/Trace/snapshot 69.68/187.45/195.03 ms; new filter overhead NOT_MEASURED |
| compositions-final | Seven older combinations; this P2 run exercises all four tools in one new all-combination candidate, not seven new builds |

Performance follow-up must decompose synchronous event fsync, projection and
business time under the same conditions. Durability has not been reduced; no
zero-overhead or stable P95 claim is made.

## Reproduce

At Builder d756a4c0, from community/finance/builder/finance-solution-builder:

```sh
PYTHONPATH=src python -m pytest -q
npm run build:ui
PYTHONPATH=src python engineering/build_history_candidate.py --harness <harness-a2428ad> --output <new-build> --unit all
PYTHONPATH=src python engineering/verify_model_snapshots.py <new-build>/candidate --harness <harness-a2428ad> --output <new-evidence>
PYTHONPATH=src python engineering/verify_snapshot_browser.py --harness <harness-a2428ad> --source <new-evidence>/execution-history/<owner-hash> --output <new-browser> --chrome <chromium>
```

Use clean source worktrees for generation, preserve each service lock, and use
new output directories. Run generated Maven tests through the Builder stages;
do not patch a locked artifact to claim a successful generation. The history
fault suite uses `verify_execution_history.py`; enable capture for its synthetic
owner's SHA-256 through the documented server-side environment grant.

Remaining release gates: native body-tracer coexistence, institution-specific
data policy, configurable retention/flush, streaming snapshots, independent
human acceptance, and fresh real-model/performance acceptance of this version.
Builder branch is pushed; maintainer acceptance and a remote Builder pipeline
result are not implied by local pytest. Stable workbench and existing Agents
were not changed.
