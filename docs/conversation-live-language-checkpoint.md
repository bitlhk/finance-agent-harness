# Natural-language evaluation checkpoint

This is a candidate checkpoint, not release or ordinary SSO acceptance.

## First real-model evaluation

Evidence root: `/home/ubuntu/outputs/reference-conversation-implementation-20261020`.
Frozen input: `live-language-cases.json`; raw results:
`live-language-real-01/cases/results.json`.
Baseline Harness `e55dfce3438dd40d2ffe2adbe25abbf00f663a0c`,
Builder `4bf5c9cf74934dcf0deee4602e8776014eaddb2f`.

The existing deepseek-v4-flash connection received 18 synthetic requests through
the actual candidate SDK classes: 16 intent cases and two saved-report questions.
The source service UID/GID was 64599 and mount namespace mnt:[4026532574].
This was not a production Host, ordinary SSO flow, or inherited production cgroup/
seccomp test. No business Provider was instantiated. SDK invocations were counted;
network retry/HTTP-attempt counts were not observed.

The probe's manager comparator incorrectly treated omitted null fields as different
from expected JSON null. The parsed manager contract had already passed its own
validation. Keep original results; assess null omission separately without rerunning
the model or changing expectations. All eight manager classifications matched after
that normalization. Seven of eight shared task classifications matched.

T01 genuinely returned CLARIFY rather than EXECUTE/AUDIT for a clear request to
check the frozen financial statement and contract. The prompt required clarification
for multiple actions but did not explain that multiple materials may belong to one
installed composite action. This is a supported prompt ambiguity, not proof of the
model's internal reasoning.

The correction counts actions by installed action reference, not number of materials
or checks. Different objects/actions remain ambiguous. Confirmation, permissions,
material completeness, and actual execution remain server decisions.

## Verification and limits

The targeted Maven run executed TaskIntentProposerTest (16 cases) and
TaskIntentProposerSdkTest (one actual SDK/local SSE test): 17 passed, zero skipped.
Command:

```sh
mvn -o -pl integrations/openjiuwen-java -am \
  -Dtest=TaskIntentProposerTest,TaskIntentProposerSdkTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

XML is retained in `task-intent-composite-01/`. Terminal stdout was not saved in
full; do not claim a complete stdout archive. The local SSE response is deterministic
and proves transport/prompt/validation wiring, not real-model classification quality.
Targeted real-model revalidation remains necessary.

This changes the shared integration JAR. Previous four-scene package qualification
does not qualify the new JAR. Recompute actual generation/collector impact and
qualify changed final packages before deployment; do not blindly rerun all 85.

Pure saved-history reading still requires zero business and model calls. An explicit
natural-language question about saved evidence is a separate model operation with
history permission checks and zero business queries. It must not be reported as
the old C05 zero-model replay case.
