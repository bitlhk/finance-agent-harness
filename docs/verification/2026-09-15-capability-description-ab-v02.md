# Capability description A/B closure verification

## Scope and decision

- Baseline: `508121bd340a7a925200a0ef88087650c118d16a`
- Branch: `feat/capability-description-ab-v01`
- Runtime: OpenJiuwen Java 0.1.15
- Model: DeepSeek V4 Flash through the existing server-side connection
- Capabilities: balance, transaction summary, spending analysis, wealth facts and internal transfer
- Case labels: engineering provisional; business-owner review remains required

This iteration keeps Runtime-native tool selection. It does not add a classifier,
Router SPI, retrieval service, extra model decision or Builder integration. It was
not deployed.

## Corrections

1. Runtime errors no longer pass merely because no tool was selected.
2. Scoring distinguishes correct call, correct clarification, correct non-call,
   wrong call, wrong response, run failure and a turn that did not run.
3. Every turn records tool name and arguments. Declared arguments are checked and
   unexpected TASK selection is checked across all turns.
4. QUERY tools return deterministic synthetic results through an in-memory
   Provider so multi-turn follow-up can use actual prior tool output. TASK tools
   are force-finished at the Runtime boundary. Bank transport remains zero.
5. Final ToolCard descriptions and the complete terminology prompt have separate
   digests. Output boundaries state what adjacent capability must not substitute.
6. Selecting one capability from a multi-capability Pack projects the description
   asset to the installed set while still rejecting another Pack or version.
7. Run completion is recorded independently from semantic selection, so a wrong
   call that also reaches the Runtime iteration limit reports both facts.
8. Controlled cashflow results echo the requested period. The test Host applies an
   explicit date rule: absolute year-month, trusted relative dates, otherwise
   clarify a month without a year.
9. Report-only metrics record final description and terminology character counts
   without changing the production catalog Snapshot contract.

## Results

| Evidence | A | B | Paired result |
| --- | ---: | ---: | --- |
| Final fixed 75 cases, one execution | 68/75 | 74/75 | 67 both pass, 7 improved, 1 regressed |
| Final adjacent matrix and new phrasings | 15/20 | 20/20 | 15 both pass, 5 improved, 0 regressed |
| Date and follow-up focus, three repetitions | 11/12 | 11/12 | 10 both pass, 1 improved, 1 regressed |

In the final fixed run, B passed all explicit queries, missing-parameter TASK,
consultation, uninstalled-capability and unsupported cases. It made zero unexpected
tool or TASK selection, and had zero model/runtime failure or incomplete run. Its
only failure was `ab-060`: in the first turn it inferred 2026 for "August" instead
of clarifying. A passed that case in the paired run, so this is a recorded
regression rather than hidden by the aggregate score.

The date-focused run repeated `ab-008`, `ab-014`, `ab-060` and `ab-071` three
times per variant. Both variants passed all six single-turn month-only executions
and all three multi-turn date completions. Each variant missed one `ab-071` balance
call by asking for an optional account identifier. This request's desired
interaction remains subject to business-owner review.

The final adjacent suite covers balance/wealth and summary/spending with both
installed, only the target installed, only the neighboring capability installed,
and neither installed. B passed all 16 matrix cases and four new engineering
phrasings. The new phrasings were written after the descriptions were frozen and
before their first run, but are not a statistically independent or business-owner
accepted set. An earlier three-repeat run with pre-closure examples scored B at
60/60 and recorded two A incomplete runs in addition to its selection errors.

For the fixed run, B added 1,455.3 description/terminology characters on average
(2,045.2 versus 589.9). Mean fresh-install wall time was 4,290.3 ms for B and
4,698.8 ms for A. Adjacent and date-focused runs observed -1.2% and +2.4%
respectively, so no latency improvement is claimed. Character counts are not token
usage or monetary cost.

## Offline regression

| Check | Result |
| --- | --- |
| Maven reactor verify | BUILD SUCCESS |
| JUnit | 294 total: 286 passed, 8 explicitly skipped, 0 failed/error |
| Contracts and assets | 22 schemas and 104 fixture/asset/state probes passed |
| Summary reproducibility | Rebuilt `full-fixed-summary.json` matched byte-for-byte |

The eight skips are the existing seven real-environment Smoke tests and the
explicit opt-in live A/B test. Live evidence was run separately with the configured
model connection as reported above.

## Evidence integrity

| File | SHA-256 |
| --- | --- |
| `full-final.json` | `bea0d517caa2899c31e36fd5c9667d4bd1f5d5b6b9408bde16535670d6106f8b` |
| `full-final-summary.json` | `b8eb913ad1e8713e8a993cc2f852b3c8b827d0e336982d02aa9ef22ef66402cf` |
| `adjacent-final.json` | `d0da2a0a65c948401c87aa62639e8ff9a2ed36a6369f97b8c9e6dd2a8200adfa` |
| `adjacent-final-summary.json` | `9c34c9610f07fb7db5a0bfd4f6927c66f0560e277ff2e8c1b85a635227850817` |
| `date-followup-repeat.json` | `d09fad5a8ee9c8b5186eb05d0a79a0cbf163b18fa4d27f8a61a678776583ca1d` |
| `date-followup-repeat-summary.json` | `429a3168b269dde4d913bbb9cdd138635613d5410979491cd6983e5b81213b36` |

Raw evidence is retained outside Git under
`/home/ubuntu/outputs/capability-description-ab-v01/closure/`. It contains model
responses from synthetic prompts and no customer data or bank calls.

## Unresolved acceptance items

- A business owner must accept the date rule and review consultation,
  missing-parameter, multi-turn and `ab-071` interaction labels.
- One of four observed `ab-060` B executions still inferred a year, so stable
  adherence to month-only clarification is not established.
- Full natural-language quality, token cost, trusted task state and confirmation
  are outside this experiment.
- B is not a Builder default, is not deployed, and C has not started.
