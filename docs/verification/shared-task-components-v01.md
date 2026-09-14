# Shared TASK component candidate V0.1

## Scope

The `internal-transfer` and `channel-preferences` packs declare independent TASK
capabilities. Both use the existing non-ai-banking Provider implementation and
the same Java Host, Installer, trusted confirmation and checkpoint mechanisms.
Neither pack adds a transaction engine or a dedicated application page.

`local-simulator-task` is an explicit synthetic environment profile, not a grant.
The Host must receive a separate operator-provisioned policy, verify the actual
source identity, and obtain source step-up before accepting confirmation.
Java supports this candidate; Python TASK installation remains rejected.

## Extension boundary

Adding the second business required its pack, assembly declaration, catalog
entry and Provider support declaration. Its existing Provider maps bank channel
preferences and the bank owns preference versioning, preflight and final state.
No channel-specific branch was added to the Builder or shared chat UI.

The checkpoint SPI permits a Host to throw `NewTaskRejected` before persisting a
new task. Installer returns `TASK_PENDING_USE_ORIGINAL` without calling the
Provider. This is an explicit admission decision, not a generic disk-error catch.
Other persistence failures still fail closed.

## Controlled evidence

`shared-tasks-browser-06` generated an actual Agent, used the original chat UI,
and called a separate persistent simulator through an observing proxy. All 29
checks passed. Evidence is in the maintainer delivery directory
`outputs/capability-extension-v01/shared-tasks-browser-06`.

- Transfer debited 0.01 CNY with one receipt and one new ledger entry.
- The same Agent changed large-text preferences and incremented the bank version.
- Forged confirmation and duplicate terminal resume produced no submission.
- A malformed response after bank submission retained an uncertain original task.
- Temporary query failure retained that task; restart recovered without resubmission.
- Killing the isolated Host after bank commit but before response delivery recovered
  the original result, with exactly one debit and no repeated submit.
- New task admission while another task was pending was blocked before bank create.
- Decline did not submit; revoked grants blocked access without model or mutation calls.

The generated Host also ran seven checkpoint tests, including separate-process
termination after nonce consumption but before Installer invocation. That probe
proves nonce recovery behavior, not a bank transaction at that exact cut.

## Limits and release gates

This report records development-snapshot controlled evidence, not a released
immutable artifact. Real-model and fixed-release acceptance are recorded separately.
Human independent onboarding has not been performed.

Decline means refusing Host authorization, not cancelling a bank task. No bank
cancellation capability is claimed. The simulator may retain its task focus;
operators must resolve that source state before another task uses the same focus.
Source session expiry/revocation blocks automatic recovery; the Host does not
silently replace the source identity or re-create an uncertain bank task.

Existing Agents keep their original Lock and code. New declarations do not upgrade
them. Retention of private TASK records is separate from artifact cleanup.
