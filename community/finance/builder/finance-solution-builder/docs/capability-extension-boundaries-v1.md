# Capability extension boundaries: checkpoint 1

Final controlled engineering acceptance and remaining release limits are in
[the final report](capability-extension-acceptance-20261007.md). This document
preserves the earlier checkpoint chronology.

Date: 2026-10-07
Branch: feat/capability-extension-boundaries-v1
Baseline: bf7d2679

## Scope and safety

This checkpoint extracts existing display projections without changing business
contracts, permissions, Provider calls, task confirmation, or runtime support.
No production deployment, artifact migration, or existing Agent restart.
The independent worktree avoids interfering with other ongoing Harness changes.

## Implemented

- DisplayProjection retains installed-capability and completed-result admission.
- BalanceDisplay, CashflowDisplay and WealthDisplay own existing business
  transformations; DisplayFields preserves allowlisted scalar extraction.
- BusinessDisplays is a trusted, immutable build-time registration table.
  It is not a dynamic user plugin loader and grants no execution permissions.
- AgentScope shared sources include every extracted class.
- A frozen baseline differential test checks 257 synthetic input combinations,
  including all seven capability IDs represented by the six cards, plus unknown
  capabilities, uninstalled capabilities, failure states, result versions and
  mixed results. Existing behavior with no specialized card remains unchanged.
- A synthetic projection proves that the dispatcher itself needs no business
  branch when adding a handler. This is NOT a new installed banking capability.
- The same test compiles and runs the existing five DisplayProjection JUnit tests.

## Limits

Checkpoint verification: the reproduction command completed with 73 passed,
zero skipped in 42.72 seconds. The JVM differential check and the five existing
JUnit checks ran inside that test suite. This is local engineering evidence,
not a six-card live acceptance or deployment result.

These are still trusted Builder template classes, not independently distributed
capability-package presentation artifacts. The registration table and AgentScope
shared-source list still need explicit maintenance. Frontend business rendering
is unchanged. Do not claim that display packaging or rapid onboarding is complete.

## Remaining implementation

1. Specify trusted presentation registration/packaging without arbitrary scripts.
2. Replace repeated build dependency maintenance with a validated artifact plan;
   preserve exact version, dependency closure, digest and old artifact checks.
3. Regress all six cards and their currently supported runtime combinations,
   including task confirmation, cancellation and historical artifact access.
4. Select two actual same-bank read-only operations after auditing their current
   upstream contracts, ownership, pagination, error and authorization semantics.
5. Implement them on OpenJiuwen Java and measure required public-code changes.
   Do not count hypothetical fixtures as onboarding or production acceptance.
6. Validate a representative query/task pair on AgentScope separately.

## Checkpoint 2: generated financial dependency closure

New OpenJiuwen Java builds now append qualified Provider Maven references to
their generated POM. Verification derives the financial dependency closure
from that POM and the fixed local Harness reactor instead of adding another
module name to a Python list. The closure includes POM digests and exact JAR
digests, is frozen before compilation, and is checked again after compilation.
Binary reuse must pass the same closure check. The artifact evidence is included
in the candidate file lock.

The resolver intentionally supports only the current simple Maven reactor.
Unresolved coordinates, financial profile dependencies, custom parents, version
conflicts, exclusions, classifiers, cycles, path traversal and symlinks are
rejected. It does not approximate Maven mediation. Non-financial dependencies
remain outside this existing financial-JAR integrity check.

Legacy candidates without a POM retain the previous strict finite check.
Current Host-required dependencies remain in the template because Host source
still imports them. This change removes the need to extend the verifier for
each additional Provider; it does NOT remove all Host packaging dependencies.
AgentScope's separate build backend is not migrated by this checkpoint.

Verification evidence:

- Isolated Harness checkout at 564c986, offline Maven install: 263 tests,
  zero failures/errors, five live tests skipped.
- Generated four-query Host: Maven package successful, 59 tests passed.
- Actual generated financial JAR closure and digests verified against the
  independently built Harness; ComponentAdmission exited successfully.
