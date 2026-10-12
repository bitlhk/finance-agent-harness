# A4 integration and release verification

Date: 2026-09-12; closure rechecked 2026-09-13. Controlled tests, stable
deployment and post-deployment pilot acceptance are complete. Human independent
onboarding and production-bank authorization remain separate, unperformed acceptance.

## Fixed implementation

- Builder integration675d586d merges actual finance df63b170 with A3 be5e7ae9.
- Harness1ec961bd669fba8203c20a76e70341754ab148ac includes reviewed PR15.
- Builder3a785617 supplies model ref/version in the pipe test fixture only.
- Subsequent ownership/public/deployment documentation does not change generated Runtime code.
- No files outside this Builder module differ from the integrated finance baseline.
- MR154 targets finance. Historical147/149/151/152 remain a documented stack;
  their reviewed commits are included, not duplicated by cherry-pick.

## Checks actually executed on the integrated implementation

| Check | Result |
| --- | --- |
| Builder local full suite | 676 passed, 1 explicit real Codex smoke skip |
| Base suite | 72 passed, 2 skipped |
| Server fresh dependency environment | 676 passed, 1 skipped |
| Native Java/Python generated combinations | 7 each, 14 fixed candidates |
| Shared business comparisons | 48 passed |
| Java snapshot checks | 142 passed |
| Python snapshot checks | 144 passed |
| Controlled snapshot/boundary faults | 83 passed |
| Combined pipe/process | 11 passed |
| Separate real model + complete synthetic bank | 20 passed |
| Actual page builds / dialogue / lifecycle | 29 passed, 3 builds |
| SOP snapshot / history / public reader | 10 passed |
| Stable page generation / two-turn dialogue / stop | 15 passed |
| Stable history, actual snapshot body and access boundary | 26 passed |
| Server Python 3.12 isolated native candidate | 58 passed |

Python combination check counts:28/30/37/42/49/51/58; Java:17/19/24/29/34/36/41.
Controlled fixture results are not real-model quality evidence. Separate actual model
checks used configured deepseek-v4-flash and the full synthetic bank, not real customers.
Revocation yielded no additional model/business read; stopped-history viewing made no calls.

Generated seven-unit-combination Lock digests:

- Java:4a1c191c31d9e379330f3964411e09d3ec9d7e4e87ca1c9c829398a634074c1f
- Python:f9c7837bc8985ea924c92acb2bcf0acfd87ea311124d5c9b1d73400392c76829

The three actual page builds used Java desktop, Python mobile and Python ATM; all
were stopped after tests. Two method versions were published through the actual API.
Captured requests contain the selected A4_SCOPE_FIRST or A4_GAP_FIRST marker and
exclude the other version. This proves version installation, not perfect model compliance.

## Failed iterations retained

The first combined pipe fixture treated installed wealth as uninstalled; corrected
that test expectation without weakening runtime checks. Direct test model metadata
was brought into line with the actual trusted Host model reference/version contract.
Server initial tests lacked esbuild; after npm ci the full suite passed. Early browser
selectors were ambiguous/incorrect; final selectors assert actual rendered snapshot body.

Postdeployment generation exposed release ownership/Git trust and systemd
ReadWritePaths omissions. The deployment correction is confined to the new release,
not wildcard Git trust or disabled filesystem protection. First-use Maven dependencies
are downloaded by the actual service; warm local timing is not compared as speedup.

## Release and remaining boundaries

The staged implementation passed before service switch. Stable service was switched
to Builder76a1b222 / Harness1ec961b with the old active Agent stopped via its authenticated
generation-checked API. Existing project/artifact/history/configuration files are retained;
33 protected files had no content change or loss at switch. Subsequent tests add only
new owned assets and pilot builds. Recheck confirmed the same deployed commits and
active service, with no running pilot Agents. Existing authorization/model/voice
configuration was retained; snapshot capture/view is explicitly enabled only for the
administrator owner on new pilots, not granted by capability or Profile labels.

