# Shared TASK integration: progress, not acceptance

2026-09-14. Stage 3 is in progress. Stage 4 has not run. No deployment.

`HostTaskState` is the first extraction from the isolated pilot's owner handle
index and confirmation nonce handling. It delegates financial checkpoints to
the existing `FileTaskCheckpointStore`, and passes trusted approval events to
the existing Installer contract. It contains no transfer or preference rules.
The private JSON mapper is copied before enabling precise decimal decoding;
the global Harness mapper is unchanged.

Five focused JUnit4 tests passed in an isolated copy of the previously compiled
candidate with these two source files overlaid. This is compilation/unit evidence,
not a newly locked Agent or a real bank recovery test. The existing candidate's
Lock was deliberately not rewritten; this directory must never be deployed.
Evidence: `/home/ubuntu/outputs/capability-extension-v01/shared-task-state-probe`.

Covered: high-precision checkpoint readback and owner index separation; consumed
approval after callback failure and store reopening resumes rather than reapproves;
expired/changed binding; revoked authority; decline and missing nonce. The backend
is controlled, not the actual banking Provider. The first test run correctly
rejected a malformed synthetic binding fingerprint; the fixture was corrected to
the real 64-hex contract and the helper now checks that shape before persisting.

## Shared Host wiring increment

The shared Java Host now calls `HostTaskState` through its existing Assembler /
Installer installation. Generic task list, challenge, confirmation, decline,
resume and source step-up endpoints are present. Workbench forwards these controls
with owner, instance-generation and session checks; it accepts neither model
confirmation text nor caller-supplied identity, scopes or revised parameters.
No capability IDs or bank business transitions were added to these routes.

`HostTaskScope` acquires an exclusive private-directory OS file lock. A separate
JVM test verifies competing acquisition fails and acquisition works after killing
that test child. The stable scope binds trusted owner/build and verified source
principal/customer. This is process-lock evidence, not bank crash recovery.

The source simulator requires original session task focus for submission
(`BankingFacade.submitTask` / `TaskRepository.requireWriteOwner`). Consequently the
Host keeps the original simulator session in a 0600 file outside the artifact and
revalidates it against current policy and source introspection on reopening.
Clean Host shutdown retains this session for recovery; explicit revocation still
revokes it. Expired/revoked source sessions are blocked, not silently replaced.
Restoring a pending write after source-session expiry is NOT yet supported.

Focused Java verification: 11 tests passed (5 state, 3 scope, 3 identity HTTP).
Identity HTTP tests use an isolated local fixture, not real bank IAM. Builder
Python: 862 passed, 1 skipped; 58 targeted route/generation/API cases passed.
`shared-host-query-regression-03` passed actual page generation, native start,
two controlled-model turns, source fact checks, revocation and stop. This was a
dirty development snapshot before the final testability constructor addition,
not fixed-commit acceptance. Earlier failures remain: `-01` enum-type compile
error; `-02` template digest changed during build, correctly rejected by admission.

## Not enabled or verified yet

- Workbench provisioning of the private TASK scope and explicit simulator TASK
  policy; original chat confirmation/step-up/status components.
- Lifecycle metadata/compatibility for QUERY and TASK combinations. Current
  templates still declare/query-install only QUERY; Python TASK remains rejected.
- Actual transfer and channel-preference result persistence, crash after bank
  submit, negative confirmations, observation failures and original-task lookup.
- Real model/browser and independent-human acceptance.

The runtime integration is present but the trusted template still advertises
QUERY only. TASK selection is deliberately not enabled. Bank commit/response-loss
and nonce-consumed/pre-Installer crash tests, the second business, original chat
UI and real-model checks remain release gates. No deployment occurred.
