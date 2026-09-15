# ADR-0010: Runtime-native capability description experiment

Status: Candidate V0.1, corrected evaluation complete, not enabled by default

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
