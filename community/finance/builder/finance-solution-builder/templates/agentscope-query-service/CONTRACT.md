# Managed AgentScope QUERY Host Contract

This template is `builder-template://agentscope-query-service`. It composes the exact
shared files in `shared-sources.json` with its runtime-specific sources. Do not copy
the excluded OJ/Python/TASK implementations or compile both `NativeConversation`s.
The complete shared + local file closure participates in the template digest.

## Entry Points

- Main: `io.github.bitlhk.finance.builder.local.LocalAgentHost <candidate-root>`.
- Admission: `io.github.bitlhk.finance.builder.local.AgentScopeAdmission <candidate-root>`.
- Collector identities: `io.github.bitlhk.finance.builder.local.CollectorQualification <candidate-root>`.
- JDK 21; actual AgentScope core and OpenAI extension 2.0.3; no OJ engine dependency.

Admission exits zero only after actual loaded SDK/Integrator/Provider identities,
the original asset references, exact component plan and both observation admissions
are checked. It writes `component-installation-plan.json` using the existing
`finance-installation-plan.v0.2` shape. Stdout is one JSON object:

```json
{"schemaVersion":"finance-agentscope-host-admission.v0.1","status":"READY",
 "runtime":"agentscope-java-2.0.3","installation":"NOT_OBSERVED",
 "modelInvocation":"NOT_OBSERVED","installationPlan":"component-installation-plan.json",
 "installationPlanSha256":"<actual bare sha256>",
 "observationAssessment":{},"hostObservationAssessment":{}}
```

Failure exits 2, never a successful installation claim. No model/grant is created by
admission; Provider factories have a deny-only source-session supplier for inspection.
Existing output is immutable: only identical installation-plan bytes may be reused.

## Candidate Files

- `host-support.v0.1.json`: `finance-host-support.v0.1`, QUERY only.
- `agentscope-support.json`: pinned `agentscope-query-support.v0.1` compatibility
  declaration consumed by the Harness. Its fixed source is
  `builder-template://agentscope-query-service/agentscope-support.v0.1.json`, frozen
  as `runtimeBinding.supportProfile.source/digest`. The generator may select routing
  descriptions but must not rewrite purposes, profiles or Provider declarations.
  For this shared simulator Host the declared purpose is `LOCAL_READONLY_DESCRIPTION`;
  the isolated CLI support and original business/domain packages remain unchanged.
- `solution.json`: unchanged Harness solution contract with original package refs.
- `assets/catalog/finance-assets.v1.json`: original profile catalog bytes, required by
  actual profile applicability/version validation; include it in the file inventory.
- `component-plan.json`: existing `finance-local-component-plan.v0.1`, exact full
  capability definitions, bindings and actual runtime/template/support identities.
- `service-lock.json`: existing files/capabilities/runtime/buildId inventory, plus
  `hostTemplateDigest` and `sharedHostSources` for the collector inventory-map identity.
  `managedTemplateDigest` independently preserves the composed-tree digest from both
  `runtimeBinding.managedTemplate` and `component-plan.managedTemplate`; never replace
  it with the collector inventory digest.
- Optional `observation-reference.json`: existing requirement reference, checked by
  shared Host admission. REQUIRED history/body is never downgraded or auto-qualified.
- Existing `model-selection.json`, `ui-config.json` and private model registry contract.

The initial supported installation unit is a whole capability pack. A partial
selection that differs from actual Prepared capabilities is rejected. Do not rewrite
original pack/Skill bytes under their original identity or silently install extra tools.
Profiles and Provider compatibility stay in versioned declarations, not business-ID branches.
Dependent Provider factories beyond the qualified direct QUERY boundary are explicitly rejected.

## Private Configuration And Qualifications

Existing `FH_HOST_TOKEN`, bank simulator/access policy, model registry, lifecycle,
history and snapshot owner variables retain their meanings. Additional native inputs:

- `FH_AGENTSCOPE_QUALIFICATION_FILE`: trusted native qualification JSON.
- `FH_AGENTSCOPE_QUALIFICATION_SHA256`: pinned `sha256:<hex>` digest.
- `FH_MODEL_TIMEOUT_SECONDS`: 45 by default, constrained by Harness ModelConnection.
- `FH_BUSINESS_TIME_ZONE`: Asia/Shanghai by default.
- `FH_BUSINESS_DATE`: optional Host-administered date; never HTTP/model input.

`FH_OBSERVATION_QUALIFICATION_FILE` / `FH_OBSERVATION_QUALIFICATION_SHA256` continue
to admit the actual `local-host-archive` and `local-client-snapshot` implementations
under `finance-observation-qualification.v0.1`. Their proof is distinct from native
qualification and binds the composed template, compiled collector/Host classes,
loaded dependencies, source closure and individual passed checks/raw evidence.
The old isolated collector does not qualify body filtering, queueing or persistence.

## Compatibility And Verification

HTTP auth, root-owned identity, AG-UI buffered delivery, source authorization and
history projection use shared implementations. Native cancellation invalidates the
generation; a subsequent authorized turn opens a fresh native generation with no
previous model/display memory. Model token streaming, fallback, transport retries,
TASK, shell, workspace and todo tools are not supported.

Shared edits also change OJ Java and Python bridge collector identities. Old agents
and their original qualification files are not rewritten. Newly generated OJ Java,
new Java and Python artifacts require their own freshly generated Host collector
qualification; the first-stage qualification is not reusable for new source bytes.
Their existing verification entry points remain `engineering/verify_execution_history.py`,
`engineering/verify_model_snapshots.py`, and `engineering/verify_python_snapshot_faults.py`.
Parent owns that cross-runtime regeneration; this worker records shared regressions.

Scope engineering entry: `engineering/verify_agentscope_host.py OUTPUT --harness HARNESS
--native-bundle NEW_NATIVE_BUNDLE --maven-repo PRIVATE_REPO`. Output must not exist.
Native API changes are requalified into a new directory by Harness
`integrations/agentscope-java/isolatedHost/verify.sh`; never replace final-4.
