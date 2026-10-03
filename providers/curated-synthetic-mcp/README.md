# Curated Synthetic MCP Provider (Draft)

GENERATED-HOST CONTROLLED QUALIFICATION PASSED / NOT DEPLOYED. The managed catalog now
declares six read-only cohort Packs (12 tools), restricted to local-read-only
Java hosts. Wealth remains outside this admission cohort. Native QUERY evidence
does not replace exact generated-Host qualification. ServiceLoader discovery and
catalog membership are not installation, grants or deployment readiness.

The `hosts-r10` isolated run on 2026-10-03 built actual OpenJiuwen and AgentScope
Java Hosts, exercised all twelve cohort tools, compared scalar facts with the
independent synthetic source and verified REQUIRED collector proof admission and
missing-proof rejection. It used a controlled model, not the original Skill
cohort or a real-model/browser acceptance run. Qualification is bound to those
exact generated artifacts and source configuration, not reusable on deployment
with a different endpoint/configuration. The Builder engineering entry is
`engineering/verify_curated_generated_hosts.py`; keep its raw reports and Locks.

The six cohort Packs have separate REQUIRED `capability.result` observation
declarations under `observation-packs/readonly/curated-*.json`. Each declaration
covers one whole Pack; selecting one Pack must not require the other five.
`eval-projection.v1.json` declares approved scalar fact paths for the same cohort.
It does not enable capture, grant resource access or mark answers correct. The
Eval Launcher must bind its server-owned resource scope and exact canonical source
configuration, verify the Host receipt, and apply size/field limits. The source
business date remains separate from the Host result timestamp.

## Boundaries

- The existing `synthetic-bank-http.v1` Connection is an **authority source**.
  Its currentSession supplier must return a currently validated Host source
  session, not a model assertion. The normal BoundFinanceProvider/current-Grant
  boundary remains mandatory: this module does not replace that authority.
- The source endpoint is a separate immutable `CuratedSourceConfig`. Only literal
  loopback HTTP `/mcp` on an explicit port is supported in this draft. No redirects,
  userinfo, query, fragments, arbitrary hosts or bank credential forwarding.
- Each call creates, initializes and closes its own official MCP SDK 0.17.2
  Streamable HTTP client. No global MCP session, no retry/fallback to local fixture
  data, and no bank HTTP response is presented as an MCP tool response.
  Return waits for `McpSyncClient.closeGracefully()` (SDK bound: 10 seconds,
  also subject to the configured HTTP request timeout). Incomplete graceful close
  fails the read instead of returning COMPLETED. Plain SDK `close()` is asynchronous
  and is used only as local fallback after a failed graceful close. A primary
  invocation exception is preserved, with cleanup failure suppressed.
- The official SDK `clientBuilder(HttpClient.Builder)` and `jsonMapper(...)`
  extension points are used without reflection, SDK copies or replacement MCP
  protocol/session logic. `BoundedHttpClient` wraps the JDK body subscriber before
  the SDK receives bytes. Every HTTP response body is limited to **256 KiB total**,
  including initialize, tools/list, tools/call, notifications, background GET and
  session DELETE, successful or not. The crossing byte-buffer batch is rejected
  before forwarding; this is not a parsed-object or character-count limit.
  SSE comments, unbroken lines and multiple events share that response's budget.
  Content-Length over the limit is rejected at headers; absent/chunked lengths
  are counted while reading. Non-identity Content-Encoding is rejected.
  This bounds SDK body buffering, not JDK/OS buffers or HTTP header memory.
- Each HTTP exchange has a wall-clock deadline equal to the configured timeout,
  covering stalled bodies after headers as well as connection setup. Timeout,
  interruption and invocation cleanup cancel the underlying HTTP future and body
  subscription; no background read is intentionally retained. GET ingress errors
  poison the invocation, rather than allowing a successful tool response to mask
  them. DELETE retains its own byte/deadline bound and must return 2xx. These are
  per-exchange/per-SDK-operation bounds, not one end-to-end invocation deadline.
  An interrupted caller's interrupt flag is restored after bounded cleanup.
