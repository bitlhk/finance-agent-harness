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
exception terminal-state gap remains OPEN and needs a focused repair/test before
release; it is not dismissed as just a fixture issue.

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

1. Complete manager conversational slot/context handling and raw Provider exception
   terminal-state repair. V2 wiring is now present, but neither a production model
   quality test nor packaged Host/admission verification has run.
2. Explain a selected immutable report/version through its existing authorized
   historical reader without new Provider calls. Precisely report any model calls.
3. Make audit/fraud/postloan consume the same versioned conversation contract via
   thin adapters, preserving their distinct authorization and input policies.
4. Validate real packaged Host cancellation/late result/confirmation behavior,
   four-scene UI interaction and IME; give Dot an accessible controlled preview.
5. Freeze final trees/inputs; update changed qualifications, verify actual service
   identity and old-artifact compatibility; normal protected merge, authoritative
   drain, rollback, controlled deployment, then independent ordinary-SSO acceptance.

The goal remains active. Do not mark it complete from these 46 distinct tests or
from synthetic previews; neither production deployment nor SSO acceptance occurred.
