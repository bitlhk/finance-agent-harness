# Natural-language delivery implementation progress

Status: IN_PROGRESS, NOT_RELEASE_READY. The active goal is implementation,
verification and controlled production release, not a design-only deliverable.
Existing financial authorization, historical access and deployment gates remain.

## Implemented in this increment

- `ConversationCapabilities`: derives manager executable actions from the admitted
  service Lock's capability IDs. Help and prepare/product execution use the same
  decision. Product execution requires both previsit and allocation capabilities;
  unknown capability IDs do not acquire actions. Installed is not authorized.
- `ConversationIntent`: strict, bounded, duplicate-key rejecting JSON parser for a
  tool-free model suggestion. Rejects unknown versions, extra authority/parameter
  fields, uninstalled actions, unauthorized objects and incomplete report/version
  references. Does not execute anything or accept model-provided confirmation.
- `ConversationConfirmation`: session-local, one-shot pending plan bound to owner,
  instance, Lock, epoch, original query and proposed object/action. Expires in five
  minutes; changed context, revoked object, removed action, invalid/repeated claim
  and cancellation invalidate it. Execution still requires its normal authorization.
- `OpenJiuwenIntentProposer`: uses the existing model-connection type and SDK with
  no tools. Only classified suggestions enter the parser. Separate SDK sessions
  prevent cross-turn model-history reuse. This class is not yet wired to public
  chat in the first increment. The following increment wires it through explicit
  `manager-conversation.v2`; legacy clients keep the old declared protocol.
  No live external model was called.

## V2 Host and UI wiring increment

The real managed Session now accepts v2 requests and advertises the supported
protocol list. A proposal invokes the tool-free classifier but makes no financial
Provider query. An explicit EXECUTE consumes the server-held original plan and
uses the existing ServicePreparation execution; CANCEL consumes the plan without
another model or business call. Input, object, owner, instance, Lock, epoch and
current authority remain checked. A trusted scope digest binds principal, mapping
revision, expiry, authentication level, resources and alias-to-resource mapping;
unchanged display names cannot hide a changed resource. Intervening legacy chat
also invalidates plans.

Builder checks the actual Host protocol before forwarding v2, performs its normal
return authorization before projecting confirmation, and permits no arbitrary
authority/action fields. The shared assistant-ui shows a confirmation card,
invalidates it on draft/object/history/task changes, and sends server-side cancel.
The offline preview simulates only fixed requests and is still not NLP evidence.

Final increment evidence in the same root:
- `v2-java-final-02.log`: 53 distinct tests, zero failures/skips. Includes six
  real managed-Session + SDK tests with synthetic authority and local model HTTP.
  These prove proposal/confirm/reject/cancel wiring, not successful bank data use.
- `v2-api-final-01.log`: 63 Python API/projection/transport regressions, passed.
- `v2-node-final-01.log`: 36 frontend contract/state tests, passed.
- `v2-browser-attempt-01/result.json`: six finite synthetic UI checks; eight
  screenshots for reply and confirmation at 1180/390/375/320; zero page errors.
  Cancellation, editing a pending draft and explicit confirmation were exercised.

First failure retained: `conversation-host-attempt-01.log` and
`conversation-host-first-failure-xml/`. The test initially threw a raw Provider
exception while expecting a normalized FAILED response. Existing ServicePreparation
rethrows this runtime exception and does not make that path terminal. The normalized
failure fixture now tests the documented CapabilityResult contract, but the raw
exception terminal-state gap was fixed in the following focused increment below;
it was not dismissed as just a fixture issue.

## Provider exception terminal-state increment

