# Python model snapshots A3 (candidate V0.1)

## Scope and dependency

This change extends the existing non-streaming Python read-only host, not the Runtime SDK.
It depends on A2 (`e7341e90` / MR151), A1 (MR149), the original candidate (PR147), and
Harness PR15 (`0289de7`). No dependency is silently merged. Java remains necessary for the
owned bridge and shared financial execution/storage implementation. A4 consolidation is separate.

The qualified units are balance, wealth facts and cashflow (summary plus analysis), in their
seven finite compositions. No claim is made for streaming, multimodal, a second model collector,
every future package or arbitrary Runtime/model-client versions.

## What is recorded

The native SDK `LLM_INVOKE_INPUT` transform first performs the existing current-authorization
check. When enabled, it attaches the host-owned `tracer_record_data` collector to that invocation.
The native client supplies prepared request data and its parsed visible response. SDK source,
model serialization, authorization order and tool dispatch are unchanged.

| Field | Meaning |
| --- | --- |
| `CLIENT_REQUEST_PREPARED` | Client-prepared messages, tool definitions and allowed parameters |
| `PARSED_MODEL_RESPONSE` | Parsed visible response, not the complete HTTP response |
| `CLIENT_ERROR` | Generic client failure marker, not upstream exception text |
| `dispatch=NOT_OBSERVED` | Snapshot presence is NOT evidence that HTTP was sent |
| `captureRuntime`, `nativeCallId` | Python provenance and original SDK call identifier |
| `pythonProjectionNanos`, `clockScope` | Local projection duration, not a Java wall-clock timestamp |

Controlled endpoint comparison establishes matching for the tested requests only. It does not
turn client snapshots into wire interception or prove provider-internal retries. Snapshot call IDs
normalize the native 32-digit UUID into the existing UUID contract; the original ID is retained.
Trace request/model-span IDs, immutable installation records and model configuration references
link the content to its execution. Model-visible tool results can be inspected in later requests;
this is not a new independent archive of raw Provider HTTP response bodies.

## Trust, limits and failure semantics

- Trace remains on by default. Body capture is opt-in through trusted host environment:
  `FH_SNAPSHOTS_ENABLED=true` and `FH_SNAPSHOT_CAPTURE_OWNERS=<history-owner-ref>`.
  On the authenticated workbench that reference is the server-derived owner hash, not a model input.
- Viewing additionally needs `FH_SNAPSHOT_VIEW_OWNERS` in the workbench. It is not forwarded to
  the bridge. Customer business grants do not grant access to historical diagnostic bodies.
- Revocation checks still run before models/tools and final output. An in-flight bank response
  after revocation cannot enter a subsequent model request. Previously collected diagnostic
  evidence remains governed by the separate history permission; it is not automatically erased.
- Capture makes an immutable allowlisted copy before asynchronous transport. Hidden reasoning,
  credential fields and known secrets are excluded/redacted. Arbitrary free text is not certified
  as fully anonymized: only approved synthetic/customer data and retention policy may be used.
- Python projection caps depth, collection size, node count and serialized content (48,000 bytes).
  Java re-applies the existing structural filter and storage policy. Truncation and filtering are
  explicit. This bounds retained/transported data, not constant CPU time for every possible input.
- Existing queue (16), per-owner file/byte quota and retention are reused. Relevant settings are
  `FH_SNAPSHOT_MAX_FILES`, `FH_SNAPSHOT_MAX_BYTES`, `FH_SNAPSHOT_RETENTION_SECONDS` and
  `FH_SNAPSHOT_FLUSH_MILLIS`. Python child shutdown has a three-second outer wait: increasing
  the Java flush setting beyond that does not promise a longer Python shutdown. Default two-second
  Java drain is exercised; forced termination can leave explicitly missing bodies.
- Metadata precedes body persistence. Quota, queue or write failures must be displayed as absent
  content, not a successful full capture. Optional storage failure does not change business success.
  A broken shared bridge protocol still fails closed; it is not merely an optional logging failure.
- This host owns the collector when capture is enabled. Declared `FH_OTHER_MODEL_BODY_COLLECTOR`
  conflicts reject startup. No compatibility with a second body collector is asserted.

## Reproduce from this checkout

Use the A2 generation instructions in `python-cashflow-a2.md` to create the three-unit candidate.
Set the package paths explicitly so an older editable installation cannot supply the history reader:

```sh
export PYTHONPATH="$PWD/src:$PWD/../base/src"
python engineering/verify_model_snapshots.py "$CANDIDATE" --harness "$HARNESS" --output "$NEW_RESULTS" --long-context
python engineering/verify_python_snapshot_faults.py --candidate "$PYTHON_CANDIDATE" --harness "$HARNESS" --output "$OTHER_NEW_RESULTS"
python -m pytest tests
```

`CANDIDATE` may be Java or Python; the fault verifier requires Python. Install the documented
Builder test dependencies first. These are controlled model/bank tests. Run them sequentially for
the same candidate because its `instance.json` is an owned lifecycle file. Real-model acceptance
requires a separately approved server-side model binding and synthetic bank access.

No per-package snapshot code is required by the current three units: tools and their results flow
through the common host/model boundary. Providers still implement business mapping, source identity
and HTTP observations; packages still declare scopes and schemas. New packages must add semantic
and authorization cases, not assume that snapshot availability proves financial correctness.

History inspection uses the existing authenticated UI and GET APIs. It never re-executes a model,
Skill or bank request. Old generated Agents remain unchanged; select the Python runtime and
generate a new locked artifact to use this template. Snapshot viewing is diagnostic comparison,
not hidden-reasoning access or automatic root-cause determination.

See [fixed validation and limitations](python-model-snapshots-a3-verification-20260912.md).
