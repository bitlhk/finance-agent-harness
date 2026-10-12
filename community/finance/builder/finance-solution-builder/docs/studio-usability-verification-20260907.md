# Studio Usability Verification, 2026-09-07

Implementation: `8fa47772e7428da5ceab671db0c1d4e0d1e42377`, on
`feat/workbench-model-selection-ui-v02` in the existing Builder worktree.
Harness: `b55d0722cac2905388f57dc4a17b85d346a0e470`.
OpenJiuwen Java remains `0.1.13`; no Runtime source or Harness changes.

## Outcomes

| Check | Observed result | Boundary |
| --- | --- | --- |
| Builder pytest | 158 passed | Python 3.12.3 on this Linux host; no new remote CI run |
| Delete regressions | 11 new tests initially failed at missing endpoint, then passed | Ownership, lifecycle/process, path, cancellation/deletion scope and reload |
| Legacy connection migration/readiness | 5 passed | Exact identity, no key persistence, changed snapshot requires rebuild, disabled registry not overwritten |
| Three generated capability compositions | 58 controlled checks passed | Actual Java hosts with separate synthetic bank and model HTTP endpoints |
| Controlled HTTP model destinations | A=8, B=11, unselected C=0 | Includes revocation before next model dispatch; not paid inference |
| Browser workflow | 45 checks passed | Real builds/API, inline choices, bank modal, preserved DOM nodes, deletion, mobile widths |
| Final clean-commit browser rerun | 45 passed; 2 real builds | UI-only polish included; A=2/B=2/C=0; does not repeat all three CLI compositions |
| Harness builds invoked by Builder | 13 modules successful; 172 tests, 165 passed, 7 skipped | Explicit live Smoke skips, not real bank validation |
| Harness Schema/probes | 9 Schema, 55 probes passed | Executed by the actual build stage |
| Real configured DeepSeek session | Two turns completed with actual tools/source GETs | Synthetic local bank, no transfers or customer migration |

`git diff --check` and JavaScript syntax checks passed. The full controlled run
preceded a CSS-only final polish. The clean-commit browser and real-model builds
used the final implementation commit, with clean Builder and Harness sources.

## Real Model Observations, Not Quality Certification

The configured model is `deepseek-v4-flash`, bound explicitly to `model-ea-002`.
The other eleven EA catalog entries remain unbound; there is no fallback mapping.
The existing server-only model configuration was reused without changing EA.

An initial attempt successfully started and received a model response, but its
first turn had **zero tool calls**. That attempt is not counted as a financial
query pass. Its event/count evidence remains under build
`agent-bdc0ed53ae254882`. No model or prompt was changed between attempts.

A second fresh build, `agent-5420444bd6e843bb`, completed a continuous session:

| Turn | Native tool | Observed source reads | Runtime elapsed |
| --- | --- | --- | --- |
| Balance query | `account_balance_query` | 1 GET | 10,835 ms |
| Wealth description | `wealth_facts_describe` | 2 GETs | 11,073 ms |

Both outputs carried the frozen model version, runtime lock, source-call records
and Harness receipts. The monetary values and missing-source states are available
for comparison with the synthetic source result. The wealth result is explicitly
partial, with suitability not evaluated. This is a connectivity/tool-path smoke,
not a claim of deterministic tool selection, expert-approved wording, production
authorization or overall model quality. The model's textual interpretation of
risk labels and internal identifiers is not certified by this smoke.

The first script invocation preceded HTTP readiness and failed to connect; that
startup attempt is retained separately. The subsequent build/start operation was
not marked successful until the actual API/health checks completed. Test-created
Agent processes were stopped at the end; no live test Agent is left running.

## Local Deployment

- Studio: `http://127.0.0.1:18138`, unit `finance-workbench-model-ui-v02.service`.
- Its isolated, in-memory synthetic bank: loopback port `18139`.
- Simulator JAR SHA256: `e40f43df54b4f54b86cf552554c3e4cd65728073a6fa71cc71cab2108681807e`.
- New environment has its own explicit synthetic policy, expiring
  `2026-09-14T00:58:00Z`; restarts do not refresh/re-enable it.
- Old `finance-workbench-pr8-verified.service` is stopped/disabled; `18128` and
  its child bank `18129` have no listeners. Historical code/artifacts are retained.
- No listener on the older `18118`. Unrelated application services were untouched.

An old metadata-only build is intentionally not modified in place. Choose the
bound DeepSeek model and rebuild to freeze its configuration, then start it.
Restart with `systemctl --user restart finance-workbench-model-ui-v02.service`;
stop with the corresponding `stop` command. Service secrets are outside Git and
artifacts, in the operator-managed configuration/environment.

## Local Evidence

Paths are relative to this Builder project and are ignored generated outputs:

- `build/studio-ux-20260907/report.json`: all three controlled compositions.
- `build/studio-ux-20260907/browser/report.json`: first 45-check browser run.
- `build/studio-ux-final-20260907/browser/report.json`: clean-commit browser rerun.
- `build/studio-live-20260907/`: initial readiness/tool-selection attempts.
- `build/studio-live-20260907-attempt2/report.json`: actual two-turn model/source evidence.
- The browser runs include desktop and 320/390px screenshots. Deletion receipts
  demonstrate generated directories removed while other builds remain.

No Builder-upstream push/acceptance, production rollout, independent human
onboarding or AG-UI implementation is claimed. The local commits and tests belong
to the existing scenario Builder, not a new fork or platform.