- The current source session is checked before transport, at each non-DELETE SDK
  HTTP request customization boundary and after the call. Session rotation fails
  closed. Only the SDK's own session DELETE bypasses business-authority refresh,
  so revocation/expiry cannot block cleanup; the exact endpoint pin remains checked
  for DELETE too. Revocation before result delivery discards the result.
  Host grants are checked by BoundFinanceProvider per invocation; the unchanged
  SPI does not supply the Provider with an independent grant-refresh callback.
- `CuratedTools` explicitly binds 16 source tool names to NEW `eval.synthetic.*`
  capabilities. It does not reuse same-named banking/insurance/Wind capabilities.

## Explicit Skill Migration Required

Published tool names are `curated_<original_name>`, not the original Skill names.
Original Skill references MUST be migrated with an explicit tool/contract binding;
uploading an unchanged Skill is not automatically compatible.

By default (`BOUND_SUBJECT`), Harness input `customer_id` is the **Host-authorized
subjectRef**, e.g. `host-customer`, NOT source `C005`. It must equal the context
subject. Only the reviewed source configuration maps that subject to `C005`,
`C006` or `I001`. Source results retain the original source customer ID; they do
not pretend that the source ID is the Host identity.

Candidate source version `curated-synthetic-source.v3` additionally permits an
explicit per-subject `customerSelection: SYNTHETIC_ALLOWLIST`. This mode is only
for this pinned loopback synthetic source. It preserves the test case's explicit
`customer_id` (for example C005 or C006) and requires that value in the trusted
subject's **per-tool** resource allowlist. It never takes the first entry or
derives a grant from Skill text. `customerId` must be absent in this mode; mixing
implicit mapping with explicit selection is rejected. v1/v2 cannot enable it.

The Host subject remains the authenticated evaluation identity, not the selected
synthetic customer. Current session, principal, session reference and scope checks,
BoundFinanceProvider grants, transport refresh and returned-identity validation
are unchanged. Even a response for a different allowlisted customer is discarded.
This is not delegated access to real bank customers and must not be copied into a
real-bank Provider as an authorization shortcut. The customer mode and resource
sets are in the canonical digest; exact config bytes remain separately pinned.
Existing v1/v2 canonical digests do not change when BOUND_SUBJECT is defaulted.

This candidate mode has Provider-level tests, not generated-Host qualification or
wealth Skill admission. The existing managed customer/wealth capability text
still describes BOUND_SUBJECT; before using the new mode in a generated Agent,
version the applicable input descriptions, validate their binding, and rerun the
exact native/Host, projection and original-case gates. Do not reuse old proofs or
publish the wealth cohort just because this configuration now parses.

`reference` and `product_id` values must belong to that subject's per-tool resource
allowlist. Defaults are expanded before authorization, so omitting `reference`
does not bypass an allowlist. The parameterless policy tool requires explicit
`get_wealth_policy_basis: ["policy"]` authorization; it is not a method-only mode.
Extra parameters, wrong types and invalid numeric ranges are rejected locally.
Valid but different wealth constraints are passed through for source DEGRADED
semantics, never silently replaced by the reference defaults.

Seven `curated-synthetic-*` Packs version `1.0.0` cover customer, wealth, insurance,
research, audit, transfer-risk and post-loan. Packs remain atomic. There is no
placeholder tool for a zero-tool method. Six Packs have candidate catalog
declarations; the wealth Pack is not registered or exposed by the managed Provider
assembly declaration. The factory's 16-tool SPI contract and isolated Provider
tests remain broader than the 12-tool managed admission cohort.

## Host Configuration Injection

Factory uses the existing managed Provider SPI, with optional local configuration
digest reporting. It requires both Host environment variables:

```
FH_CURATED_MCP_CONFIG=/absolute/reviewed/source.json
FH_CURATED_MCP_CONFIG_SHA256=<sha256 of exact source.json bytes>
```

