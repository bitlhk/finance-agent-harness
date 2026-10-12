# Current-Turn Dialogue Scope V0.1

## Observed Failure

An earlier synthetic demonstration session returned an
account-ID question for a balance request, then an unsolicited wealth overview
for a greeting. Native evidence records zero tools on the first turn and
`wealth_facts_describe` on the second. This is an actual tool-selection problem,
not a Markdown rendering or AG-UI message-display mismatch. Original model
request bodies were not persisted, so this evidence alone does not reconstruct
the original model's entire context.

The balance Pack exposes an optional `accountId`. The bank Provider supports
listing the authorized customer's accounts when it is absent. Requiring it for
every balance request was an invented prerequisite. Wealth method instructions
describe calling the wealth tool, but the old host did not sufficiently scope
those instructions to the current user task.

## Implementation And Boundary

The generated `NativeConversation` adds one host conversation-policy section:

- Interpret the current user request; use history only when relevant.
- Installed methods describe how to perform a requested task, not standing
  instructions to query customer data on every turn.
- Greetings, thanks, small talk, cancellation and capability questions do not
  request customer facts. Explicit financial requests accompanying a greeting
  still run the required task.
- An unspecified balance uses the installed balance tool with its optional
  selector omitted. A wealth overview is not a substitute for account balance.
- Keep existing authorization checks, model-call veto, session isolation and
  read-only installation unchanged.

This is application conversation policy, not a second intent engine, scripted
greeting response, SDK change, or deterministic semantic safety guarantee.
Financial permission checks remain executable mechanisms outside the model.
The immutable Agent JAR must be regenerated; updating Studio's template does
not change already-created Agents or clear their sessions.

## Reproduction

Generate the existing combined read-only recipe with the updated Builder, then:

```bash
.venv/bin/python engineering/verify_local_service.py <candidate>
.venv/bin/python engineering/verify_dialogue_scope.py \
  --candidate <candidate> --output <new-report-directory>
```

The second command explicitly requires `FH_MODEL_URL`, `FH_MODEL_NAME` and
`FH_MODEL_KEY` from a private server-side environment. It uses the real configured
model with synthetic bank HTTP data, not the controlled-model responder.
Its five isolated sessions cover fourteen turns: balance then greeting, thanks,
refresh, greeting with a financial request, wealth then greeting then balance,
capability explanation, and cancellation. It asserts actual tool calls and
business read counts. Reports contain synthetic questions/results, never keys.

The controlled verifier also inspects the actual model HTTP payload for the
latest user message, one non-duplicated host policy, the optional account filter,
retained multi-turn history and customer isolation. Scripted-model checks do not
prove live-model understanding.

For an explicitly authorized deployed synthetic environment:

```bash
.venv/bin/python engineering/verify_public_demo.py \
  --access-file <private-access-json> --output <new-report-directory> \
  --dialogue-regression
```

This creates a new combined Agent, runs balance/greeting/wealth/thanks using
the actual configured model, checks native tool evidence, revokes its own test
session and stops its own new instance. It does not modify old Agent artifacts.

## Local Verification

Implementation baseline: Builder `504cc7a`; Harness unchanged at `b55d072`.

- Builder Python: 193 passed.
- Newly generated combined candidate: Maven build passed.
- Controlled native service: 35 checks passed, including model-wire checks.
- Actual `deepseek-v4-flash`: 14/14 dialogue cases passed; zero business writes.
  Evidence: `build/dialogue-scope-live-01/report.json` and per-turn files.
- Original balance/greeting sequence passed twice in separate sessions.
- Native AG-UI: 8 checks passed on the new compiled candidate, including delayed
  bank response after revocation: no subsequent model HTTP request and no
  financial text/evidence event.

These are targeted regression results, not a universal guarantee for all models
or tasks. Real bank IAM, production customer data and human independent onboarding
are not part of these tests. Shanghai publication and full-simulator checks are
recorded separately after execution. Builder upstream acceptance remains pending.

## Deployment Handoff

Site-specific instance IDs, topology and operational evidence are retained in the
original private development records, not included in this upstream handoff.
Old Agents retain their old behavior until explicitly regenerated. Repeat the
model and simulator checks for newly built artifacts; do not treat the historical
local results above as results for a maintainer's new environment.
