# CRM + KYP multi-source candidate

Status: foundation and isolated CRM HTTP + product MCP integration implemented;
NOT registered, generated-Host qualified, deployed or enabled.

## Frozen scope

One employee-assisted Agent uses authorized CRM customer facts and a separately
bound product source. Product facts, bank sales eligibility and suitability are
different claims. A public fund quote is not permission to sell. Skill declares
dependencies; neither Skill nor an MCP response grants authority.

No new accounts, credentials, policy grants, external inference or real product
queries are part of this candidate. The existing synthetic product catalog and
existing Agents are unchanged. Do not expose the candidate in the build catalog
until the complete generated-Host path is admitted.

## Implemented foundation

`ManagedProviderFactoriesV01.createBound` takes Host-owned per-Provider
connections AND per-Provider current-grant suppliers, covering the dependency
closure exactly. It validates configuration and connection digests before
constructing Providers. Dependencies retain their own bound authorization.
Observations use each connection's observation supplier. The old `create`
entrypoint retains single-connection semantics. Connection hashes identify
reviewed configuration, not authenticity or permission; the Host must supply
trusted bindings and the reviewed manifest, never request-supplied hashes.

`createReferenced` resolves the verified plan's logical references through a
Host-owned source map. Required Provider and grant coverage is exact, including
dependencies. Connection kind and pinned connection/configuration identities
are checked before factory construction. Extra Host sources are not selected
implicitly. Grants remain per Provider, even when two Providers explicitly use
the same source. Installation does not acquire sessions or grants. The caller
must obtain pins from trusted installation, not recompute them from arbitrary
request configuration to make a mismatch pass.

`providers/wealth-product-mcp/ProductCatalog` is a contract adapter. It accepts the reviewed nested EA envelope
(`data.products` or `data.items`, `productFacts`, `salesEligibility`). It does not
guess all historical aliases. Missing business timestamps are not substituted
with query/update time; day terms are not rounded into months; missing minimum
amounts are not zero. Demo/unverified sales cannot become verified through
response fields. Invalid or stale fields prevent screening. Passing these field
checks is NOT suitability, bank authorization, or approval to purchase.

`KypProductFactory` now implements an explicitly installed candidate Provider.
It uses `packages/java/mcp-transport/PinnedMcpReader` with official MCP SDK
0.17.2. The bounded HTTP client was extracted from curated-synthetic-mcp without
removing that Provider's synthetic source checks. This changes the curated
artifact dependency closure and requires release qualification before shipping.
Core remains independent of the SDK.

The candidate supports stateless public-source transport only: HTTPS or literal
loopback HTTP, fixed endpoint/tool/schema digest, bounded ingress and timeout,
no redirects, no credential forwarding and no fallback catalog. Authenticated
product sources require a separately reviewed transport mode. The request only
forwards the explicit search keyword and fixed pagination, not the CRM session
or customer context. A keyword is not a general data-loss-prevention boundary.
Incomplete pagination is rejected rather than reported as a complete catalog.

Required audit is written before network access and before result delivery;
audit failure prevents delivery. Authority is checked during exchanges and
after response/cleanup, including grant-generation changes. Optional transport
observations do not substitute for required audit. Transport RETURNED and query
COMPLETED do not mean suitability approved. Normalized content digests are not
wire signatures or proof of source authenticity.

The Builder candidate adds opt-in logical `providerConnections` only to the
internal resolver. It requires `provider-connections.v1` in reviewed Host
support, exact Provider coverage and compatible connection kinds. No existing
Host advertises it. Logical references contain no URLs or credentials. Their
digest must be carried into the future generated plan/Lock. Existing API input
schemas and runtime factories are not silently switched to this path.

## Remaining implementation gates

1. Confirm the reviewed product-source envelope and transport mode against an
   authorized deployment. Current protocol tests use local synthetic servers,
   not the employee-agent live MCP or a bank-authorized product account.
2. Register the product Capability Pack, Provider and observation requirements,
   with test/demo versus verified source policy explicitly owned by deployment.
   Product-source credentials are never CRM credentials. The old EA HTTPS path
   is shadow/unauthenticated; it is not evidence of production authorization.
3. Wire reviewed connection references through generated Host configuration,
   immutable Lock, service identity and qualification. Unknown references or
   unqualified Hosts fail closed. Do not merely add a feature flag to old Hosts.
4. Run actual CRM HTTP + isolated MCP through the final generated Host. Bind
   customer, request, source versions and authority before/after composite work;
   revoke/cancel/late results must not publish success. Verify independent source
   outages and required-audit failures without synthetic fallback.
5. Apply the existing suitability method to authorized CRM and normalized KYP
   facts. Missing sale eligibility stays incomplete. Qualification must cover
   actual changed generated inputs, not inherited UI-only qualification.

## Acceptance ledger

