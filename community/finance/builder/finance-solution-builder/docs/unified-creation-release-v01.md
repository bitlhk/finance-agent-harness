# Unified creation: release verification

Date: 2026-09-14. Demonstration deployment, not production banking acceptance.

## Sources and boundary

The deployed Builder implementation is
`90730b5b768a32d2a9073bb352d311cea2dda1d4`, based on
`f306c8bac6c1a25e8660791579cb2c9493d3d540`.
Harness remains `aae7574e9e462d5141159eed7a947d2f4d520de7`.
The following documentation commit does not replace that deployed source identity.

The branch is `feat/unified-agent-creation-v01`. Upstream maintainer acceptance
has not been verified. This release does not merge unrelated branches.

There is one creation page: templates preselect packages, business modules organize
them, and explicit versioned bindings determine installation. Internal transfer
uses the existing transfer module and generated conversation/task controls. The
banking protocol, Runtime SDK and Harness confirmation/recovery rules are reused.
Python TASK combinations remain rejected without losing the user's selections.

## Results

| Gate | Actual result | Evidence boundary |
| --- | --- | --- |
| Final Builder regression | 919 passed, one skipped; zero failures/errors | Python 3.11, 920 collected |
| UI development verification | 10 unit assertions, 59 browser checks | Desktop/mobile, controlled directory |
| Fixed-release task verification | 35 checks passed | Actual service UID; isolated persistent synthetic bank |
| Observation admission | Correct accepted; missing, disabled and old artifact rejected | Basic execution history and capability result |
| Required-observation Java queries | 41 checks passed | Fixed release; controlled model/source |
| Required-observation Python queries | 58 checks passed | Fixed release; controlled model/source |
| Real model and isolated bank | 17 checks passed | Configured DeepSeek model; synthetic transfer ledger |
| Public original conversation | Passed | Read-only balance, AG-UI completion, history linkage |
| Release integrity | 200 artifact files verified | Lock and loaded source identities checked |
| Existing state preservation | Six configuration files and 22 old Agent Locks unchanged | Digest comparison with pre-switch backup |

Overlapping gates are not added together as independent test counts. Earlier
development runs are retained separately, not relabeled as fixed-release tests.
All fault injection and transfer mutations used separate synthetic databases;
no transfer was requested in the public demonstration bank during public acceptance.

Observation qualification SHA-256:
`f342cf72ad5a68e638037ab27be0c4a90a973d6ec968753a6523333ace4b20b4`.

Public pilot artifact Lock SHA-256:
`dd9ef4d3db55e763dffb2c7812a5a9055e2c92176f59eec53b0eaae090b7692a`.

## Verification setup corrections

The earlier `a7fb61f5` candidate was not published. Its revocation verifier assumed
HTTP 403 even when background authorization checks had already cleared the session.
The updated assertion accepts HTTP 409 only with INSTANCE_CHANGED/clearConversation,
a newly appended explicit authorization DENIED event, and no additional model call
or business mutation. Tests reject old denial events and unrelated failures.
Authorization behavior itself was not relaxed. All fixed-release gates were rerun.

The first public browser run also encountered a session-start rejection after the
API smoke had created a session without closing it. The task host intentionally
permits one conversation for its frozen task scope. Only the new pilot was restarted
before repeating browser acceptance. The old failure evidence is retained; public
acceptance then passed and explicitly ended its conversation. The diagnostic helper
initially read sessionId at the top level instead of instance.sessionId, so that
initial boolean is not evidence that no API session existed.

When mixing API and experience-page tests, release the previous conversation first
or restart only the owned test instance. Do not weaken the host's session isolation
to make independent test drivers share a task session.

## Deployment and rollback

Entry: <https://finance-harness.demo.linggan.top/>. Authentication remains enabled.
Choose the mobile-banking template or blank creation, then open transfer service
and select internal transfer. Creation and Business Assets use the same package
identity and version. Template/module selection does not grant banking permissions.

Release: `20260914-unified-creation-v02`. Previous release:
`20260914-shared-task-v02`. New instances use the new generated artifact; existing
Agent Locks, histories and configuration are not rewritten or auto-upgraded.
Previous running instances were stopped for the controlled workbench restart.

Operators retain `deployment.json`, `final-audit.json`, `pilot.json`, qualification
evidence and a private configuration/state backup under the release directory.
The release-local `rollback_release.py` stops the workbench, restores the previous
Builder/Harness links and unit configuration, then starts the previous release.
It deliberately does not restore the entire database backup over newer state.
Rollback is prepared, but was not needed or exercised for this release.

Local review evidence is in `outputs/unified-agent-creation-v01`, including JUnit,
fixed-release summaries, original failed probes and public screenshots. Sensitive
operational configuration and model credentials are not part of this document.

## Still separate

Real bank authorization, production transactions, broad model quality, independent
human onboarding and upstream Builder acceptance are not established by these
checks. Basic observation qualification does not claim full business replay or
complete model wire snapshots. No old Agent is silently upgraded to this template.
