# Service preparation integration checkpoint

Date: 2026-10-09, Asia/Shanghai.
Status: **NOT RELEASED. Builder managed startup and browser acceptance remain open.**

## Verified live identity and source

- The specified Linggan account has one matching login identity. Its existing EA
  channel link resolves to the existing active wealth-manager adoption.
- Builder derives its private Owner from the authenticated proxy user with its
  existing HMAC scheme. The proxy user string is not the internal Owner.
- No role was created and no customer permissions were granted. The EA resolver
  can record a snapshot of existing rights; that is not a new authorization.
- The governed customer directory returned READY, total 14 synthetic references.
  The isolated query allowlist was narrowed to one of those existing references.
- The original EA platform-tool route accepted the previsit query and returned
  a source receipt. No product tool or CRM write was requested by the probe.
- EA signing credentials remain within EA. The Java subprocess environment is
  explicitly allowlisted; Java refuses inherited EA signing-secret or DB variables.

No customer facts, channel identifiers, tokens or signing keys are included here.

## Live runs (all retained)

| Request / report | Observation |
| --- | --- |
| `harness-probe-efe0282f53cf2aef` | Broker protocol 200, one Provider dispatch, source receipt |
| `harness-probe-99f650440e93bab1` | Broker protocol 200 |
| `9ab4f009-6ec9-49cb-97a3-a8dcd5ecdc92` | Java facts, report, engineering review, readback and wrong-Owner rejection passed; one dispatch |
| `harness-probe-4ed8b2f0d1b22463` | Broker protocol 200; composite probe subsequently exited with failure |
| `5e768288-e10a-48e4-9c6a-6f6c7b137d6b` | Associated saved report inspected: READY_FOR_REVIEW, REVIEWED, facts preserved; do not count the failed composite probe as a pass |
| `harness-probe-3f4aba52082cbc13` | Broker protocol 200 after diagnostic/parser changes |
| `b1c354cf-47b4-4fdd-b652-b0ecab675bb0` | Restricted-environment Java run passed: facts/receipt, report save/readback, wrong-Owner rejection; one dispatch |

The probe now parses structured JSON instead of matching serialized substrings.
The earlier failed composite probe lacked a detailed exit reason; do not claim
its precise cause was conclusively reconstructed. It was not a lost business
report: the retained file was independently inspected.

Reports remain in the private isolated server directory
`/root/employee-agent-deploy/incoming/harness-authority-20261009/reports`.
The probe creates an ephemeral loopback listener and closes it on completion;
it does not replace the EA or Builder service. No automatic retries were used.

**Explanation is controlled, not a live model.** READY_FOR_REVIEW means ready for
human review, not business-owner acceptance. Saved engineering review text does
not upgrade the report's business/explanation status.

## Source changes

- `capability-packs/wealth-manager-previsit/assembly.v0.1.json`: one actual query,
  employee-authority Host requirement, service-preparation method dependency.
- `providers/employee-agent/assembly.v0.1.json`: existing EA Provider artifact and
  connection kind; no product/allocation capability admitted in this declaration.
- `catalog/finance-assets.v1.json`: remove the obsolete allocation dependency
  from the service-preparation method.
- `EmployeeAgentPreparationBroker`: consume scoped leases without importing EA
  signing credentials; validate owner, session, capability, scope and customers.
- `BrokeredServicePreparation`: refresh authority outside Task monitors, including
  after explanation; connect real Provider to the existing report lifecycle.
- `ServicePreparationBrokerAcceptance`: opt-in synthetic live query and private
  report save/readback; explicitly controlled explanation.
- EA feature branch: readonly existing channel-link lookup, narrow authority
  endpoint, short-lived leases, original previsit Provider invocation and
  pre/post authorization checks. Stable authority revision is separate from
  the session-bound lease, so a new session does not fabricate a new permission.

## Tests

- EA broker HTTP tests: 15 passed.
- EA existing-authority adapter tests: 10 passed.
- EA TypeScript checking passed before the final diagnostic-only probe edit;
  run again before any release.
- Java broker tests: 7 passed.
- Existing ServicePreparation tests: 20 passed.
- Brokered report tests: 3 passed (save without requery, explanation failure keeps
  facts, authority change during explanation discards the report).
- Builder declaration/admission/server-access tests: 56 passed with explicit
  `FH_PREPARATION_HARNESS_REPO`. First sandbox run had 51 passes and 5 socket-
  permission setup errors; the permitted loopback run passed all 56.
- Maven reactor package for the previsit module and dependencies passed. This
  is not a complete three-Runtime or existing-six-card online regression.

## Release gates still open

1. Builder's managed Host still assumes the synthetic-bank customer connection.
   An employee declaration alone must not enable that Host. New support is
   intentionally absent from the enabled Host enumeration.
2. Qualify actual packaged employee Provider installation, managed process
   startup/health, owner-scoped session actions and four-step browser rendering.
3. Link reports to the existing execution-history archive and verify its page
   replay. A matching report/run ID in this checkpoint is NOT proof that the
   existing runtime history page is wired.
4. Exercise the frozen model configuration and preserve facts on model failure.
5. Run candidate browser and existing-six-card regressions, freeze clean commits
   and rollback artifacts, then deploy. Do not hand this checkpoint to Dot as a
   deployed, ready-to-test MVP.

No production service was switched. Audit and anti-fraud remain disabled.
