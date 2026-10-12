# Finance Skill Eval Runtime Launcher V1 (Candidate)

This private integration implements Eval's existing five Launcher operations over
the Finance Solution Builder. It is not a new Runtime or a public upload runner.
Native end-to-end and live-model acceptance status must be checked in the delivery
report before registering a deployment as available.

## Ownership

- Eval owns cases, comparison, scoring and the original Skill identity.
- Builder owns temporary build/instance lifecycle and immutable build inputs.
- Harness owns admitted capability dependencies, execution checks and native
  Runtime integration. An uploaded instruction never grants bank permissions.
- Bank and model connections are server-owned. Client credentials, scripts, MCP
  launch commands and arbitrary Provider binaries are rejected in this version.

## Protocol

Register a separate environment URL per Runtime, e.g.:

| Target | Base URL on the same host |
| --- | --- |
| OpenJiuwen Java 0.1.15 | `http://127.0.0.1:18218/oj/` |
| AgentScope Java 2.0.3 | `http://127.0.0.1:18218/as/` |

These are configuration examples, not a claim that port 18218 is deployed.
Loopback is relative to the calling Eval process; containers and remote machines
must not assume they share localhost. Do not expose this listener publicly.

Each base URL implements:

1. `GET v1/runtime-capabilities`
2. `POST v1/runtime-instances` with `Idempotency-Key`
3. `GET v1/runtime-instances/{instanceId}`
4. `POST v1/runtime-instances/{instanceId}/execute`
5. `DELETE v1/runtime-instances/{instanceId}`

All operations require a service bearer token. Its SHA-256 maps to a server-owned
principal and allowed Runtime aliases. No request field supplies an owner.
Browser Origin requests are rejected. Eval's existing `runtime_launcher` adapter
consumes the protocol; the current environment catalog can classify AgentScope
as `generic_http` without pretending its native runtime is OpenJiuwen.

The facade accepts a fixed `skill_version`, a UTF-8 ZIP with root `SKILL.md`, text
references, and empty client configuration/secrets. It rejects baseline-without-
Skill, unknown tools, executable dependencies and unsupported content. Supported
subsets are deliberately narrower than the general Eval Launcher protocol.

Capabilities include `nativeToolNames: string[]`, the sorted, deduplicated native
tool inventory from the selected runtime alias's server-owned preset `toolNames`
(exposed as `requiredToolNames` in fixture preset information). This version has
one preset per alias, so its runtime union is that preset's inventory; tools from
other aliases or owners are not merged. No business tool names are hardcoded.
Eval can use this inventory during preflight to distinguish installed native tools
from missing external MCP dependencies. It is not an authorization grant:
create/execute still enforce the exact chosen preset, and external MCP stays off.

This additive field requires paired deployment with the updated Eval capabilities
schema, where it is optional for compatibility with older Launcher servers.
Older strict-schema Eval clients can reject the new response field; do not assume
that an unchanged schemaVersion makes those clients forward-compatible.

## Source and Runtime Evidence

The Eval asset fingerprint, ZIP SHA-256, per-file SHA-256, transformed method
digest, service Lock and actual runtime installation Lock are different identities.
They are checked and linked; one must not be relabeled as another.

Published method mode requires exact admitted source bytes. The adapter checks
that the source content/resources match the selected method, applies the existing
`runtime_files` transformation and verifies its installed Skill digest. Referenced
text must be consumed identically on both targets; unused resources are not silently
reported as loaded. Temporary execution admission must not be called publication.

### Temporary Uploaded Method

A server preset may opt into `allowDrafts: true`. Its admitted base method defines
the trusted capability bindings, source and cases. Uploaded Markdown and text
references replace only method content, not permissions, Provider code or model
configuration. The existing importer and source/compatibility checks run before
the private candidate enters the existing build and installation checks.

The candidate is marked `EXECUTION_CANDIDATE / NOT_PUBLISHED`, has an immutable
source and installation digest, and is not written into the public directory or
formal release store. The internal legacy `releaseDigest` field identifies this
snapshot for existing build contracts; it is not evidence of formal publication.
All supplied reference resources must be included in the installed method.

This first adapter does not infer arbitrary new tools from prose. New dependencies
need an admitted template/binding before execution. A successful import or content
check alone is not execution admission, nor a professional quality verdict.

Evidence reports tool outcomes independently from request completion. A successful
HTTP response or nonempty model reply is not proof that the business answer is
correct. Scoring remains Eval's responsibility. No hidden reasoning is collected.

