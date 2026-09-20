# Isolated Candidate Verification

Baseline: `597a834`; first-batch docs-only `14c985b` cherry-picked as `b085ba6`.
Parent first-batch merge: `40b4864`; terminal reply race fix: `96dec41`;
LIVE evaluation distinction: `02ff041`; independent required continuation check: `47e3f6e`.
This regeneration performs no push, deployment, root catalog publication or Builder modification.

## Fixed Controlled Artifact

Final output on this verification machine:
`/home/ubuntu/finance-harness-agentscope-query/target/agentscope-fixed-final-4`.
This is an immutable run directory, not the portable script's required location.
Runtime and test source commit: `47e3f6eee36a2cf176e6e89a772cff7f6f813bad`.
Any later documentation commit is distinct from this executed source identity.

```bash
env -u FH_ISOLATED_SOLUTION -u FH_ISOLATED_SUPPORT -u FH_ISOLATED_FIXTURE \
  FH_MAVEN_REPO=/home/ubuntu/outputs/agentscope-onboarding-v01/m2-agentscope \
  bash integrations/agentscope-java/isolatedHost/verify.sh \
  "$PWD/target/agentscope-fixed-final-4"
```

Result: 31 tests, zero failures/errors/skips; all 24 required named checks passed.
The new `native-continuation` check independently covers immediate reactive `flatMap`
continuation into two further native turns: 3 model HTTP requests, zero bank customer reads,
no SESSION_BUSY, final reply retained and session valid. The original compound, multi-turn
and missing-data checks each retain exactly three distinct passing evidence files.
Qualification checks actual packaged classes
against the tested bytes and resolves the Provider's own Maven metadata. The integration
JAR excludes test servers and the reference Host. Runtime dependency tree excludes OpenJiuwen;
the example bank dependency is test-scoped. The CLI uses separately packaged reference
Provider dependencies and no Builder service classes.

- Raw controlled suite: 72 model HTTP requests, 63 bank HTTP requests including direct
  Provider comparisons. See `qualification/summary.json`, `qualification/raw/`, and `raw/`.
- Two separate JVM packaged CLI balance checks: each 2 model HTTP requests, 1 bank customer read.
  See `controlled-host/model-http.json`, `bank-http.json`, `result.json` and `observations.json`.
- Qualification: `qualification/agentscope-observation-qualification.json`.
- Pinned digest: `sha256:63f1b388ea60df0b2129fd700378a2adeb4f624302b94e84d009c24edbddf2bb`.
- Fixed integration JAR SHA-256: `ed838650960c32891786e32321af33dab625a99a3c5d44fc3200d38f894bcb7e`.
- `bundle-identity.json` records all 49 JARs, both packaged Host classes, source commit,
  installed declarations/digests, evidence hashes and exact proof counts. The official SDK,
  assembly and Provider JAR hashes remain the same as the prior final-2 bundle.
- Installation and assessment: `controlled-host/solution-lock.json` and
  `controlled-host/observation-assessment.json`. Reads do not regenerate the installation date.
- Native multi-tool executions have unique child Harness request IDs and native tool IDs,
  linked to the unchanged root request/trace and existing EvidenceReceipt contract.

Packaged subset Host verification, completed before freezing the new bundle:

```bash
ROOT="$PWD"
node integrations/agentscope-java/isolatedHost/subset-smoke.mjs "$ROOT" \
  "$ROOT/target/agentscope-fixed-final-4" "$ROOT/target/agentscope-fixed-final-4/subset-host"
```

All 6 checks passed. Balance-only: 2 model HTTP requests / 1 bank read. Cashflow-only with
an uninstalled balance tool response: 1 model HTTP request / 0 bank reads, rejected without
continuation. Both actual model tool declarations and Lock installations match the subset,
and original capability references/digests are preserved. Four absolute/relative/symlink/support
outside-root selections reject before output creation or fixture startup. The bundle's own
packaged Host now includes FH_ISOLATED_SOLUTION/FH_ISOLATED_SUPPORT; no old final-2 overlay is
needed. The subset helper's separately compiled Host class was also byte-compared with the
packaged Host and is identical. `subset-host/classpath.txt` uses the same final-4 dependencies
and qualification. See `subset-host/summary.json`, per-case raw HTTP and process logs.
The commands above document the actual runs; existing output directories reject replay.
Choose a new directory for any future rebuild and generate a new qualification identity.

## Safety Regressions