Stable endpoint: https://finance-harness.demo.linggan.top/ . Actual new artifacts:

| Pilot | Installation Lock SHA256 | Locked files verified |
| --- | --- | --- |
| Python agent-f1b094a3da0d4e53 | cebf97083d193b4a43273167b9cc60f45f30b79f07180c5433c948c94e674f53 | 357 |
| Java agent-83a690c6c3044c56 | 1f3d97ed489330d9595bd6a5bbe45e5f37b9aadfefa2e66263338d6fdee4cf07 | 179 |

Both pilots used all four read-only tools, actual model two-turn cashflow dialogue,
source cards and mobile/desktop display checks, then stopped through the API.
History checks independently waited for rendered content after an initial page probe
asserted too early. They verified the selected SOP marker in the stored client input,
old-record snapshot absence, anonymous rejection, and no new execution from viewing.
Neither that initial failed assertion nor a failed dependency-download build is counted
as a passing run. The server controlled test used a separately generated fixture
candidate; a real pilot's frozen model binding was not weakened to reuse that verifier.

Python build elapsed 52,047 ms with verified cached wheels; Java asset reassembly took
594 ms using an existing compiled artifact. These are not cold-build comparisons or
speedup claims. The first server Python download timed out; 164 cached Python 3.12
wheels were subsequently checked against official PyPI filename/digest metadata.
A truncated Maven download was replaced by the same-version tested JAR, not a dependency
upgrade. Failed builds remain visible. Later report/deployment-document commits are
not represented as the deployed source SHA.

## Rollback and merge lineage

Protected backup SHA256:
73fa84086d2574bdcd06962c2999948daab26a3460aa16f60b3fcc9a41809cd6.
The previous release remains at releases/20260912-history-v01 (Builder76b9c1a,
Harnessa7b0b14). For normal rollback, stop current owned pilots through the authenticated
API, stop finance-studio, restore the builder/harness symlinks to that release, remove
the A4-specific systemd drop-in, daemon-reload and start/check the service. Restore
private data from backup only for disaster recovery, not routine rollback: doing so
would overwrite legitimate post-release records. A live rollback drill was NOT run.
Public guide rollback independently restores the previous public-current symlink.

MR154 carries the reviewed 147/149/151/152 stack into finance; historical reviews and
branches are retained, not blindly merged or deleted. At closure finance advanced to
015e0cc14c02224f2ea42ab8cefef712e6f48369 through unrelated Task Discovery changes;
neither this module nor Base differs from the tested df63b170 baseline. Platform merge
status is recorded separately after the final documentation push, not inferred from
null API response fields. Platform acceptance is not independent human onboarding.

Public guide V0.9 adds runtime, method and snapshot usage without changing diagrams.
Published index digest:84fe4cf6ecdf6c7c6c8ad03952e44f38ec2e40f5947edbf228df13710f689a91.
Public docs remain unauthenticated; workbench operations retain authentication.

Snapshots are prepared/parsed client projections, dispatch NOT_OBSERVED, no hidden
reasoning. Python still uses a Java bridge and Host storage, not an independent Python
Harness SDK. See [responsibility review](python-studio-a4-ownership.md) for actual
reuse, simulator-specific trusted policy wiring and the remaining UI label mapping.

Harness main CI34697162633 succeeded. GitCode's merge gate is not a separately executed
Builder CI pipeline: no task count must not be reported as new CI test coverage.
No production authorization, independent human onboarding, production P95, distributed
recovery or dashboard acceptance is claimed. Earlier A3 overhead observations are not
remeasured A4 performance results. Existing generated Agents are never auto-upgraded.

Maintainer evidence directory: outputs/python-studio-a4-v01; fixed manifests, junit XML,
business comparisons, snapshot/pipe/fault JSON, page screenshots, live recorder report
and deployment inventory are retained separately from confidential cookies/configuration.