## Isolation and Cleanup

Each case creates a new Host session. Later cases restart the same frozen artifact
using the existing stop/start lifecycle, then log in again. Same-model switching is
not used as a customer reset: the Host correctly retains its trusted subject binding
on that path. No authorization check is removed to permit a new test case.
Same-instance cases serialize with a bounded deadline; different instances can run
independently subject to the existing Host/Runtime constraints. Multi-turn cases
within one preserved session are not part of V1.

Case elapsed time includes this isolation restart; it must not be reported as pure
model latency. A Launcher instance is the logical evaluation handle; the actual
Host instance and session identifiers are recorded per case.

Delete, TTL and execution timeout close only the owned instance. The current build
queue cannot cancel a single Maven build; a cancelled build is allowed to finish
and is then reclaimed, never launched for use. This is not immediate build
cancellation. Locks/evidence needed for diagnosis are retained separately from
large disposable candidates. Historical evidence does not rerun a model or bank.

## Model Comparison Boundary

Matching model IDs is insufficient. Without a `generation` field, the two native
paths retain their existing, different defaults. A Launcher create request may
optionally include this complete, closed object:

```json
{"generation":{"schemaVersion":"finance.eval-generation.v1","temperature":0.0,"top_p":1.0,"max_tokens":2000}}
```

All fields are required when the object is present. Temperature is a finite number
in `[0,2]`, top_p is a finite number in `(0,1]`, and max_tokens is an integer in
`[1,32768]`. Booleans, numeric strings, null, partial objects and unknown fields
are rejected. Omission preserves the old request and native-default behavior.
No parameter may change the server-owned model ID, URL, key, provider or owner.

The backend binds these values to the preset's modelRef. The private build API
passes a keyword-only binding to Jobs.create; the public Workbench selection and
start bodies are unchanged. Jobs freezes a deep copy as modelSelection.modelGeneration,
which is written into model-selection.json and the artifact service lock during
construction, not patched into an already compiled artifact. Model connection
configVersion keeps its existing meaning and does not substitute for a generation
digest. This is a private Solution Builder extension, not a Base contract change.

Jobs.start injects only the frozen binding as FH_MODEL_GENERATION_JSON in the
new child process environment, without writing to the parent environment or shared
model registry. Host checks the value against the locked selection, applies it to
the selected model, and returns modelGeneration/modelGenerationSha256. Later case
restarts use the same lock and binding. Job access remains owner-scoped.
The generation digest is SHA-256 of the canonical JSON UTF-8 bytes emitted by
Python into the child environment. Java hashes those same input bytes after
checking the parsed value against the lock; it does not reserialize floating-point
values to calculate the digest. This avoids Python/Java exponent-format differences.

Explicit OJ sessions set temperature, topP and maxTokens. AS sessions use the
Harness ModelConnection Generation constructor; the updated SDK is required to
compile the new Host. Host template/SDK changes require current qualification;
old qualification and seed binaries must not be relabeled or bypassed.

Evidence distinguishes `modelGenerationHostVerified=true` (locked Host configuration
and matching Host response) from actual wire verification. `modelParametersVerified`
remains false with `LOCKED_HOST_CONFIGURATION_NOT_WIRE_OBSERVATION`; preset declarations
alone never establish parameter equivalence. Verify wire parameters, business date,
timezone and final method bytes before interpreting quality differences.

## Local Verification

From the Finance Solution Builder project, with its dependencies installed:

```bash
PYTHONPATH=src:../base/src python -m pytest tests/test_eval_launcher_service.py tests/test_eval_launcher_backend.py
PYTHONPATH=src:../base/src python -m pytest tests --junitxml=/private/output/generation-builder-tests.xml
python engineering/verify_eval_launcher_protocol.py --eval-root /path/to/built/finance-skill-eval --output /private/output/protocol.json
```

The protocol command uses the actual built Eval TypeScript client against a controlled
backend. It proves schema and lifecycle compatibility, not native execution.

Native opt-in verification uses current Harness assets, the existing qualification
files/environment, and an isolated synthetic bank:

```bash
python engineering/verify_eval_launcher_native.py --harness /path/to/finance-agent-harness --output /private/new-output-directory
```

