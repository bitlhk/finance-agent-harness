# H1b minimal remaining gates

## Decision

**Keep PR #100 Draft. Do not merge or deploy.** The requested citation fixes and
installed safety checks are complete, but two gates remain open:

1. The final installed run retains a failed model explanation. Do not combine
   its safety success with an earlier answer and claim one fully successful run.
2. Builder MR !400 has no independently verified remote CI success. Its public
   page shows Checks 0. The authenticated API reports
   `only_allow_merge_if_pipeline_succeeds=false`; `ci_state_passed=true` is NOT a
   test run. No CI policy or remote status was invented or weakened.

No new business scenario, Provider authorization mechanism or UI was introduced.

## Exact execution identity

- Harness runtime: `56c55aa8690803a05f8498f0348787687e806f13`.
- Builder runtime: `ce035f4bf00ff80fbb76bde2d74d6f2aad4f223e` (paired MR !400).
- Factory runtime: `53cca76e5d8ee9c8ee558200dafa1c03253b4323`, unchanged isolated copy.
- Fresh Agent: `agent-b8d6e91313b04f25`.
- Instance: `c342564a-2ece-4668-95af-711ac16bea43`.
- Installation Lock: `e4f36332d934dc58ce9cf8f592abc443631b0ad5cc5922c4a749db26bdcdc385`.
- Product: `synthetic-product-policy-e1bdc9d3687e`, version 1.
- Manifest: `1d51ac2b45f67ad61099c5a741017c23bb5096c7300fe12a3395f5a45de1449f`.
- Original Host request/trace: `52c22f25-5120-4b9c-9481-cecf90249841`.
- Original Factory receipt: `8960dd13f886c6729ffe67b7cf3bccd8cb29a0c27a488fc7cb143509a131251f`.

This Harness follow-up changes documentation/evidence only. The previous
`56c55aa` also differs from `1773f63` only in documentation/evidence. Final
publication SHAs and their CI status are recorded in the PR handoff; neither
claim replaces the actual runtime identity above.

## Gate matrix

| Gate | Result | Evidence level / limits |
| --- | --- | --- |
| Normal browser Build | PASS | Fresh browser selection, actual REQUIRED qualification, install and Lock |
| Three Providers | PASS | Actual installed CRM/product HTTP fixtures plus real Factory/RAGFlow document query |
| Independent identity | PASS with explicit public-source boundary | Independent policy/connections; NOT three credential-backed source logins |
| Model answer and source citations | BLOCKED on final run | joint-12 READY answer retained; joint-13 explanation FAILED, facts/quotes preserved |
| Five ordinary references | PASS | Four saved missing/unknown facts; one valid empty collection; two policy quote references unchanged |
| Stop and independent history | PASS | Same joint-13 instance, 62 assertions, stopped lease count zero, exact saved failed answer/evidence |
| Manifest mismatch | PASS | Real Factory response modified only at isolated transport; installed Provider rejects, no disclosure |
| Upstream outage | PASS | Isolated transport HTTP 503, installed Host reports failure, no disclosure |
| Revoke before return | PASS | Hold actual Factory response, disable own Host knowledge authority, then release; explicit rejection |
| Stop and late return | PASS | Hold actual response, invoke normal Stop, release late response; no disclosure or archive changes |
| Duplicate / stopped submission | PASS | 409 with unchanged model/business/query-receipt counts |
| Current source/history access | PASS | Independent history, exact receipt scope, CRM/product/source revocation and other viewer denied |
| Harness regression | PASS | 1204 discovered, 38 skipped, 1166 executed Java tests; 77 Python tests plus 191 subtests |
| Builder regression | PASS locally | 500 affected Python, 258 cross-scenario Python, 134 Node tests, desktop/mobile history checks |
| Harness required CI | PASS at runtime HEAD | public-developer-guide, offline-contracts-and-java (3.10), (3.12); final docs HEAD rechecked in PR |
| Builder remote CI | NOT VERIFIED | MR Draft, target finance, Checks 0, no required pipeline gate |
| Production SSO / controlled bank source | NOT COVERED | Local creator, synthetic business facts, isolated published document product |