Managed Factory construction requires `curated-synthetic-source.v2` or v3 and
`runtimePolicy` with mode `STATELESS_BOUNDED` and a 64-hex `sourceIdentitySha256`.
The mode and digest are part of the canonical source config identity. The HTTP
client rejects source session headers before passing a body to the SDK, without
guessing ownership or sending a fallback DELETE. Legacy v1 direct Provider
construction remains for isolated regression only, not Factory admission.

Every v2 response must contain exactly one `X-Finance-Source-Sha256` header equal
to the fixed source identity. Missing, duplicate or different markers fail before
the SDK body consumer. The bounded source entry adds this from its startup-verified
manifest, overriding downstream values. This detects deployment mismatches on the
managed loopback path; it is NOT cryptographic remote attestation, authentication,
or permission. A hostile process able to spoof the marker is not excluded by it.

The file must be a regular non-symlink file under non-symlink parents, at most
256 KiB; duplicate/unknown JSON fields and a hash mismatch are rejected.
The Host must protect BOTH the file and expected hash. Hash pinning does not
protect against an actor allowed to replace both values. The candidate Builder/Host
additionally freeze the file-byte hash in service Lock, require it at factory
construction and instance start, carry it into Runtime Lock and collector identity,
and compare it in Eval delivery/execution evidence. Replacing file plus environment
pin without replacing the reviewed installation is rejected. Configuration changes
require new qualification; no old Agent is automatically upgraded.

Illustrative shape only (placeholder pins deliberately do not work):

```json
{
  "schemaVersion": "curated-synthetic-source.v2",
  "runtimePolicy": {"mode": "STATELESS_BOUNDED", "sourceIdentitySha256": "<reviewed identity SHA-256>"},
  "endpoint": "http://127.0.0.1:56363/mcp",
  "contractVersion": "1.0.0",
  "toolSchemaPins": {"get_customer_profile": "<reviewed schema SHA-256>"},
  "subjects": {
    "host-customer": {
      "customerId": "C005",
      "resources": {"get_customer_profile": ["C005"]}
    }
  }
}
```

Schema pins hash the SDK 0.17.2 `JsonSchema` representation after HarnessJson
NON_NULL serialization and recursive map-key ordering. These pins are reviewed
configuration, never automatically accepted from a running endpoint in production.
The SDK representation is not a lossless hash of every raw tools/list field.
Local explicit parameter contracts, source metadata/version and returned selector
checks remain necessary. The opt-in test alone discovers pins from its own child
process, whose three source files are pinned in `reference-contract.v1.json`.

## Results and Evidence

`COMPLETED` means the QUERY read completed, not that the business is READY, safe,
approved or suitable. `sourceResult` retains the source JSON including gaps;
`sourceState` separately retains decision/status/readiness including UNKNOWN,
UNAVAILABLE and DEGRADED. Those are not transport failures and do not create a
TASK reference. MCP `isError=true` and transport/contract failures remain FAILED;
raw upstream errors are not exposed as user-facing diagnostic text.

Required output structure is checked for all 16 tools, including nested records
and array elements, all three customer profile variants, READY/DEGRADED shapes,
and the three risk-reference states. Metadata is type checked (e.g. the string
`"true"` is not the boolean `true`); secondary product/account/enterprise identities
must also match the authorized selector. Key non-execution and non-approval flags
are fixed by the reference contract. Missing/incorrectly typed required fields
fail without `mcpExecution`. Text and structured content, when both supplied,
must describe the same JSON; duplicate keys, trailing JSON and nesting beyond 64
are rejected using the SDK's supported JSON mapper extension. Additional source
fields are retained, not certified; this is not a complete closed JSON Schema or
business-semantic qualification.

