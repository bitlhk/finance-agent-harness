# Local Financial Agent Workbench V0.1

## Scope and ownership

This Finance Solution Builder extension generates a persistent OpenJiuwen Java 0.1.13 application.
It does not modify the SDK or Base, copy Harness Java code, require the mobile banking Fork, or
create a centralized Harness service. The old controlled ten-case recipe and report visualizer remain.
The new UI is under `examples/local-workbench`, with real jobs in `workbench.py` and a finite recipe
in `service.py`. Host lifecycle and model/session wiring are Builder template responsibilities;
bank protocol/session adaptation and financial constraints remain Harness/Provider responsibilities.

Three compositions are accepted: wealth only, balance only, or both. The balance-only Pack contains
no transfer tool. Multi-Provider compositions include explicit capability bindings. No unvalidated
Runtime/model/provider selection is accepted. Model credentials and policy are external host inputs,
never part of the generated artifact. Base `copy_directory_tree`, `require_path_within`, `run_process`
and `spawn_process` are reused without API changes.

## Prerequisites

- JDK 21, Maven 3.8+, Python 3.11.4 through 3.13. Install this project and `../base` into the same venv.
- Harness branch containing host-session support, local-read-only profile and account-balance-readonly Pack.
- A built non-ai-banking simulator JAR with GET `/api/v1/sessions/current` and `/api/v1/sessions/runtime`.
  Its SHA must be supplied explicitly. The launcher uses a fresh in-memory database, not the existing file database.
- To demonstrate actual dialogue, configure `FH_MODEL_URL`, `FH_MODEL_NAME` and `FH_MODEL_KEY` in the local
  server environment. Only a configured OpenAI-compatible model is supported. HTTPS is required except
  for a model on loopback. Do not send real banking data to this demonstration model.
- No model fallback exists. With no model configuration the UI can build artifacts, but launch/dialogue
  acceptance remains blocked. `CONTROLLED_TEST` is only for the explicitly invoked regression tool.

## Start and stop

From the Finance Solution Builder project:

```bash
python3 -m venv .venv
.venv/bin/pip install -e ../base -e . -r <HARNESS>/engineering/requirements-conformance.txt pytest
# Export FH_MODEL_URL, FH_MODEL_NAME, FH_MODEL_KEY in the server environment, not the browser.
.venv/bin/python engineering/start_local_stack.py \
  --harness-repo <HARNESS> --simulator-jar <BANK_JAR> \
  --simulator-sha256 <ACTUAL_JAR_SHA256> --provision-demo-policy
```

The explicit provisioning flag creates a one-day synthetic policy for the documented simulator customer.
It is an operator-provisioned test policy, not an IAM/real-bank authorization claim. Reuse the existing
policy on subsequent starts by omitting the flag. Existing policy, including revoked permissions, is
never automatically overwritten. Changing capability selection never changes this policy.
The full simulator currently has one login customer. Different-customer isolation is verified separately
with two controlled identities; this does not claim multi-customer source-IAM acceptance.

Open `http://127.0.0.1:18118/`. Select capabilities, create a build, inspect actual stages, start, then chat.
The initial application connection is the configured synthetic login, not a user-supplied customer ID.
Stop/start creates a new process and sessions. Ctrl+C in the stack launcher stops its UI and bank children;
the UI stops only Agent processes it created. When used remotely, forward the loopback port over SSH;
do not bind this development console to a public interface.

Alternatively, run the already configured simulator separately, set FH_BANK_URL, FH_BANK_LOGIN,
FH_BANK_SECRET and FH_ACCESS_POLICY, then run `python -m finance_solution_builder.workbench --harness-repo <HARNESS>`.
Bank authentication/expiry comes from session introspection; the independently maintained policy grants
read scopes. Both are checked before every model invocation, on each tool, and before returning an answer.
The native `BEFORE_MODEL_CALL` callback uses SDK force-finish when authorization is denied or cannot
be checked. The host then rejects the turn and releases its Runtime session. This also covers a bank
response arriving after revocation, before the next model request; it does not retract an already-sent request.

## Output and portability

`build/workbench/<build-id>/` contains the candidate, stage logs, build report and lock. The candidate
contains one standard Java service template, selected assets and dependency JARs. It has no source-checkout
runtime dependency. Copy the candidate, supply the external connection/policy configuration, then run
its `run.py`; Ctrl+C stops that launcher-owned process. API details are in its README.

The lock lists selected capabilities, source revisions, all generated assets and actual dependency JAR
hashes. Harness JARs must match the local build exactly. The service verifies the artifact manifest;
the workbench also checks the locked digest, new PID, instance ID and health before reporting RUNNING.
Native per-session installation records are returned by the session endpoint. Build success alone
does not imply model/bank success. Added JARs and modified frozen assets are rejected at startup.

The UI uses an HttpOnly/SameSite local session, request origin/Host checks and CSRF tokens. Build
ownership is bound to that local session. It exposes no arbitrary shell command, path, credential
or tool-execution endpoint. Refresh restores job state, but not customer plaintext. After a backend
crash, old processes are marked unmanaged; their recorded PIDs are not automatically killed or adopted.
Runtime invocations are serialized because SDK tool names are global. There are at most 16 sessions
and 20 turns per session. This is not a multi-user production control plane or throughput claim.

