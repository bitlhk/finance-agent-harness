# ADR-0010: Runtime-native capability description experiment

Status: Candidate V0.1, evaluation closure complete, not enabled by default

## Decision

For the current five capabilities, first improve the descriptions supplied to the
Runtime's native tool selection. Do not introduce an independent intent router,
retrieval service or planner.

Capability descriptions and institution terminology are separate versioned assets.
The assembler compiles them after package resolution, records their files and the
derived catalog digest, and verifies that the derived and installed capability sets
are identical. Existing strict Capability Pack V1 files are unchanged.

Terminology explains language only. It grants no scope and creates no capability.
Routing guidance never bypasses Host identity, current grants, task parameter
freezing, trusted confirmation, result lookup or recovery.

## Consequences

Builder integrations may select and pass these generic assets, but must not encode
balance, cashflow, wealth or transfer intent branches. Old generated Agents continue
to use their frozen assets. An independent routing contract or SPI is considered
only if the paired experiment shows stable errors that description improvements
cannot address.

## Initial evaluation outcome

On 2026-09-15, one balanced-order run with DeepSeek V4 Flash and OpenJiuwen Java
0.1.15 scored A at 64/75 and B at 68/75. Paired outcomes were 8 improvements,
4 regressions, 60 both passing and 3 both failing. Neither variant attempted an
unexpected transfer, and the tool boundary prevented all Provider operations.

The regressions include two cases where an unavailable requested capability led B
to select another installed capability. B therefore remains opt-in and is not a
production default. The run used synthetic cases, one stochastic attempt per
variant and no natural-language clarification scoring; it does not justify a Router
SPI or production rollout.

## Corrected evaluation outcome

The corrected evaluator executes deterministic QUERY results through the in-memory
Provider, keeps bank transport at zero, and records every turn's selection,
arguments, response and runtime failure separately. TASK selection is still
blocked before the Provider. It distinguishes correct calls, clarification,
non-calls, wrong calls, wrong responses and run failures.

With the final candidate descriptions, one fixed 75-case run scored A at 63/75
and B at 73/75. B had no regression in that single paired run and made no
unexpected tool or TASK selection. A 36-execution-per-variant repeated set for
complete transfers, missing transfer parameters and the two original adjacent
capability substitutions scored A at 22/36 and B at 36/36. A separate 20-case
engineering holdout scored A at 16/20 and B at 19/20, but B still substituted
spending analysis for an unavailable transaction-summary request once.

These results show useful improvement, especially for missing task parameters and
known unavailable capabilities, but not stable elimination of adjacent-capability
substitution. Case labels are still awaiting business-owner review; two fixed-set
failures also expose inconsistent expectations for a month without a year. B
therefore remains opt-in, Builder integration remains deferred, and no independent
Router SPI is introduced.

## Evaluation closure outcome

The product rule used by the test Host is now explicit: an absolute year-month is
used as supplied, relative dates such as "last month" are resolved from the trusted
business date, and a month without a year is clarified unless the conversation
already contains a trusted year. Capability examples follow the same rule. The
controlled cashflow Provider also returns the requested period instead of a fixed
month.

On the final code, the fixed 75-case run scored A at 68/75 and B at 74/75. B made
no unexpected tool or TASK selection, had no runtime failure or incomplete run,
but regressed once against A: one multi-turn month-only case inferred the current
year rather than clarifying. The same case passed all three repetitions in the
date-focused run, so model adherence remains stochastic rather than proven.

The final adjacent-capability matrix scored A at 15/20 and B at 20/20. It covers
balance versus wealth facts and transaction summary versus spending analysis with
both capabilities installed, only the requested capability, only its neighbor,
and neither. Four new engineering phrasings also passed B. An earlier three-repeat
run with the pre-closure wording scored B at 60/60; this remains supporting rather
than final-version evidence.

The fixed run observed 2,045.2 description and terminology characters for B versus
589.9 for A, an increase of 1,455.3 characters. Mean per-case wall time was 4,290.3
ms for B and 4,698.8 ms for A. These are character counts and fresh-install wall
times, not token usage, monetary cost, model-only latency or evidence that B is
faster. Full response quality and business-owner label acceptance remain outside
this experiment. B therefore remains opt-in; Builder integration and C remain
deferred.

## Date, account and compound-task closure

The provisional product rules are now explicit in the candidate assets and test
suite. An absolute year-month is used directly; a relative month is resolved only
from a trusted Host business date; a bare month is clarified. An omitted account
selector means all accounts currently authorized by the Provider. A canonical
account ID may narrow the query, while a natural-language alias or ordinal requires
a trusted Host mapping and is otherwise clarified. Capability metadata never
creates that mapping or authority.

In a three-repeat, ten-case Java policy run, B passed 28/30 automated checks. The
two failures were correct clarifications expressed as `账户ID` or `账户标识`, which the
initial lexical evidence list did not accept. After correcting that scorer, the
affected alias case passed B in 5/5 repeated runs; A invoked the balance tool in
5/5. This is engineering evidence, not business-owner acceptance.

The read-only compound baseline used the existing Java Runtime, Harness installer,
deterministic Provider and the Java reference Host limit of eight iterations. Across
eight cases repeated three times, A passed 16/24 and B passed 15/24. B improved one
pair, regressed two and failed with A in seven. Most failures completed after calling
only one requested tool; none were Runtime failures, incomplete runs, TASK calls or
bank transports. B therefore has no demonstrated compound-task advantage.

A separately frozen OpenJiuwen Python 0.1.18 Candidate with the delegated Java
financial bridge completed 7/8 compound cases in one live-model run; the relative
date case made no tool call because that Host has no trusted business-date input.
This single run establishes feasibility, not repeat stability, and it used the
Candidate's original descriptions rather than B. No planner, Router SPI or Builder
business branch is introduced. The next changes, if approved, are limited to a
runtime-neutral trusted business-date/context input and generic propagation of the
already frozen description catalog into the Python installation plan.
