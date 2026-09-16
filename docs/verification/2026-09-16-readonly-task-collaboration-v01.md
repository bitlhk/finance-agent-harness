# Read-only task collaboration V0.1, 2026-09-16

## Status and baseline

- Harness baseline: `a69b43143473730e2f3621453b6966a8e19096a1` (PR #25).
- Experiment branch: `feat/readonly-task-collaboration-v01`.
- Mobile-banking source reviewed: `wuxiaojingjing/mobile-banking-3.0@1e02b685bd6c11a9144690b4bc2d2426205bd7af`.
- Scope: read-only cash-flow summary plus spending analysis. No Router SPI,
  planner, workflow engine, Builder business branch, bank network request, or
  production default is introduced.

The contract and implementation are candidates. Engineering acceptance does not
replace business Owner review of dates, account selection, or expected answers.

## What was reused

The upstream mobile-banking workspace keeps business differences in capability
metadata rather than Java query branches (`agents/mobile-banking-assistant/AGENTS.md:11-25`),
describes capabilities with WHAT/WHEN and negative boundaries (`:62-93`), and
distinguishes multiple goals from ambiguous alternatives
(`framework/intent-engine/src/main/resources/prompts/framework.defaults.yaml:48-81`).

This experiment borrows those concepts only:

| Source concept | Harness adaptation | Deliberately not imported |
| --- | --- | --- |
| Use cases, example utterances, output/negative boundaries | Existing `CapabilityDescriptionCatalogV01` assets | Upstream catalog classes and framework types |
| Multiple user goals are not the same as alternative candidates | One concise read-only collaboration instruction | Cardinality model, decomposition, planner, BM25/vector recall |
| Unknown or ambiguous target must be clarified | Existing Runtime response plus evaluator checks | First-candidate fallback or automatic execution |

No upstream source file was copied. No root license file was found at the reviewed
commit, so the SHA and conceptual provenance are recorded and ownership remains
with the upstream repository.

## Implemented slice

1. `TrustedBusinessTimeV01` carries only Host date, timezone, and source. It has
   no identity, scope, or authorization meaning.
2. `ReadOnlyTaskCollaborationV01` asks the native Runtime loop to cover each user
   goal with evidence or an explicit clarification/missing/unsupported result.
   It does not produce a plan or authorize a call, and it is installed only when
   the selected catalog contains QUERY capabilities exclusively.
3. Enhanced compiled descriptions replace the base ToolCard description in the
   generic prepared installation plan. Terminology and collaboration prompts are
   frozen in `capabilityPrompts`; their catalog and instruction digests are also
   recorded.
4. The Java installer uses the same optional assets. The Java reference Host and
   Python generated Host accept the same all-or-nothing business-time inputs:
   `FH_BUSINESS_DATE`, `FH_BUSINESS_TIME_ZONE`, and
   `FH_BUSINESS_DATE_SOURCE`. Missing context requires clarification.
5. The evaluator returns deterministic in-memory results whose month follows the
   request. Expected goals stay in the scorer and are never injected into Runtime.

## Source-located GAP table

| GAP | Before | Implemented / remaining boundary |
| --- | --- | --- |
| Trusted date existed only in the Java evaluator | `CapabilityDescriptionAbLiveTest` previously embedded `2026-09-15`; the reference Host had no input | Core value and Java/Python Host inputs added. Existing generated Agents remain unchanged until rebuilt. |
| Python registered base descriptions | Python template reads `plan.capabilities[*].definition.description` | Assembler now freezes compiled descriptions into that existing field; no capability-specific Python branch. A newly generated Python Candidate is still required for live acceptance. |
| Terminology did not reach Python | Template consumed only `capabilityPrompts` | Assembler adds the selected catalog terminology to the same list and records the catalog snapshot. |
| Compound request often stopped after one goal | Native loop had no generic completion reminder | Optional collaboration prompt added with B assets. It is advisory and scored against actual calls/results, not trusted as completion evidence. |
| Adjacent ability substitution | Description boundaries existed but partial-installation behavior was not scored | New cases require the available goal to run and the missing goal to be stated; no nearby tool may stand in for it. |
| Account aliases have no authoritative mapping | Provider accepts absent or canonical `accountId` only | Policy remains: canonical ID or all authorized accounts; aliases/ordinals clarify. No model-generated account mapping was added. |

## Runtime support matrix

| Item | OpenJiuwen Java 0.1.15 | OpenJiuwen Python 0.1.18 + Java bridge |
| --- | --- | --- |
| Native read-only tools | Verified existing implementation | Verified existing implementation |
| Enhanced ToolCard descriptions | Native installer and plan serialization covered by tests | Generic plan consumption implemented; regenerated Candidate live check pending |
| Terminology and collaboration prompt | Native installer covered by tests and live experiment | Generic `capabilityPrompts` consumption implemented; regenerated Candidate live check pending |
| Trusted date/time/source | Live evaluator plus reference Host wiring | Template wiring and unit tests passed; live model Candidate pending |
| Compound-task live result | See evidence below | Previous A-only baseline remains `7/8`; new B parity is not yet claimed |
| Authorization and Provider behavior | Unchanged | Unchanged; Python continues through the Java bridge |

## Controlled live evidence

The same `deepseek-v4-flash` connection, temperature, trusted time, controlled
data, and maximum eight Runtime iterations were used for A and B. Eleven cases
ran three times per variant (66 executions):

| Metric | A: base descriptions | B: enhanced descriptions + collaboration |
| --- | ---: | ---: |
| Passed | 21 / 33 | 26 / 33 |
| Failed | 12 | 7 |
| Runtime failures / incomplete | 0 / 0 | 0 / 0 |
| Unexpected calls on no-call cases | 3 | 0 |
| Total elapsed | 220.850 s | 222.463 s |

Paired outcomes contained six improvements and one regression. B fixed all three
unsupported-adjacent substitutions and completed three previously partial compound
runs. It regressed one month-only clarification wording. B still failed all three
multi-turn month-change cases, one explicit two-goal run, one relative two-goal
run, and one partial-installation response. The result is useful but not stable
enough for a default or a claim of general task completion.

Evidence:

- `java-task-collaboration-final.json` SHA-256
  `2021a52cf87231c568bd0da957168c533c12e2a3086826d65dfc05828e888b0f`.
- `java-task-collaboration-final.log` SHA-256
  `d26c33d8846815a8051e6efb4da50e53b9935659913ca334359c9ac8fc5ba330`.
- Local evidence directory:
  `/home/ubuntu/outputs/readonly-task-collaboration-v01/`.

The ten-case date/account policy suite was rerun once after the change. B passed
`10/10` with no unexpected tool call, Runtime failure, or incomplete run; A passed
`7/10`. This is a regression check, not a replacement for the earlier repeated
policy run or business Owner confirmation. The report SHA-256 is
`4f0f71a4239b626511652fb77421b197cda8c8ab8dcae5b979f94375275d9d14`.

All Providers were in-memory. `bankTransportOperations` is zero for every result.
No task capability was installed. Model quality and engineering behavior remain
separate from business Owner acceptance.

## Minimal next modification

1. Regenerate one Python cash-flow Candidate from the committed Harness and
   Builder template, then rerun this exact suite with the same model and Host time.
2. Have the cash-flow Owner review the provisional wording and the acceptable
   partial-completion response language.
3. Keep the collaboration instruction optional until repeated Java/Python results
   show no material regression. If omissions remain, first inspect tool result
   scope and Runtime completion behavior; do not add a planner by default.

Known limits: the evaluator checks observable tool calls, arguments, completion,
and answer evidence. It does not inspect hidden reasoning, prove all answer prose
correct, or establish production authorization. No non-author human acceptance
was performed.

## Verification commands

- `mvn -B -ntp verify`: 50 test classes, 296 tests; 288 passed, 8 explicit
  real-environment Smokes skipped, 0 failures/errors.
- `python engineering/verify_contracts.py`: 22 schemas and 104 probes passed
  (run in an isolated venv using the pinned requirements).
- `python engineering/verify_component_assembly.py`: 8 declarations, 6 packs,
  7 supported capabilities passed offline; Runtime installation and business
  authorization were intentionally not run by this command.
- Builder Python template targeted suite: 17 passed after using the repository's
  required `PYTHONPATH=.:src:../base/src`. The first invocation without that path
  produced six collection errors and is not reported as a product failure.
