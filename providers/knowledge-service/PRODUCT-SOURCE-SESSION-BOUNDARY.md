# H1b product source identity boundary

This is a description of the existing public MCP transport, not a new grant or
an additional login mechanism. The acceptance data is synthetic. It does not
establish access to a bank's controlled product system.

| Boundary | CRM | Product MCP | Knowledge |
| --- | --- | --- | --- |
| Managed Provider | customer-crm-readonly | wealth-product-mcp | knowledge-service |
| Independent connection and policy | Required | Required | Required |
| Credential-bearing source session | CRM source token and employee scope | None for this public source | Private Host lease, independently checked by Factory |
| Return guard | Current CRM scope | Current product policy and unchanged authority binding | Current Host session, fixed Manifest and Factory permission |
| History authorization | Current manager access | Current product-read permission | Separate history lease and current source-read permission |

`KypPreparationBootstrap` deliberately supplies a session getter that rejects
credential acquisition with `KYP_PUBLIC_SOURCE_HAS_NO_CREDENTIAL`. It does not
construct an imaginary upstream login. `KypProductFactory` checks the independent
grant before and after reading. `KypPreparationAccess` also retains and checks
the product authority binding at result release. Its `productSession` is a Host
policy reference, NOT proof of an authenticated upstream SourceSession.

Public access does not remove Host authorization. Audit evidence identifies the
installed Provider, connection, source configuration/content digests, retrieval
time, request/trace and grant binding. The MCP transport receives no CRM bearer
or knowledge lease. The isolated source rejects foreign Authorization headers.
`KypManagedIntegrationTest` and `KypPreparationBootstrapTest` assert this boundary,
including policy revocation before returned facts are released.

**Three independent credential-bearing SourceSessions: NOT SATISFIED.** The
development result is three independently bound Providers, with two credential
paths and one explicitly credentialless source. It must not be relabeled as
three upstream logins or production SSO acceptance.

## Controlled-source follow-up, not implemented by this change

1. Obtain a real approved test product endpoint and its authentication contract
   from the source owner; keep secrets in the existing Host connection provider.
2. Bind a distinct product credential/session to its own principal, subject,
   scopes, purpose, expiry and connection identity. Never reuse the CRM grant.
3. Declare the authenticated source in the existing managed configuration and
   perform normal Build/qualification/installation. Do not relax the current
   public adapter or fabricate a session for the old source.
4. Prove source-side 401/403, expiry, credential rotation, in-flight revocation,
   Stop and independent history checks using that controlled source. Reconcile
   its audit ID with Host requestId/traceId and the Installation Lock.

Production SSO, real bank upstream admission, business-owner acceptance and a
third credential-backed source remain outside this development acceptance.