The shared ServicePreparation query boundary now distinguishes a raw source
exception from authorization, cancellation and necessary-evidence failures. It
retains FAILED (or PARTIAL if earlier facts exist), NOT_RUN explanation and safe
PREPARATION_SOURCE_CALL_FAILED without the exception body. Dispatch is honestly
INVOKED_SEND_UNOBSERVED, not a claimed HTTP count. The consumed plan cannot retry.
Reauthorization and mandatory decision writes still precede any result release.
Revocation discards the result; a prior CANCELLED snapshot is not overwritten.
If evidence storage fails after dispatch, the response is withheld and the existing
report store attempts to retain the honest failure. No successful persistence in a
failed medium is promised.

- `source-terminal-final-01.log` and `source-terminal-final-01-xml/`: 58 distinct
  tests, zero failures/skips, including 11 managed-Session/SDK tests. This replaces,
  not adds to, the preceding manager run's 53-test count.
- `source-terminal-shared-regression-01.log`: 49 distinct additional regressions:
  ScenarioEvidenceTest 6, EmployeeAgentPreparationBrokerTest 7,
  ServicePreparationTest 33, BrokeredServicePreparationTest 3. Raw XML retained
  separately under `source-terminal-shared-{core,employee,wealth}-xml/`.
- Intermediate attempt 01 failed a whole-JSON numeric-node representation equality
  assertion; canonical whole-content digest comparison fixes the test representation.
  Attempt 03 incorrectly expected discovery to throw for a revoked record; existing
  latest deliberately hides unauthorized records. The corrected test requires
  found=false and explicit read rejection, plus a durable stripped DISCARDED state.
  Both original logs/XML remain; no production failures or records were rewritten.

This changes the shared service-preparation JAR. Its actual consumers must be included
in generated-input/qualification impact analysis, not classified as a UI-only change.
All model responses in this increment came from loopback synthetic fixtures.

## Actual verification

Evidence root: `/home/ubuntu/outputs/reference-conversation-implementation-20261020`.

- `conversation-contract-regression-01.log`: 43 tests, zero failures/skips.
  Includes 29 existing manager tests and 14 new contract/confirmation/capability
  tests. Intermediate repeat executions must not be added to this count.
- `intent-contract-regression-01-xml/`: raw Surefire reports for that run.
- `intent-sdk-attempt-01.log` and `.xml`: three additional tests using the real
  OpenJiuwen SDK and a loopback HTTP/SSE synthetic fixture. Two separate turns
  cause two measured HTTP requests without tools or shared prior query; a forged
  object reply is rejected after one HTTP request; pre-cancellation sends zero.
  These are transport/integration assertions, not evidence that a real language
  model correctly interprets arbitrary user sentences.
- `intent-contract-attempt-01.log` and `intent-confirmation-attempt-01.log` preserve
  incremental runs; no test failure occurred in these increments.

No production configuration, privileges, service, Agent or report was modified.
No dependencies were added, and no shared Host template changed in this increment.
The customer-manager JAR changes, therefore its generated Collector identities
and qualifications require actual impact analysis before any release.

## Remaining ordered work

### Short-lived clarification follow-up

The manager v2 path now supplies one pending, not-executed request to the tool-free
classifier. RESOLVE_OBJECT is distinct from a new PREPARE/PRODUCT_REVIEW, so an
unrelated new goal of the same kind does not inherit old text. The original request
plus the actual user supplement are displayed in the immutable confirmation.
No business query happens from clarification alone. This is not general dialogue
memory or automatic multi-step planning.

Pending context expires after five minutes and is bound to the same trusted
owner/instance/Lock/scope/epoch. It is consumed on the next turn, and cleared by
legacy turns, object selection, cancellation, closure or changed trusted scope.
The browser rotates an untrusted conversation grouping on task.open. The server
uses changes only to invalidate context/confirmation, never as identity or authority.
A new dialogue cannot reuse an earlier plan.

Evidence at the root above:
- `clarification-final-01.log` and `clarification-final-01-xml/`: 62 manager tests
  passed, replacing the previous 58 count, including four new managed Session
  scenarios over the actual SDK and loopback synthetic model responses.
