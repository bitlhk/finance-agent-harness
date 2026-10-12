# First-wave business-domain acceptance

Status: PASSED FOR CATALOG-ONLY RELEASE

This record covers only the six-domain browsing catalog and the existing six
mobile-banking capability cards. Smart Audit, anti-fraud and the customer
manager pilot remain inventory-only and are not enabled by this release.

The release gate requires isolated build, execution, read-only record replay
and compatibility with representative pre-release workbench records. Results
must also be mapped to the outstanding Dot browser findings; an unrelated
existing finding is not considered fixed by a catalog-only change.

## Scope and frozen baseline

- Source baseline: `9abbedaf99bc3e367b2de7dbb6a4f5d6d2748fc5`.
- Catalog commits under test: `fd64b906` and `d94ea3be`.
- Isolated release: `/opt/finance-studio/releases/20261008-business-domain-d94ea3be-r3`.
- Source archive SHA-256: `1f94e8c1eab8ed3d6a45d0eae2399748cf10a161515de28f4fcf1cd28d771466`.
- Isolated UI and simulator ports: `18178` and `18179`.
- Production data was not copied wholesale, migrated, cleaned or rewritten.
- Smart Audit and anti-fraud remain inventory-only. The customer-manager pilot
  is not part of this release.

The six domains are browsing classifications only. They do not grant access,
change runtime compatibility or constrain cross-domain composition. The
`mobile-banking` identifier remains available as an internal channel and legacy
record value, but is no longer rendered as the first-level scenario template.

## Acceptance results

| Gate | Result | Evidence |
| --- | --- | --- |
| Catalog projection and contracts | PASS | 97 focused Python tests; prior full branch run 1816 passed, 5 skipped |
| Browser catalog behavior | PASS | 10 catalog unit checks and 81 browser checks; six domains, four task blueprints, responsive layouts and legacy record rendering |
| Java isolated build | PASS | `agent-bd8cecedb9d24dc3`; all 5 build stages passed, including component installation and observation admission |
| Python isolated build | PASS | `agent-83d7668e11c24226`; all 9 build stages passed, including locked install and `python_installation_plan` |
| Controlled combination run | PASS | `shared-task-decline-r3/result.json`; 11/11 checks, including real build, pending confirmation after model failure, decline and zero submit |
| Execution history replay | PASS | `replay-result.json`; 10 records listed, a completed 24-span record replayed twice identically, zero gaps and unchanged source hashes |
| Old record compatibility | PASS | `compat-result.json`; Python 0.1.18, Java 0.1.15 and legacy Java 0.1.13 records remained readable; repeat reads stable and source hashes unchanged |
| Catalog endpoint | PASS | exactly six browsing domains, four task blueprints and all 26 source modules assigned to a primary domain plus optional related domains |

The controlled Java history run produced four completed requests plus expected
error, denied, cancelled and interrupted cases. The shared combination test did
not submit a transfer or preference update.

## Dot finding correspondence

| Dot finding | Current status | This release's evidence or boundary |
| --- | --- | --- |
| Java cashflow failed at component-installation admission | NOT REPRODUCED | Fresh Java candidate passed all five stages. |
| Python wealth failed at read-only installation-plan verification | NOT REPRODUCED | Fresh Python candidate passed all nine stages. |
| Failed build still allowed launch / contradictory build state | REGRESSION PASS | Build state remains fail-closed; browser checks keep launch disabled unless all required stages pass. |
| Pending transfer became unreachable after model failure | REGRESSION PASS IN CONTROLLED TEST | Confirmation and decline remained reachable; decline produced zero business submit. Synthetic authorization only. |
| Python tool fact disappeared after response-model failure | INHERITED FIX, NOT RE-CREDITED | The behavior belongs to `8e200488`; this catalog change does not alter the response fallback path. A live-model rerun was not counted because dependency download stalled before business execution. |
| Java retained facts but reported a top-level generic execution error | INHERITED FIX, NOT RE-CREDITED | Existing structured degradation behavior remains unchanged by this release. |
| Balance response contained an unsupported historical transfer amount | INHERITED FACT-GUARD FIX, NOT RE-CREDITED | No routing or response-generation code changed in this batch. |
| AgentScope observation qualification blocked build | OUT OF SCOPE | AgentScope support is not enabled or widened by the directory release. |
| Approximately 30-second external model intervals | OPEN EXTERNAL OBSERVATION | No timeout, retry or provider setting was changed. |

## Known baseline test-contract issue

A fresh `9abbedaf` Java candidate and this branch's Java candidate both carry
required observation metadata. The old fault-injection verifier expects the
Host to continue business execution when the required history store is made
unavailable, while current Host admission rejects startup with
`OBSERVATION_REQUIREMENTS_UNMET`. The same failure reproduces on the exact
`9abbedaf` baseline candidate, so it is not introduced by the six-domain
catalog. Normal storage, execution, history and replay paths pass.

This finding is not marked fixed. It requires a separate decision on whether a
`REQUIRED` observation contract must fail closed or may degrade. It does not
change the catalog-only release decision and must not be hidden by disabling
admission checks.

## Release decision and rollback

The catalog-only change is approved for the existing Builder release flow. It
does not change provider contracts, authorization, generated Host semantics,
model configuration, data mounts or runtime limits. The previous release
symlink and its rollback script remain the rollback source. Post-switch checks
must verify the six-domain catalog, legacy record listing, a no-submit controlled
run and the public health endpoint before the release is declared complete.

Smart Audit and anti-fraud remain in inventory status. The customer-manager
trial must be a separate batch using synthetic identities and a bounded service
preparation task.
