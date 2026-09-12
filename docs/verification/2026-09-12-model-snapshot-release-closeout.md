# Java model snapshot release closeout

Date: 2026-09-12. Candidate V0.1, not a stable deployment acceptance.
PR12 and PR13 were merged by the parent reviewer; reported main is
`5b02a814`. PR14 remains separately reviewed against main. This work did not
merge PRs, rewrite historical locks, replace stable service, or upgrade old Agents.

## Version and evidence matrix

| Component / artifact | Actual version | Evidence scope |
| --- | --- | --- |
| Harness implementation | 9d958f0c6a2c202ce8afe5caeb3dc4dbdbfadade | Snapshot/observation implementation; later closeout commits are documentation/probes |
| Java Runtime | com.openjiuwen:agent-core-java:0.1.15 | Generated service and page catalog use this version; SDK source unchanged |
| Four-capability Host | Builder ccedabdd7740e122233c249ab520372b3bbbda41 | Clean generation, all three build stages passed |
| Four-capability installation Lock | c2da48e3dae377007b1a67169faaed6d5306fc935afb86a33025b21c0ac4ca71 | Actual model, controlled, history, long-context and overhead evidence below |
| Page-generated balance Host | Builder e303665fca304f5e6cb0798b9e96e238de9f7b0c | Actual page POST/build; 0.1.15, balance only |
| Page balance Lock | 7818b700bd0881518ebe73ab8c5624db9ef81092960c7c6adb50b019ce295c3f | agent-43398f94a1ef49b6; not substituted for the all-capability Lock |
| Reviewer UI | Builder 5857f953, then quota-test-only e303665f | Full-width, wrapped snapshot body, one selected copy, technical metadata collapsed |

Builder commits are pushed to `feat/model-snapshot-v01` in the colleague's
GitCode repository. Upstream maintainer acceptance is still separate. The latest
documentation commit does not change these generated Host implementations.
The original all-capability candidate remains immutable; the UI generation adds
catalog/version alignment and a quota test that respects operator configuration.

Evidence root: `/home/ubuntu/outputs/model-snapshot-release-v01/`.

| Evidence | Actual result | Boundary |
| --- | --- | --- |
| candidate-all | 18 Host tests passed; three build stages passed, 34484 ms | Local warm dependency cache; not an onboarding speedup metric |
| controlled/report.json | 134 checks, 16 stored bodies | Scripted model, synthetic facts; schema matching and owner/view controls |
| history/report.json | 51 checks | Controlled slow/failure/interruption/recovery with capture enabled |
| long-context-2/report.json | 142 checks | Actual native prepared request reaches BYTE_LIMIT; no injected truncation marker |
| live/report.json | 22 checks | Configured deepseek-v4-flash, full synthetic bank, summary/analysis/balance/wealth plus revocation |
| ui-runtime-tests.xml | 457 Python tests passed | Builder module regression |
| ui-build-3/report.json | 11 checks | Actual page balance generation, Lock, history filtering and actual truncation |
| layout-fix-3/report.json | 9 checks | Request/response at 1440/768/390 widths, full model-step screenshots, no history reexecution |

The live check recorded seven allowed business GET attempts, zero business
writes, refusal after revocation without another business read, and no upstream
call on history inspection. It is real-model connectivity/tool-use evidence,
not a model-quality score, real bank IAM acceptance, or production data testing.
Historical seven-combination results remain at their original versions; this
closeout exercises four capabilities in the all-capability Host and page balance,
not a new seven-by-all-fault Cartesian test matrix.

Retained failed preparations are not counted as passing: `ui-build` lacked an
explicit model registry binding; `ui-build-2` hit a CSP-incompatible test wait,
and that candidate independently failed the fixed-128 quota test under a 512-file
operator configuration. The test now fills the effective configured quota.
`long-context` exceeded Host input size before reaching the intended snapshot
limit; `long-context-2` uses valid input size. `layout-fix` originally confused
the static ag-ui.js resource with a conversation endpoint; endpoint matching was
corrected, and layout-fix-2/3 passed. No failure was silently relabeled.

## Durability and measured cost

Same workload, alternating modes, two warmups and twelve samples per mode.
Client monotonic timing; synthetic endpoints; no stable production P95 claim.

| Mode | Median / range ms | Append median ms | Force median ms | Projection median ms | Archive bytes |
| --- | --- | --- | --- | --- | --- |
| Off | 62.20 / 48.67..86.64 | not recorded | not recorded | not recorded | 0 |
| Trace | 176.53 / 154.89..274.64 | 116.32 | 92.35 | 0 | 929072 |
| Trace + snapshot | 186.57 / 168.35..297.81 | 122.28 | 99.46 | 6.13 | 1919301 |

Append includes force and excludes waiting to enter the synchronized append
method. Provider attempt medians were 9.12/9.46 ms and include observation work.
These overlapping medians cannot be added to reconstruct total latency.
Projection excludes asynchronous body I/O; diagnostic timing covers through root
end, excluding its own diagnostic record. Storage totals include warmups.
The evidence shows meaningful synchronous cost, dominated within append by force;
no durability reduction was applied (`force(false)` events, `force(true)` bodies).

Operator quota, expire-on-put retention and bounded flush are implemented in
the Host. Defaults: 128 files, 16 MiB, no automatic deletion, 2000 ms flush.
Expired bodies retain a digest-bound marker; metadata/locks are not removed.
Expiry is not immediate TTL destruction. Marker cleanup remains an operator
lifecycle gap. Flush timeout cannot promise body completion after process exit.
Additional model-body collectors are rejected only when explicitly declared by
`FH_OTHER_MODEL_BODY_COLLECTOR`; arbitrary SDK collectors are not auto-detected.

## Review environment and acceptance boundary

Isolated reviewer: `http://127.0.0.1:18428/`; private login bridge on 18429.
Login secret is only in
`/home/ubuntu/outputs/model-snapshot-release-v01/reviewer/private/login-url.txt`.
Do not include it in reports. Synthetic history copies are owner-rebound for the
reviewer, with body-reference digests recomputed; source reports and Locks remain
unchanged. Reviewer servers and bank tunnel are kept alive, not restarted for UI
hot updates. Stable Shanghai deployment remains unchanged.

Representative historical requests:

- Slow: c431078a-3888-4de6-b46d-edbff6f00c21.
- Interrupted: 2d8148d7-db5b-40fd-b938-a048ba011ed7.
- Truncated: 1b0583d0-de39-498e-92ce-e92c6869043e.

The parent reviewer reports direct Safari navigation before the layout fix:
0.1.15 catalog/Python unavailable, slow-request filtering, timeline, and model
tool-result/schema inspection. After the fix the reviewer inspected code and
complete screenshots; the Mac was locked and final UI was not clicked again.
This is not non-author independent onboarding or customer acceptance.
Real day-long retention, multi-node recovery, institution DLP, streaming snapshot
product support and stable rollout remain unaccepted. Python SDK probing is
separate from Java acceptance and does not establish a Python Finance Agent.

## Reproduction

Use the Builder versioned scripts with explicit fresh output directories:
`build_history_candidate.py`, `verify_model_snapshots.py` (optionally
`--long-context`), `verify_execution_history.py`, `measure_history_overhead.py`,
`verify_snapshot_review_ui.py`, and `verify_snapshot_layout.py` under engineering.
Actual-model smoke uses separately supplied server-side model and synthetic bank
configuration. Do not put credentials in generated artifacts. Historical reads
require the owner session plus separate snapshot-view authorization.
See Builder `docs/model-snapshots-v01.md` for settings and capture limitations.