Revocation clears server Runtime context and UI facts; authorization is also rechecked after inference.
Already delivered data cannot be erased from screenshots/client-controlled copies. Runtime evidence
stores hashes and event metadata, not a new customer plaintext archive. Simulator IAM login/revocation
and audit writes are distinct from financial business writes. No business TASK tools are installed.

## Verification and remaining acceptance

```bash
.venv/bin/python -m pytest -q -p no:cacheprovider
.venv/bin/python engineering/verify_local_service.py <CANDIDATE>
.venv/bin/python engineering/verify_local_service.py <CANDIDATE> --bank-url http://127.0.0.1:18119
.venv/bin/python engineering/verify_service_lifecycle.py <BUILD_DIRECTORY> --harness <HARNESS>
.venv/bin/python engineering/verify_revocation_race.py <CANDIDATE> --output <REPORT_DIRECTORY>/policy-race.json
.venv/bin/python engineering/verify_revocation_race.py <CANDIDATE> --mode source --output <REPORT_DIRECTORY>/source-race.json
.venv/bin/python engineering/verify_revocation_race.py <CANDIDATE> --mode policy_unavailable --output <REPORT_DIRECTORY>/unavailable-race.json
```

Both commands above deliberately use a test model. They check native multi-turn execution, real
installation, scope/subject rejection, revocation, and source calls. The second uses the full simulator.
They never establish real model quality. Preserve the original ten-case controlled recipe separately.
The lifecycle check uses actual Java child processes, artifact downloads, an occupied port and a
modified locked file. Its lifecycle portion never calls a model; its separate portability portion
uses the explicitly labelled test model. The full-simulator verifier records forwarded source
requests and refuses business-write attempts; simulator login/revocation is counted separately.

The standard library workbench backend can be exercised with any browser. For automated desktop
and mobile verification, use Playwright against the actual loopback server, with no mocked build
responses. Verify a new build ID, three completed stages and hashes, owner/CSRF boundaries, refresh,
download and honest launch failure when no model is configured. Do not publish the browser cookie
or CSRF bootstrap token in the acceptance report.

Actual model dialogue, UI lifecycle, full simulator, real bank authorization, same-condition Provider
substitution and independent human onboarding have separate results. A human must record manual time,
cache conditions, edits and assistance; AI or cross-platform self-tests cannot fill that record.
No transaction UI, anti-fraud transfer, UNKNOWN recovery extension or second Runtime is included.

## PR #8 boundary hardening

- Instance requests capture the Popen object identity and token (not a reusable PID). Session creation,
  chat/revoke/status, start success/failure, and stop success/failure validate that generation and their
  expected state under the same lock as each update. Network I/O and process waits occur outside it.
  Superseded completions return `INSTANCE_CHANGED`; they never stop or clear a replacement instance.
- The verifier observes actual simulator business calls in full-simulator mode, excluding session
  introspection. A 403 with an extra financial read fails. Multi-turn checks inspect the second turn's
  first model request for the unique first-turn marker and exact prior tool messages, plus customer isolation.
- Race checks use a delayed bank response and count HTTP requests at the controlled model endpoint.
  Actual model dialogue is a separate smoke, not a substitute for deterministic timing checks.
- Existing Candidate archives are immutable and do not receive template changes. Generate a new build,
  retain old failing evidence, and verify the new manifest before acceptance. No Builder Base API,
  Harness contract, Runtime source, or prior ten-case recipe changes in this correction.

Verified implementation baseline: `72e0ac35e592483a32321f9f463dbe0b3853448d` (2026-09-06).
The following documentation update does not change generated Java or workbench implementation.

- [x] Old implementation reproduced: nine failing boundary/assertion regressions, two HTTP status
  regressions, plus a generated-host probe observing a financial model request after revocation.
- [x] Builder suite: 85 passed; eight handoff patches replayed on clean `cb749fab`, identical tree,
  and 85 tests passed there too. This was AI-operated, not human onboarding.
- [x] New balance/wealth/both Candidates: controlled checks 26/29/29; full simulator checks 18/19/19;
  nine delayed-bank revocation probes, zero new model requests after revocation; lifecycle 11 passed.
- [x] Actual-model workflow executed on isolated ports 18128/18129. Final repeat: 27 checks passed,
  including two turns, source/tool evidence, revoke and rebuild without balance.
- [ ] Stable professional output: the first final-baseline attempt incorrectly asked for an optional
  accountId and did not query balances. The actual native model schema preserves its optionality.
  Unsupported risk-grade interpretation was also observed. Passing a subsequent repeat does not close
  these model-quality gaps. Do not present the smoke as a reliability benchmark or formal business acceptance.
- [ ] Builder maintainer acceptance, authoritative business semantics and independent human onboarding.

Harness evidence: `reports/local-workbench-pr8-final-20260906/`; old failures remain separately under
`reports/local-workbench-pr8-hardening-20260906/`. See its
`docs/integration-acceptance/local-workbench-pr8-hardening-20260906.md` for versioned results and limits.
Do not run multiple Maven builds against the same Harness checkout concurrently. The isolated preview
uses a separate Builder checkout, but still shares that Harness build directory.
