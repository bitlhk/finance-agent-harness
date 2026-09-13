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
