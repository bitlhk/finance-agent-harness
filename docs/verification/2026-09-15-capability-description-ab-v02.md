# Capability description A/B corrected verification

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

## Results

| Evidence | A | B | Paired result |
| --- | ---: | ---: | --- |
| Fixed 75 cases, one execution | 63/75 | 73/75 | 63 both pass, 10 improved, 0 regressed, 2 both fail |
| TASK and substitution focus, three repetitions | 22/36 | 36/36 | 22 both pass, 14 improved, 0 regressed |
| Engineering holdout, 20 cases | 16/20 | 19/20 | 16 both pass, 3 improved, 0 regressed, 1 both fail |

In the fixed run, B passed all missing-parameter TASK, multi-turn, consultation,
uninstalled-capability and unsupported cases. It made zero unexpected tool calls,
zero unexpected transfer selections and had zero model/runtime failures. Its two
failures asked for a missing year while the current labels require immediate
execution.

The repeated focus set covered five complete transfers, five missing-parameter
transfers and the two original balance/wealth substitutions, each three times.
B passed all 36 executions and never reached the TASK Provider.

The holdout is an engineering set created after the initial experiment, not a
business-confirmed or statistically independent acceptance set. B still selected
spending analysis once when transaction summary was requested but not installed.
This is an adjacent-capability substitution and prevents enabling B by default.

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
| `full-fixed.json` | `268536915ee2f89d9da7b771cc720f155a6f9e6a315069bbd0da8573e64257f6` |
| `full-fixed-summary.json` | `15dfb2020e4dd13346c152eb1458aff4b169ac5406ae805dbf427df780b1297f` |
| `task-substitution-final-repeat.json` | `f6148a51678d46f39a4101a8b552fe813c32e31d9ea2e97bc20bb7e28e3dfab1` |
| `task-substitution-final-repeat-summary.json` | `26ef544be0064b7b86176f615a514e13e4a5636c8cf2fe69b5826ffde749ccd4` |
| `held-out-final.json` | `e838a4197e0148bd749fd59ae6ac4376a37616ee843ff53f7541c691fa6d985b` |
| `held-out-final-summary.json` | `2d1d83eb6046d1efe563cf4135ce069c2d312ba48e8df52755396afaed487383` |

Raw evidence is retained outside Git under
`/home/ubuntu/outputs/capability-description-ab-v01/corrected/`. It contains model
responses from synthetic prompts and no customer data or bank calls.

## Unresolved acceptance items

- A business owner must resolve inconsistent year/month expectations and review
  consultation, missing-parameter and multi-turn labels.
- Adjacent-capability substitution still occurs in the engineering holdout.
- Full natural-language quality, token cost, trusted task state and confirmation
  are outside this experiment.
- B is not a Builder default, is not deployed, and C has not started.