`mcpExecution` records tool, contract/schema/source-config digest and input/output
digests only after the actual SDK call. The source-config digest is a canonical
object digest, distinct from the exact file-byte hash used by the factory.
This is local execution metadata, NOT an independent source authenticity receipt
or a Host qualification. Source asOf remains in sourceResult; outer asOf is read
time. Bank access credentials and raw endpoint are not placed in result evidence.
For v2/v3, `sourceIdentity` separately records `RESPONSE_HEADER_MATCHED_PIN` and
`authentication: NOT_PROVIDED_BY_MARKER`. Failed reads do not receive this success
marker. File-byte, canonical configuration and source-manifest hashes are distinct.

## Tests

Run from the Harness repository root (loopback sockets must be permitted):

```sh
mvn -o -pl providers/curated-synthetic-mcp -am test
```

To additionally start and stop the **actual** local synthetic reference MCP:

```sh
mvn -o -pl providers/curated-synthetic-mcp -am test \
  -Dcurated.reference.directory=/home/ubuntu/finance-eval-unified-v1/integrations/openjiuwen \
  -Dcurated.reference.python=/home/ubuntu/finance-python-018-probe/bin/python
```

The opt-in test verifies pinned source bytes, binds a temporary loopback port,
calls all 16 original tools through this Provider/SDK, checks UNKNOWN/UNAVAILABLE
and DEGRADED preservation, and terminates its child process. It runs once with
the legacy stateful source and once with the bounded stateless entry, requiring
the exact source/dependency identity and source.v2 configuration for the latter.
The bounded run also uses source.v3 to read the original C005 and C006 customer
profile, portfolio and allocation context, verifies returned identities and the
canonical config pin, and refuses unconfigured I001 without another HTTP attempt.
Focused tests cover missing/expired/mismatched Host sessions, missing grants,
revocation before dispatch and delivery, and wrong-customer source responses.
This checks SDK/Provider compatibility without changing deployment defaults.
The separate Eval TCP tests check rejection, disconnect and request bounds;
neither suite alone qualifies the complete Host deployment described below.
This is not a bank
call, deployment, AgentScope or OpenJiuwen qualification.

Candidate security regressions also exercise exact byte boundaries before the
SDK consumer, UTF-8 byte counting, fixed/chunked 16 MiB hostile bodies at each
request phase, unterminated SSE lines/events and comment floods, background GET
overflow, DELETE status/timeout failure, encoded bodies, repeated slow-stream
timeout/interruption with observed server-side disconnect, 16 concurrent calls
with separate sessions, and recursive missing/null/wrong-type output mutations.
The existing authority rotation/revocation/expiry and no-token-forwarding tests
remain mandatory. These local tests do not qualify a Host deployment.

## Remaining Release Gates

The two `BLOCK_*` items below concern the legacy **stateful** source path, which
remains outside managed Factory admission. The current candidate uses the pinned
`STATELESS_BOUNDED` v2 source instead; it does not claim to solve remote ownership
or reclaim an unowned stateful session. Current release gates are exact generated
Host qualification, the original Skill cohort (including its no-tool method),
real-model evidence and the authenticated Eval browser workflow.

- The HTTP/SSE body ingress limit and focused cancellation regressions above are
  implemented as a candidate, not Host admission. Header limits, aggregate Host
  concurrency/resource budgets and production cancellation/load qualification
  remain Host release gates.
