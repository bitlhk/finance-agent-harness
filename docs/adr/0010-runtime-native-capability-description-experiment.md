# ADR-0010: Runtime-native capability description experiment

Status: Candidate V0.1, evaluated but not enabled by default

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

## Evaluation outcome

On 2026-09-15, one balanced-order run with DeepSeek V4 Flash and OpenJiuwen Java
0.1.15 scored A at 64/75 and B at 68/75. Paired outcomes were 8 improvements,
4 regressions, 60 both passing and 3 both failing. Neither variant attempted an
unexpected transfer, and the tool boundary prevented all Provider operations.

The regressions include two cases where an unavailable requested capability led B
to select another installed capability. B therefore remains opt-in and is not a
production default. The run used synthetic cases, one stochastic attempt per
variant and no natural-language clarification scoring; it does not justify a Router
SPI or production rollout.