| Check | Status / evidence |
| --- | --- |
| Legacy Factory and single-connection behavior | Java core regression executed |
| Different connection kinds and per-source grants | Isolated Java registry tests executed |
| Missing/extra/swapped connection bindings | Negative Java/Builder tests executed |
| Revoke one source without granting another | Java registry test executed (pre-call checkpoint) |
| Product dates / public facts vs sales / missing inputs | ProductCatalog tests executed |
| Actual network MCP protocol and after-call revocation | 14 isolated integration tests, actual SDK and loopback HTTP |
| CRM + KYP generated Agent / UI / qualification | NOT RUN / Host integration not implemented |
| Real third-party or internal product account | NOT RUN / not enabled or called |

## EA reuse findings

Source reviewed: `/home/ubuntu/employee-agent` at
`0503bab254d52ac496bcf5fd51e23532aee975fe` (one pre-existing untracked customer
fixture left untouched). The relevant paths are
`server/_core/platform-tools-mcp.ts`, `wealth-product-source-client.ts`,
`wealth-allocation-context.ts`, and `docs/wealth-product-mcp-v2.2-intake-assessment.md`.
Reuse tool contracts and envelope knowledge, not role declarations as grants.
The old adapter's `updatedAt` fallback and day-to-month rounding are deliberately
not used for financial eligibility. EA's existing 15 isolated transport/context
tests were run during the preceding investigation; they are not new Host tests.

## Rollback and release

No deployment or source migration in this phase. Existing single-source releases
remain runnable. Once multi-source Locks exist, an older Host must reject them;
compatibility cannot be established by discarding connection entries. Production
release must wait for installation and final generated-Host evidence.

## Executed checks (2026-10-28)

Command: `mvn -o -pl providers/wealth-product-mcp,providers/customer-crm -am test -q`.
Surefire results: Core 68, CRM 9, product contract 8; zero failures/errors/skips.
These are Java unit/component tests, not 85 generated combinations. CRM tests
use a local HTTP fixture; product contract tests parse local payloads without MCP.
Reports are in each module's `target/surefire-reports/` (regenerable, not committed).

Follow-up command: `mvn -o -pl providers/curated-synthetic-mcp,providers/wealth-product-mcp -am test -q`.
Surefire: Core 68 passed, shared MCP transport 6 passed, CRM 9 passed,
curated MCP 83 passed / 2 skipped, KYP 22 passed (8 contract + 14 integration).
The two external reference tests require `curated.reference.directory` and
`curated.reference.python`; neither was configured. They are not claimed passed.
No generated matrix, production calls or model inference was run.

Integration tests cover distinct CRM/product connections, no CRM credential
forwarding, grant revoke/change, failed required audit, schema drift, source
failure, oversize response, session rejection, arbitrary parameter rejection,
timeout and cancellation. The first integration failure was a fixture whose CRM
session expiry changed on every read: production correctly rejected it with
`CRM_AUTHORITY_CHANGED`. Only fixture expiry was fixed. Original report retained
at `/home/ubuntu/outputs/crm-kyp-candidate-20261028/first-failure/`.

Effect acceptance remains open: run the same method version, input facts and
model settings through the generated Host and employee-agent comparison; check
useful customer insights, missing-fact disclosure, product provenance and sales
eligibility separately. Preserving fields in integration tests does not prove
report quality parity. Avoid a second manager-specific runtime or UI.

Connection-reference follow-up: `mvn -o -pl providers/wealth-product-mcp -am test -q`
passed 107 tests (Core 70, transport 6, CRM 9, KYP 22), zero skipped. All 14
CRM/MCP integration cases now construct through `createReferenced`, not a
pre-resolved Provider binding map. Added checks prove independently revocable
grants on a shared source and rejection of unknown references, missing grants,
wrong kinds and mismatched endpoint pins. A first test fixture used an invalid
one-letter Provider ID; production validation correctly rejected it. The test
IDs alone were corrected; original XML is retained as
`/home/ubuntu/outputs/crm-kyp-candidate-20261028/first-failure/reference-provider-id.xml`.

Builder's candidate `ReadOnlyProviders.createReferenced` now consumes its locked
plan and delegates to this API, retaining existing query guards. Its explicit
injection SPI is tested, but the normal session bootstrap is not switched to it.
Product registration and complete generated-Agent/service-identity admission
remain open; a helper method passing does not establish their completion.

Builder: `PYTHONPATH=src:../base/src /tmp/finance-builder-business-domain-venv/bin/python
-m pytest tests/test_provider_connections.py tests/test_component_assembly.py
tests/test_managed_selection.py -q -p no:cacheprovider`: 62 passed. The interpreter
path is local test infrastructure, not a production dependency or deployment step.

## Authorized Demo source compatibility check (2026-10-28)

The user approved read-only access to
`https://mcp.demo.linggan.top/wealth/product-data/mcp` with no purchase,
transaction or CRM-write permission. Unverified products must not become
recommendable candidates. No credentials were read, copied or added.

Three direct read-only protocol requests completed: initialize, tools/list,
and wealth_assistant_product_search (empty keyword, page 1, pageSize 50).
The server identified itself as wealth-assistant-product 2.2.0, negotiated
2025-06-18, and returned SSE-wrapped JSON-RPC messages. Search returned 15
flat items in one page, not nested productFacts/salesEligibility details.
This is protocol/source evidence only, not generated Host admission evidence.

