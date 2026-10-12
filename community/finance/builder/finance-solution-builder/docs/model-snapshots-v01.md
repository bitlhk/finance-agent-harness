# Model snapshot candidate V0.1

Development-only dependent branch on Java 0.1.15 qualification. Not deployed.
This changes the generated non-streaming service template, not Builder Base.
Existing generated instances and their locks are not upgraded.

The SDK client hook observes CLIENT_REQUEST_PREPARED, not HTTP sent. Responses
are PARSED_MODEL_RESPONSE, never raw HTTP or hidden reasoning. The host keeps
the existing authorization callbacks, global native lock and maxRetries=0.
Snapshot scope is thread-local for the supported synchronous Runner path;
streaming/async body capture is not supported by this slice.
When disabled or ungranted the Host retains the default OpenAI client. When
enabled this adapter owns the SDK's single request-trace callback during invoke;
it must not be advertised as simultaneously preserving another native body-trace
consumer's request payload capture. Existing Harness callback timing is separate.
Coexistence with an additional native body-trace consumer is not qualified.

Default remains Trace only. Server-side `FH_SNAPSHOTS_ENABLED=true` and
`FH_SNAPSHOT_CAPTURE_OWNERS` (comma-separated owner SHA-256 values) are both
required for capture. `FH_SNAPSHOT_VIEW_OWNERS` is a separate workbench-side
allowlist; it is not forwarded to the Agent. Neither is a customer business grant.
These values are operator configuration, not page or model inputs.

Known model/session/customer/bank secrets are redacted before queueing, along
with common token and long numeric patterns. Structural keys are allowlisted;
unlisted fields, including vendor reasoning metadata, are omitted. This is a
filtered diagnostic view, not a byte-perfect model request. Business tool content
is model-visible content, not an independently captured upstream bank response.
Free text redaction is not a universal DLP guarantee; enable only for approved
synthetic environments until institution-specific data policy is reviewed.

The V02 structural filter handles request, message, function-call and JSON Schema
objects separately. Schema `properties`, `$defs` and supported definition maps
preserve dynamic names such as `period` and `accountId`; their values are visited
as schemas, not arbitrary payload objects. Unsupported schema keywords or object
literals are explicitly reported as filtered. This is not an unrestricted JSON
Schema serializer or a replacement for the original executable tool schema.

Snapshots carry `changes` (reason and JSON-pointer path), `changesOmitted`, and
original/retained counts when known. Counts for BYTE_LIMIT are the projected
UTF-8 bytes before the body-size guard, not the unfiltered wire size. Limits are
128 collection items or retained map properties, depth 16, 4096 visited nodes,
and 48,000 body bytes. A limit yields TRUNCATED; other projection changes yield
FILTERED for a normally captured call. Up to 16 details are retained; omitted
details are counted, and long paths are marked abbreviated. Partial diagnostic
schemas must not be reused as tool definitions. The request sent to the model
is not modified by these storage limits.

Fixed bounds: queue 16 records, one daemon writer, body limit 48,000
bytes (oversized bodies omitted with TRUNCATED). Operator settings are:

| Setting | Default | Range / behavior |
| --- | --- | --- |
| FH_SNAPSHOT_MAX_FILES | 128 | 1..4096 body files per owner directory |
| FH_SNAPSHOT_MAX_BYTES | 16777216 | 65536..1073741824 body bytes per owner directory |
| FH_SNAPSHOT_RETENTION_SECONDS | 0 | 0..31536000; zero disables deletion |
| FH_SNAPSHOT_FLUSH_MILLIS | 2000 | 1..30000 bounded shutdown wait |
| FH_OTHER_MODEL_BODY_COLLECTOR | none | Other declarations reject snapshot-enabled startup |

Collector exclusivity is an operator declaration, not automatic discovery of SDK
callbacks. Retention expires bodies on a subsequent put/write, not immediately
when their age reaches TTL. A durable digest-bound EXPIRED marker precedes body
deletion; history metadata, locks and markers remain. Marker cleanup and general
archive retention are not implemented. Do not promise timely deletion without a
separate approved lifecycle mechanism. Injected-clock tests verify this behavior.

