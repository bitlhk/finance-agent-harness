# Workbench Backend API V0.2

This is a Finance Solution Builder scenario extension on Builder `728e339` and Harness main
`b55d0722cac2905388f57dc4a17b85d346a0e470`. It does not change Base, Harness Core, SDK source,
the existing page, or the service running on 18128. Builder upstream acceptance is separate.

## Scope and Compatibility

The new `/api/v1/` facade uses the same Jobs queue, ownership, processes, host, provider,
authorization and build pipeline as `/api/jobs`. The URL denotes the first versioned HTTP API;
payload `schemaVersion` is `finance-workbench-api.v0.2`. Old four-field creation and old response
shapes remain supported. The new selection is a strict `ExtensionModel`, not a new Base contract.

One application template (`banking-readonly-web-v1`) wraps `templates/local-readonly-service`.
It means a CUSTOMER_SELF read-only Web host, not a native package, employee authorization, or
an already-delivered independent page. Phone/desktop are UI presentation choices only.
Only OpenJiuwen Java 0.1.13 and wealth, balance, or their combination are enabled.
Unknown fields, arbitrary template paths, URLs, scopes, subjects and credentials are rejected.

## HTTP Contract

All versioned endpoints require the existing HttpOnly owner cookie. Call `GET /api/bootstrap`
first; POSTs also require its `X-CSRF-Token`, JSON content type and same-origin/loopback checks.
Never expose the Java Host token, bank session, policy file or credentials to a browser.

| Method and path | Request / behavior |
| --- | --- |
| `GET /api/v1/catalog` | Finite runtimes, application templates, model/bank connections, capabilities, mandatory controls, selection JSON Schema |
| `GET /api/v1/connections` | Local configuration metadata; no model/bank network access |
| `POST /api/v1/preflight` | Strict Selection below; checks local executables, source assets and configuration only |
| `POST /api/v1/builds` | Same Selection; recomputes build preflight, returns 202, uses existing real queue |
| `GET /api/v1/builds` | Only current owner's build views |
| `GET /api/v1/builds/{buildId}` | Selection, real stages, build/artifact/instance/session references, safe errors |
| `GET /api/v1/builds/{buildId}/optimization-view` | Owner-scoped, read-only projection of verified Evaluation and Optimization records |
| `POST /api/v1/builds/{buildId}/optimization-runs` | Trusted control-plane trigger using only digested Request and baseline Evaluation references already owned by this Build; returns the verified read view |
| `POST /api/v1/builds/{buildId}/start` | `{artifactId}`; checks configuration and frozen lock before spawn |
| `POST /api/v1/builds/{buildId}/stop` | `{instanceId}`; stale instance cannot stop a replacement |
| `POST /api/v1/builds/{buildId}/sessions` | `{instanceId}`; trusted host creates identity, grant, session and actual installation evidence |
| `POST /api/v1/builds/{buildId}/chat` | `{instanceId,sessionId,query}`; same native host authorization before every model call and final output |
| `POST /api/v1/builds/{buildId}/authorization` | `{instanceId,sessionId}`; current authorization check, no model call |
| `POST /api/v1/builds/{buildId}/revoke` | `{instanceId,sessionId}`; existing revoke/clear path |
| `GET /api/jobs/{buildId}/artifact` | Existing owner-scoped, manifest-whitelisted export; no new download implementation |

Selection:

```json
{
  "extension_type": "finance-workbench.selection",
  "schema_version": "0.2",
  "runtimeId": "openjiuwen-java-0.1.13",
  "applicationTemplateId": "banking-readonly-web-v1",
  "modelConnectionId": "configured-model",
  "bankConnectionId": "local-simulator",
  "capabilityIds": ["wealth.facts.describe", "account.balance.query"]
}
```

Capability order is canonicalized; duplicates fail. Configuration cannot disable trusted identity,
current authorization, the read-only boundary, or evidence collection. Selection is saved before
queueing and translated by the existing `solution_for` recipe; the fixed template source is locked
by Builder commit and manifest file hashes. Legacy builds are marked `LEGACY_FIXED_RECIPE`.

## Connections and Preflight

`configurationStatus` is MISSING, INVALID, or CONFIGURED_UNTESTED. URL syntax and presence/readability
of the local policy file are checked; policy content, identity, scopes and connectivity are not
certified. `connectivityStatus=NOT_CHECKED` and `authorizationStatus=RUNTIME_CHECK_REQUIRED` remain
explicit. `secretRef` is an opaque logical label, not a dereferenceable file path or credential API.
Only a conservative model-name token is displayed; no endpoint, login or key is returned.

