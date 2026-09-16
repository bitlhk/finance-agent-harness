# Read-only compound-task baseline, 2026-09-16

Status: engineering candidate evidence. No production rollout, Router SPI, planner,
or Builder business branch is approved by this report.

## Fixed baselines

- Harness source before this closure: `7053e962da20b64cece14d04d77b997ef18d10ca`.
- Builder source inspected for the Python Host: `9f57be5d24ecc8701f0501fd5242a1260662d11b`.
- Java Runtime: OpenJiuwen Java `0.1.15`; model: `deepseek-v4-flash`.
- Python probe: frozen Candidate `o1-qualifying-python-7`, OpenJiuwen Python
  `0.1.18`, delegated Java financial bridge, original A descriptions.
- All values and identities were synthetic. Java used an in-memory Provider with
  bank transport fixed at zero. Python used controlled bank HTTP and recorded zero
  writes.

The Java evaluator accepts a suite, repetition count and iteration limit in
`CapabilityDescriptionAbLiveTest.java:117-161`. Compound scoring at lines 418-442
requires the exact capability multiset, arguments and response evidence; it does
not treat a partial call as success.

## Product rules closed in B

| Topic | V0.1 rule | Evidence and ownership |
| --- | --- | --- |
| Explicit month | `YYYY-MM` is used as supplied. | Cashflow description asset and deterministic argument checks. |
| Relative month | Resolve only from a trusted Host business date. | The evaluator injects `2026-09-15`; the public Java reference Host currently does not. |
| Bare month | Clarify the year unless trusted conversation state supplies it. | Four date-policy cases, three repetitions each, passed A and B. |
| No account selector | Omit `accountId`; query all currently authorized accounts. | `NonAiBankingProvider.java:163-183` selects the list route only when the selector is absent. |
| Canonical account ID | Pass the explicit ID and let the Provider verify the returned account. | The same Provider checks returned account equality at lines 170-176. |
| Alias or ordinal | Use only a trusted Host mapping; otherwise clarify. Never guess. | B alias scorer passed 5/5 after lexical evidence correction; A called the tool 5/5. |

These are parameter-selection rules. They do not grant scopes or prove account
ownership. The Provider still checks the source session and actual grants.

## Actual results

### Java policy suite

The full run executed 10 cases x A/B x 3 repetitions. B passed 28/30. The two
automated failures contained correct clarification but used `账户ID` or `账户标识`, not
one of the initial accepted phrases. The corrected targeted run then passed B 5/5
and made zero Provider calls; A called the balance Provider 5/5.

| Measure | A | B |
| --- | ---: | ---: |
| Full run passed | 21/30 | 28/30 |
| Date policy | 12/12 | 12/12 |
| Unexpected tool calls | 9 | 0 |
| Runtime failures | 0 | 0 |

### Java compound suite

The run executed 8 cases x A/B x 3 repetitions with `maxIterations=8`.

| Measure | A | B |
| --- | ---: | ---: |
| Passed | 16/24 | 15/24 |
| Improved / regressed pairs | 1 / 2 | same paired set |
| Runtime failures / incomplete runs | 0 / 0 | 0 / 0 |
| Controlled Provider operations | 51 | 54 |
| Bank transport operations | 0 | 0 |

Failures were predominantly completed responses that called only one requested
capability. B did not improve compound execution and caused more controlled reads.
This is evidence against enabling B in Builder by default; it is not evidence that
a planner is now required.

### Python compound probe

The frozen Python Candidate installed all four read-only capabilities and, in one
live-model run, passed 7/8 exact-call cases. The relative-month case returned an
answer without a tool call because the Host prompt has no trusted business date.
The run performed 21 synthetic bank reads and zero writes. It used A descriptions,
`max_iterations=5`, and was not repeated; stability and B propagation remain
unverified.

## Source-located GAP table

