# Historical model interaction snapshot: review candidate

This is an isolated, non-streaming vertical slice, not a production release.
PR #13 owns Java 0.1.15 qualification; this dependent branch owns snapshot code.
No PR was merged remotely and no stable service/Agent was upgraded.

## Implementation

- Integration registers the distinct FinanceSnapshotOpenAI client only when the
  Host has explicitly enabled and authorized capture. It does not replace the
  default OpenAI factory. Default Trace uses the default client.
- A synchronous thread-local scope correlates native prepared request maps and
  parsed responses to one model call. A concurrent two-client test verifies
  isolation and scope cleanup. Receiver exceptions do not change model results.
- Builder Host freezes an allowlisted/redacted representation in the callback,
  then queues bytes, not mutable SDK objects. Reasoning/vendor fields are not
  selected. Known credentials/customer/session identifiers are removed.
- Request metadata durably references request/call/span/part and a body digest
  before enqueueing. If the reference cannot be recorded, no body is enqueued.
  Existing immutable request locks provide artifact/version linkage.
- Body retrieval requires both the owner session and a separately configured
  snapshot view grant. Trace access alone is insufficient. Reads verify digest
  and owner/request/call/span binding and never execute the Agent or bank.
- The history UI offers request/response expansion under model spans and an
  overall snapshot section. Text is escaped; a synthetic HTML injection test
  verifies that it remains text. Old records show no snapshot collected.

CLIENT_REQUEST_PREPARED does not mean HTTP_SENT. PARSED_MODEL_RESPONSE is not
raw HTTP. Filtered client tool-result content is not an independent bank-source
response capture. No hidden reasoning or automatic root-cause conclusion is shown.

## Actual evidence

This table is the pre-P2 slice evidence. The current closeout is recorded in
[P2 correctness closeout](2026-09-12-model-snapshot-p2-closeout.md), with a new
Builder commit and freshly generated candidate. Old snapshots are not repaired
or relabelled by the new filter.

Evidence root: `/home/ubuntu/outputs/model-snapshot-v01/`.

| Validation | Observed result / exact scope |
| --- | --- |
| Harness Java after latest generated build | 218 tests: 211 passed, 7 explicit smoke skips, zero failures/errors |
| Harness Python after version expectation fix | 21 passed; initial separate worktree lacked generated Java timestamp fixture |
| Builder Python | 454 passed |
| Generated Host Java | 12 passed, including freeze/redact/truncate, queue rejection, unavailable directory, quota, metadata failure |
| `compositions-final/report.json` | Seven regenerated combinations passed; four read-only capabilities, zero writes |
| `review-controlled/report.json` | 88 checks; 16 persisted bodies, separate view grant, restart reads, zero history-triggered HTTP |
| `history-with-snapshots/report.json` | 51 checks with capture enabled: revocation, cancellation, concurrent sessions, process interruption, storage degradation |
| `browser-final/report.json` | Five real browser/API checks; explicitly owner-remapped synthetic records; desktop/mobile screenshots |
| `live-1/report.json` | 22 checks, actual configured deepseek-v4-flash plus full synthetic bank; four read capabilities, revocation denial; quality NOT_SCORED |
| `overhead-1/report.json` | 12 samples/mode after two warmups, alternating order; timing and storage observations, not stable production P95 |

`review-all/candidate/service-lock.json` identifies Harness b9e9d249 and Builder
f2731a71, both clean, Runtime 0.1.15. Lock SHA-256:
`d5c78649182bd85948ee0af1ba70f024344c9cfcd9779db0c36a731e6b7d6756`.
Later Harness changes only synchronize a Python test expectation, not runtime
code. Builder 9e1906cd subsequently changed only the history reader and its test:
unverified body references show BODY_NOT_VERIFIED. It is not the source SHA of
the f2731a71 generated Host. Earlier live/overhead runs have their own lock
`3f7a9192cbf6f29f8745fcdee5912d4fc4625b2d0d43d6159b3f362db820e7d8`;
they must not be relabeled as runs of the final candidate. The metadata-failure
guard was added after the seven-composition run and verified in the fresh all
candidate and its generated tests/88 checks.

Initial browser probes exposed test harness issues (signal registration in a
thread, an unrebuilt UI bundle, then a generic public denial code); these were
corrected and the final browser probe passed. Initial remote PR13 CI exposed an
unmigrated Python 0.1.13 expected string; the strict expectation was updated to
0.1.15, not weakened. Remote CI status must be read for the latest submitted SHA.

The first overhead observation had median milliseconds off=69.68, Trace=187.45,
snapshot=195.03, with stored bytes 0 / 915941 / 1862539 respectively. Includes
incremental fsync and local fixture behavior. No zero-overhead or financial
quality claim follows from this small experiment.
The Trace increase is a performance investigation item: separately measure
synchronous event fsync and business-stage time before proposing changes. The
P2 correction does not reduce durability or introduce a new storage platform.

## Reproduction and isolated entry

Use the Builder `feat/model-snapshot-v01` branch, its existing venv/dependencies,
and this Harness branch. From its `community/finance/builder/finance-solution-builder`:

```sh
PYTHONPATH=src python engineering/build_history_candidate.py --harness <harness> --output <new-build> --unit all
PYTHONPATH=src python engineering/verify_model_snapshots.py <new-build>/candidate --harness <harness> --output <new-evidence>
PYTHONPATH=src python -m finance_solution_builder.workbench --harness-repo <harness> --data-dir <private-workspace> --port 18428
```

The last command is the isolated loopback UI entry at http://127.0.0.1:18428,
not an already-deployed public endpoint. Configure the current operator's hashed
owner reference in `FH_SNAPSHOT_CAPTURE_OWNERS` and `FH_SNAPSHOT_VIEW_OWNERS`, and
set `FH_SNAPSHOTS_ENABLED=true` on the operator-controlled server. Do not give
page parameters the ability to grant these rights. Runtime/model/bank configuration
follows the existing private host instructions. The automated browser reproducer
uses a separate synthetic history copy, not a customer history authorization bypass.

## Remaining release gates

- Snapshot capture is synchronous non-streaming only. Stream first-chunk and
  partial-failure probes qualify transport observations, not streaming snapshots.
- The native SDK request-trace slot supports one callback. This opted-in adapter
  is not qualified to coexist with another native request-body trace collector.
- Queue=16, body limit=48000 bytes, body files=128 per owner are fixed conservative
  candidate bounds. Configurable retention, flush policy and institutional DLP
  remain work before broader release. Process exit may lose queued bodies, which
  must remain missing rather than complete. No automatic destructive cleanup.
- Redaction is not a universal guarantee for arbitrary private free text; this
  acceptance uses synthetic bank data. True customer-content collection is not
  authorized or accepted by these experiments.
- No new Python Finance Agent implementation was discovered. Need its repository,
  fixed commit, generated artifact, dependency lock and launch/identity proof.
- No human independent acceptance, actual-day retention, production deployment,
  multi-node storage or real financial transaction acceptance.

This slice proves a filtered historical model interaction can be inspected,
including after restart. It does not claim the entire A/B/C/D program complete.
