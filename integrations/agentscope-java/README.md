# AgentScope Java QUERY Candidate

JDK 21, native AgentScope Java 2.0.3 `ReActAgent`, official `OpenAIChatModel` and
`JdkHttpTransport`. No OpenJiuwen runtime dependency, copied model client, SDK patch,
alternative planner, TASK lifecycle, Builder service or default shell/workspace tools.

This is an isolated candidate, not a deployment. Controlled acceptance does not establish
real-model routing quality. The real-model gate must pass before third-batch publication.

## Build And Run

All Maven commands use the private repository. Do not install these SNAPSHOT artifacts
into shared `~/.m2`; another workstream builds a different baseline with the same versions.

```bash
cd "$ROOT"
bash integrations/agentscope-java/isolatedHost/verify.sh
```

The script creates a new timestamped output under the repository's `target/` by default
(`FH_PROBE_OUTPUT` or first argument overrides; `FH_MAVEN_REPO` overrides the private
repository default `target/m2-agentscope`), runs the real SDK with loopback model
and bank HTTP fixtures, packages the reactor, checks runtime dependencies, verifies
packaged bytes against tested classes, generates operator-pinned observation qualification,
then executes the isolated CLI from JARs. Existing output directories reject, not overwrite.
Raw requests, each repeated test, JUnit reports/logs, dependency tree, qualification,
actual installation Lock and assessment are retained. The bank request totals include
separate direct-Provider comparisons; they are not all Agent invocations.

QUERY input validation also emits a bounded `query-input-validation` observation
before invoking the native SDK tool executor. A rejected input records
`DENIED / CAPABILITY_ARGUMENTS_INVALID` and the capability ID, not parameter
values. This closes a diagnostic gap where native SDK validation rejected a call
before `AgentTool.callAsync` and no Provider span existed. SDK validation, tool
entry authorization and terminal session invalidation remain in place; this is
not automatic parameter correction or a retry mechanism. Previously missing
parameter evidence is not reconstructed from this new event.

`FixtureBank` and `ControlledModel` are test sources. `isolatedHost/` is compiled separately
by the script. Neither is in the integration JAR or registered as an Agent tool.
The example bank Provider is a test-scoped dependency; a production Host supplies its own
Provider registry and connection binding. The isolated distribution explicitly includes that
reference Provider, not the integration's production dependency graph.

```bash
mvn -B -Dmaven.repo.local=/home/ubuntu/outputs/agentscope-onboarding-v01/m2-agentscope \
  -pl integrations/openjiuwen-java -am test
```

## Template API

The template depends on `finance-harness-agentscope-java` and the existing Provider, not
on Builder's OpenJiuwen service classes. The stable integration boundary is:

```java
RuntimeQualification qualification = RuntimeQualification.load(file, hostPinnedSha256);
var prepared = new AgentScopeQueryAssembler().prepare(
    repositoryRoot, solutionFile, supportFile, describedProviders, qualification);
var assessment = prepared.observationAssessment();
try (var session = prepared.openSession(trustedIdentity, currentGrantSupplier, managedConnection)) {
    var frozenInstallationLock = session.lock();
    var reply = session.invoke(new AgentScopeQuerySession.Input(
        text, rootRequestId, traceId, trustedBusinessTime, observationRun, explicitParentId)).block();
}
```

- `ModelConnection(URI, model, apiKey, timeout)` has no stream/retry/fallback escape hatch.
- `Identity` is Host-authenticated principal/type/subject/session/purpose/channel/auth level
  and generation. The Host owns current bank identity and grant verification; a selected
  pack's required scopes never become a grant. No identity comes from tool JSON.
- One session owns one native Agent/Toolkit/memory. Concurrent calls on that session
  reject `SESSION_BUSY`; distinct sessions may run concurrently. A repeated root request
  ID rejects. The Host must explicitly serialize its UI conversation.
- `invalidate()` discards the generation and protected native state; `cancel()` also
  interrupts the Agent. `close()` is terminal. Restoring a grant cannot revive that object.
- `Reply.executions` maps root request -> native tool call -> unique Harness child request
  -> unchanged result and `EvidenceReceiptV1`. Native tool IDs and child request IDs are
  distinct for multi-tool loops. The root `ObservationRun` and trace remain correlated.
- `ObservationRun` and parent are passed explicitly through native RuntimeContext/Reactor
  context. A same-worker scope bridges the existing synchronous Provider observer.
  Configure `NonAiBankingProvider.Config.observations(AgentScopeObservationCollector::currentRun)`.
- An optional seventh Input argument, `ModelDiagnostics`, requires both enablement and
  live capture authorization. It reuses `ModelSnapshotReceiver` and the existing
  `CLIENT_REQUEST_PREPARED` / `PARSED_MODEL_RESPONSE` labels. Host must filter/redact and
  enforce storage/view permissions. Credentials/headers and reasoning blocks are not
  deliberately projected. Bodies are not dispatched-HTTP proof. Failures are reported
  separately and do not repeat a bank query. Required body archive collection is not
  qualified by this metadata collector and must reject; third-batch Host storage remains
  independently qualified.

