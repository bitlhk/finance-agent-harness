# Isolated Candidate Verification

Baseline: `597a834`; first-batch docs-only `14c985b` cherry-picked as `b085ba6`.
No push, deployment, root catalog publication or Builder modification.

## Fixed Controlled Artifact

Final output on this verification machine:
`/home/ubuntu/outputs/agentscope-onboarding-v01/formal-final-1`.
This is an immutable run directory, not the portable script's required location.

```bash
FH_MAVEN_REPO=/home/ubuntu/outputs/agentscope-onboarding-v01/m2-agentscope \
  bash integrations/agentscope-java/isolatedHost/verify.sh \
  /home/ubuntu/outputs/agentscope-onboarding-v01/formal-final-1
```

Result: 30 tests, zero failures/errors/skips. Qualification checks actual packaged classes
against the tested bytes and resolves the Provider's own Maven metadata. The integration
JAR excludes test servers and the reference Host. Runtime dependency tree excludes OpenJiuwen;
the example bank dependency is test-scoped. The CLI uses separately packaged reference
Provider dependencies and no Builder service classes.

- Raw controlled suite: 69 model HTTP requests, 63 bank HTTP requests including direct
  Provider comparisons. See `qualification/summary.json`, `qualification/raw/`, and `raw/`.
- Separate packaged CLI balance check: 2 model HTTP requests, 1 bank customer read.
  See `controlled-host/model-http.json`, `bank-http.json`, `result.json` and `observations.json`.
- Qualification: `qualification/agentscope-observation-qualification.json`.
- Pinned digest: `sha256:596fe691671c26aa83f05e8fdc443954a5c17689e8f725c0e888611f0742c9e1`.
- Installation and assessment: `controlled-host/solution-lock.json` and
  `controlled-host/observation-assessment.json`. Reads do not regenerate the installation date.
- Native multi-tool executions have unique child Harness request IDs and native tool IDs,
  linked to the unchanged root request/trace and existing EvidenceReceipt contract.

## Safety Regressions

| Regression | Controlled result |
| --- | --- |
| Provider-return revocation | 1 initial model request, 1 bank read, no additional model HTTP; restored grant cannot revive generation |
| Tool-before denial | 1 model request, zero bank requests |
| Invalid tool JSON / missing tool / TASK-like tool | No bank access or subsequent model request |
| Native/model/Provider errors | No unchecked model continuation, retry, fallback or bank redo |
| Redirect 307 and 308, with and without revocation | Each origin gets 1 request; redirect target gets 0 |
| Bank HTTP 401 and 403 | Each has 1 bank read and 1 model request; no continuation, generation poisoned |
| Interrupt-ignoring late Provider and terminal response revocation | Actual native context and summary empty in 20 consecutive terminal samples; raw `terminalMemoryChecks` retained |
| Two concurrent sessions | No prompt/context/subject crossover; explicit runtime-tool -> capability -> provider-http parents and worker-thread observations |
| One session concurrency | SESSION_BUSY, no second concurrent native invocation |
| Observation/capture sink failures | Business outcome preserved, degradation reported, no repeat bank query |
| Diagnostic body switches | Separate enablement and capture authorization; no capture when either denies |
| Qualification tampering | Missing named check, failed proof, artifact/declaration mismatch and evidence tamper reject |
| Compatibility | Version/source/scope, provider artifact/connection mismatch and duplicate declarations reject |

Compound, multi-turn and missing-data scenarios each execute three times per controlled run.
These are native SDK protocol/financial-contract tests, not a model-intelligence score. Direct
Provider comparisons exclude only per-read `observedAt`, not business totals, source identity,
period, classification or gaps. Source summary/analysis differences remain intact.

## Compatibility Regression

```bash
mvn -B -Dmaven.repo.local=/home/ubuntu/outputs/agentscope-onboarding-v01/m2-agentscope \
  -pl integrations/openjiuwen-java,integrations/agentscope-java -am test \
  -Dagentscope.evidenceDirectory=/home/ubuntu/outputs/agentscope-onboarding-v01/formal-cross-regression-1/raw \
  -l /home/ubuntu/outputs/agentscope-onboarding-v01/formal-cross-regression-1.log
```

Result: AgentScope 30/30; OpenJiuwen 75 tests, zero failures/errors, one existing skip.
Both run against the same shared assembly sources, with SDK-specific schema-validator ABIs.
The negative runtime-class test uses the real OpenJiuwen `com.openjiuwen.core.singleagent.BaseAgent`.

Existing business packages, SOP, Provider source, old SDK pins, catalog and old qualification
dictionary have no diff. Additions are the new integration, isolated references, family-specific
artifact inventory and the minimal shared extraction/compatibility facades.

## Retained Failures And Pending Gate

Earlier logs `agentscope-formal-test-run-1/2/3.log` under `/tmp` and archived raw records at
`outputs/agentscope-onboarding-v01/formal-controlled-evidence-history` are preserved. Failures
were incorrect test API names, incompatible networknt ABI, and comparing independently sampled
`observedAt` as if identical. The later declaration-refactor compile failure is retained in
`formal-declared-packaged-run-1/maven.log`. None was renamed to PASS or erased.

Real managed model evaluation is NOT RUN by this workstream. Use the same isolated CLI and
`isolatedHost/live-driver.mjs`; runtime completion is explicitly not an evaluation pass. The
main thread must inspect answer quality, method boundaries and source gaps, and retain genuine
timeouts/errors without automatic retry. Third-batch Host archive/body-store qualification,
catalog publication and deployment remain outside this candidate.
