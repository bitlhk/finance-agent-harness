# A2 candidate acceptance (2026-09-12)

Status: implementation and isolated candidate acceptance complete. Upstream acceptance, production
authorization and independent human onboarding remain pending. No stable deployment or merge.
Reproduction: [A2 developer entry](python-cashflow-a2.md).

## Fixed versions

Harness remains `0289de7cabe2e4c0e41da61a9ea768ed32df4c7a` (PR15).
Builder starts at A1 `d5b6dabee21801916be0b81aba66aa0d36ca2068` (MR149, dependent on PR147).
A2 implementation is `9402bf2133df66742cadf32d288ca995ff19ec2f`.
`fd5cafc3` and `c3229c46cc6509d728567696e0f0848da31a417c` change only verification/comparison.
Fourteen matrix artifacts and four published-method artifacts use 9402bf21; the separately
page-generated artifact uses c3229c46. All source worktrees were clean at generation.
Subsequent verification entry and documentation do not change generated service implementation.

## Actual acceptance

| Check | Result |
| --- | --- |
| Harness build inside A2 seed generation | 223 tests: 216 passed, 7 explicit live Smoke skipped |
| Schema/assets | 13 schemas, 92 probes passed |
| Builder full tests | 616 passed, zero skipped/failure |
| Java seven compositions | 17 / 19 / 24 / 29 / 34 / 36 / 41 checks passed |
| Python seven compositions | 28 / 30 / 37 / 42 / 49 / 51 / 58 checks passed |
| Portable matrix entry re-run | 14 artifacts and 48 business comparisons passed |
| Native four-tool input boundary | 9 checks passed |
| Cashflow pipe input/auth/uninstalled-tool boundary | 14 checks passed |
| Existing pipe/process regression re-run on A2 | 11 checks passed |
| Publication/owner/version checks | 7 passed |
| Method/source tampering and unsupported declarations | 5 passed |
| Real model + full synthetic simulator | 40 checks across five runs passed |
| Actual page build + two-round dialogue | 5 browser assertions passed |
| Page artifact installation/stop/restart/stale generation | 15 assertions passed |
| Artifact integrity | 19 candidates, 4,927 locked files verified |

Unit bits are balance=1, wealth=2, cashflow=4. Cashflow installs two QUERY capabilities together;
seven compositions are not seven different business contracts. The matrix preserves source
identity, amounts, currency, period, scope, source/policy business time, gaps and failure semantics.
Cashflow per-invocation observedAt is excluded, not source asOf or policyAsOf. Negative comparator
tests confirm business changes still fail. True zero, missing expense and inconsistent totals are
separate cases. The first zero fixture was inconsistent and correctly rejected; it was corrected
in the verifier, not by weakening the Provider.

The real model was deepseek-v4-flash. Published SOP1.0.0 (scope first) and SOP1.1.0 (gaps first)
were each generated for Java and Python and checked against actual model requests/output and
synthetic bank GETs. Each run verified selected method text, month/currency, no business writes,
and revoked access producing no additional model/business calls. A fifth run restarts the old
1.0.0 artifact and confirms its pinned method. This is immutable-artifact reuse, not proof of a
running instance being hot-updated during publication. Method trials use a controlled model and
do not establish professional quality. Forty assertions are not forty independent model samples.

## Page and lifecycle

The page actually selected Python, the financial-assistant unit and published SOP1.1.0 and
submitted build `agent-59ccd9d282e540cd`. It produced a new independent environment in 64,068ms
using a verified reusable Java host and cached dependencies. This is one warm local observation,
not an efficiency baseline or P95. Lock SHA256:
`b4c31ba334b72ff3041c4a0017538eeb215fd6730ede1f3875f83778bf2df7ff`.

Two actual model conversation rounds rendered source-qualified summary/classification facts and
the selected gaps-first explanation. Mobile and desktop screenshots and overflow checks passed.
Runtime evidence registers exactly both cashflow tools and method1.1.0 and equals the exported
plan. Stop reaped the owned Python/Java pair; restart uses the same artifact, changes generation,
and rejects an old-generation stop. The final test instance is stopped; workbench remains available.

One early page probe used an expired experience entry; another expected a heading to be visible
inside collapsed Assistant Explanation. Neither is counted as a pass. The corrected probe opens
the authorized entry and expands the explanation. No product visibility behavior was weakened.

## Evidence and delivery limits

Maintainer root: `/home/ubuntu/outputs/python-cashflow-methods-v01`.
`portable-matrix-checks/` contains all fourteen actual checks and business-comparisons.json.
`artifact-manifest.json` binds each candidate to its source/Lock and checked files.
`live-methods-v2/`, `method-tamper-checks.json`, `cashflow-pipe-checks.json`, `native-inputs.json`,
`pipe-a2/`, `builder-final.xml` retain separate evidence. `reviewer/` holds page-build.json,
browser-checks.json, page-lifecycle.json, screenshots, dialogue and runtime installation receipts.
Do not distribute its private directory, connection files or access tokens.

Isolated reviewer: http://127.0.0.1:18458/ (private login supplied separately).
18448 (A1), 18438, 18428 and the stable site are not upgraded. The three prior local endpoints
were checked HTTP200 after acceptance. No upstream merge or production bank call was performed.
Snapshots remain disabled and `NOT_SUPPORTED_PYTHON_V01`. A3 must use separate changes/evidence.
Business-owner signoff, real bank authorization, independent human usage and upstream integration
are NOT_RUN/PENDING, not implied by synthetic acceptance or the real model service being reachable.
