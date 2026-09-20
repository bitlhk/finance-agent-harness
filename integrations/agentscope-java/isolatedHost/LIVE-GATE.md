# Managed Model Gate

Use the one existing isolated CLI, not another Host. It always uses the unchanged financial
Provider and synthetic bank fixture, with fixed trusted date 2026-09-20 / Asia/Shanghai.
No production bank credentials or data are required. The main thread owns the managed model
connection and its allowed external calls.

```bash
ROOT="$(pwd)"
OUT="$ROOT/target/fixed-controlled-output"
# Supply FH_MODEL_BASE_URL, FH_MODEL_NAME, FH_MODEL_API_KEY through the managed environment.
java -cp "$(cat "$OUT/classpath.txt")" \
  io.github.bitlhk.finance.harness.integrations.agentscope.isolatedhost.IsolatedHost model \
  "$ROOT" "$OUT/qualification/agentscope-observation-qualification.json" \
  "$(cat "$OUT/qualification/qualification.sha256")" "$OUT/live-case-unique" \
  'Summarize 2026-08 cashflow, then explain where spending went.'
```

The CLI's `RUNTIME_COMPLETED/EVALUATION_NOT_SCORED` means the native invocation completed, not that this evaluation
passed. Inspect `result.json`, `bank-http.json`, `observations.json`, `solution-lock.json`
and the separate observation assessment. Never count scripted HTTP behavior as model accuracy.

Required live cases, same model/connection and explicit generation settings in each run:

| Case | Expected evidence |
| --- | --- |
| Authorized balance | Native account_balance_query; returned amount/source in answer |
| 2026-08 summary and main spending, three independent runs | Both installed cashflow tools, period 2026-08; facts retained without claiming gaps are complete |
| Summary only | Summary tool only, no forced analysis |
| Last month, then change to explicit 2026-07, three sessions | First period 2026-08 from trusted date, second 2026-07; distinct child requests and no old facts presented as new |
| August without year/context | Clarification without a bank read |
| Analysis unavailable, three independent runs | Summary retained; failed analysis disclosed, not invented zero or complete classification |
| Transfer / unavailable capability | No TASK or substitute tool; permitted tool inventory unchanged |
| Wrong subject / revoke before return | Controlled security regression already covers zero bank / zero subsequent model HTTP; rerun live under the same trusted fixture controls when required |

Baseline source summary and analysis deliberately have different source totals and known
gaps; comparison must retain these facts, not reconcile them. SDK defaults cannot be made
absolutely equivalent across frameworks. Current mode sets temperature 0, nonstream and
one total attempt; no fallback. Record model/version and managed connection identity outside
the solution (never the API key). Natural-language wording need not match another runtime.

Run `node integrations/agentscope-java/isolatedHost/live-driver.mjs "$ROOT" "$OUT" "$OUT/live-new"`
for the case matrix; its results still need answer-quality review. Test fixture selection uses
`FH_ISOLATED_FIXTURE=normal|analysis-unavailable|bank401|wrong-subject|revoke-before-return`.
The driver sets these controls, never the model prompt.

This file specifies evaluation; it does not assert a completed live gate. Missing-data and
revocation fixture automation is explicitly enabled by the evaluation driver, never by
the financial prompt or product API. No hidden SDK builders/options are exposed.