## Metadata Compatibility

Solution input remains `finance-agent-solution-build.v1`; installation Lock remains
`finance-solution-lock.v1`, frozen once after native tool registration. Existing fields
and domain `profile/capabilities/skills` are retained. Runtime assets use real
`RUNTIME_TEMPLATE` / `RUNTIME_INTEGRATION` / `PROVIDER_RUNTIME` versions; assembly,
collector, authorization binding and declarations have additional asset kinds, not a
replacement Lock schema. Observation assessment stays a separate public accessor/file.

The support sidecar is an explicit narrow admission policy, not a replacement solution
contract. It admits QUERY, one total model attempt and tool attempt, nonstream, no fallback
or pending-tool recovery. Six native reasoning iterations are allowed: one attempt does
not mean one model call for the entire conversation. Model timeout is bounded to 60s;
tool timeout is 30s. No unqualified mode is exposed.

The pinned reference support admits `local-read-only@0.1.0` and `cashflow-read-only@0.1.0`,
with CUSTOMER self-service and the qualified balance/cashflow subset. These are versioned
declarations, not business-ID branches in the generic adapter. Profile source/identity must match
the existing catalog and capability/domain scope. Missing/unknown versions and evaluation
pack references reject. Provider coordinates and connection kinds come from the support
declarations and must match the Provider's own loaded Maven artifact. Existing pack/provider component sidecars are read and checked;
their original bytes are unchanged. Domain references use the existing tree hash, capability
pack files use file hashes, Provider source references preserve file-or-tree semantics.

`reference/catalog-entry.json` uses the existing catalog entry shape but is not published.
`engineering/runtime-qualification-artifacts.agentscope-java.json` is the new family-specific
inventory; the old OpenJiuwen qualification dictionary and SDK pins are untouched.

The observation qualification envelope is the existing
`finance-observation-qualification.v0.1` (`collectors`, identity, semantics, passed, local
digest-checked evidence). Runtime identity binds actual SDK/Integrator/assembly/Provider/
collector artifacts. Required named checks, each passed result and three repeated evidence
files are mandatory. This is trusted operator assertion with checked evidence, not a
cryptographic attestation that tests executed or a production IAM approval.
Evidence references use the existing bare-hex `sha256` field. Host-pinned qualification
digests and installation asset digests use the `sha256:` prefix.

## Extraction Boundary

`packages/java/assembly` contains pack DTO/loading, dependency data, description/terminology
compilation, original digest functions, local-only schema policy and the synchronous model
snapshot receiver interface. It has no SDK imports or Agent loop. Old public records and
entry points remain facades; TASK/checkpoint/recovery stay in OpenJiuwen.

The common module does not depend on networknt: each adapter makes a small validator
call while sharing the same local-reference policy. Both Java adapters now use networknt
2.0.0, also required by the managed MCP SDK 0.17.2. The former OpenJiuwen adapter's
1.5.6 dependency selected an incompatible ABI in the packaged Host; this is an adapter
dependency correction, not an OpenJiuwen or AgentScope Runtime SDK upgrade. Exact Host
classpath probes and fresh qualification are required before deploying this change.

## Fixed SDK Evidence

Official tag `v2.0.3` is annotated: object `dddea5298de632d44e62cf8c8868e2ee8bc99e3e`
peels to commit `1b8e3dcd2338550ae5198bdb2a7bae56df5bf2e0`. The archive prefix is not main.
The preceding probe's `sdk-probe/REPORT.md`, `evidence/final-audit.json`, source-tree and
Maven hash records are retained under the onboarding output directory.

Relevant official source locations at that commit:

- `agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java:3503,3712`: final-summary/error behavior.
- `agentscope-core/.../tool/ToolExecutor.java`: native error results and execution policy.
- `agentscope-core/.../tool/ToolValidator.java:22`: networknt 2.x ABI.
- `agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/src/main/java/io/agentscope/extensions/model/openai/OpenAIChatModel.java:95`: retry below model hook.
- Same module `OpenAIClient.java:406,710`: injected transport boundary.
- `agentscope-core/src/main/java/io/agentscope/core/model/transport/JdkHttpTransport.java:117`:
  default automatic redirects; integration supplies an official transport with a JDK client
  configured `Redirect.NEVER`. A 307/308 response cannot make an unchecked second send.

The unwrapped SDK is not fail-closed: native tool errors and final-summary recovery can
be ordinary responses. Middleware, checked per-send transport, sticky generation invalidation
and final delivery authorization are all necessary. HTTP SENT telemetry is asserted only
after the delegate returns a response; loopback server logs are the controlled external proof.
Bank `NOT_AUTHENTICATED` (including HTTP 401) and `NOT_AUTHORIZED` both poison the
session, not ordinary model-visible tool facts. Cancellation/terminal cleanup is repeated
at native lifecycle and late tool completion boundaries; tests inspect the actual native
context and summary after an interrupt-ignoring Provider returns.
