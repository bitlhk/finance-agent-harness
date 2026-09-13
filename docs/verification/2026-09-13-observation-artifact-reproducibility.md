# Observation artifact reproducibility

Shanghai admission correctly rejected a newly page-built Agent: a root-built
qualification did not describe its service-user-built Integration JAR. Repeating
qualification as the service user exposed a second difference: identical JAR
entries still had different archive bytes. Archive metadata must not invalidate
an otherwise identical build, and admission must not ignore differing artifacts.

The parent now pins maven-jar-plugin 3.4.2 and an archive output timestamp.
This timestamp is packaging metadata, not a business, build or execution time.
Real timestamps remain in build records and execution evidence. No qualification
identity, checksum comparison, authorization or runtime code was weakened.

## Regression

```sh
python engineering/verify_reproducible_jars.py --report /tmp/harness-repro/report.json
mvn -B -ntp verify
python engineering/verify_contracts.py
```

The first command performs two clean builds with different build usernames and
wall-clock times. Use a report directory outside the repository's target tree.
It deliberately skips tests during packaging; the following verify runs tests.
CI runs this gate before other checks so clean does not erase their evidence.

Local JDK 21 / Maven results on 2026-09-13:
- Before fix: all 13 JARs differ; the regression fails.
- After fix: all 13 JARs byte-identical.
- Reactor verify: 243 tests, 236 passed, 7 explicit live Smoke skips, no failures/errors.
- Contract validation: 17 schemas, 92 fixture/asset/state probes plus shared
  display/observation/collector cases pass.

This proves reproducibility under the tested JDK/Maven environment, not across
arbitrary compiler versions. Shanghai must rebuild, qualify, and test newly
page-generated artifacts again. Earlier qualification files cannot be reused:
the changed JAR bytes and source revision require new identities. The prior
release remains rolled back until those deployment gates succeed.