Search declares eight demo wealth products and seven fund entries marked real;
that declaration does not establish bank sales authority. Some on_sale flags
coexist with past sales-period end dates. Its top-level business asOf is
2026-08-20 while updatedAt is the request-time refresh. Neither is silently
converted into a per-product verified business timestamp.

ProductCatalog now preserves search names, risks and supplied minimum amounts,
while explicitly retaining summary-only, missing sales eligibility and missing
dates as limitations. Unknown facts types stay unknown. Pagination with omitted
rows or additional pages and explicit source failure are rejected. Two new
tests cover these cases without calling the external source.

Validation executed: `mvn -o -pl providers/wealth-product-mcp -am test -q`
completed successfully; `mvn -o -pl integrations/customer-manager-java -am test
-q -DskipTests` compiled integration and test sources successfully, but did not
execute integration tests. KypPreparationAccess is still an uncommitted draft;
normal-session wiring, independent grant provisioning, generated-Agent
admission, report-quality validation and release are NOT complete. Production
was not changed by this compatibility check.

## Architecture review and release decision (2026-10-28)

Decision: the layering is appropriate, but the candidate is NOT release-ready.
Do not equate the new SPI or source connectivity with normal Build support.

Ownership remains:

1. Core SPI owns connection references, exact dependency closure, independent
   grants and locked configuration identity; it must not depend on an MCP SDK,
   product schema, risk matching or UI.
2. The shared mcp-transport module owns bounded protocol exchange, timeout,
   cancellation, target/schema pins and rejection of unsupported sessions.
3. The wealth-product-mcp Provider owns this source's schema normalization,
   provenance, completeness and sales-trust interpretation. CRM credentials and
   customer facts are not remote product search arguments.
4. Domain preparation composes customer and product facts; the reference UI only
   projects its result. It does not grant access or select arbitrary endpoints.
5. Host startup must resolve the installed plan using trusted source settings
   and independent authority. Build freezes the logical references and digests;
   a Skill declaring a dependency does not grant it.

Review fixes in this working candidate:

- Product-source missingInputs survive into the stored report and model context.
  Partial/unknown product status cannot erase missing CRM facts or be described
  as a completed suitability result. Policy references no longer invent a
  catalogVersion when only catalogDigest exists.
- KypPreparationAccess now requires an independently supplied product grant and
  InvocationAccess, checks it before/after querying and before returning, rejects
  mismatched request/trace results, and rejects missing CRM receipts explicitly.
  This is a candidate composition seam, not production authorization wiring.
- Report instructions distinguish nonempty eligible candidates from partial
  evidence. Source products, Demo products and excluded products cannot be
  promoted to recommendations. Prompt instructions are not a substitute for
  deterministic authorization or output validation.

Unclosed release requirements (implementation work, not a request for broader
user permissions):

- CustomerManagerScenarioExtension.open still constructs DirectCrmPreparationAccess;
  it does not construct the KYP composition. LocalAgentHost opens the scenario
  through Context, whose current contract does not inject independently bound
  Providers. ReadOnlyProviders.createReferenced is not called by this path.
- No normal asset registration/Build selection yet freezes the real KYP source
  and independent grant binding. Do not solve this by deriving KYP permissions
  from the CRM grant, reading model-supplied URLs or silently overriding old Locks.
- Product authority must also be checked at report release/history checkpoints
  under the existing history policy; source-return checks alone do not prove
  revocation during model generation is handled.
- The reviewed Demo source is usable for incomplete product facts, not proven
  bank sales eligibility. Detail/pagination/session-auth support is not implied
  by the first-page public-stateless transport. Additional MCPs reuse transport
  and binding mechanisms, but still need reviewed tool/schema contracts,
  credential ownership and authority policy, source normalization and tests.
- A newly generated Agent must pass actual service-identity installation, use
  both independent sources through its normal application entry and preserve
  both evidence sets. Report quality parity is not proven by fixture tests.
  Final generation-input identities, affected qualification, protected merge,
  drain and compatible rollback are still required before deployment.

No production configuration, permissions, credentials, release or Agent state
was changed by this review. The user has already authorized the stated Demo
read-only scope; that authorization is not bank sales verification.

Review verification: the offline customer-manager reactor test run exited 0.
After the report-instruction adjustment, the focused command below passed 36
tests, zero failures/errors/skips (ProductCatalog 10, KypPreparationAccess 7,
PrevisitDeliveryQuality 11, OpenJiuwenReportGenerator 8). The model test uses
the actual SDK against a loopback deterministic model fixture, not the online
model and not an independent report-quality acceptance.

```sh
mvn -o -pl integrations/customer-manager-java -am test -q \
  -Dtest=KypPreparationAccessTest,OpenJiuwenReportGeneratorTest,PrevisitDeliveryQualityTest,ProductCatalogTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Evidence is the corresponding module target/surefire-reports XML and text files.
No production Build, online business query or deployment was run during review.
