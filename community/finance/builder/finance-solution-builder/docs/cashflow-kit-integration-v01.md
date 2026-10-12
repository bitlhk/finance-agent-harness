# Cashflow Read-only Assembly V0.1

Status: implementation, controlled verification, and preliminary real-model terminal checks completed locally. Deployment, frozen-commit reruns, upstream acceptance and human onboarding are recorded separately in the release evidence.

## Baselines

- Builder: `38a9a17a`, branch `feat/studio-cashflow-kit-v01`.
- Harness: PR #10 candidate `64bce5fd22d63964c843466a270bc483559273df`; the integration branch adds source-period coverage checks and method version 0.1.1. PR #10 is a dependency, not an already merged mainline feature.
- Mobile banking upstream main inspected: `db38c7f454b25f109a607308dbeb4f01de75e9d2`. The 26-entry UI inventory retains its separately recorded original inventory SHA; it is not a standard or an assertion of 26 implemented Harness capabilities.
- Full simulator integration baseline: `162ef4e587c9e9b781bab60bf89591cf4255e579` from the bitlhk integration fork. Do not attribute its integration additions to original upstream main.

## Assembly and Method Scope

Three installation units are supported: balance, wealth facts, and the cashflow pair. Their seven nonempty combinations are finite recipes. The cashflow pair installs `finance.transaction-summary.query` and `finance.spending-analysis.query` together. Single-member installation is rejected before build. No write tools are installed.

A method's declared capability subset is different from an installation unit. A summary-only private method may declare only `finance.transaction-summary.query`. Trial and preview preparation expand that to the smallest legal installation set; the method manifest still declares only summary. This does not grant scopes or enforce a separate per-Skill tool sandbox.

The current replacement granularity is an entire Domain Pack: any cashflow method replaces the default cashflow Domain Pack, while retaining unrelated wealth methods. A summary-only replacement therefore does not automatically preserve the default analysis SOP; the analysis tool remains installed and governed. The UI and documentation must not imply the method uses both tools. Exact frozen method content and scope are verified during build.

## Source Semantics

The summary source uses UTC statement months, up to 100 returned accounts, and does not explicitly filter BOOKED status. The analysis source filters BOOKED/CNY and uses source SQL month formatting without an explicit business timezone. Neither interface establishes complete history coverage. Results retain these gaps, even for source-reported zero values or future periods. Monthly values are not evidence of comparable trends. No synthetic ratios, forecasts, FX conversion, or duplicate wealth/credit-card addition is performed.

Known Chinese classification labels are a display-only mapping from simulator `V130__spending_analysis_capability.sql`, `spending-category-v1`: DAILY_LIVING (daily consumption), INCOME (income), UTILITIES (utility payments). Original codes and classification versions remain in source details; unknown codes or different taxonomy versions are not guessed or relabeled. No Core or financial fact is changed for display.

## Authorization Compatibility

Existing `FH_ACCESS_POLICY` and immutable old Agent artifacts remain unchanged. Cashflow-enabled instances additionally require an explicitly provisioned `FH_CASHFLOW_ACCESS_POLICY`; the trusted launcher passes this as that child's policy. It is not generated from a UI selection or required scopes. The local simulator launcher offers a separate explicit `--provision-cashflow-demo-policy` option for synthetic customer authorization. Existing policy files are never overwritten.

## Verification Status

- Harness: two new period/coverage regression assertions failed before the fix; focused Provider/cashflow Maven test passed afterward (17 Provider tests, zero failures).
- Builder method asset tests: 32 passed after separating method subsets from installation units. These tests alone do not establish actual method trial execution.
- First broad Python pass during implementation: 429 passed, four failures (three old inventory-count expectations and missing local npm dependencies). These were corrected. Latest Builder and Base run: 511 passed, two explicit skips.
- Harness reactor install: 193 tests, 186 passed, seven explicit live Smoke skips; zero failures/errors. Schema validation: ten schemas and 69 probes passed.
- Seven generated installation combinations and an actual summary-only private-method trial passed against controlled HTTP/model. Cashflow service passed 28 boundary checks, including in-flight revocation before another model request.
- Full synthetic simulator with controlled model: summary, analysis, balance and wealth passed, seven source business GETs and zero writes.
- Preliminary actual deepseek-v4-flash runs: mobile and ATM each completed summary/analysis turns; desktop completed summary/analysis/balance and selection of earlier source evidence. All owned acceptance instances were stopped. These checks do not establish real-bank authorization or expert model quality.
- Preserved failed attempts: an early browser runner received SIGTERM (cause not established); another incorrectly asserted the old cashflow gap after selecting a balance answer. The latter verifier was corrected to assert cashflow gaps on each cashflow turn and require balance not to replay old cashflow facts; corrected desktop run passed. No application behavior was weakened to satisfy the test.
- Frozen-commit reruns, deployment preservation and online verification: release-stage tasks, not claimed by the preliminary evidence above.
- Builder upstream acceptance and human independent onboarding: pending and not replaced by AI tests.

## Reproduction

From this module, prepare its documented Python/Node dependencies and JDK 21/Maven, with a fixed Harness checkout. Run `python -m pytest tests ../base/tests -q`, `npm run build:ui`, then `python engineering/verify_cashflow_assembly.py --harness <checkout> --output <new-evidence-directory>`. The latter builds and executes generated Java hosts; it is not a mock of Builder assembly.

Run `python engineering/verify_cashflow_service.py --help` for the generated service boundary entry. The seven-combination output contains a cashflow-only candidate in `combination-4/candidate`. Real model browser checks are explicit via `engineering/verify_cashflow_browser.py --help`; use a configured full synthetic simulator and keep secrets server-side. No real transfers are part of these commands.

New source integration primarily changes Provider mapping, cashflow domain contracts/methods and the finite Builder recipe plus result projection. Core and Runtime SDK were not edited. This batch does not establish arbitrary capability combinations or independently verified customer onboarding. No time-saving percentage is asserted.
