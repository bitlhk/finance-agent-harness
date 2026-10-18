# Scenario evidence A candidate

Scope frozen before implementation: synthetic customer-manager, credit-material
review, post-loan and fraud; existing authority policies, same-owner review.
No new roles, permission source, storage service, UI, or production mutation.
Phase1/2/3 remain closed. Digests are integrity links, not signatures or WORM.

## Frozen acceptance (A1-A6)

| ID | Checkable input / fault | Required assertion |
|---|---|---|
| A1.1 | Reordered object keys; mutate supplied nested parameters | Stable canonical digest, immutable frozen binding |
| A1.2 | Changed owner/instance/object/purpose/material/parameters | Different binding; caller cannot override trusted Host fields |
| A2.1 | Authorized deterministic result | ENTRY and RETURN refer to same binding; release evidence precedes actual output |
| A2.2 | Change authority/object while source or model is executing | No late result/delta to model or page; existing authority policy retained |
| A2.3 | Manager product query changes amount/period/channel | Distinct bound request, no reuse of earlier allow |
| A3.1 | Necessary ENTRY evidence write fails | No source/model invocation; safe evidence error |
| A3.2 | RETURN/release evidence write fails after invocation | No result released; observed dispatch not relabeled NOT_SENT |
| A3.3 | Missing inputs, source failure, model failure | Existing error/missing-input semantics; facts kept only when authorized |
| A4.1 | Tamper audit report/material/review linkage | Replay refuses altered content; no runner invocation |
| A4.2 | Concurrent/stale review revision or wrong run | No overwrite or cross-run review |
| A5.1 | Wrong owner; revoked CRM or changed subject mapping | Existing history/review policy rejects; no fabricated bank grant |
| A5.2 | Old records without new binding | Existing integrity/authority checks; never synthesize historical evidence |
| A6.1 | Existing cashflow and shared observation/history contracts | No semantic regression; test execution counts separate from cases |
| A6.2 | Generated Host binding and packaged dependencies | Actual source/Lock binding required; qualification impact enumerated |

New necessary records reuse existing private report/run directories. Failed
storage cannot be promised to record its own failure. Caller receives a stable
safe code; prior ENTRY evidence is retained where already persisted. Provider
invocation count is not HTTP count; authority checks are not business queries.

History policy is NOT generalized from cashflow: manager requires current CRM,
risk scenarios current mapping, credit audit current owner/material ownership.
Old Agents are not upgraded by a new source commit. Rollback uses previous
release and leaves additive evidence files; do not rewrite older reports.

## Candidate boundary and release blockers

This is an incomplete review checkpoint, NOT a deployable A1-A6 release.

* A1: canonical immutable evidence and managed Host fields are implemented.
  Bootstrap history has trusted Host identity but no observation request;
  its correlation is explicitly NOT_OBSERVED. Business queries require a
  real Host observation request. Direct legacy constructors remain labeled
  LEGACY_LOCAL_CALLER, never presented as managed-host validation.
* A2/A3: the three managed scenarios check entry, return and model/page release.
  Credit has parent run evidence and a child task-generation/owner/session
  check at Provider and model boundaries. The combined generated credit package
  protocol is not independently exercised yet. A check is not atomic revocation.
* A4: credit replay now verifies report content against reportDigest, rejects
  missing completed reports, checks record ownership, and verifies evidence
  before review. Full concurrent material-update/review serialization is NOT
  demonstrated by the current tests.
* A5: scenario report reads retain existing CRM/mapping/owner policies. Generic
  Builder ExecutionHistory get/list/snapshot remains a blocker: it uses owner
  isolation and a snapshot whitelist, not the scene's current resource authority.
  There is no existing cross-process scene-authority callback in that reader.
  Do not silently treat owner as CRM authorization or hide this gap behind
  passing scene replay tests. A bounded internal adapter contract must identify
  frozen scene/object bindings, reuse the trusted authority source without an
  active Agent, and define old-record handling without fabricated bindings.
* A6: Java reactor packages and focused tests pass; this does NOT establish
  actual generated Host installation or service-identity qualification.
  A prior cashflow packaged fixture selected an obsolete Harness by default;
  its 22 setup errors are retained and are not candidate business failures or
  passing compatibility evidence.

Qualification impact: core content changes, so old Collector identities cannot
be assumed valid for any package embedding the new core. Recompute identities
for Java common, credit, fraud, postloan and manager packages; inspect Python
and AgentScope native dependency manifests before deciding reuse. No 85-case
rerun is implied, and no prior qualification has been relabeled as this version.

Migration: evidence files are additive under existing private records. Old
reports are not modified or supplied with invented execution bindings. Credit
parent/child task checks require the paired Builder and Harness changes; no
arbitrary mixed-version compatibility claim. Roll back the paired source and
runtime artifact, retain historical files, do not delete evidence on rollback.
