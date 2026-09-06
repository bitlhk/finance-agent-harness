# Local Agent Workbench V0.1

## Baseline and ownership

- Harness main: `296ada0`, after merge commits for PR #6 and PR #7. Both reviewed heads were unchanged.
- Builder development starts at `8229b3c`, on top of upstream MR #118 `66d2767`.
  During implementation, upstream `cb749fab` was merged as local `86dc16d`;
  its README/visualizer changes are preserved, not replaced by the new workbench.
  Upstream acceptance is pending; this is a local patch branch, not a new Builder repository.
- OpenJiuwen Java: `0.1.13`, unchanged SDK. Host templates belong to Builder.
- Banking simulator: isolated synthetic data only. EA and the old mobile application are not runtime dependencies.

## Work Items

- [x] Merge reviewed PRs in dependency order and create short-lived development branches.
- [x] Host-bound bank sessions, no implicit login on revoked sessions; regression tests.
- [x] Independent balance-only Pack; three finite read-only compositions.
- [x] Portable persistent native Agent host; conversation, authorization and revocation isolation.
- [x] Builder job API and red/white/gray UI with real process state.
- [x] Full simulator read smoke with an explicitly controlled test model.
- [x] Configured real model workflow executed; one failed tool-use attempt retained, complete repeat passed.
- [x] PR #8 per-model revocation, instance generation and two verifier corrections; new artifacts checked.
- [ ] Stable model task/professional quality: optional-parameter and unsupported explanation errors remain.
- [x] Desktop/mobile UI, lifecycle failure validation and actual-model UI workflow exercised.
- [x] Version/fixture/artifact inventory, reproduction instructions and scoped evidence.
- [ ] Independent human onboarding: external organizational acceptance, not AI self-testing.

## Decisions

The workbench is a local single-operator development tool, not bank IAM or a multi-tenant platform.
Configured capabilities never grant access. Simulator session introspection establishes the source
customer; a server-owned test access policy grants a bounded set of read scopes to that identity.
Revocation invalidates the local conversation, clears runtime memory and blocks model use before
each model request, including within a turn. Bank-session and host-grant validity are checked again
at the tool boundary and before returning a response.

Runtime tool names are globally registered by SDK 0.1.13. The local host serializes native invocations
and mounts/unmounts each session's tools, keeping sessions separate without modifying the SDK.
This deliberately does not claim concurrent multi-tenant throughput.

All new business capabilities are QUERY. Authentication and session revocation are identity control
operations, reported separately from bank business reads/writes. No transaction tools are installed.
External model use must be explicit and use only the configured synthetic banking connection.

## Acceptance Boundaries

Report separately: unit/contract tests, controlled matrix, full simulator reads, actual model turns,
UI process lifecycle, real bank authorization, provider substitution and human onboarding.
Only the first five are executable in this local iteration. No production bank data or funds are used.

## Compatibility and reproduction

Existing Config constructors and simulator-login behavior remain available. `Config.hostSession`
is an opt-in path: its trusted supplier must revalidate the actual source identity and host grant
on each call. An expired/401 session is not silently replaced by a fresh login. The supplier is
a host integration API, never a model argument. Transport observations exclude request bodies,
headers and account IDs; they are read-response observations, not a complete audit of all attempts.

The Builder owns `templates/local-readonly-service`, `service.py`, `workbench.py`, and
`engineering/start_local_stack.py` under `community/finance/builder/finance-solution-builder`.
Its `docs/local-workbench-v01.md` documents three compositions, server configuration, portable
artifacts, local policy, startup/shutdown and explicit controlled checks. No EA/old-mobile deployment
or second Builder repository is involved. Model configuration is present; professional reliability and
human onboarding remain separate acceptance items. Exact component SHAs, red/green regressions and
current limits are in the [PR #8 correction report](local-workbench-pr8-hardening-20260906.md).
