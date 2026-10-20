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
Targeted real-model revalidation used the actual newly packaged shared integration
JAR (SHA256 `9867768ae29b86c3257476b2b9593eb6ed54de55a26740bcadd6bdd29b46f82d`)
with the unchanged verified dependency set. Results are in
`live-language-composite-real-01/cases/results.json`. Five SDK invocations were made
once each. Four expected classifications passed: the original audit request,
negated audit, saved-result explanation, and unsupported transfer.

The fifth case (two named clients) returned EXECUTE/VERIFY with null object instead
of the frozen expected CLARIFY. Keep it as a classification failure, not a PASS.
The test's names were "合成客户甲/乙", whereas its authorized object aliases were
"合成对象甲/乙"; do not silently change the input or normalize these identities.
Builder TaskConversation.turn refuses to issue a risk execution confirmation when
objectRef is null and returns a request for an authorized object instead. It does not
fall back to selectedObject. Thus this result does not show unauthorized execution,
but the intent-level expectation remains unmet. No business call was made by the
probe. Actual HTTP retry attempts remain unobserved.

This evaluation used the real service UID/mount namespace but no business Host.
It is not final package admission, independent experience acceptance, or a live
business test. No online service, permissions, or policies were changed.

This changes the shared integration JAR. Previous four-scene package qualification
does not qualify the new JAR. Recompute actual generation/collector impact and
qualify changed final packages before deployment; do not blindly rerun all 85.

Pure saved-history reading still requires zero business and model calls. An explicit
natural-language question about saved evidence is a separate model operation with
history permission checks and zero business queries. It must not be reported as
the old C05 zero-model replay case.
