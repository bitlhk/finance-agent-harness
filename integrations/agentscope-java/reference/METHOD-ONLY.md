# Method-only candidate admission

This is a candidate native installation path, not a claim that the Eval
Launcher or generated Host supports every uploaded Skill. It does not install
a placeholder bank capability.

## Contracts

- A method-only Domain Pack explicitly declares `capabilities: []`, contains
  at least one real Skill, and has no capability-pack dependencies.
- A method-only Solution explicitly declares empty capability/provider lists
  and bindings, with at least one method reference. Missing financial
  dependencies are still errors, not a reason to choose method-only mode.
- AgentScope uses a Host-owned `agentscope-method-support.v0.1` support document
  with `operation: METHOD_ONLY`. Provider and capability-description references
  are empty. Profile compatibility, model connection and version checks remain.
- `agentscope-method-qualification-evidence.v0.1` is a separate qualification
  scope. Its exact artifact inventory contains no Provider; it cannot authorize
  QUERY or TASK installation. Query/TASK qualification cannot silently substitute
  for it. The operator pins the qualification digest, never the uploaded Skill.
- Only installation, authorization and model observation are declared for this
  mode. No business-result evidence is claimed when no business capability ran.
- Host grants still bind principal, type, subject, purpose, session and expiry.
  They are rechecked at every existing model/response boundary. The shared
  `BoundFinanceProvider.checkContext` does not grant any capability or scope.
- Optional `read_case_file` remains a bounded Host workspace tool, separate from
  financial capabilities and Provider dependencies. Files are not injected into
  the initial model request, and workspace closure rejects subsequent reads.

## Reproduce native qualification

Use a fresh evidence/output directory for every run and the same Maven repository
through both steps. Native tests use loopback controlled models and synthetic
text only. They do not measure professional response quality.

```sh
mvn -pl integrations/agentscope-java -am \
  -Dtest=MethodOnlyNativeTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dagentscope.methodEvidenceDirectory=/absolute/fresh-native-evidence test
python engineering/qualify_agentscope_method.py \
  --evidence /absolute/fresh-native-evidence --output /absolute/fresh-proof
```

Then run a separate JVM using the digest printed by the packaging command:

```sh
mvn -pl integrations/agentscope-java -am \
  -Dtest=MethodOnlyNativeTest#operatorPinnedEvidenceAdmitsFreshNativeSession \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dagentscope.methodQualificationFile=/absolute/fresh-proof/qualification.json \
  -Dagentscope.methodQualificationDigest=sha256:ACTUAL_DIGEST \
  -Dagentscope.methodEvidenceDirectory=/absolute/fresh-admission-evidence test
```

The second step loads the raw evidence and current artifact hashes, installs the
real method, invokes the SDK and verifies one fresh model request. Rebuilding or
repackaging runtime artifacts invalidates these hashes; a generated Host needs
its own matching native and Host-level qualification. Do not use directory-class
test evidence as proof for a differently packaged production JAR.

## Remaining release gates

The shared `observation-packs/readonly/method-only.json` declares the three
required common boundaries without claiming a Provider result. Builder may
project this explicit method-only declaration; it must not weaken a QUERY
declaration by silently dropping unmet requirements.

`isolatedHost/MethodOnlyTestWorker.java` can run the same native JUnit tests with
the generated Host's dependency JARs on the classpath, rather than Maven's
class directories. Compile the worker with JUnit Platform 1.14.1 / Jupiter
5.14.1 test dependencies and the existing test classes; no test fixture is
included in a product JAR. Run the worker without arguments, package its fresh
evidence with `engineering/qualify_agentscope_method.py`, then run it in a new
JVM with `fresh-admission` and the two pinned qualification properties above.
This is still native qualification, not the independent generated Host archive
and snapshot qualification.

Builder/Launcher method-only selection, generated-host observation admission,
curated Skill cohort, actual model comparison, authenticated browser flows and
resource cleanup must pass before switching the formal Eval entry. This candidate
does not change old Agent installations or deploy any runtime.
