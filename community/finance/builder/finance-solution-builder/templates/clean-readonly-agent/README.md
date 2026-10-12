# Generated Controlled Wealth Agent

This candidate uses the stock OpenJiuwen Java SDK and installed Harness JARs.
No mobile-banking fork, Task-centric engine, bank business code or Harness Java source is copied.
`finance-example-wealth-facts-kit` is the explicitly selected controlled host:
synthetic identity/grants, loopback HTTP fixtures and a scripted model, not a live host adapter.
The native AgentLoop, tool installation, authorization checks, mapping and evidence are real code.

Build with `mvn -B -ntp package`. Run with JDK 21:

```bash
java -cp 'target/classes:target/dependency/*' io.github.bitlhk.finance.builder.candidate.ReadOnlyCandidate assets target/runs
python verification/verify_wealth_kit.py --root assets --runs target/runs --matrix customer-readonly
```

On Windows use `;` instead of `:` in the Java classpath. Python requires the copied
`verification/requirements-conformance.txt` dependencies. Use the Builder command for recorded
build/run/verification and dependency hashes. Keep the separate JARs; an unverified shaded JAR
must not be treated as the observed original Runtime artifact.
The template imports Harness's Jackson BOM 2.20.1 explicitly; the SDK's transitive Jackson
version is not a substitute. Console-only WARN logging avoids the SDK's default disk appenders.

After build, runtime uses only this directory plus JDK/Python, with no Builder/Harness checkout
required. The ten cases include same-customer controlled bank/snapshot substitution, denied and
expired grants, wrong subject, model customer spoofing, missing risk/semantic metadata, zero and bad responses.
This is not a full banking UI, real model evaluation, real bank authorization or a transfer service.
Live operation cannot be enabled with an environment switch. Production use requires a separately
reviewed trusted host adapter; never reuse the fixture grants as authorization.