- `clarification-api-01.log`: 63 transport/API regression tests passed.
- `clarification-node-01.log`: 37 frontend tests passed, including actual client
  request generation and context rotation (mock HTTP, no ordinary SSO).
- Generated frontend bundle: 684233 bytes, 123 packages, 27 license notices,
  zero cloud-service bytes per existing build check. No dependency upgrade.

This proves the constrained protocol flow, not real-model interpretation quality.
At that checkpoint history explanation was not connected. The next increment below
connects selected-version follow-up on the active manager Host. No new package
qualification, production deployment or independent QA is claimed.

### Selected report explanation increment

V2 requests can carry one reportId/revision pair (not together with an execution
confirmation). The actual managed Session checks existing historical authorization
before intent classification. EXPLAIN_REPORT then reads that exact immutable version,
writes necessary MODEL_ENTRY evidence, invokes a tool-free SDK explainer, rereads and
checks the whole report digest/current historical permission, and writes required
EXPLANATION_RELEASE before returning. New-execution proposals in a report-bound turn
request a new conversation instead. No report revision or review state is changed.

The model receives a bounded display projection, not owner/authority metadata.
Failed modelSuggestion is excluded. Answer JSON and exact existing leaf references
are validated. Reference validation is NOT proof of factual/numeric correctness of
free-language paraphrase; the answer is labeled interpretation of saved evidence,
not newly verified facts or a replacement for the report's original validation.
Real-model interpretation quality is still untested. No arbitrary tools are supplied.

Counts explicitly distinguish explanationModelInvocations=1 from businessQueries=0;
model HTTP attempts remain NOT_OBSERVED outside the local fixture. Intent classification
is another model invocation, separately visible in the fixture's measured HTTP list.
If necessary evidence fails after the model, the exception stage is
EXPLANATION_RELEASE_AFTER_MODEL, not an assertion that no model call occurred.

Builder forwards the exact pair, validates the returned pair against the request,
and the UI rejects an answer bound to another visible report/version. Existing report
and its original validation/review state are retained. The current-report composer
sends this binding, rather than treating every follow-up as a new financial query.

Evidence:
- `history-answer-final-02.log`: 69 manager tests plus 36 shared preparation tests,
  all passed. The former supersedes 62, not a cumulative addition. Final XML is in
  `history-answer-final-02-xml/` and `history-answer-shared-02-xml/`.
- Actual managed Session + SDK/local synthetic HTTP covers selected-version answer,
  original report digest unchanged, zero additional business call, revocation during
  explanation, failed post-model evidence write, and missing version before model.
- `history-answer-api-01.log`: 64 API/transport regressions passed.
- `history-answer-node-01.log`: 38 UI/client/store tests passed; mismatched version
  or object cannot attach an explanation to another displayed report.
- UI build: 684846 bytes, same 123 packages/27 notices; no dependency upgrade.

Remaining: stopped-Host historical explanation is NOT connected yet; its existing
independent historical reading remains unchanged. Shared four-scene interpretation,
real model evaluation, packaged Host/qualification, actual service identity and
independent browser/SSO checks remain release blockers.

1. Complete manager conversational slot/context handling. V2 wiring and the source
   exception repair are present, but neither a production model
   quality test nor packaged Host/admission verification has run.
2. Extend selected-version explanation to the independent stopped-Host historical
   reader without new Provider calls; complete real-model quality and failure checks.
3. Make audit/fraud/postloan consume the same versioned conversation contract via
   thin adapters, preserving their distinct authorization and input policies.
4. Validate real packaged Host cancellation/late result/confirmation behavior,
   four-scene UI interaction and IME; give Dot an accessible controlled preview.
5. Freeze final trees/inputs; update changed qualifications, verify actual service
   identity and old-artifact compatibility; normal protected merge, authoritative
   drain, rollback, controlled deployment, then independent ordinary-SSO acceptance.

The goal remains active. Do not mark it complete from these bounded tests or
from synthetic previews; neither production deployment nor SSO acceptance occurred.