`canBuild` depends on the finite selection, executable presence and asset presence. It does not
promise successful compilation. Missing model credentials still allow artifact generation.
`startPrerequisitesSatisfied` additionally requires configuration; it does not mean the instance
is started or any customer authorized. Submit and start recalculate their own prerequisites.
No preflight response is an authorization grant. There is no connection-edit or network-test API
in this batch. Server-side `FH_MODEL_MODE=CONTROLLED_TEST` explicitly labels an isolated test model;
default LIVE means configured external/local model, never a fallback script.

## State and Evidence

- `buildId`: one generated selection/version. Rebuild creates a new ID.
- `artifact.artifactId`: `sha256:` plus the actual immutable service-lock digest. It is null before
  a compiled, matching manifest exists. File bytes are reverified by the generated host at start.
- `instance.instanceId`: observed Java host UUID for one process generation; restart changes it.
- `instance.sessionId`: current host-created session; restart/revocation invalidates it.
- `runId`: server-generated API chat-attempt correlation, not a new native Runtime trace ID or grant.

The three stages come from actual `build-report.json`: harness_contracts, harness_tests_install,
candidate_package. States map RUNNING/PASS/FAIL to RUNNING/SUCCEEDED/FAILED. Unobserved stages stay
PENDING; there is no synthetic percentage or timed success. Build and instance states are separate.
A compiled artifact survives instance failure and remains exportable. Retry launch is permitted only
when a valid completed build exists and no old owned process is still live; it rechecks the lock.
Do not label BUILT as READY or READY as AUTHORIZED. A failed/unfinished build cannot be launched.

`sources` contains recorded Builder/Harness commits and dirty flags when available. Stage output
excludes raw commands, logs, paths and exception bodies. `artifact.verification` means manifest
association only, not a signature, full runtime attestation, financial certification or deployment.
`links.agentPage=null` until the parallel UI route is implemented. The portable bundle currently
contains the service, not a new standalone browser login system.

Session creation returns actual `installations`; chat's `result` is the already-authorized host
response containing tool results, source calls, runtime lock, evidence hash and event metadata.
Do not cache these sensitive result bodies in a public GET endpoint. No new customer transcript
archive is created. Errors have `{code,retryable}` and suppress upstream bodies. `INSTANCE_CHANGED`
returns 409/clearConversation; authorization denial is 403/clearConversation. UI must reject late
results whose build/instance/session/run reference no longer matches its active view, and clear
corresponding protected content after denial. Identity checks in the backend remain mandatory.

## AG-UI Boundary: Design Only

The [Studio/model-selection extension](model-selection-ui-v02.md) now connects the
existing UI to this facade and adds versioned multi-model selection. It does not
change the AG-UI or retention boundaries below. The original four-field and 0.2
single-model requests remain supported; multi-model callers use selection 0.3.