| GAP | Source evidence | What it proves | Minimal response |
| --- | --- | --- | --- |
| Trusted date exists only in the Java test Host | `CapabilityDescriptionAbLiveTest.java:325-334`; `RetailBankingAgentApplication.java:87-100` has no date input. | Evaluator relative-date success cannot be generalized to generated Hosts. | Add one trusted immutable Host context value and render it into the prompt; never infer it from model text. |
| Trusted account alias mapping is absent | `account-balance-readonly.v0.1.json:8-12`; `NonAiBankingProvider.java:163-183` accepts absent or canonical `accountId`. | Provider behavior is bounded, but aliases cannot be resolved today. | Keep clarification by default. Add a Host resolver only when a bank supplies an authorized mapping. |
| Java compound selection is stochastic | Exact scorer at `CapabilityDescriptionAbLiveTest.java:418-442`; B result 15/24. | Runtime can issue multiple calls, but B does not make them reliable. | Keep native selection and repeat regression coverage; do not add a planner without a separate benefit experiment. |
| Python does not consume B catalog assets | Java attachment is `OpenJiuwenSolutionAssembler.java:180-200`; Python template registers prepared `definition['description']` at `templates/python-readonly-service/finance_python/agent.py:71-87,121-127`. | Python supports the tools but not the B text or terminology snapshot. | Put frozen compiled descriptions and terminology in the generic prepared plan; consume them without capability branches. |
| Python lacks trusted date and uses another iteration limit | Python template `agent.py:80-90` uses method prompts and `max_iterations=5`. | Java and Python baseline results are not directly equivalent. | Make Host context and iteration policy explicit and recorded, then rerun the same suite. |
| Python evidence is one frozen-Candidate run | Candidate `PYTHON-README.md` and this probe. | Feasibility, not repeated quality or current-source reproducibility. | Generate a fresh Candidate after generic propagation and repeat the exact-call suite. |

## Java / Python support matrix

| Concern | Java 0.1.15 | Python 0.1.18 + Java bridge |
| --- | --- | --- |
| Four read-only tools installed | Implemented and exercised | Implemented and exercised |
| Compound calls in one user turn | 16/24 A, 15/24 B over three repeats | 7/8 in one A-only run |
| Exact duplicate/missing-call detection | Repository evaluator | External probe only; not yet a repository test |
| B enhanced descriptions | Frozen and digestible | Not propagated into frozen Candidate |
| Institution terminology | Java prompt section | B terminology snapshot not propagated |
| Trusted business date | Test Host only; reference Host gap | Gap |
| Account alias mapping | Clarify unless Host maps | Clarify unless Host maps |
| Provider authorization | Existing Bridge and Provider checks | Delegated to the same Java Bridge checks |
| TASK capability | Existing confirmed/checkpointed Java path, outside this baseline | Explicitly unsupported by read-only Python plan |

## Minimal modification plan

1. **No production routing change now.** Keep B opt-in, PR draft, and Builder unchanged.
2. **Host context contract:** add a small runtime-neutral trusted business-date
   value; bind it from Host configuration/session evidence and freeze it per request.
3. **Optional account resolver:** add a Host-side canonical selector result only
   when a source system supplies an authorized mapping. Clarify otherwise.
4. **Generic Python propagation:** place compiled capability descriptions,
   terminology prompt and digests into the existing prepared installation plan;
   register them generically in Python. No balance/cashflow/wealth branches.
5. **Repeat before adoption:** generate fresh Java and Python Candidates and run the
   same policy and compound suites. A Router or planner needs its own A/B/C evidence
   and is not implied by these failures.

## Evidence files

Raw local evidence is retained under
`/home/ubuntu/outputs/capability-description-ab-v01/compound/`:

| File | SHA-256 |
| --- | --- |
| `routing-policy-final.json` | `524871354768c39da30e143519b893a1a4014eae0c63e00a4e54886fa25d0748` |
| `routing-policy-final-summary.json` | `a5387810e5932744a1805e6bd918ca4872faeb100b758396cfce9d15c7d63054` |
| `account-alias-targeted.json` | `4e9cd99bc096364ed7e5c651b9c2aed2e6ea208f5770c21afced3587f2f3c3b3` |
| `readonly-compound-repeat.json` | `ffd005d3aed8f7ecf3d5dcee275fd1ec9f6fb4aeaa814b6ab50d8e53c35cf501` |
| `readonly-compound-summary.json` | `9cfa1add9b2c05251e30395d3adee9fe35003995b2bfdef2e091d789250a1b8f` |
| `python-compound-baseline.json` | `a3b0bd391ca0283c3a25cdbfcf56e311afb08229bde4c905efb5b07298dd6459` |

Model credentials, private configuration and customer text are not stored in the
repository. No business-owner or independent-human acceptance was performed.