Fault tests use a loopback proxy configured BEFORE normal Build through the
existing trusted connection configuration. It forwards actual Factory requests
and sessions; it does not fabricate successful knowledge content. The explicit
transport faults are not claims that the live Factory was restarted, its original
Manifest changed, or a real bank failed. The return-race tests are installed
Agent tests, not standalone Provider mocks. Existing component tests cover the
wider expiry/identity/permission/error matrix separately.

## Fixes and retained failures

Builder `58dbe33ac` adds saved quality metadata to the authorized manager view and
distinguishes PRESENT, EMPTY, MISSING and UNAVAILABLE. Empty arrays and explicit
nulls no longer imply nonexistent references or zero facts. Authorized history
shows the stored missing-input/empty-set explanation without rewriting the answer.
Ambiguous, unauthorized and non-scalar object citations are not guessed. In
joint-12 two object-level paths (`/profiles/0/detail` and its `riskAssessment`)
remain explicitly unresolved; they were not changed into verified claims.

Builder `b19e0c4b6` keeps the model gate failed while allowing safety/history
checks to proceed. It does not retry a model explanation or mark a failed one
READY. Each fresh run and its outcome remain independently recorded.

Builder `ce035f4bf` fixes a real SSE error-path defect exposed in joint-12:
`ApiError` was imported only inside a different `do_POST` branch, so a failure
after SSE headers caused an unbound-local exception and a second HTTP response
inside the stream. The import is now correctly scoped; disconnected writes are
bounded. Two real HTTP regressions require exactly one safe RUN_ERROR, no private
detail and no second HTTP status line. No authorization check was relaxed.

| Run | Builder | Outcome |
| --- | --- | --- |
| joint-10 | 2c5cbf437 | Previously accepted full path; original evidence untouched |
| joint-11 | 58dbe33ac | Build/query succeeded, explanation FAILED; stopped safely |
| joint-12 | b19e0c4b6 | New real DeepSeek READY answer; Manifest fault exposed malformed SSE error framing |
| joint-13 | ce035f4bf | New Build/query, four fault checks and 62 history assertions passed; explanation FAILED |

The final model diagnostic is `REPORT_MODEL_FAILED` /
`MODEL_PROVIDER_OR_RUNTIME_FAILED`, stage `MODEL_TRANSPORT`, cause category
`IllegalArgumentException`, no provisional deltas. Instance logs also include
`Failed to read streaming response`. The bounded diagnostic does not identify an
upstream HTTP status/request ID, so it cannot establish that a key is invalid,
the endpoint is unreachable, or that this is purely an upstream outage. No
retry-until-green or model validation relaxation was used. Remaining work is a
targeted reproducible explanation-stream diagnosis, not another Provider design.

## History proof

The normal query service was stopped before history reading. The independent
history worker recorded zero outbound attempts; model END counts and Core query
receipt count/digest stayed unchanged. Business-history calls were authorization
checks only, not CRM/product fact retrieval. Source revocation was applied only
to the isolated database copy and then correctly denied disclosure. All fault
checks compare the original archived request files byte-for-byte.

See [public evidence](evidence/h1b-final-gates-20261102/), including the exact
Installation Lock, original Factory receipt, failure SSEs, history checks,
same-instance binding, retained joint-11/12 outcomes and original joint-12 model
answer. Private original run directories remain under
`/home/ubuntu/outputs/h1b-acceptance-20261102/joint-{10,11,12,13}`.

## Minimal handoff

1. Diagnose the bounded model stream failure with safe request/termination
   evidence; preserve all failed attempts. Then run one explicit final-candidate
   Build/answer/Stop/history acceptance. Do not count a safe failure as answer success.
2. Obtain or enable the owner's intended Builder CI and verify the exact MR HEAD;
   do not synthesize a green check or infer CI from mergeability.
3. When both gates close, mark PR #100 Ready for independent review, never merge
   automatically. The subsequent priority is real bank role-task acceptance,
   including SSO, controlled product-source admission and business-owner review.

Eight protected integration processes retain their original PID/start identity.
Both protected Harness/Builder worktrees retain their original HEAD and are clean.
No Factory changes, colleagues' PR edits, main changes, Shanghai deployment or
shared Maven SNAPSHOT installation occurred. All newly created test services
were stopped; shared RAGFlow and the original preview were not restarted.
