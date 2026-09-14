# Shared TASK release V0.1: fixed deployment evidence

Verified on 2026-09-14. This supplements `shared-task-components-v01.md` and
separates code, controlled banking behavior, model use, and deployment acceptance.

## Fixed components

| Component | Executed version |
| --- | --- |
| Harness | `aae7574e9e462d5141159eed7a947d2f4d520de7`, clean |
| Builder | `f306c8bac6c1a25e8660791579cb2c9493d3d540`, clean |
| Runtime | OpenJiuwen Java `0.1.15`; SDK source unchanged |
| Simulator source | `162ef4e587c9e9b781bab60bf89591cf4255e579` |
| Simulator JAR SHA-256 | `e40f43df54b4f54b86cf552554c3e4cd65728073a6fa71cc71cab2108681807e` |
| Observation qualification SHA-256 | `dea1f40a93dee348e8a042368a5ab998fca1f37736a24d1884e4ba27caeb88e5` |

PR #21 merged as `acc72bacbd4fdc88ff04b95270d277faeee50b00`.
PR #22 merged as `878929e14aaaccb55fbb152bd7b25e4a0e83bd2a`; its tree is identical
to the executed Harness head above. Both Python-version CI jobs passed after
merge. The deployment remains pinned to the qualified source and artifacts,
not a moving main branch. This subsequent documentation change does not upgrade
the deployed candidate or relabel its qualification.

Builder code is pushed to `feat/capability-extension-v01` in the existing Builder
repository. Upstream maintainer acceptance has NOT been verified or claimed.

## Results

All server tests below ran as the actual `finance-studio` UID, 64599, with an
isolated persistent simulator. They did not mutate customer or production-bank
data. The simulator, not the Agent, owns transfer and preference business state.

| Check | Observed result |
| --- | --- |
| Required observation plus shared TASK/browser regression | 29 checks passed |
| Qualification correct / missing / history disabled / old artifact | Allow / reject / reject / reject |
| Required observation, Java combined read-only candidate | 41 checks passed |
| Required observation, Python combined read-only candidate | 58 checks passed |
| Real `deepseek-v4-flash`, shared UI, isolated persistent bank | 12 checks passed; 7 model HTTP requests |
| Post-restart historical reads | 44 requests readable; immutable service versions verified |
| Cross-owner history reads | 44 rejected; historical reader invoked no Agent or bank |
| Public workbench and original experience UI | Build, start, balance tool, AG-UI result and history passed |
| Existing configuration and artifacts | 6 configuration files and 21 existing installation locks unchanged |
| New pilot manifest | 200 listed files matched their locked digests |

The controlled test checks one 0.01 CNY debit, one receipt and one ledger entry;
the other TASK updates large-text preferences with exactly one version increment.
It also tests forged confirmation, duplicate resume, malformed submit response,
query failure, process termination and recovery without resubmission, pending-task
admission, decline and revoked access. Real-model checks are independent of the
controlled fault-timing checks; neither replaces the other.

The earlier query matrix remains a separate baseline, not a newly executed
full Cartesian product of all combinations and faults.

## Observation scope

The six managed packs cover seven capabilities. Common `execution.history` and
per-capability `capability.result` requirements are composed from Harness assets.
The three added requirement files cover transfer, channel preferences and the
existing wealth-customer-context query. They express need, not collector support
or customer authorization.

Qualification is tied to the actual Runtime, Harness/Builder sources, dependency
digests and collector implementation. Java and Python have distinct identities.
The server verified actual request terminal events and capability result fields,
then regenerated and started candidates with REQUIRED admission enabled. Evidence
files have separate immutable names even when two candidate types use the same
collector identity. An earlier evidence-name collision was rejected and corrected;
the gate was not disabled to publish.

This is **basic execution observation**, not full transaction replay. Existing
Host request, capability, Provider HTTP, model and tool metadata are inspectable.
Dedicated confirmation/resume endpoint history is not claimed as a complete
business lifecycle timeline. Source task results and recovery have separate test
evidence. Model snapshot qualification, hidden reasoning and full wire payload
capture are not implied by basic observation readiness.

## Deployment and use

Entry: https://finance-harness.demo.linggan.top/ (existing authenticated access).
Choose **Create Agent -> capability components**, then select account balance,
internal transfer and/or channel preferences. TASK combinations require Java and
the separately provisioned synthetic environment policy. They use the original
chat and shared confirmation/status controls, not a separate transfer application.
Bank scope grants are independent of capability requirements and UI selections.

Published pilot: `agent-d2fccdc68a554141` (shared task acceptance assistant).
Pilot installation Lock SHA-256:
`f34b572c5309ca6b1b2e72fe2f80d0094d4f7930fb2589de45d25a1195185505`.
The live public smoke requested balance only; transfer and preference mutations
were performed in the separate isolated simulator acceptance environment.

Release directory on the Shanghai host:
`/opt/finance-studio/releases/20260914-shared-task-v02`.
Its `deployment.json`, `pilot.json`, `final-audit.json` and `qualification/`
record the effective versions and evidence. The service override points both
requirements and qualification to this release; the old qualification is not
reused. Explicit `PYTHONPATH`, the external release virtual environment and the
service write sandbox were verified through actual builds as the service user.

The synthetic TASK policy expires on 2026-09-21 UTC. Operators must renew it
explicitly; expiry must not be bypassed. The shared source uses one active
operation session. End a test conversation before opening another; the public
smoke ended its session and left the pilot process available.

Old Agent artifacts, identity/model/speech configuration and history are retained.
Previously running instances were stopped during deployment: they were NOT
unaffected or automatically upgraded. Existing agents continue to require their
original compatible environment. A new installation Lock is required for new code.

## Reproduction and rollback

Maintainer evidence and executable probe scripts are retained under
`/home/ubuntu/outputs/capability-extension-v01` on the development host. Server
qualification artifacts are under the finance-studio state directory:

- `shared-task-qualification-v02`: actual UID qualification, admitted controlled
  TASK run, four admission cases and historical read checks.
- `shared-task-release-check-v02-r2`: admitted Java/Python query candidates and
  real-model isolated-bank acceptance.
- Public browser evidence: development host `public-v02/`.

Use the Builder's `engineering/verify_shared_task_workbench.py` with a fresh output
directory, fixed Harness checkout, simulator JAR, Maven and Chromium. Its optional
live-model base uses an operator-controlled connection; credentials never belong
in the generated artifact. Query verification uses `verify_python_packages.py`.
REQUIRED builds must pass `observation_requirements` explicitly when calling the
build function directly; the workbench supplies its configured selection itself.
Do not treat a direct build without that option as admission-qualified.

The first release attempt was rolled back because the inherited observation
selection/qualification belonged to the old release. V02 passed fresh gates and
is published; it has not required rollback. The old release and private state
backup remain available. Operator rollback entry on Shanghai:

```sh
python3 /opt/finance-studio/releases/20260914-shared-task-v02/rollback_shared_task_v02.py
```

This switches service/code links and keeps stored artifacts and TASK recovery
state. It does not erase or restore a bank database over later results. Stop new
intake and inventory pending tasks before any future rollback; never recreate an
UNKNOWN task in an older release. Private backup archives contain configuration
and must not be published as evidence bundles.

## Remaining independent acceptance

- Builder maintainer acceptance and non-author human onboarding.
- Real bank IAM, real customer authorization and real business transactions.
- Full confirmation/recovery replay beyond the basic metadata scope above.
- Multi-node operation and automatic migration of old generated Agents.
- Automated source-bank cancellation: Host decline is not bank cancellation.

These items are not represented as passed by the controlled or public demo.
