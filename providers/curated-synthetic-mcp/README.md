# Curated Synthetic MCP Provider (Draft)

NOT QUALIFIED / NOT HOST-INTEGRATED / NOT DEPLOYED. This module is not in the
finance asset catalog or either Java Host support profile. ServiceLoader discovery
is not admission. No Builder/Eval changes or TASK implementation are included.

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
- The current source session is checked before transport, at each SDK HTTP request
  customization boundary and after the call. Session rotation fails closed.
  Host grants are checked by BoundFinanceProvider per invocation; the unchanged
  SPI does not supply the Provider with an independent grant-refresh callback.
- `CuratedTools` explicitly binds 16 source tool names to NEW `eval.synthetic.*`
  capabilities. It does not reuse same-named banking/insurance/Wind capabilities.

## Explicit Skill Migration Required

Published tool names are `curated_<original_name>`, not the original Skill names.
Original Skill references MUST be migrated with an explicit tool/contract binding;
uploading an unchanged Skill is not automatically compatible.

For customer tools, Harness input `customer_id` is the **Host-authorized
subjectRef**, e.g. `host-customer`, NOT source `C005`. It must equal the context
subject. Only the reviewed source configuration maps that subject to `C005`,
`C006` or `I001`. Source results retain the original source customer ID; they do
not pretend that the source ID is the Host identity.

`reference` and `product_id` values must belong to that subject's per-tool resource
allowlist. Defaults are expanded before authorization, so omitting `reference`
does not bypass an allowlist. The parameterless policy tool requires explicit
`get_wealth_policy_basis: ["policy"]` authorization; it is not a method-only mode.
Extra parameters, wrong types and invalid numeric ranges are rejected locally.
Valid but different wealth constraints are passed through for source DEGRADED
semantics, never silently replaced by the reference defaults.

Seven `curated-synthetic-*` Packs version `1.0.0` cover customer, wealth, insurance,
research, audit, transfer-risk and post-loan. Packs remain atomic. There is no
placeholder tool for a zero-tool method and no formal catalog registration.

## Host Configuration Injection

No new SPI. Factory requires both Host environment variables:

```
FH_CURATED_MCP_CONFIG=/absolute/reviewed/source.json
FH_CURATED_MCP_CONFIG_SHA256=<sha256 of exact source.json bytes>
```

The file must be a regular non-symlink file under non-symlink parents, at most
256 KiB; duplicate/unknown JSON fields and a hash mismatch are rejected.
The Host must protect BOTH the file and expected hash. Hash pinning does not
protect against an actor allowed to replace both values.

Illustrative shape only (placeholder pins deliberately do not work):

```json
{
  "schemaVersion": "curated-synthetic-source.v1",
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

`mcpExecution` records tool, contract/schema/source-config digest and input/output
digests only after the actual SDK call. The source-config digest is a canonical
object digest, distinct from the exact file-byte hash used by the factory.
This is local execution metadata, NOT an independent source authenticity receipt
or a Host qualification. Source asOf remains in sourceResult; outer asOf is read
time. Bank access credentials and raw endpoint are not placed in result evidence.

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
and DEGRADED preservation, and terminates its child process. This is not a bank
call, deployment, AgentScope or OpenJiuwen qualification.

## Remaining Release Gates

- SDK responses are fully buffered before this draft's 256 KiB application check.
  This is NOT an HTTP ingress/SSE byte limit. A bounded transport/ingress policy
  and adversarial oversized-stream tests are required before any Host opening.
- Future artifact/Host Lock and both runtime qualifications MUST bind source
  configuration (exact file and canonical digest), schema pins, subject/resource
  mappings, Provider jar/dependencies, Pack bytes and reference source manifest.
  Current factory pinning alone is not that Lock or a qualification.
- Real source credentials beyond loopback are unsupported. Source authenticity,
  resource ownership and dynamic scope policies need Host-reviewed provisioning;
  a tool name, source label or readOnlyHint does not confer authority.
- Full output JSON Schema/semantic qualification, cancellation stress, hostile
  server negotiation, bounded cleanup and concurrency load remain follow-up gates.
  This draft checks source/version/date/selected identity and required fields,
  not every business field's full schema.
- Builder generic packaging/admission and explicit Skill migration bindings are
  intentionally outside this change. Neither Runtime is marked ready.
