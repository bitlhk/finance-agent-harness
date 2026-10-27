# Conversation State Closeout (Candidate Only)

## Scope and Diagnosis

Base: Harness e1b5a01a6f09729c40228c55a06dc78bb7256363 and Builder
95aca89218a1919391d642f1ef916f5cb4ecac25. Includes checkpoint 31ddaeb.
No deployment, service restart, live model/business call, permission change,
Agent deletion, or saved report rewrite was performed.

This is a scenario-orchestration defect, not an assistant-ui rendering defect
or something every Skill author should repair. The archived failed request
contains previous turns. Text history was present but the pending goal, object,
inputs and execution approval had inconsistent lifecycles. Missing inputs were
also treated as missing intent. Standard runtime/UI components do not supply
the business-specific state contract automatically.

The bounded audit covered manager proposal/confirmation/dispatch, shared Java
and Python task interpretation, audit/risk adapters, native banking context,
and reference UI binding/cancellation/history state. It is not an assertion
that every repository module or arbitrary future scenario has been verified.

## Findings and Repairs

| Finding | Repair / boundary |
| --- | --- |
| Known product goal lost across customer/amount/term/channel turns | Immutable pending task, fixed five-minute expiry, existing owner/instance/Lock/epoch/scope binding |
| Confirmation could lose parameters and reparse stale combined text | Freeze exact parameters in the one-shot plan; execution consumes that map |
| Customer replacement or failure could retain stale task state | Different customer drops parameters; failure/cancel/context change clears pending state and approval |
| Unchanged old UI selection could erase a newer conversational customer | Distinguish unchanged input from an actual selection change |
| Known object in CLARIFY caused invalid intent | Validate known hint separately from action; orphan object resolution only clarifies |
| Risk/audit clarification cleared an existing goal | Preserve typed pending request; project it to wording; ask for missing object, not intent |
| Java and Python private intent checks differed | Match opt-in continuity semantics; legacy banking context remains strict |
| Failed product retrieval described as completed with zero candidates | Missing productReview is incomplete execution, not an empty successful selection |
| Decision evidence omitted pending state | Add pending-task digest; keep required evidence and release checks |

Main Harness changes are in integrations/customer-manager-java (PendingConversationTask,
ConversationConfirmation, ConversationIntent, CustomerManagerScenarioExtension,
OpenJiuwenIntentProposer) and integrations/openjiuwen-java/conversation/TaskIntentProposer.
Builder changes are conversation_continuity.py, conversation_worker.py and
task_conversation.py; no reference UI or native banking template modification.

## Verification

Evidence root: /home/ubuntu/outputs/conversation-state-closeout-20261027.

| Check | Actual result | Evidence |
| --- | --- | --- |
| Targeted Java package run | 119 tests, zero failures/errors/skips | java-final.log; 12 Surefire suites |
| Python adapters/workers/history | 122 tests, zero failures | python-closeout.xml |
| Existing native banking context source contract | 1 passed, 31 deselected | bank-context.xml; source assertion, not a Host execution |
| Existing UI state/route/stream tests | 38 passed | node --test seven selected files; no browser/SSO claim |
| Same intent corpus through Python and actual packaged Java | 180 cases: 21 accepted, 159 rejected | parity-corpus.json, verify-packaged.py, packaged-probe.log |
| Packaged manager pending inputs | Accumulation, amount correction, object isolation and expiry passed | PackagedConversationProbe.java, packaged-probe.log |

Java Host tests exercise actual managed sessions and SDK loopback transport with
deterministic model/Provider fixtures, not paid inference. Product flow has no
business call before confirmation; exact confirmation causes one previsit and
one product fixture invocation, and replay is rejected. The product fixture
deliberately fails to test honest failure wording, not successful live screening.
Cross-language cases overlap unit scenarios and are not 180 extra user journeys.
Repeated test runs are not added to coverage totals.

First Python collection failed because the venv referenced an older Base package;
PYTHONPATH=src:../base/src selects this checkout without changing dependencies.
First executed run: 121 passed, one fixture-directory collision failed. The
fixture now permits its already-created directory; no production check changed.
Original executed failure is retained as python-first-executed.xml. Later
python-final.xml predates the last missingInputs correction; use python-closeout.xml.

Frozen production failure remains at
/home/ubuntu/outputs/manager-clarification-20261027/first-failure.
Final failed model response SHA-256 remains
2501f8ae8268c137b709cb4533b38a0f2ff1faa14860366f25ee5b747b52ca14.

## Release Impact and Limits

Read-only generationInputDigest comparison used the existing generator and the
85 stored snapshots, not 85 builds. generation-impact.json records every result.
G01/G02/G03/G04/G06 include the changed OpenJiuwen module; G05 additionally
includes the manager module. All groups include the Builder import closure;
G07 changes only through that closure. Its actual changed files are the three
Python conversation modules above, not builder.py itself. This gate is
conservative: a changed digest is not proof the G07 collector bytes changed,
but it does prohibit asserting unchanged qualification without verification.

Existing qualification is NOT claimed for this candidate. Final representative
candidate installation, affected qualification and actual service-identity
checks remain NOT_RUN. Reuse group equivalence only through the existing gate;
do not replace this with automatic 85-business-combination execution.

No public reference endpoint or Core permission contract changed. CONTINUE_TASK
is an additive private manager model-proposal kind, not an executable command.
Deploy matching H/B artifacts; existing Agents remain pinned to their old Locks.
No persistent state migration is needed. In-memory drafts expire or disappear on
restart; historical reports retain their existing authorization and versions.

Real-model classification of the revised instructions is not yet verified.
Frozen responses prove Host behavior, not interpretation of arbitrary wording.
Product parameter parsing still uses the existing limited explicit formats;
this is not an arbitrary-language or arbitrary-Skill universal planner.
Native banking has bounded untrusted conversation history rather than this
manager pending-task record; its model quality was not retested here.
Do not advertise the candidate as deployed or as proof that any uploaded Skill
automatically acquires a complete conversational adapter.