| Regression | Controlled result |
| --- | --- |
| Provider-return revocation | 1 initial model request, 1 bank read, no additional model HTTP; restored grant cannot revive generation |
| Tool-before denial | 1 model request, zero bank requests |
| Invalid tool JSON / missing tool / TASK-like tool | No bank access or subsequent model request |
| Tested exception paths | No unchecked continuation, SDK automatic retry, fallback or bank redo; model-selected new tool calls are a distinct LIVE behavior |
| Redirect 307 and 308, with and without revocation | Each origin gets 1 request; redirect target gets 0 |
| Bank HTTP 401 and 403 | Each has 1 bank read and 1 model request; no continuation, generation poisoned |
| Interrupt-ignoring late Provider and terminal response revocation | Actual native context and summary empty in 20 consecutive terminal samples; raw `terminalMemoryChecks` retained |
| Two concurrent sessions | No prompt/context/subject crossover; explicit runtime-tool -> capability -> provider-http parents and worker-thread observations |
| One session concurrency | SESSION_BUSY, no second concurrent native invocation |
| Immediate reactive continuation | Completed reply releases the session before downstream flatMap starts the next native turn; three turns complete |
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

Historical result before the terminal reply fix: AgentScope 30/30; OpenJiuwen 75 tests,
zero failures/errors, one existing skip. This cross-runtime command was not rerun during
the final-4 regeneration; its result must not be relabeled as a new 31-test cross-runtime run.
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
The earlier `formal-final-1` artifact is superseded: a default-output replay exposed
noncanonical Map ordering in cross-JVM qualification identity hashing. The identity now
uses sorted map keys, has an independent canonical-byte unit assertion, and passes two
separate JVM CLI runs. The failed replay is retained at
`target/agentscope-probe-20260920T124011Z`. The script also cleans only module targets,
not the parent target containing the default private Maven cache and run output.

`agentscope-fixed-final-2` is now superseded as the current candidate. Its historical 30-test /
23-check / two-JVM PASS and pin
`sha256:edd5c3d69f42a89ecaa28a54ec067a75354d7dc70e52326c220b0b2ffaca7d4b` remain unchanged.
CI #32 exposed a real terminal-delivery race: doFinally released busy after downstream received
the reply. The new immediate-continuation test deterministically failed the old implementation
with SESSION_BUSY, and passed after the parent fix. Original logs remain at
`/home/ubuntu/outputs/agentscope-onboarding-v01/native-continuation-before.log` and
`/home/ubuntu/outputs/agentscope-onboarding-v01/native-continuation-after.log`.
Pre-clean raw records and Surefire reports were preserved under that output root as
`native-continuation-raw-before-requalification-96dec41` and
`native-continuation-surefire-before-requalification-96dec41`.

`agentscope-fixed-final-3` is a retained FAILED qualification-generation attempt at `96dec41`,
not a qualified LIVE bundle. All 31 tests passed, but the added continuation test used the
multi-turn tag, producing four proof files where the existing loader requires exactly three.
Public load rejected QUALIFICATION_REPETITIONS_MISSING before either packaged JVM check.
See its `GENERATION-FAILED.md`, logs and unchanged raw/proof files. No failing SDK test was
rerun to obtain a green result. The parent resolved the evidence taxonomy at `47e3f6e` by giving
continuation its own required check; final-4 retains all 31 tests without dropping evidence or
loosening the repeated-scenario requirement.

## Live Gate Ownership

Real managed model evaluation is NOT RUN on final-4 by this workstream. Parent-run three-case
Chinese smoke and the original 17-run native matrix belong to final-2. The original matrix
reported 14 strict automated passes and three missing-data tool-count failures; its evidence at
`/home/ubuntu/outputs/agentscope-onboarding-v01/agentscope-live-final` remains unchanged.
The parent updated evaluation to distinguish exact capability coverage and per-attempt
parameter/result validity from model-selected repeated calls after retryable source failures.
Those new native calls are not SDK transport retries; repeat counts remain evidence.
Missing-data answers retained the summary and disclosed unavailable classification, as required
by the original design, but the observed initial tools overlapped. Capability coverage alone
does not prove the SOP's summary-before-analysis execution order.

Use the same isolated CLI and parent-owned `isolatedHost/live-driver.mjs` for new-bundle LIVE
evaluation; runtime completion is explicitly not an evaluation pass. The parent owns the
same-model rerun/comparison, answer quality, ordering adjudication and PR. Historical LIVE
results must not be transferred to the new JAR/qualification. Third-batch Host archive/body-store
qualification, catalog publication and deployment remain outside this candidate.
