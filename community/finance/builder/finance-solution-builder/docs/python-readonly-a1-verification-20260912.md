# A1: shared read-only plan verification (2026-09-12)

## Fixed implementation and scope

- Harness base/main: `c3801e2df973b68bc11e80bde35577f4d23ccc7d`.
- Harness implementation: `0289de7cabe2e4c0e41da61a9ea768ed32df4c7a`, PR 15.
- Builder base: `c6fbdda3e392a05d143a48ee9c3f6030bb83b5d8` (PR 147 dependency).
- Builder runtime implementation: `e0b6975385417b59ef5814c49fd13badc83344f5`.
- `bbf49d00` adds only the business comparison script and its negative tests. The page-generated
  candidate uses this revision; the three Python and three Java CLI candidates use `e0b69753`.
- Builder review: MR 149 against `feat/python-finance-candidate-v01`, not a request to merge all
  inherited Studio changes into finance. Remote finance `3adfc54c` was not silently integrated.

Native OpenJiuwen Python 0.1.18 drives the model/tool loop. The owned Java bridge still requires
Java and OpenJiuwen Java 0.1.15 artifacts. This is not a pure Python SDK or proof of arbitrary
Runtime portability. Only balance, wealth facts and their combination are enabled in Python A1.
Cashflow, customer Skill replacement and Python model snapshots remain A2/A3.

## Actual checks

| Check | Result |
| --- | --- |
| Harness Java | 223 tests; 216 passed, 7 explicit real-environment smoke skips, no failure/error |
| Builder full Python suite | 599 passed, including comparison negative tests |
| Schema / fixture / asset checks | 13 schemas, 92 probes passed |
| Final Python balance / wealth / combined artifacts | 28 / 30 / 37 controlled checks passed |
| Java counterpart balance / wealth / combined | 17 / 19 / 24 controlled checks passed |
| Java/Python business comparisons | 10 corresponding normal, zero and missing-risk results passed |
| Native raw tool input tests | 5 passed on final combined artifact |
| Owned bridge pipe / process boundary tests | 11 passed on final balance artifact |
| Actual model + full synthetic simulator | 8 wealth checks passed on final wealth artifact |
| Page-generated Python combined | Actual page selection/build/start, wealth then balance rendered; 4 browser assertions passed |
| Page artifact lifecycle | 8 checks: stop, owned process cleanup, restart, new generation, stale stop rejection, Lock and native tool registration |

The controlled checks use the actual native SDK host, not a scripted replacement Runtime. The
HTTP model is controlled and labelled as such. The separate real-model run used deepseek-v4-flash,
two conversation rounds, actual simulator GET requests, matching source facts and display, and
revocation with zero additional model/business-read calls and no returned old facts. This is not
production customer authorization or statistical model-quality evaluation.

Business comparisons keep customer references, amounts, currency, asset scope, risk state/version,
source contract, source business time, gaps and suitability-not-evaluated. They exclude runtime
request/evidence identifiers and per-request observation time/payload digest. Valuation and risk
dates are not removed. Negative comparator tests demonstrate that changed identity, amount data,
scope, risk availability, source fact time or result status cannot be normalized away.

The shared installation plan is validated, immutable and explicitly NOT runtime registration
evidence. Session opening rechecks exact plan equality against the actual Providers and schemas;
native tool names are separately recorded. QUERY-only, duplicate names/IDs, uninstalled tools,
forged identity fields and invalid selectors are checked before business execution. The schema
loader explicitly rejects resource loading; this does not claim every JSON Schema feature is supported.

Each model/tool boundary checks all selected public scopes AND required underlying source scopes.
Wealth source-scope withdrawal blocks old context from the next model call. Late bank-return
revocation, isolation, zero versus unavailable, no business writes and no model UI/trace envelope
are included in the controlled acceptance.

## Evidence and reproduction

Maintainer evidence root: `/home/ubuntu/outputs/python-readonly-packages-v01`.
`artifact-manifest.json` records full Lock SHA256, actual source revisions and paths. Directory
names do not establish a version; compare the manifest, build report and actual file digests.

| Candidate | Service Lock SHA256 prefix |
| --- | --- |
| Python balance | d5b5f5416b4b |
| Python wealth | 0cabf4005795 |
| Python combined | 3717c1801f29 |
| Page-generated Python combined | b76c1d9ebabd |
| Java balance | f377d0181357 |
| Java wealth | a984b3bafa98 |
| Java combined | 44786d9106d2 |

From the Builder module, use the commands in `python-readonly-a1.md`. Run
`engineering/verify_python_packages.py` for each generated candidate. Store Python outputs under
`release-checks/{balance,wealth,combo}` and Java outputs under `java-checks/{balance,wealth,combo}`.
Then run `python engineering/compare_readonly_business.py --root /path/to/evidence`.
Do not run concurrent verifiers against the same candidate's instance file.

Final evidence: `release-checks`, `java-checks`, `business-comparison.json`, `live-wealth-release`,
`builder-delivery.xml` and `harness-counts.json`. Earlier failing iterations remain under the same
root and are not counted as final passes. In particular the early native LocalFunction input
formatting failure was fixed by native raw-input Tool registration; its failure log is retained.
`release/browser-checks.json`, `page-lifecycle.json`, `mobile-live.png` and `desktop-live.png`
cover the separately page-generated candidate. This browser probe checks actual rendering and
execution records; it does not assert that this non-streaming host emits token or TOOL_CALL events.
An earlier browser probe was interrupted while Playwright waited for SSE response completion;
that probe is not counted as a pass. The corrected probe uses bounded rendered-state assertions.

## Delivery boundaries

Isolated reviewer: `http://127.0.0.1:18448/`, private loopback login material in
`release/private/login-url.txt` (do not commit/share publicly). Existing 18438 and stable services
were not upgraded. The reviewer uses a private copy of simulator authorization/model connection
configuration. No secrets are in generated artifacts or this report.

CLI artifacts are retained for reproducible tests, not imported with missing UI selection history.
The separate page build records the actual Python selection, model binding and eight build stages.
No stable deployment or repository merge is part of this delivery. Independent human reproduction,
upstream Builder acceptance and production authorization remain NOT_RUN / pending. Python snapshot
capture remains NOT_SUPPORTED_PYTHON_V01. The GitCode push reported an existing LFS quota warning;
no upstream objects were deleted as part of A1.
