# Model snapshot P2 correctness closeout

Scope: two snapshot correctness issues only. PR #14 remains draft and depends
on draft PR #13, which depends on the unmerged execution-history work. No remote
PR merge, production deployment, SDK modification or streaming feature.

## Fixed implementation and candidate

| Component | Actual input |
| --- | --- |
| Harness used by the build | 9a286a7bb2523d21c3778a925e40de6d34cfc3b7, clean |
| Builder template, reader and UI | 8b302d7f9efc8e29f65e5c92dbe4f151ea4c547e, clean |
| Runtime | OpenJiuwen Java 0.1.15 |
| Generated directory | /home/ubuntu/outputs/model-snapshot-v01/p2-final-all/candidate |
| Service Lock SHA-256 | 41b976a59c21a733640121a8dfa523e3450c0747aa5c6eda68337cf7fdb82ac8 |
| Generated Host JAR SHA-256 | b8923ce109c286a355743ca3728fb0d904d0e74a517d620b66531c61e5e8e8d6 |

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

   Final count regression: 129 unknown keys that are all filtered out remain
   FILTERED with an empty projection, not TRUNCATED with a fictitious retained
   count of 128. Property-limit counts reflect actual retained properties.

Only the frozen diagnostic copy is projected. Model requests, authorization
callbacks, locking and business execution are unchanged. No captured context is
labelled HTTP_SENT; parsed responses are not raw HTTP or hidden reasoning.

## New verification

Evidence root: `/home/ubuntu/outputs/model-snapshot-v01/`.

| Evidence | Actual result |
| --- | --- |
| p2-red-candidate targeted tests against old template | Two tests fail: dynamic properties removed; 129 messages remain CAPTURED |
| p2-final-all normal Builder stages | All three stages pass; total build elapsed 34,188 ms, not a development-efficiency comparison; 158 locked hashes verified |
| Generated Host JUnit | 15 passed, 0 failures/errors/skips; three new structural/loss tests |
| Harness reactor in that build | 218 tests: 211 passed, 7 explicit real-environment smoke skips, 0 failures/errors |
| Schema validation in that build | 13 schemas, 92 fixture/asset/state probes pass |
| p2-final-builder-pytest.xml | 456 passed, includes permission, legacy-body, summary and history tests |
| p2-final-controlled/report.json | 134 checks; four capabilities, 16 bodies; received tool definitions match; post-restart reads produce zero model/bank HTTP |
| p2-final-history/report.json | 51 checks with capture enabled; revocation, faults, concurrency, interruption and storage degradation |
| p2-final-browser/report.json | Seven browser/API checks; loss details visible, owner gate enforced, no HTML execution, desktop/mobile no overflow |

Generated tests cover nested properties and `$defs`, 129 messages, 129 schema
properties, 129 enum values, excess depth, 60,000-character content, redaction,
detail overflow and existing write/queue/quota failures. Browser loss metadata
and malicious HTML are explicitly injected into an owner-remapped synthetic
copy to exercise presentation. The source lock is recorded in its report;
original candidate records remain untouched. This browser test is not evidence
that a real model call exceeded a storage limit.

Report SHA-256 values:

- Controlled: `38eca750b5527dce7f2093a3ab1b746d5bbf3da1530d1e49fb63bcbcfd987198`.
- Browser: `43f241bfd68557241d4fd02e5da74705bca37ac3de012e5b3adca305a0ce12ca`.
- History faults: `bf5516ca6c2229759769660a6a460b16b0da2e0f06fc3026ea4e689f586fafbb`.

## Historical evidence is not upgraded

| Earlier evidence | Preserve this qualification |
| --- | --- |
| p2-generated-all / p2-controlled / p2-browser / p2-history | Intermediate d756a4c0 / lock 077b547b..., preserved before the retained-count refinement; final evidence uses p2-final-* |
| review-all / review-controlled | Builder f2731a71, lock d5c78649..., 88 old checks, pre-P2 filter |
| Builder 9e1906cd | Reader reference-state correction only; not a regenerated f2731a71 Host |
| live-1 | 22 configured-model/full-synthetic-bank checks at lock 3f7a9192..., not rerun with the P2 filter; quality NOT_SCORED |
| overhead-1 | Same older lock; 12 samples per mode, medians off/Trace/snapshot 69.68/187.45/195.03 ms; new filter overhead NOT_MEASURED |
| compositions-final | Seven older combinations; this P2 run exercises all four tools in one new all-combination candidate, not seven new builds |

Performance follow-up must decompose synchronous event fsync, projection and
business time under the same conditions. Durability has not been reduced; no
zero-overhead or stable P95 claim is made.

## Reproduce

At Builder 8b302d7f, from community/finance/builder/finance-solution-builder:

```sh
PYTHONPATH=src python -m pytest -q
npm run build:ui
PYTHONPATH=src python engineering/build_history_candidate.py --harness <harness-9a286a7> --output <new-build> --unit all
PYTHONPATH=src python engineering/verify_model_snapshots.py <new-build>/candidate --harness <harness-9a286a7> --output <new-evidence>
PYTHONPATH=src python engineering/verify_snapshot_browser.py --harness <harness-9a286a7> --source <new-evidence>/execution-history/<owner-hash> --output <new-browser> --chrome <chromium>
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
