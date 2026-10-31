# Evidence-only Knowledge Provider

Merge-preparation extraction from H1a Harness 8b9a8d0. Only
`knowledge.evidence.search` is supported. The two-tool synthetic rule probe
remains on PR95; its rule API and capability pack are not included here.

The candidate is version 0.1.0-probe.2 because removing the unaccepted rule
changes Provider bytes. Prior probe.1 collector identity / Installation Locks
are preserved as original evidence, not relabeled as this candidate.

Host-owned pinned bindings, current source session and Grant, required scopes,
bounded HTTP, receipt correlations and late-revocation checks are retained.
Preparation is metadata-only; it cannot acquire credentials or call a source.
This isolated loopback adapter does not claim production SSO, real-model quality
or arbitrary remote endpoints. Managed installation still requires the matching
Host, qualified collector, and current Factory authorization.

## Provider-scoped composition preparation

`InstalledKnowledgeProvider.prepare(root, peers)` and the corresponding `create`
overload accept actual, Host-prepared peer adapters. They return only the knowledge
adapter; they do not install or authorize peers. Existing callers that omit peers
remain knowledge-only and reject a joint artifact.

The global capability set must exactly match the locked plan, with no duplicate
capabilities. All provider bindings and connection references must be covered by
the actual registry. Each peer's descriptor must match its key and support the
assigned QUERY capabilities. Unknown, unused, mismatched and duplicate providers
are rejected; other knowledge capabilities, including rule evaluation, remain
unsupported. A single connection reference cannot name different transports.

This is an additional composition check, not a replacement for complete artifact
digest verification, actual provider/runtime identity checks, REQUIRED observation
qualification, or the combined registry's Installation Lock. Constructing the
knowledge adapter obtains no session or grant and invokes no source. Each Provider
still requires its own current authority at runtime.

The overload is preparation for H1b, not acceptance of a combined customer-manager
Host. CRM/product/knowledge runtime orchestration, joint answer archival and
independent history authorization still require explicit wiring and real acceptance.
