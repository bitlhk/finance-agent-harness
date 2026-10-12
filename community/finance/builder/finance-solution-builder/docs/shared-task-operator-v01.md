# Shared TASK Host: operator and extension guide

This candidate extends the existing managed Java entry and original Agent chat.
It does not use a transfer-specific application. Select compatible capability
packs through the managed asset directory; installation resolves their declared
requirements against trusted Host support. Python remains QUERY only.

## AgentScope Java candidate

AgentScope Java is fixed to 2.0.3. Its managed TASK Host reuses the shared
`finance-harness-execution` coordinator, the original Provider and the same chat
confirmation controls. The current candidate admits the original internal
transfer package with supported query packages; this is not blanket admission
of every TASK package or a production-funds acceptance.

The QUERY Host retains `LOCAL_READONLY_DESCRIPTION`; the TASK Host uses
`LOCAL_SIMULATOR_TASK`. The generator must materialize the selected, digest-locked
Host support file. A query-only Host or query-only native qualification cannot
admit TASK. Equal or incomparable matching Host declarations remain ambiguous;
the creation page may choose a unique strictly narrower supported operation set.

New Java artifacts use `FileTaskJournal` in a separate `checkpoints-v01` directory.
Existing artifacts and legacy checkpoint files are not upgraded. AgentScope
installation/turn evidence is additionally scoped by process instance, so a
persistent task session can restart without replacing its previous immutable
Lock. Request history continues to preserve its own installation references.

Native qualification must be produced using the Harness isolated probe's
`--tasks` mode, then the actual generated Host must pass its observation checks.
Configure the resulting native and Host qualification files and digests through
the existing managed admission environment. Keep REQUIRED history and snapshot
requirements; explicitly permit the actual owner to capture/view synthetic test
bodies. A wildcard is not an owner grant. Requirements must be versioned assets
inside the Harness workspace, not arbitrary external files.

The controlled browser command below accepts `--runtime agentscope-java-2.0.3`.
It uses the admitted balance/transfer combination, while the default OpenJiuwen
path also exercises channel preferences. Do not treat controlled-model results
as live-model quality or independent human acceptance. Deployment remains gated
on the release-specific qualification, restart/fault checks and public acceptance.

## Explicit server configuration

TASK startup requires `FH_TASK_SIMULATOR_ENABLED=true` and
`FH_TASK_ACCESS_POLICY` pointing to an independently provisioned synthetic grant
policy. Required scopes in a pack never create grants. The workbench derives
the private recovery owner from its authenticated owner, not browser input.

For `engineering/start_local_stack.py`, use both `--persistent-simulator` and
`--task-policy /operator/owned/policy.json`. Without those explicit arguments the
launcher clears inherited TASK enablement. Existing read-only defaults remain.
The policy must reference the selected loopback bank URL. Do not put policy,
source sessions or credentials in downloadable Agent artifacts.

## Actual lifecycle

1. Build a new managed Java Agent, then start and open its original chat.
2. Request an installed task. The bank performs its existing preflight/create;
   the model cannot approve it. A structured pending task appears below messages.
3. Use the confirmation control. If requested, complete synthetic bank step-up
   with its configured test verification code. Reopen confirmation afterwards.
4. Confirm the frozen parameters, or decline that authorization. Completion is
   displayed from Installer/Provider state, never inferred from model prose.
5. Query an uncertain original task with its existing handle. Stop/restart keeps
   private checkpoint and source-session binding, and checks current grants again.

`TASK_PENDING_USE_ORIGINAL` blocks a second task before bank create. Decline is
not bank cancellation; the source may retain focus and needs source-side handling.
Do not claim a cancelled transfer merely because the local authorization was declined.
Expired/revoked source sessions require operator resolution, not automatic login
and replacement of an uncertain task. A stopped Agent can be deleted when its
private task scope contains only session bindings and empty task directories; the
scope is then removed with the Agent. Any saved task handle, confirmation, or
checkpoint keeps deletion blocked (`TASK_RECOVERY_STATE_RETAINED`) until the
original bank task is checked and its retention is resolved. An occupied scope
lock is also blocked (`TASK_STATE_IN_USE`). Do not manually remove a task scope
to bypass either check.

## Reproduce controlled acceptance

Use Python with project/test dependencies, Requests and Playwright, Node UI
build dependencies, JDK 21 and Maven. Build UI with `npm run build:ui` first.

```sh
PYTHONPATH=src:../base/src python engineering/verify_shared_task_workbench.py \
  --harness /path/to/finance-agent-harness \
  --output /new/private/evidence-directory \
  --bank-jar /path/to/non-ai-banking.jar \
  --maven /path/to/mvn --chromium /path/to/chromium
```

The script creates and cleans its own simulator, workbench and Agent processes.
It deliberately terminates only its generated isolated Host during the crash
test. Never point the fault injector at existing service instances.

Controlled results `shared-tasks-browser-06`: 29 assertions passed, including two
different business outcomes, malformed response, query failure, real Host process
termination, original-task recovery, duplicate confirmation, decline and revocation.
Builder regression before the final lifecycle refinement: 862 passed, 1 skipped.
These do not substitute for fixed-release, real-model or independent human checks.

## Customer extension

Keep business fields and bank protocol in Capability/Provider assets. The shared
Host and UI consume TASK metadata and structured handles/challenges/results.
Do not add capability-name branches, grant switches or executable pack UI code.
Use the declaration schema and compatibility checks before adding a component.
New packages must provide their own behavior and negative cases; passing the old
suite alone does not prove the new Provider's business semantics.
