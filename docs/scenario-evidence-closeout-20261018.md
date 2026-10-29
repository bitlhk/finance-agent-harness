# Scenario A/B release and bounded validation patches

This is the current index replacing the status interpretation of
[the historical A checkpoint](scenario-evidence-a.md). It does not overwrite
original failures or claim all four business paths passed.

## Published baseline and evidence

Published Harness: `74498487b5dc358a26c91132475a525f5da8314c`.
Published Builder: `68884b05c9657ee289cd1b604941283e583c9bd8`.
Developer-guide PR73 remains in the Harness baseline.

Maintainer-local evidence (not public download URLs):

- `/home/ubuntu/outputs/scenario-a-closeout/RESULT-FINAL.txt`
- `/home/ubuntu/outputs/scenario-a-closeout/MATRIX-FINAL.tsv`
- `/home/ubuntu/outputs/scenario-a-closeout/frozen-closeout.json`
- `/home/ubuntu/outputs/scenario-ab-release-20261018/RESULT.txt`
- `/home/ubuntu/outputs/dot-four-counts-20261018/remote-evidence.json`
- `/home/ubuntu/outputs/dot-four-counts-20261018/RESULT.txt`

The A5 general-history authority adapter, audit concurrency/generation checks,
paired package tests and seven-group installation work were completed after
the original checkpoint. The release report is the source for deployed identity
and qualification binding, not the old checkpoint. Cloud CI is NOT_RUN where
reported; internal tests and installation receipts do not turn it into PASS.

## Independent ordinary-SSO observations

Dot reported one first business execution per new Agent, with no retry selection:

| Scenario / Agent | Result and retained report |
|---|---|
| Manager `agent-17367d78371f424c` | 100/30/70 facts and first explanation passed; v3 plus reviewed v4 retained; stopped history readable |
| Audit `agent-cdc255db54ac425f` | Both differences 173456.78; explanation rejected due to authenticity negation; no review saved; saved-evidence history readable |
| Fraud `agent-f68bee3a954840b4` | Object B matched; v3 plus reviewed v4 retained; stopped history readable |
| Postloan `agent-a1079f6ea89e4e5d` | Deterministic ORANGE correct; freshness pending statement incorrectly rejected; v3 PARTIAL retained; no review saved; stopped history readable |

These UI results belong to Dot, not the code author's browser execution.
Read-only server inventory at 2026-10-18 06:26:38 UTC: 12 retained records
(10 STOPPED, 1 BUILT, 1 FAILED); the three new managed instances are STOPPED;
audit is a dedicated-workbench BUILT artifact without a managed instance.
No listeners remained on their three recorded ports when checked. This agrees
with Dot's 12/36 artifacts and 0/2 managed-instance display.

## Count interpretation

| Initial business | Recorded business dispatches/results | Captured SDK response call IDs | Persisted authorization decision records |
|---|---:|---:|---:|
| Manager | 1 returned previsit binding; capability span missing | 1 | 9 |
| Audit | 2 completed toolAttempts | SDK attempt count not independently captured; one saved explanation | 5 child checks + 2 parent checks |
| Fraud | 1 capability start/end and returned binding | 1 | 5 |
| Postloan | 2 capability start/end and returned bindings | 1 | 7 |

These are different observation layers. A logical Provider query may make
multiple underlying source requests. Authorization checks are not business
queries; 9/7/5 are persisted decision counts, not all underlying authorization
source HTTP calls. SDK call IDs do not expose network retry/HTTP counts.

Available manager/fraud/postloan request archives contain respectively 5/10/3
requests, only one business execution each. Other captured selection/history/
review requests have zero capability starts and zero model response call IDs;
all captured terminal records report complete=true and zero dropped events,
listener failures and sink rejections. This establishes the archived-request
observation, not a network-level zero for all later UI actions.

Persisted HISTORY_RELEASE decisions: manager 15, fraud 8, postloan 4, including
review reads and version-list expansion. They are not a count of UI clicks.
Stopped generic-history reads recheck current scene authority without an active
Host, but do not persist a complete per-read Provider/model/authorization counter.
Therefore the post-stop read deltas are not independently reconstructible as a
numeric network total. Dot's successful reads plus the read-only implementation
support saved-evidence replay; do not turn missing counters into measured zeros.
Audit replay checks material/report/review digests and returns
SAVED_EVIDENCE_ONLY / executedDuringReplay=false; its per-read authorization and
SDK counters are also not independently retained. No evidence of repeat business
execution was found. The two frozen failed reports retain their original hashes.

## Minimal candidates, not deployed

- Harness `a43ea8e055b0c42bbfe35de280328e14b69a6d23`: audit authenticity negation
  scope and frozen 2565-character response; 37 targeted tests passed.
- Harness `a6416d07b7bc3c3334503ca82e6a3d63370861c9`: includes the audit patch and
  adds postloan pending-freshness handling; frozen 1305-character output and
  37 targeted postloan tests passed. No invented freshness policy.
- Builder `49b4056787660476539409969b05aebc218fa0f5`: separate execution and
  explanation status, safe Chinese reason, collapsed unvalidated raw output.
  32 Python tests, 52 viewport assertions and 9 interaction assertions passed;
  these are separate counts, not unique business cases.

Proof indices: `/home/ubuntu/outputs/audit-authenticity-20261018/RESULT.txt` and
`/home/ubuntu/outputs/postloan-freshness-20261018/RESULT.txt`. Both retain failing
before-tests and packaged-JAR checks. This document-only follow-up does not
change either runtime implementation. Old reports stay failed; old Agents are
not automatically upgraded.

Combined installation impact: G02 audit (3 combinations) and G04 postloan
(1 combination) reference changed JAR contents and require new matching
qualification/Lock/installation binding. Other five groups do not reference
those two changed JARs; reuse still requires final dependency/config equivalence.
No seven-group or 85-combination rerun is implied. A combined small release is
reasonable only after these two groups close and Dot approves; it is not yet a
deploy-ready qualification claim.

## Deferred display issues

- Explain that ending a service session does not stop the managed instance.
- Capacity badge can briefly lag the stopped card until refresh.
- Do not say Candidate assembly is finished while BUILDING.
- Generic history should distinguish execution completion from scene PARTIAL.
  This last item can mislead business interpretation: until separately fixed,
  generic COMPLETED must not be presented as business/model validation success.
  It is explicitly pending, not silently included in this patch or marked fixed.

No business rerun, deployment, restart, authorization change or original-record
mutation was performed for this read-only count review. Phase1/2/3 boundaries
remain unchanged; the report does not certify bank-production quality or safety.
