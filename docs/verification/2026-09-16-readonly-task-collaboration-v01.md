# Read-only task collaboration V0.1, 2026-09-16

## Result and fixed baseline

- Harness: `d957f178abd56d5a2907eb20b62d8f2bc54f2b16`.
- Builder: `004e9d4a2385eed7d2ff5b8c3ea10294561720c0`.
- Runtime: OpenJiuwen Java `0.1.15` and Python `0.1.18` with the existing Java finance bridge.
- Model acceptance: configured `deepseek-v4-flash`; synthetic local bank only.
- Scope: transaction summary plus spending analysis. No Router SPI, planner, workflow engine,
  Builder business branch, real bank call, or production-wide default was introduced.

The result supports a limited conclusion: enhanced descriptions and the native Runtime loop can
complete the tested read-only multi-goal task, reuse valid evidence, and refresh invalidated evidence.
It does not prove general task planning or business answer quality. Business Owner review and
independent human onboarding remain pending.

## Policy closed in this iteration

1. Absolute `YYYY-MM` is accepted directly.
2. Relative dates such as "last month" are resolved only from Host-supplied business date, timezone,
   and source. The value has no identity or authorization meaning
   (`packages/java/core/.../TrustedBusinessTimeV01.java:7-13`).
3. A month without a year is clarified when the conversation has no explicit year context. A prior
   explicit month may be reused in the same conversation; the isolated no-context case is tested in
   a new session (`ReadOnlyTaskCollaborationV01.java:20-23`).
4. Missing account selection means all accounts already authorized by the source contract. A
   canonical account ID is accepted; aliases and ordinals require an authoritative mapping or
   clarification. The model cannot invent an account mapping.
5. Task completion is goal-to-evidence based. Two goals do not always require two new calls: an
   existing, still-valid result may satisfy one goal. A changed month invalidates both old facts and
   requires new evidence for both goals (`ReadOnlyTaskCollaborationV01.java:12-15`).

## What was reused

The reviewed mobile-banking source was
`wuxiaojingjing/mobile-banking-3.0@1e02b685bd6c11a9144690b4bc2d2426205bd7af`.
Its WHAT/WHEN descriptions, negative boundaries, and distinction between multiple goals and
alternative candidates informed this experiment. No upstream source file or framework type was
copied. BM25/vector recall, planner, cardinality model, and first-candidate fallback were not imported.

## Source-located GAP table

| GAP | Implemented evidence | Remaining boundary |
| --- | --- | --- |
| Trusted business date differed by Runtime | Core value plus strict Host inputs; Workbench derives `Asia/Shanghai` date unless all three explicit test fields are supplied (`Builder workbench.py:55-79`) | Existing generated Agents do not change until rebuilt; real bank business-date policy is not validated |
| Python did not receive B descriptions | Builder discovers routing assets from selected packs (`component_plan.py:57-87`), copies and freezes them (`service.py:225-238`), and Python consumes the same descriptions/prompts (`finance_python/agent.py:77-88,123-126`) | Only the current managed Python Host is verified; this is not a statement about every Python host |
| Compound calls were undercounted | Java evaluator now separates callback observations from Provider-authoritative executions (`CapabilityDescriptionAbLiveTest.java:198-205,213-225,243-260`) | SDK callback coverage remains diagnostic and cannot prove network dispatch |
| Tool success was confused with request completion | Thin read-only instruction requires evidence or explicit clarification/missing/unsupported status (`ReadOnlyTaskCollaborationV01.java:9-16`) | It remains model guidance, not an authorization source or general planner |
| Adjacent capability substitution | Catalog renders explicit output/negative boundaries and known-unsupported terminology (`CapabilityDescriptionCatalogV01.java:97-123,141-153,172-185`) | Builder currently selects the cash-flow pack atomically; arbitrary partial-pack installation is not a page feature |
| Directory could drift from installation | Catalog requires exact installed IDs (`CapabilityDescriptionCatalogV01.java:156-159`) and final Lock freezes description, terminology, and collaboration digests | Catalog updates do not upgrade old Agents automatically |
| Candidate dependency checks saw Builder packages | Generated Python build and portable installer now remove Builder interpreter path variables while retaining network settings (`Builder python_service.py:22-27,108-125`) | Wheel resolution remains build-platform specific and must be rerun for another target platform |

## Java / Python support matrix