- Candidate path: /tmp/finance-boundaries-acceptance/query/candidate.
- Full Builder regression: 1791 passed, five skipped in 292.02 seconds;
  JUnit XML: /tmp/finance-boundaries-acceptance/builder-tests.xml.
  Three additional parent/version-conflict tests were added after suite
  collection; the final artifact-plan suite separately passed all 18 tests.
- Its original build report remains NOT_RUN because generation used verify=False
  and Maven/admission were executed separately. Do not relabel it as a fully
  verified Builder delivery.
- Initial default-cache Maven build failed for a missing assembly artifact;
  fixed by building the frozen source in an isolated Maven cache, not by
  downloading an unverified replacement or weakening admission.
- First full pytest attempt needed engineering on PYTHONPATH. A sandbox run
  could not start local test servers. The next run stopped at five failures:
  four missing frontend dependencies and one process-options fixture needing
  explicit isolation from the new reactor planner. These failures are not
  erased by the subsequent corrected-environment run.

Merge remains blocked until all promised extension and compatibility acceptance
is complete, including two real new capabilities. No production state changed.

## Reproduction

From the finance-solution-builder directory, with cached JDK, Harness, Jackson
and JUnit artifacts and the project's Python dependencies:

```sh
PYTHONPATH=src:../base/src python -m pytest tests/test_display_boundary.py tests/test_agentscope_backend.py tests/test_unified_catalog.py -q
```

Missing local dependencies produce explicit skips in the differential test.
Acceptance requires the JVM test to run, not merely a successful pytest exit.

## Extension candidate implementation (2026-10-07)

The paired Harness branch now supplies two actual read-only source contracts:
account.statement.query and account.transaction.query. Their upstream source is
non-ai-banking 162ef4e587c9e9b781bab60bf89591cf4255e579. They are not mock capability
IDs: a controlled HTTP fixture exercises the same two source routes through the
native generated Host, tool adapter, managed Provider, evidence and display.

New packs own optional presentation.v0.1.json descriptors. Builder admits only
bounded QUERY-owned fields, forbids paths/scripts/private identity fields and
copies selected views into a locked JAR resource. StructuredDisplay supplies a
generic record projection and the browser supplies an escaped generic table.
Changing embedded views invalidates binary reuse. Existing specialized six-card
projections are preserved; unsupported historical views remain unsupported.

Adding these packs did not change the OpenJiuwen Runtime, Host dispatch branches,
authorization mechanism or TASK execution. Bank contracts and source mapping
still require implementation and tests. Bespoke views, new operations, new
authentication mechanisms and a new Runtime are not zero-work extensions.

Development verification (before final frozen matrix):

- Builder: 1805 passed, 5 skipped; an additional binary-resource reuse test was
  added afterward and is included in the final run.
- All 17 Harness reactor modules compiled and tested successfully after fixing
  stale reference metadata. Eight live tests remain deliberately unexecuted.
- New combined candidate: all 59 generated Host tests pass; component admission
  passes and build report is BUILT (not merely manually compiled).
- Both new queries passed a real HTTP chat entry with a controlled model and
  synthetic bank. This does not establish real-model interpretation quality.
- Matrix resolution: Java 255 accepted subsets; Python and AgentScope each 7
  accepted subsets and 5 rejected unsupported singletons. Resolution is not
  execution. Artifact matrix remains a separate gate.

Failures retained under /tmp/finance-boundaries-acceptance:

1. New test generic type inference error, fixed without changing behavior.
2. Factory test assumed six Provider IDs; replaced by exact eight-ID assertion.
3. Governance example catalog digest stale after adding packs; synchronized
   catalog 0.2.7 / 2026-10-07.account-history-1 and example digest.
4. AgentScope frozen reference Provider digest stale; updated only that source
   reference, without adding runtime support or weakening digest admission.
5. Development matrix interrupted by directory/template changes; invalidated
   runs are not accepted and a frozen-source run is required.
6. Display boolean hasMore initially lost by the legacy text-only helper;
   fixed in the new projection only, with native-chat and projection assertions.
7. Standard Chromium screenshot processes stalled and were stopped; the local
   dedicated headless binary produced desktop/mobile synthetic-render captures.

Production remains untouched. No full live-model, real-bank or independent
human acceptance is implied by these local engineering results. Merge remains
conditional on the final frozen matrix and review.