## Shared stopped-history checkpoint

This supersedes the stopped-Host gap above, but not the remaining release gates.
The shared OpenJiuwen SavedReportExplainer and one-shot ConversationWorker are now
packaged independently of a running Agent Host. Builder discovers the actual class
in compiled dependency JARs and binds its declaration and complete classpath to the
artifact Lock digest. Old packages without this declaration are explicitly rejected
for explanation; their existing historical reads are unchanged.

The authenticated `report.explain` command adapts manager CRM relationship checks,
fraud/postloan persisted object mapping, and audit owner/material/run/review checks.
It rechecks historical permission and immutable version digest before and after the
model. It writes required entry/release receipts, withholds on evidence failure, and
never changes an original report or promotes interpretation to verified facts.
Audit revision zero means original run evidence without saved review, not report v0.
The worker has no business tools and does not inherit bank/CRM environment variables.
This is application control, NOT an independent OS sandbox or atomic revocation.
The model worker can run after Agent stop; this consumes model resources and is
distinct from zero-model historical replay.

Current evidence under the same compact output root:
- `shared-history-final-01.log`: 141 Python checks passed, including four persisted
  history adapters, cross-owner/version/tamper denial, revocation during explanation,
  required audit failure, package binding and generation gate regression.
- `conversation-worker-packaged-02.log`: one actual packaged-JAR + actual SDK +
  loopback synthetic HTTP test passed; no target/classes on the worker classpath,
  exactly one observed fixture HTTP request, no tools, no business credentials
  inherited, original Lock unchanged. Not real service identity or production model.
- `shared-explainer-java-01.log`: 25 Java checks passed for shared extraction.
- `history-node-01.log`: 38 UI/client/store checks passed.
- `browser-history-02.log`: seven finite synthetic browser checks passed, including
  explicit confirmation and stopped-version question; 1180/390/375/320 screenshots.
  The fixture explicitly labels its explanation as fixed, not a real model answer.
- `history-service-01.log`: 18 passed, one failure. Existing owner drain rejects a
  nonexistent child as RuntimeError(NO_OWNED_PROCESS); old test expects ValueError.
  Neither owner_drain.py nor this test was changed here. No stop/restart was performed.

First failures retained: packaged test classpath extraction initially included an
empty Surefire classpath component; test preparation corrected. Generation gate test
collection initially lacked engineering on PYTHONPATH; corrected invocation.
Browser history test initially left the sidebar collapsed after mobile sizing;
test now opens the actual history control before selecting a record. No product
authorization, failure expectation or release gate was relaxed.

Still pending: audit/fraud/postloan NEW-task intent/clarification/confirmation through
the common contract, finite real-model quality evaluation, final generated-package
qualifications and actual service identity, independent experience review, release
and ordinary SSO acceptance. This checkpoint is not a deployable completion claim.

## Shared task proposal and confirmed-material checkpoint

Added `TaskIntentProposer` in the existing OpenJiuwen integration, through the same
locked one-shot worker. Its output is a strict `finance-task-intent.v1` proposal:
kind, installed action reference, authorized object reference; no owner/role/tool
arguments, permissions or generated business answer. Greeting, capability/object
questions, ambiguity, saved-report explanation and unsupported requests cannot
carry an execution action. Missing-object continuation requires server-held context.
This is a reusable primitive for the remaining three adapters, not an assertion
that their UI/API orchestration is already connected or that real-model intent
accuracy has been established.

Builder revalidates the proposal and adds bounded, single-use confirmation storage.
Plans bind owner, Agent, conversation context, artifact, instance, authority digest,
input digest and generation. Concurrent claims consume once; expiry, changed binding,
new proposal and cancellation invalidate the old proposal. These helpers still need
the actual trusted scenario capture/dispatch wiring before release.

