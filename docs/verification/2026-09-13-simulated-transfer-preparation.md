# Simulated transfer preparation (not a deployed transfer Agent)

Baseline main21afb5c. The current Builder read-only Host deliberately rejects
transfer scopes and uses an in-memory task store on each mount. It has no
authenticated confirmation endpoint. Adding a transfer checkbox is insufficient.
An isolated Java candidate must use the existing trusted confirmation/controller
and persistent Checkpoint mechanisms, with saved simulator beneficiaries only.
Python TASK and the stable read-only workbench stay disabled for transactions.

## First discovered Adapter gap

Config.hostSession accepts a revalidated bank session but the transfer Adapter
previously only remembered its own legacy step-up operation. Thus a Host whose
bank session was already I2/I3 still always received STEP_UP_AUTHENTICATION.
No model-controlled boolean or Context label should resolve this gap.

For the Host-session path the Adapter now reads /api/v1/sessions/current and
checks source customer, session ID, expiry and identityStrength. I2/I3 satisfy
this simulator's strong-authentication requirement. It does not cache that result
across operations; authentication is checked before confirm as well as submit.
The existing supplier must still recheck current Host authorization. The legacy
configured-login path remains compatible; it is not the new candidate's identity
source. Approval remains a separate trusted Host event bound to frozen parameters.

The old task message asking for chat confirmation is replaced with a Host
confirmation-interface instruction. This is not a new confirmation implementation.

## Isolation tests executed

First nine added cases fail against the old implementation; original fourteen
cases pass. After the fix, all24 transfer Provider cases pass, including one
additional downgrade-between-confirm-and-submit case. Checks count actual local
HTTP introspection/create/confirm/submit attempts. They cover I2/I3, I1/missing or
unknown strength, wrong customer/session/expired result, downgrade before confirm
and downgrade after confirm. No real bank or deployed simulator was called.

Full Maven verify:253 tests,246 passed,7 explicit live Smoke skips, zero failures
or errors. The temporary name-resolution compilation error during development
was fixed before this verification. These Provider tests directly supply the
SPI confirmation fixture; they do not establish trusted browser confirmation.

## Remaining before any transfer demonstration

- Isolated Java Host confirmation UI/API, independently authenticated source
  identity and transfer grant, current IAM step-up consumption.
- Saved-beneficiary selection; authoritative currency, exact money and frozen
  action/version binding; no new account or beneficiary creation.
- Persistent task/session ownership and recovery without inheriting stale grants.
- Full simulator fault tests for lost responses, UNKNOWN, duplicate confirmation,
  mismatched owner, parameter changes, expiry, interruption and restart.
- Separate candidate/Lock and review, then controlled deployment. Do not enable
  transactions in the stable read-only recipe or relax Python's TASK gate.

This change is a necessary boundary repair, not completion of those milestones.

## Native tool / Adapter integration follow-up

`TransferHostRecoveryTest` in the retail example now joins the native installed
tool, public Installation confirmation API, file store, BoundFinanceProvider and
real NonAiBankingProvider against an isolated HTTP bank. It does not bypass the
Installer by directly constructing an SPI confirmation in the Provider call.

Eleven cases pass: file-store reopen with identical confirmation binding,
terminal duplicate confirmation, four model-text variants without approval,
revoked grant after reopen, different host scope, changed amount/expired approval,
decline, malformed submit plus temporary observation failure, and malformed create
plus attempted parameter change. Actual HTTP create/confirm/submit/query/reconcile
counts are asserted. Reopening installations is an in-JVM recovery test, not a
multi-process crash test. Fixture approval events are still not browser/IAM proof.

Commands from the repository root:

```bash
mvn -q -pl examples/solutions/openjiuwen-retail-banking -am test \
  -Dtest=TransferHostRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -q verify
```

This run: 264 Java tests, 257 passed, 7 explicit live Smoke skips, zero failures
or errors. Initial test development had a checked-exception compile error and
an incomplete fixture tool schema; both were corrected, using the shipped retail
Capability schema before the passing run. These are not claimed as product
regressions reproduced on an old implementation.

No new transfer UI, Candidate, deployment or live bank acceptance is claimed by
this follow-up. The remaining list above continues to apply.

## Separate JVM follow-up (2026-09-14)

The feature branch incorporates main 7899fd5 without changing the read-only
release. Three additional cases launch fresh Java processes with the original
FileTaskCheckpointStore and the same isolated HTTP bank. Fourteen cases in
TransferHostRecoveryTest pass, including the existing eleven.

- A new process consumes the original bound approval; another new process
  receives the cached terminal result. Create/confirm/submit each occur once.
- A fresh process with a revoked fixture grant, or a different Host scope,
  performs no task query, confirm or submit.
- A malformed submit response followed by temporary observation failure retains
  the handle across fresh processes. A later process reconciles the task without
  another create, confirm or submit.

Each child has a bounded timeout and is reaped by the test. Approval timestamps,
event ID and binding are preserved, not renewed during recovery. These tests
prove sequential process replacement with an isolated HTTP fixture, not crash
atomicity during an in-flight write, concurrent processes, real bank IAM,
authenticated browser approval, or complete simulator ledger reconciliation.
The trusted Host/UI and full simulator acceptance remain outstanding.

## Full simulator confirmation dialect follow-up

The isolated Builder Host exposed a source protocol mismatch: the public trusted
approval uses an English Host event reference, while this simulator's legacy
confirm endpoint requires the Chinese confirmation token. The Provider now
translates the already verified positive approval to that source dialect, retaining
the Host event reference. It does not infer approval from model text or resume.

`nativeHostApprovalUsesTheBanksConfirmationDialect` failed before the change and
passes after it; it asserts the actual HTTP confirmation payload and one confirm
and submit. No Core confirmation contract changed. The isolated Builder candidate
also exercised the full synthetic bank ledger, duplicate confirmation and killing
its own Host after bank commit but before response delivery. Those cross-repository
results are development evidence, not this repository's production acceptance.

Full `mvn -q verify` on this integrated branch: 267 tests in 44 classes,
260 passed, 7 explicit live Smoke skips, zero failures/errors. Runtime is
OpenJiuwen Java 0.1.15. No deployed service was changed by these tests.