To verify explicit parameters, add
`--generation '{"schemaVersion":"finance.eval-generation.v1","temperature":0.0,"top_p":1.0,"max_tokens":2000}'`.
The controlled native probe then checks every captured model request for all three
wire fields on both runtimes and both fresh Host generations, plus the Host binding
digest. Only a passing run reports `CONTROLLED_WIRE_VERIFIED`. Adding this option
does not run the probe or constitute qualification evidence by itself.

### Native Fixture for Eval UI

Add `--serve-info /private/serve-info.json --serve-seconds 1200
--stop-file /private/stop-fixture` to bootstrap the same admitted native fixture
and expose its five Launcher operations instead of running the script's cases.
`--draft` remains the independent switch for execution-candidate admission.
This mode uses the real BuilderBackend and the same synthetic bank/model fixture,
not a fake Launcher. Current native and Host qualification is still required.

The new info file has mode 0600 and contains `baseUrl`, a random test `token`,
`skill` (including archiveBase64/files), `modelId: launcher-model`, and
`runtimes.oj/as` with `path: oj/` or `as/`, model/runtime metadata, required tools,
and private preset references. An explicitly supplied generation object is also
included. The token is not printed. Info and stop-file paths must initially be
absent. Read `status: READY` before connecting; shutdown changes it to `STOPPED`.

SIGTERM/SIGINT, the appearance of the stop file, or the deadline stop the listener;
the script's finally block closes Launcher, owned Jobs, and the bank. The serving
duration is restricted to 1..1200 seconds after READY; build/bootstrap time is
separate. Finally, `fixture-model-requests.json` and `fixture-bank-reads.json` retain
the raw synthetic request bodies/read records privately, without model credentials
or HTTP authorization headers. A fixture session is not an acceptance verdict.
This listener is for isolated Eval UI tests only, not a production deployment.
READY means that the fixture HTTP listener is available; it does not mean either
Runtime has completed an evaluation. Unit-test/JUnit results, native qualification,
captured wire checks, and real-model evaluation remain separate evidence levels.

For an approved live-model test, serve mode additionally accepts the paired flags
`--live-model-base http://127.0.0.1:PORT/v1 --live-model-name MODEL`.
Both require `--serve-info`. Only HTTP(S) URLs whose hostname is exactly
`127.0.0.1` or `localhost` are accepted, without userinfo, query or fragment.
The parent-owned loopback relay holds the upstream HTTPS credentials in memory;
the fixture uses only a local placeholder key. Its model registry connection and
`FH_MODEL_MODE=LIVE` target that relay; the synthetic bank remains unchanged and
Launcher admission remains QUERY-only. Omission preserves controlled defaults.

Live info reports `mode: LIVE_NATIVE_FIXTURE`, `realModel: LIVE`, and model HTTP
evidence ownership `EXTERNAL_LOOPBACK_RELAY`. These describe configuration, not a
successful invocation. Finally, `fixture-model-evidence.json` records invocation
status `NOT_OBSERVED_BY_FIXTURE`: an empty `fixture-model-requests.json` is not
evidence that no model was called. Retain the relay's independent HTTP records for
wire-parameter and real-model claims. Without `--resume-admission`, bootstrap's
existing admission trial can itself call the relay before READY.

If the pinned Builder declaration marks AgentScope as `CANDIDATE`, the isolated
test operator must explicitly enable `FH_CANDIDATE_RUNTIME_IDS=agentscope-java-2.0.3`
using the existing candidate admission mechanism. This does not replace native
and Host observation qualification files. A disabled target is rejected rather
than silently replaced with another runtime.

It must not disable REQUIRED observation, authorization, source version or Runtime
admission checks. Controlled-model results are not real-model results. AI execution
is not independent human onboarding acceptance.

## Service Startup

Provision a dedicated, owner-private Jobs directory and server-owned presets. Use
the same existing model registry, simulator grants and qualification environment as
the admitted Host. Never point the candidate at the production Jobs directory.

```bash
PYTHONPATH=src:../base/src python -m finance_solution_builder.eval_launcher_http --config /private/launcher.json --port 18218
```

The configuration file must not be a symlink and must have mode 0600. Its top-level
keys are `backend`, `presets`, `principals`, optional `maxInstances` and `ttlSeconds`.
`backend` supplies `project`, `harness`, `data_dir`, optional `maven` and
`max_instances`. Presets bind runtime identity, parsed selection, method, model,
allowed tools and source digests. Tokens and connection keys never enter Skill ZIPs.

Until native/live acceptance passes, retain this integration as a candidate and
do not advertise it as an available production evaluation target.
