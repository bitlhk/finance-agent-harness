# Local Read-only Agent

This is a persistent OpenJiuwen Java 0.1.15 host with Harness dependencies, not a scripted model.
The bank must be the isolated synthetic simulator, including GET /api/v1/sessions/current.

Set server-side bank/host variables: FH_BANK_URL, FH_BANK_LOGIN, FH_BANK_SECRET,
FH_ACCESS_POLICY and FH_HOST_TOKEN (32+ random characters).
Legacy single-model artifacts also need FH_MODEL_URL, FH_MODEL_NAME and FH_MODEL_KEY.
Artifacts with model-selection.json instead use the private registry described below.
FH_ACCESS_POLICY points to a private JSON file outside this candidate. It contains schemaVersion
`local-simulator-access.v0.1`, bankUrl, and subjects keyed by source-verified customer reference.
Each rule contains enabled, expiresAt (UTC), and explicitly granted read scopes. Selecting a capability
does not write this policy. Do not use real banking/customer data in this local demonstration.
Set FH_MODEL_MODE=LIVE for real model use. CONTROLLED_TEST is reserved for explicitly invoked regression
tools and is reported as such; the graphical workbench never uses it as a fallback.

## Multi-model Bindings

Set FH_MODEL_REGISTRY to an external host-administered file, not a file in this artifact:

```json
{
  "schemaVersion": "finance-model-bindings.v1",
  "bindings": [{
    "modelRef": "model-your-persisted-id",
    "revision": "v1",
    "enabled": true,
    "protocol": "OPENAI_CHAT_COMPLETIONS",
    "provider": "OpenAI",
    "modelName": "your-approved-model",
    "urlEnvironment": "FH_CONN_PRIMARY_URL",
    "keyEnvironment": "FH_CONN_PRIMARY_KEY"
  }]
}
```

Use the original host binding, not the illustrative IDs above. Every selected ref
must match its frozen model identity and configuration version. Set the named URL
and key variables on the server. Changing endpoints/revisions requires a new
build; do not edit model-selection.json or its lock. Previously unbound artifacts
must be rebuilt after binding. OpenRouter is supported only through an explicitly
configured Chat Completions binding. No legacy-global or unselected-model fallback
exists. Atomically replace the external registry with enabled=false to deny its
next dispatch. Restart for process-environment/secret rotation. Private bindings,
credentials and access policy must not be included in downloads.

## Run and Stop

Run `python run.py` from the copied candidate directory with JDK 21 available. No original source
checkout is required when target/ and its dependency JARs are included. Ctrl+C stops this instance.
FH_PORT defaults to 0 (OS-assigned); instance.json records the actual port and instance/build IDs.
Re-run the same command to restart; old sessions are intentionally not recovered.

All HTTP requests need `Authorization: Bearer <FH_HOST_TOKEN>` and target loopback only:

- GET /health: actual instance and locked artifact inventory.
- POST /sessions with `{}`: login to the configured bank identity, verify local policy and install tools.
- POST /sessions with `{"modelRef":"<selected-ref>","previousSessionId":"<old-session>"}`:
  start a fresh native conversation on that selected model, retiring the old one
  without copying financial history. Omit previousSessionId on first open.
- POST /sessions/{sessionId}/chat with `{"query":"..."}`: native multi-turn Agent invocation.
- POST /sessions/{sessionId}/revoke with `{}`: revoke bank session and erase protected runtime context.

The host does not accept identity overrides or tool-execution routes. It serializes SDK tool
registration/invocation because tool names are global in this SDK version. At most 16 active sessions
are allowed. No bank business write tool is installed. IAM login/revocation are reported separately.
Evidence stores native event counts/hashes and runtime locks, not a customer plaintext log archive.
Authorized results identify modelRef/modelConfigVersion. A client may keep old
conversations in page memory for authorized review, but must clear them on
authorization rejection or an instance/session change, not transfer them to the
new model session automatically.
Verify startup manifest hashes; a successful compilation alone does not prove model or bank access.