| Item | OpenJiuwen Java 0.1.15 | OpenJiuwen Python 0.1.18 + Java bridge |
| --- | --- | --- |
| Enhanced Tool descriptions | Verified by installer tests and live A/B evaluator | Verified in generated installation plan and live browser Candidate |
| Terminology and collaboration prompt | Verified; digests recorded | Verified; same digests recorded in Lock/plan |
| Trusted date/time/source | Verified in evaluator and reference Host | Verified from Workbench process environment to generated Host |
| Single read-only goal | Live controlled Provider result passed | Live page-created Agent passed |
| Two goals, valid prior evidence | Provider-authoritative execution/result checked | Summary reused and analysis newly called; answer contained both facts |
| Changed-month continuation | Both new-month facts checked | Both tools called with `2026-08` from trusted `2026-09-15` |
| Known term without tool | No substitute call | `i豆` explained as unsupported with zero business read |
| Month without year and no context | Clarification checked | New-session clarification with zero business read |
| TASK or mixed QUERY/TASK | Existing confirmation/recovery path retained; collaboration prompt installs only for all-QUERY catalogs | Rejected by the finite Python composition; no false compatibility claim |

## Controlled evidence

### Java Runtime

The final focused suite used Provider-authoritative execution evidence. Five key cases ran once for
both A and B; both variants passed `5/5`, with no Runtime failure, incomplete run, or unexpected task
selection. This result corrects the earlier callback-only false negative and does not claim B is
universally better than A.

- Report: `/home/ubuntu/outputs/readonly-task-collaboration-v01-closure/java-targeted-final.json`
- SHA-256: `0adfdac9eb0f195c6fb3d2bad9272a9c9ebc7e28cfc11193c7683acbdb146552`

### Generated Python Candidate and actual Builder page

The public creation controls selected `cashflow-readonly`, Python Runtime, and desktop terminal. The
Workbench produced and started a new Agent, the experience page ran five cases, and the script stopped
only that owned instance. All 20 checks passed. The final build report records both source worktrees as
clean and all nine build stages as PASS.

- Result: `/home/ubuntu/outputs/readonly-task-collaboration-v01-final/browser-workbench-clean/result.json`
- Result SHA-256: `b92aac1418a3f79c32f2c3f251e4d8de2cdcbe9f92c8ce52228b59718b56bf51`
- Service Lock SHA-256: `d0625071c527a6bc5af83df224a18b72bf835933c66f312490be79fe8daa2287`
- Description catalog: `sha256:f1321af1553b9e62869ec17e4412e74d5bc546a19c46a60e73be5e3da2cdf2b8`
- Terminology: `sha256:12af7c02914ad9e2c3b3f87fa182d3dd4892e682c73c5b9df57c4c0cba48bcc8`
- Collaboration instruction: `sha256:04cab40897333cd90d705700906d80010ce5e3c6cde7b3cb65c3857698256fb5`
- Desktop screenshot SHA-256: `2df8f7920b9af19efc2c0567709afc26862449c68bdc4f0db3987f123ef67a77`
- Mobile screenshot SHA-256: `a3cf1705e850ee426f615fa6418d2568ca954aa9a05590cd73227961fd77b448`

The simulator received no write request. These are real-model calls against synthetic bank facts, not
real-bank or production-authorization acceptance.

## Minimal modification decision

No task planner or Router SPI is justified by this evidence. The minimum retained change is:

1. packages own use cases, output boundaries, negative examples, and terminology references;
2. Builder generically discovers and freezes those assets from selected packs;
3. Host supplies trusted business time and the existing native Runtime loop performs tool selection;
4. Harness execution still enforces identity, actual scopes, confirmation/recovery where applicable,
   and Provider result semantics;
5. evaluators score actual Provider executions and goal evidence, not callback count alone.

Reconsider a thin completion checker only after new blind cases show omissions that cannot be fixed by
correct capability boundaries, tool results, or Runtime configuration. Do not infer a need for a
planner from one callback or one missing duplicate call.

## Verification

- Harness `mvn -B -ntp verify`: 14 modules; 296 tests, 288 passed, 8 explicit real-environment Smokes
  skipped, 0 failures/errors.
- Builder `pytest -q`: 992 passed, 1 skipped.
- Clean browser build: nine build stages PASS and 20 browser/runtime checks PASS.
- Earlier direct generated-Python acceptance: 14 checks PASS; retained as a lower-level diagnostic,
  superseded for release evidence by the page-created clean Candidate above.

Not run: real bank, independent human onboarding, production authorization, general answer-quality
review, and arbitrary third-party Python Host compatibility.