Audit has an actual atomic input precondition at `_begin_run` under the existing
workspace lock. Existing empty-body runs remain compatible. An optional
`expectedInput` with generation/materialSetRef/materialSetDigest can only restrict
execution: changed input rejects BEFORE creating an attempt or invoking the runner;
it never replaces owner authorization. The confirmation endpoint must supply its
server-frozen precondition when wired. Risk dispatch still needs equivalent binding
between confirmed object mapping and the Host selection generation; do not claim
the new generic plan alone closes that race.

Evidence:
- `task-intent-java-01.log`, `task-intent-java-01-xml/`: 16 Java contract checks.
- `task-intent-python-01.log`: 30 proposal/worker/history regression checks.
- `task-intent-packaged-01.log`: 3 actual packaged SDK/loopback HTTP cases
  (saved explanation, allowed intent, rejected uninstalled intent) plus 8
  confirmation-store checks. Each SDK case observed exactly one local HTTP call;
  no target/classes, business tools or CRM environment passed to the child.
- `task-audit-binding-01.log`: 46 actual workspace/API regression checks, including
  changed materials, matching generation, malformed input and wrong owner.

No production model inference, business request, service mutation, qualification
replacement, push/merge or deployment occurred in this checkpoint.

## Audit/fraud/postloan routing checkpoint

The three adapters now consume `conversation.turn` and `conversation.confirm` on
the existing authenticated reference command route. They no longer send every new
message straight to `run.start` / `risk.run`. The common model context uses verified
artifact Lock capabilities, existing owner materials or current risk object mapping,
and server-held pending requests. Model proposals are strictly revalidated. Entry
and proposal release evidence are necessary; evidence failure yields no executable
plan. Plans contain the original request and trusted binding, not client commands.

Audit dispatch reuses `run_audit` and the atomic confirmed-material precondition.
Fraud/postloan Hosts advertise `risk-confirmation.v1` and accept paired optional
expectedMappingRevision/expectedSubjectRef on select. The synchronized selection
rejects a changed mapping before verification, while the existing verify lifecycle
continues checking authority and selection generation. Old two-field selections
remain supported for legacy explicit clients. Old Hosts lacking the new handshake
are refused for this new conversational dispatch, not silently downgraded.
These are Host changes affecting qualification, not an UI-only release.

UI uses the same input/messages/confirmation component for the three adapters;
editing text/object/materials invalidates visible proposals. Old direct task APIs
remain compatible; the model has no access to them. Historical explanations retain
the previous independent read policies. New-task risk conversation currently requires
an active, bound managed Host; stopped report reading/explanation does not.

Evidence:
- `task-adapters-01.log`: retained first failure, two audit tests rejected valid
  material because new adapter compared prefixed digest to the audit's bare SHA.
  Fixed by reusing the existing audit digest function, not relaxing integrity.
- `task-adapters-final-01.log`: 80 bounded API/workspace/history regression tests
  passed. Actual audit workspace; synthetic risk transport/model are explicitly
  substituted in adapter tests. Not a real packaged four-Host run.
- `risk-confirmation-java-01.log`: 14 fraud + 16 postloan task regressions passed,
  including expected mapping revision/resource mismatch before query and the existing
  source/authority/evidence behavior. These are Java task tests, not service admission.
- `task-adapters-node-01.log`: local UI/client/store checks.
- `task-browser-01/result.json`: audit/fraud/postloan actual shared UI with finite
  synthetic transport, greeting/no report, cancel/no report, explicit confirmation/
  one fixed report; 1180/390/375/320 screenshots. No production SSO or actual model
  understanding claim. Existing narrow-screen dense amount rows need independent
  readability review; document width checks alone do not prove every inner label.

Production bundle: 687137 bytes, existing 123 packages and 27 notices, cloudBytes=0.
No dependency upgrade. Remaining release gates are real packaged integrated paths,
real-model finite understanding/answer evaluation, changed group qualifications,
actual service identity, independent experience review, protected merge/deployment
and ordinary SSO acceptance. Do not infer them from this checkpoint.
