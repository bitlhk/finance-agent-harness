# Model Selection and Studio UI V0.2

This scenario extension builds on Builder `d362291`. No Base, Harness contract,
EA configuration, Runtime source or running 18128 service is changed. The new UI
uses the existing `/api/v1` facade and generated native Java service.

## Configuration Is Not Validation

`examples/model-catalog/ea-models.json` contains the twelve actual EA metadata
entries observed on 2026-09-06. These are not twelve verified Java connections.
The persisted `modelRef` identifies a connection entry independently of display
aliases and model names. Multiple entries for the same model may have different
IDs. Do not regenerate IDs from list positions or URLs.

Refresh a metadata file through the read-only EA adapter (operator CLI only):

```sh
.venv/bin/python engineering/sync_ea_model_catalog.py \
  --ea-repo <EA-checkout> --catalog <existing-catalog-json>
```

This invokes the existing server-side selectable-model reader and exports only
whitelisted metadata. It does not copy credentials or grant runtime access.
Ambiguous identity reconciliation fails instead of silently replacing a binding.
Set `FH_MODEL_CATALOG` to a local metadata file; absent it the checked-in dated
snapshot is used. `__auto`, OpenClaw OAuth and unverified special protocols are
not imported as runnable OpenAI-compatible clients.

The trusted host administrator separately sets `FH_MODEL_REGISTRY` to an external
JSON file with this shape (not inside a generated artifact):

```json
{
  "schemaVersion": "finance-model-bindings.v1",
  "bindings": [{
    "modelRef": "model-ea-002",
    "revision": "v1",
    "enabled": true,
    "protocol": "OPENAI_CHAT_COMPLETIONS",
    "provider": "OpenAI",
    "modelName": "deepseek-v4-flash",
    "urlEnvironment": "FH_CONN_FLASH_URL",
    "keyEnvironment": "FH_CONN_FLASH_KEY"
  }]
}
```

Supply those environment variables only on the server. The registry stores names
of environment variables, not their secret values. Keep it outside exports and
atomically replace it when disabling a connection. Only selected variables reach
the spawned Agent. Existing session clients do not hot-reload rotated secrets;
restart the instance to use changed process environment. No arbitrary connection
editing, endpoint probing or credential retrieval HTTP API is exposed.

Only the native OpenAI-compatible Chat Completions client is currently enabled.
OpenRouter uses this client when explicitly bound with that protocol, but
provider-specific options, real tool calling and service availability still need
per-model validation. Unsupported protocols are rejected, never guessed by name.

## Versioned Selection

Old `schema_version: "0.2"` and legacy four-field requests retain their single
`configured-model` path. New `schema_version: "0.3"` replaces `modelConnectionId`
with `selectedModelRefs`, `defaultModelRef`, `modelCatalogRevision` and an optional
`presentation` (`MOBILE_WEB` or `DESKTOP_WEB`). All other existing fields remain.
There is exactly one default belonging to a nonempty, unique allowlist. Only the
two registered read-only capabilities and their three compositions are enabled.

Preflight rejects stale catalog versions and unknown/disabled/unsupported refs.
Unbound entries may generate an artifact, but cannot start it. Binding them later
requires a new build to freeze a non-null configuration version. `canBuild` and
`startPrerequisitesSatisfied` remain separate; neither proves model connectivity.

`model-selection.json` and the service lock freeze selected refs, default,
catalog revision, model identity and connection configuration version. A version
is SHA256 of length-prefixed UTF-8 fields: ref, explicit revision, protocol,
provider, exact model name, URL environment name, key environment name, resolved
URL. The secret value is excluded. No URL or secret appears in the selection,
lock, downloadable artifact or public directory. Changed binding configuration
requires rebuilding; server-side `enabled:false` takes effect on the next model
dispatch even for an existing build. Discovery changes alone are not revocation;
the host binding remains the runtime authority.

## Sessions and Evidence

`POST /api/v1/builds/{id}/sessions` optionally accepts `modelRef` alongside
`instanceId`; otherwise uses the frozen default. `switch-model` accepts
`instanceId`, `sessionId`, `modelRef`, validates the captured instance/session and
creates a fresh native session. It retires the previous Runtime session without
copying its messages. Old asynchronous responses cannot adopt a newer session.
The current model ref/configuration version is included in authorized results
and hash-only persistent turn evidence. Unknown/unselected refs fail before model
HTTP, with no global-model or out-of-list fallback.

Before each actual model call, both current financial authorization and current
model binding are checked. Existing SDK force-finish, tool boundary and final
response checks remain. Failure clears the affected Runtime conversation. The UI
keeps previous model conversations only in page memory for authorized review;
refresh does not restore them. Revocation clears all related visible transcripts
and evidence, including drawers. No financial history is stored in localStorage.

The UI reads actual catalogs, build stages, locks and lifecycle results. Its
loading animation has no timer-driven success. Phone and desktop are responsive
Web presentations, not native app packages or different authorization profiles.
AG-UI, storage deletion, new Runtime and real-model certification remain out of
scope. No `concept-*` capability is registered. Missing configuration and actual
failed requests are surfaced instead of switching to scripted answers.

## Verification Entrypoints

```sh
.venv/bin/python -m pytest tests -q
.venv/bin/python engineering/verify_multi_model.py \
  --harness <isolated-Harness-checkout> --output build/multi-model-unique
.venv/bin/python engineering/verify_workbench_api.py \
  --harness <isolated-Harness-checkout> --output build/legacy-api-unique
# With @playwright/test and its Chromium browser available to Node:
NODE_PATH=<existing-node-dependencies> .venv/bin/python engineering/verify_multi_model.py \
  --harness <isolated-Harness-checkout> --output build/model-ui-unique --browser
```

The multi-model verifier uses separate controlled A/B/C model HTTP servers and a
separate synthetic bank. It compiles three real Candidates, proves request
destination, default, new-session history isolation, unselected C count zero,
hot disable during delayed bank output, financial authorization, and no writes.
These are not real-model or production-bank tests. Do not run simultaneous Maven
builds against the same Harness checkout. Actual model testing, Builder upstream
acceptance and independent human onboarding remain separate acceptance items.

The optional browser run uses the actual server, real Maven build and native Java
host. Only the model and bank endpoints are synthetic. It exercises the drawers,
default radio, fresh-session switching, authorized history review, revocation,
320/390/1440px layouts, and editing a new immutable build with a narrower model
and capability set. `--browser-only` isolates UI integration diagnostics without
claiming to have repeated all three CLI compositions.

## Local Preview and Ownership

Run the same workbench command documented in `local-workbench-v01.md`, with an
unused loopback port. Metadata-only preview needs no model secret; it can inspect
configuration and build but cannot start an unbound model. Use explicit private
bindings above for a configured instance. Preview and controlled test mode are
separate from acceptance of any real EA model connection.

Changes live in the existing scenario Builder and generated thin host template.
Base, OpenJiuwen SDK, Harness public rules, EA and the simulator are not copied or
modified. The model connection adapter is owned by the Builder host; banking
identity and execution boundaries still run through Harness. Maintenance by the
upstream Builder owner and a non-author developer's independent run are pending.