Current transport is synchronous JSON, NOT AG-UI and NOT token streaming. The
[official event reference](https://docs.ag-ui.com/concepts/events) informs this proposal, not a
claim of implemented compatibility. A later host adapter
can map actual run start/end/error, message and tool events to a pinned AG-UI schema. Existing
build stages are build progress, not native model-reasoning events.

Minimal future mapping: accepted invocation -> RUN_STARTED; finally authorized text ->
TEXT_MESSAGE_START/CONTENT/END then RUN_FINISHED; denial -> RUN_ERROR plus a documented custom
clear-session state (there is no invented standard revocation event). Run IDs bind to owner,
build, artifact, instance and session on the server. If tools are exposed, tool events must derive
from observed native calls, with sensitive arguments/results withheld until authorized delivery.

Before every model dispatch retain the existing authorization callback/force-finish mechanism.
At final output retain current authorization and generation checks. Buffer financial text and
tool results until final release; clearing the DOM cannot retract bytes already sent. Safe observed
status may stream earlier, but this is not a claim of real token streaming. Reconnect must not
resubmit the model request or replay unauthorized financial content. Protocol compatibility,
disconnect/ordering cases and streamed authorization behavior need their own future tests.

## Storage and Reproduction

No automatic archive or deletion in this batch. Later cleanup preview must list owned files and
protect active/queued/building/unknown-owned processes, pinned releases and unresolved evidence.
Archive metadata belongs outside locked artifacts; do not add ARCHIVED to execution state. Retain
an index of selection, commits, lock hashes, reports and explicit retention decisions. This policy
is not an implemented cleanup endpoint and does not block UI integration.

```sh
.venv/bin/python -m pytest tests -q
.venv/bin/python engineering/export_workbench_api_examples.py
.venv/bin/python engineering/verify_workbench_api.py --harness <isolated-harness-checkout> --output build/api-verification-unique
```

The last command performs three real serial Maven builds, starts generated hosts and exercises
owner-scoped HTTP, two-turn native tools, revoke, restart and stale-tab rejection. It creates only
local synthetic model/bank HTTP endpoints, never reads a private model configuration or calls a
paid model. Keep its report separate from real-model, complete-simulator and human/UI acceptance.
Use a separate Harness checkout from active workbench builds to avoid shared Maven target races.
Old generation and revocation regression entrypoints remain unchanged and must also be rerun.

## Evaluation And Optimization Workbench

The actual build page can show how one Candidate moves from baseline Evaluation to a delivery
decision. This view is a projection of immutable records; it is not another optimizer and exposes
no start, approve, resume, mutate, or retry operation. A normal Build for which Optimization has
not been run returns `status=not-executed`, so the UI never presents build success as business
acceptance.

The control-plane endpoint calls `Jobs.run_optimization_from_records(...)`, which verifies that
both supplied records are digest-bound files below the selected Build and resolves only
server-configured correction, rebuild, Evaluation and proposal adapters. It then delegates to
`Jobs.run_optimization(...)`, which runs the accepted
`OptimizationController` against one owned, completed Build. It records the baseline and later
Evaluation reports under that Build, normalizes correction/rebuild/proposal evidence to the same
Build root, derives the complete binding from the Controller's immutable request, rounds and
result, and asks the projector to verify the view before publishing it. The browser does not
render an Optimization trigger and cannot submit a model command, host path or adapter selection.
The endpoint fails closed when the deployment has not installed a trusted gateway factory;
`Jobs.build(...)` does not start Optimization implicitly.
The HTTP handler additionally requires `X-Builder-Control-Token`, whose value is supplied through
the server-only `FH_OPTIMIZATION_CONTROL_TOKEN` environment variable and is never returned by
`/api/bootstrap`. Owner cookie and CSRF checks still apply. A deployment embeds its reviewed
gateway factory through `serve(..., optimization_gateway_factory=...)`; the default standalone
Workbench rejects execution rather than substituting test adapters.

After this verification, the server stores an internal `OptimizationViewBinding` under the owned
Build record. It contains
digested workspace references to the Candidate Descriptor, current Solution Lock, Optimization
Request, baseline Evaluation, ordered round records, optional result, follow-up Evaluation reports,
and public Asset Change Proposals. The binding itself is omitted from ordinary build responses.
All locators must remain below the Build directory, have a SHA-256, and be unique.

Before returning a verified view, the projector checks:

- every referenced file exists below the owned Build root and its byte digest matches;
- Request, Evaluation, round and result identities form one ordered Optimization run;
- each Evaluation is bound to the expected Candidate, Descriptor, Lock, artifact, Eval Pack,
  dataset, rules and evidence level;
- the current Candidate Descriptor and Solution Lock match the best snapshot;
- a public Asset Change Proposal is referenced by a round and contains only sanitized summary,
  scope and target metadata.

If any check fails, the response is `status=unverifiable`, contains only a stable issue code, and
omits the untrusted record content. A verified response distinguishes `executing` and `completed`.
Its ordered `storyline` contains stage title, status, business-readable summary and public
input/output references. The Evaluation section exposes metrics, aggregate progress and safe sample
facts: sample ID, dataset ID, execution status, matched flag, duration, error stage and error code.
It never returns Query text, expected/actual payloads, prompts, reasoning, credentials or process
logs. Each round exposes finding summaries, selected responsibility target, approval fact, parent
and candidate Lock digests, change digest, metric deltas, promotion result and stop reason. Proposal,
Finding and reference text that is not public-safe fails closed. The UI shows a recorded Build time
when it exists and explicitly says that the source record did not provide a stage time otherwise;
filesystem timestamps are never substituted for business events. External Harness proposals are
shown separately with their target, status, public summary and permitted scopes.

Example empty response:

```json
{
  "schemaVersion": "finance-workbench-optimization-view.v1",
  "buildId": "agent-example",
  "status": "not-executed",
  "verifiability": {"status": "not-executed", "issues": []},
  "storyline": [],
  "evaluation": null,
  "rounds": [],
  "result": null,
  "nextAction": {
    "kind": "run-evaluation",
    "label": "先运行 Candidate Evaluation",
    "reason": "当前 Build 尚未绑定评测与优化记录。"
  }
}
```

The Browser polls this GET endpoint together with the build view. Clicking a Storyline node updates
only its detail panel, preserving the page scroll position. Backend-provided `nextAction` is an
explanation for the Solution owner; it is not an executable UI command.
