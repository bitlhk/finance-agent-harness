# Capability description A/B V0.1 verification

## Fixed scope

- Harness baseline: `508121bd340a7a925200a0ef88087650c118d16a`
- Branch: `feat/capability-description-ab-v01`
- Runtime: OpenJiuwen Java 0.1.15
- Model: DeepSeek V4 Flash through the existing server-side model connection
- Capabilities: balance, transaction summary, spending analysis, wealth facts and internal transfer
- Upstream mobile review: `7988c4af399e4506705b1363723339d9f80fca67`

No upstream intent engine code was imported. No Builder or production deployment
was changed in this candidate.

## Implemented

1. Strict JSON schemas and versioned assets for use cases, example utterances,
   negative examples and institution terminology.
2. A Runtime-neutral compiled catalog containing only actually installed
   capabilities. Unsupported terms can be recognized without creating a tool.
3. An OpenJiuwen Java installation adapter that changes ToolCard descriptions only
   when explicitly selected. Institution terminology is installed once per Agent.
4. Assembler locking of source assets and the derived catalog digest. Installation
   checks the catalog and capability set before registering tools.
5. Eighty controlled cases: 75 paired live cases plus five deterministic model
   transport failures. Multi-turn scoring uses only the final turn while retaining
   the full tool-call sequence.

## Verification

| Check | Result |
| --- | --- |
| Maven reactor verify | 14 reactor projects, BUILD SUCCESS |
| JUnit | 290 total: 282 passed, 8 skipped, 0 failed/error |
| Explicit skips | 7 real-environment Smoke tests and 1 opt-in live A/B test |
| Contracts and assets | 22 schemas and 104 fixture/asset/state probes passed |
| Targeted integration tests | 26 total: 25 passed, 1 opt-in live test skipped |
| Controlled model failures | 5 model attempts, 0 Provider operations |
| Live A/B | 75 cases per variant, 150 model executions, BUILD SUCCESS |
| Bank/Provider calls during live A/B | 0 |

The final raw report SHA-256 is
`763644486eef1204441fdb98d3d354a4b889821c885d544db64bfa0c9c7cde74`.
It is retained outside Git because it is execution evidence rather than a source
asset.

## A/B result

| Metric | A: existing descriptions | B: enhanced descriptions |
| --- | ---: | ---: |
| Passed | 64/75 | 68/75 |
| Unexpected tool calls where none was expected | 7 | 3 |
| Unexpected transfer calls | 0 | 0 |
| Mean case wall time | 3135.4 ms | 3041.1 ms |
| Median case wall time | 2658 ms | 2745 ms |

Paired outcomes: 60 both passed, 8 improved, 4 regressed and 3 both failed.
The four B regressions were one wealth multi-turn continuation, two requests for an
uninstalled capability that incorrectly selected another installed tool, and one
balance request containing a cancellation constraint.

This is a technical pre-experiment, not a statistically powered business
acceptance set. Wall time includes fresh Agent installation. Token usage and model
cost were not exposed by this Runtime path. Missing-parameter task cases verify no
tool call but do not score clarification wording.

## Decision

B is useful as an opt-in candidate but is not the production default. Do not add a
Router SPI or independent retrieval service yet. First obtain business review of
the case labels, repeat the paired run, and remove the uninstalled-capability
regressions. Builder integration, UI testing, real bank authorization and
non-author independent validation remain separate acceptance items.