- **BLOCK_INIT_CLEANUP_OWNERSHIP: remote cleanup cannot be guaranteed for a
  rejected initialize response under the current source contract.**
  SDK 0.17.2 accepts the session id in `sendMessage` only when consuming a response
  event, while `createTransportSession` skips DELETE when it has no accepted id.
  An oversized Content-Length can therefore be rejected before the SDK learns the
  id, even if the server already allocated a session. The test
  `rejectedInitializeCannotDeleteASessionTheSdkNeverAccepted` proves FAILED, zero
  tool calls, zero DELETEs and one remaining server-side session.
  The JDK `responseInfo` callback in the existing bounded wrapper **can** observe
  the exact header before rejecting the body; a one-shot, pinned, deadline-bound
  HTTP DELETE is technically possible without reflection. Missing SDK session
  adoption is therefore not the decisive blocker. The missing fact is whether
  that ID denotes a newly created session belonging only to this invocation.
  `validInitializeHeaderCanNameAnExistingSharedSessionSoHeaderOnlyCleanupStaysBlocked`
  supplies an existing shared UUID from the same pinned initialize endpoint,
  without an outgoing session header, then sends an oversized fixed/chunked body.
  It proves FAILED, zero tool calls, zero DELETEs, and the shared session survives.
  A length limit, visible-ASCII check, UUID format or per-invocation storage would
  all accept this ID; none proves ownership. A client-local registry cannot detect
  sessions belonging to other processes. No header-only fallback DELETE is added.
  A missing session header also gives no target and is not proof the server did
  not allocate a session. Ordinary SDK cleanup is not retried: a rejected DELETE
  remains FAILED, and normal close deletes exactly its SDK session once without
  touching a separate shared session. The existing accepted-session path still
  relies on source correctness; these tests do not qualify hostile session-ID
  aliasing after successful SDK initialization.
  Before enabling an orphan-session DELETE, a Host-reviewed ownership mechanism
  must bind the exact initialize invocation, session and endpoint (for example a
  verified, exclusive server allocation contract or a request-bound cleanup
  capability), including duplicate/late-header and concurrent-close handling.
  See the [official 0.17.2 transport source](https://github.com/modelcontextprotocol/java-sdk/blob/v0.17.2/mcp-core/src/main/java/io/modelcontextprotocol/client/transport/HttpClientStreamableHttpTransport.java)
  (`createTransportSession`, `createDelete`, `sendMessage`, `Builder.clientBuilder`).
- **BLOCK_SERVER_TTL_QUALIFICATION: the observed idle TTL is not yet an accepted
  compensation.** The opt-in `ReferenceCleanupCompensationTest` uses public Python
  SDK APIs against the installed source dependency, in-process ASGI, with no
  server/SDK edits, private-field access, fake clock or DELETE requests. It writes
  observed versions/source hashes to `target/cleanup-ttl-probe.json`. Observed:
  `mcp 1.30.0`, `fastmcp 2.14.7`, default idle timeout **1800 seconds**. The local
  FastMCP HTTP factory does not override that manager default. With an explicitly
  shortened **0.25-second test TTL**, an oversized initialization's session returns
  404 after idling, while a separately kept-alive session remains usable (202).
  However, an initialize response held in flight remains usable (202) after
  three times the idle TTL; only after release and subsequent idle does it return
  404. This is idle expiry, not an absolute post-header reclamation deadline.
  The 1800-second wall-clock path and the real reference application's failed-read
  cleanup are **not** demonstrated by that accelerated dependency probe. The
  reference source manifest also does not pin these Python dependency bytes or
  the running Host's TTL settings. Acceptance needs reviewed dependency/config
  pins, a finite bound on in-flight requests/disconnect handling as well as idle
  expiry, and end-to-end orphan-reclamation/isolation evidence against the actual
  source. Merely having a TTL constructor option does not clear this gate.
- Artifact/Host Lock and both runtime qualifications MUST bind source
  configuration (exact file and canonical digest), schema pins, subject/resource
  mappings, Provider jar/dependencies, Pack bytes and reference source manifest.
  The controlled generated-Host run verifies these bindings; factory pinning
  alone is still not that Lock or a qualification for another deployment.
- Real source credentials beyond loopback are unsupported. Source authenticity,
  resource ownership and dynamic scope policies need Host-reviewed provisioning;
  a tool name, source label or readOnlyHint does not confer authority.
- Full output JSON Schema/semantic qualification, broader cancellation stress,
  hostile server negotiation and sustained concurrency load remain follow-up
  gates. Required output structures and focused cleanup tests are implemented;
  this draft still does not certify every additional business field, financial
  relationship, source authenticity or remote cleanup outcome.
- Builder generated-Host qualification is now exercised by the companion
  verifier. Original Skill migration bindings and end-user release acceptance
  remain pending; neither complete Skill cohort is marked ready for promotion.
