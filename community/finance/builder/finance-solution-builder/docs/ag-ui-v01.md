# AG-UI V0.1

The Studio uses `@ag-ui/client` 0.0.59; its server adapter uses `ag-ui-protocol`
0.1.22. The browser bundle is checked in and reproduced with `npm ci` then
`npm run build:transport`; no runtime CDN is required.

## Endpoints

- Studio: POST `/api/v1/builds/{buildId}/ag-ui`, authenticated owner cookie,
  `X-CSRF-Token` and same origin. It adapts the existing guarded JSON Host path.
- Newly generated portable Agent: POST `/sessions/{sessionId}/ag-ui`, loopback
  Host Bearer authentication. It directly invokes the same native conversation.
- Existing JSON chat endpoints remain compatible. Old artifacts are not rewritten.
  The new service Lock declares `transport.agUi=AG_UI_SSE_V1`; a Studio capability
  declaration alone does not certify the transport of an older downloaded artifact.

Both accept the standard RunAgentInput subset: threadId is the server-created
session; runId and message id are unique caller identifiers; forwardedProps contains
only instanceId. Supply exactly one new text user message, tools=[], context=[],
state={}. History, customer identity, scopes, tool catalog and model are Host-owned.
Client-authored history/system/tool messages, state injection and resume are rejected.
Bank authorization is separate from Studio login. New runs are not automatically retried.

## Events and Boundaries

RUN_STARTED -> STEP_STARTED(governed_execution) is sent when the guarded run starts.
After native tool/model execution and a fresh authorization check, an authorized
batch contains STEP_FINISHED, TEXT_MESSAGE_START/CONTENT/END, CUSTOM finance.evidence
and RUN_FINISHED. Failure emits CUSTOM finance.clearConversation and RUN_ERROR,
without financial output. A pre-run rejection remains a non-2xx JSON error.

This is SSE lifecycle transport with **buffered final text**, not token streaming.
No synthetic token animation, fictional tool-call event, reasoning trace or success
flag is used. The finance.evidence extension carries the existing native tool/source
results and lock identity; it is not a new AG-UI standard event type. Each native
model call still checks current authorization, including after a delayed tool result.

Client disconnects do not replay the task. A read-only in-flight request may finish
on the Host; stop/revoke remains available separately. The Studio verifies captured
owner, session and instance generation before delivering the final output batch.

## Verification

`pytest tests/test_ag_ui_transport.py` validates official event schemas and negative
input, post-call revocation and stale-instance rejection. The HTTPS deployment smoke
checks real generated code, delayed source revocation against observed model HTTP,
multi-turn history, ownership, restart and cleanup with controlled model/bank data.
Real model and real simulator deployment evidence is reported separately.

References: https://docs.ag-ui.com/concepts/events and
https://docs.ag-ui.com/sdk/js/client/http-agent .
