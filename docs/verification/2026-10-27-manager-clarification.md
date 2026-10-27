# Manager Clarification: Partial Candidate

## Status

Local candidate only. No merge, deployment, service restart, online model retry,
Provider invocation, role change, or history rewrite. This is not a completed
product-selection workflow or release qualification.

Base Harness: e1b5a01a6f09729c40228c55a06dc78bb7256363.
Builder unchanged: 95aca89218a1919391d642f1ef916f5cb4ecac25.

## Frozen Failure

Agent: agent-0bbc5ab197474046.
Instance: 90337283-b635-4b2f-af73-1dd23f334463.

Three archived model responses establish the sequence:

| Request | Time (UTC) | Observed classification | Host behavior |
| --- | --- | --- | --- |
| 02909eff-173e-4d96-8fd0-999f7fa2b363 | 01:03:47 | CLARIFY, object null | Did not record a typed pending product action |
| 461c6b27-fb76-4b54-b4dc-0ca3e26c70f2 | 01:04:03 | RESOLVE_OBJECT, named customer | No pending action; clarification path discarded the object hint |
| 6fa83619-dcf2-442c-b1f2-90fa4b4cd8b0 | 01:04:22 | CLARIFY, named customer | Parser rejected objectRef on CLARIFY |

The last request contains the previous three turns. Context transport was not
absent. The missing piece was typed task state, followed by an overly narrow
clarification shape. The typo itself is not proven to be the cause.

The final response snapshot SHA-256 is
2501f8ae8268c137b709cb4533b38a0f2ff1faa14860366f25ee5b747b52ca14.
The archived final request records one model scope and no Provider/business
span. This is archive-level evidence, not network-level zero-call proof.

Small original request/response snapshots and the sealed final execution log
are retained in /home/ubuntu/outputs/manager-clarification-20261027/first-failure.
No credential files or full build caches were copied.

## Candidate Changes

- A CLARIFY proposal may carry a uniquely resolved, server-known object hint,
  but never report/version references or executable arguments. Unknown objects,
  ambiguity, extra fields, malformed JSON and uninstalled actions still fail.
- An orphan RESOLVE_OBJECT becomes non-executable clarification. It retains the
  object only after live authority checks, without inferring an executable goal
  from previous assistant wording.
- Clarification retains an existing typed pending action. Cancellation, new
  conversation, expiry and authority binding checks remain in force.
- The intent instruction separates a known business goal from missing execution
  inputs. Missing amount/term/channel must not alone erase PRODUCT_REVIEW.
  Whole-directory product execution is not promised.

## Verification

70 tests, 0 failures/errors/skips in the final targeted Maven package run:
ConversationHostTest (45), ConversationIntentTest (7),
ConversationDialogueTest (5), ConversationConfirmationTest (6),
ConversationCapabilitiesTest (3), OpenJiuwenIntentProposerTest (4).

Actual Java managed session and SDK use a loopback HTTP fixture with frozen or
deterministic model responses. No paid model or bank endpoint is contacted.
The three frozen classifications now reach non-executable clarification without
losing the known object or creating a report/confirmation. This does NOT prove
that the revised instruction makes the real model classify the first turn
correctly. A separate fixed PRODUCT_REVIEW response verifies pending-goal reuse.

First test run: 56 tests, one failing new ambiguity fixture. Its second alias
was not actually ambiguous under the existing display-name resolver. Corrected
the fixture, not the resolver; original log retained as targeted-first.log.
Subsequent 70-test runs are repeats, not additive coverage.

Final log: /home/ubuntu/outputs/manager-clarification-20261027/package-final.log.
Packaged integration JAR SHA-256:
ed446c5d12945d01da1e610b94d9e5fcfb61d78db86af7987d2a2161d06fc33d.

## Remaining Blockers

1. Product argument continuation is not closed. Existing converse() clears the
   legacy pending-product arguments on new messages and confirmation. Split
   amount/term/channel input needs a bound, expiring pending request and an exact
   final confirmation; this candidate does not claim to fix that path.
2. No live-model semantic acceptance has been run. Fixed responses validate Host
   behavior, not model interpretation quality.
3. Manager integration content changed. Existing generated-Host qualification
   cannot be reused blindly. Final generation-input impact mapping, affected
   qualification and actual-service-identity installation remain NOT_RUN.
4. Existing Agents are pinned. This local JAR is not installed into their Locks;
   no claim is made that the running Agent is repaired.

Do not publish this partial candidate as a completed product-selection fix.