Metadata references
are appended before queueing; body fsync follows asynchronously. Missing body,
queue failure or interrupted write is never called a complete snapshot. Reader
checks digest and request/call/span/owner binding before returning content.
New Java hosts also append an optional `model-snapshot-storage` write result through
the existing bounded archive. Its absence does not prove that writing is pending.
See `docs/run-records-inspection-v01.md` for read-state and recording semantics.
Graceful exit waits up to the configured flush deadline and reports DRAINED or
INCOMPLETE. Hard process exit may lose queued bodies; historical metadata remains
queryable. Neither storage failure nor flush timeout changes the business result.

History GET `/api/v1/execution-history/{request}/snapshots/{call}/{request|response}`
does not contact the Agent, model or bank. It requires the workbench owner session
and separate snapshot view grant. Ordinary history returns references only.
UI renders an explicit loss summary (reason, path, counts and omitted details)
and returned JSON through text escaping, not as HTML. Older records without
loss metadata say that details were not recorded; they are not reclassified as
complete and their body files/locks are never rewritten by this upgrade.

Generate using `engineering/build_history_candidate.py --unit all`; run
`engineering/verify_model_snapshots.py <candidate> --harness <harness> --output <new-evidence-dir>`.
This is controlled model validation, not real-model or independent-user acceptance.
## P2 closeout evidence

Builder implementation 8b302d7f with Harness 9a286a7 produced a clean, fresh
all-capabilities candidate through the normal Builder (not a copied test tree).
Service lock SHA-256:
`41b976a59c21a733640121a8dfa523e3450c0747aa5c6eda68337cf7fdb82ac8`.

The intermediate d756a4c0 / 077b547b... artifact remains under p2-generated-all;
the final regeneration also checks that filtered-out unknown keys do not
incorrectly report 128 retained properties or a truncation that never occurred.

Evidence root: `/home/ubuntu/outputs/model-snapshot-v01/`.

- `p2-red-candidate`: test-only old implementation, two new tests fail. This
  copied tree has no valid regenerated lock and was never launched as an Agent.
- `p2-final-all`: 15 generated Java tests passed, including nested schema
  names/definitions, 129 messages/properties/enum items, depth, bytes, redaction
  and bounded loss-detail reporting. Build logs include 218 Harness tests
  (211 passed, 7 explicit smoke skips) and 13 schemas / 92 probes.
- `p2-final-controlled/report.json`: 134 assertions, 16 bodies; eight received model
  requests' complete tool definitions match stored definitions. Required fields
  remain present. Independent view grant, redaction, restart and zero-history-HTTP
  assertions passed. Four tools exercise all three existing units.
- `p2-final-history/report.json`: 51 controlled fault checks with capture enabled;
  isolated termination can cause an expected synthetic HTTP BrokenPipe.
- `p2-final-browser/report.json`: seven browser/API checks and desktop/mobile images;
  source lock is recorded. Uses an owner-remapped copy and explicitly injected
  synthetic loss metadata/HTML text to test rendering, not an actual oversized
  model request. Original records are unchanged.
- `p2-final-builder-pytest.xml`: 456 Python tests passed, including reader permissions,
  loss fields, old-format reading and browser summary behavior.

The older seven-composition, actual-model and overhead reports are retained with
their original versions, not claimed as reruns of 8b302d7f. f2731a71 identifies
the preceding generated Host; 9e1906cd changed the workbench reader's reference
state to BODY_NOT_VERIFIED without changing that Host template.

At the historical P2 checkpoint, still unqualified: native body-tracer coexistence, streaming snapshots,
institution-specific DLP, tunable retention/flush and independent human usage.
Current P2 acceptance is controlled, with no new live-model run or deployment.
The earlier off/Trace/snapshot median timings (69.68/187.45/195.03 ms, 12 samples
per mode) require a follow-up decomposition of synchronous fsync versus business
time. No durability reduction, zero-overhead claim or storage-platform rewrite
is included here.

## Release closeout

Current candidate evidence is recorded in the Harness report
`docs/verification/2026-09-12-model-snapshot-release-closeout.md`, rooted at
`/home/ubuntu/outputs/model-snapshot-release-v01/`. It supersedes the above
historical pending items only where new evidence is explicitly listed. The local
reviewer is isolated; this does not constitute stable deployment or human onboarding.
