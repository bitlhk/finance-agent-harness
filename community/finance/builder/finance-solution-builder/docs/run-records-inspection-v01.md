# Run record inspection V0.1

The workbench inspects persisted evidence. Opening a record, changing a tab or
reading a model snapshot does not invoke an Agent, model, Skill or enterprise
interface. Do not repair historical gaps by re-executing a financial action.

## Reading a record

Execution result and recording coverage are separate. A completed service request
does not prove that a financial transaction succeeded. Inspect the capability's
recorded business status; the bank's task lifecycle remains authoritative.

The process tab uses recorded span relationships. Child identity/permission checks
can be expanded below their actual parent. Checks without a recorded parent remain
separate; failed checks must remain visible. No event is deleted by this view.
Overlapping or nested durations must not be summed as wall time. The longest stage
is an interval worth inspecting, not an automatically diagnosed root cause.

Model interaction uses request/call/span identifiers, not row position. Input is
the client-prepared request; output is the SDK-parsed response. Neither boundary
proves HTTP delivery. A body reference means **awaiting body-read verification**,
not reviewed answer quality. On authorized read, body digest and request/call/span/
owner binding are checked before content is displayed. Original capture state and
current read state remain distinguishable.

## Missing bodies

New Java hosts append a small `model-snapshot-storage` fact after the asynchronous
body writer finishes. It identifies the request, call, part, span, digest, timestamp
and WRITTEN/WRITE_FAILED result. This optional storage fact contains no prompt,
tool result, customer text, key or exception message. It can legitimately arrive
after the business request terminal event; it is not a late business operation.

Only observed failures are classified. A queue reference with no terminal storage
fact does not prove that a writer is still running. Old unavailable bodies retain
the explanation that the specific reason was not recorded. A matching expiry
marker proves expiry; a verified digest mismatch proves changed/corrupt bytes, not
their cause. View authorization is distinct from business authorization and from
capture authorization. No new permission is granted by this interface.

Write-result recording uses the existing bounded archive, and can itself fail. In
that case the absence remains explicit; no fallback plaintext log is created.
Observation failure never changes an already completed business outcome.

## Compatibility and extension

Existing generated Agents and immutable Locks are unchanged. They do not acquire
new write-result records until explicitly rebuilt. Frozen component descriptions
are used for historical business names; legacy fallback is labeled as such.
Provider authors add versioned operation descriptions and real transport events.
They must not label HTTP 200 as business success or claim unobserved retries.

List paging and detailed retrieval remain owner-scoped. Customer scopes are not
permissions to inspect another workbench owner's history. Default persistence
omits customer questions, raw tool bodies and hidden reasoning; if no allowed
summary/name was saved, the UI must not infer one from snapshots.

The list requests 20 records per server page. Signed cursors bind the owner and
filters and exclude subsequently inserted records above the initial sort bound.
This is a live keyset view, not a frozen database snapshot: backdated inserts,
retention and damaged files can still change later pages. Backend restart invalidates
cursors; refresh the filters to start again. The workbench displays and filters by
Beijing time (UTC+8) through an explicit UTC half-open range. The legacy `date` query
retains UTC semantics for existing callers. Metadata scanning is
linear in the owner's file count; full record reconstruction is page-bounded.

An explicit snapshot-view authorization refusal clears all cached bodies. The
interface does not poll continuously for revocation: an already delivered body
cannot be retracted from a viewer's machine. Each new body read checks current
view authorization independently.

## Verification

Run the corresponding history/snapshot Python tests and browser tests against an
isolated workspace. Inject malformed, expired, unauthorized and write-failed body
cases only there. Confirm rapid selection cannot display another call's response,
and historical reads add zero model/enterprise HTTP requests. Screenshots from a
design mockup are not acceptance evidence. Record actual source and artifact
versions, counts and measured list/detail latency in the release report.
