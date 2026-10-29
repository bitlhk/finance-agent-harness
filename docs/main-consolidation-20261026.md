# Main consolidation, 2026-10-26

This is a source integration, not a deployment or a new runtime qualification.
Baseline: `533a54d786da829c435010fb19ac48eed20bc586` on `origin/main`.
Use `main` as the canonical integrated source. Existing deployed releases and
Agent locks remain pinned; never replace their SNAPSHOT JARs in place.

## Integrated by this change

| Source | Disposition | Compatibility boundary |
| --- | --- | --- |
| PR42 `codex/windows-private-task-stores`, `1cf4f0a` | Merge | POSIX owner-only permissions retained; non-POSIX requires an ACL view, otherwise fails. Windows runtime testing remains NOT_RUN. |
| PR84 `codex/observation-assembly`, `1ba8403` | Merge | Optional SDK 0.1.16 metadata bridge and bounded analyzer; default SDK 0.1.15 unchanged. Does not replace mandatory audit or grant access. |
| PR80 `aa3b983`, PR82 `db1bad6` | Included through PR84 | All 6 PR80 files and all 13 PR82 files match PR84 byte-for-byte. Close as superseded, not as independent new implementations. |
| PR78 `codex/quotation-readonly-h1`, `ecb8cd4` | Merge | Synthetic read-only quotation components only. Host/public-disclosure/Builder qualification is NOT_RUN, not implied by component tests. |
| Local `feat/capability-extension-boundaries-v1`, commits `44bd0b4`, `723140b` | Cherry-pick and reconcile catalog | Explicit account/month or UTC date range; current page only; source identity and numeric bounds checked; existing authorization remains required. No live-bank acceptance implied. |
| Same branch, `9554417` | Do not restore older implementation | Current AgentScope ModelConnection/GenerateOptions already support bounded generation parameters and the later thinking option. Preserve main's implementation and tests. |
| PR27 `codex/feat006-antifraud-conformance`, `71c3f82` | Merge with conflict adaptation | Five deterministic fixture cases and QUERY/TASK evidence identity retained; current shared TaskCoordination/ProviderTaskExecution and safe authorization failure codes are retained, not reverted to the old coordinator. |

Integration repairs:

- Observation assembly now pins the actual combined root POM after quotation
  modules were added. A stale digest was correctly rejected before repair.
- Quotation verification now requires the existing registry/Lock test as well
  as the other 35 Java tests. The original list omitted that fourth config
  test and incorrectly rejected a fully successful Maven run. A negative test
  requires rejection when that registry test is omitted.
- Catalog and its proposed governance example retain all current entries and
  bind the combined catalog bytes; no old catalog revision replaces main.
- Added Linux owner-only checkpoint/journal checks and TASK-denial evidence
  identity coverage. These are not Windows ACL or power-loss certification.

## Retained, not integrated

| Source | Concrete blocker | Reusable part / next prerequisite |
| --- | --- | --- |
| PR44 `codex/antifraud-live-assets`, `0bb095e` | Provider/assembly/catalog conflicts. Its `fraud-risk-readonly` rc package and single-capability provider declaration predate main's `fraud.risk-evidence.query`. The optional static HostSession protects only the old assess path, not the later evidence path. | Retain readonly method/fixture ideas and credential-redaction tests. First map the current factory/session lifecycle and both operations, then adapt declarations without removing current capability support. Not a mechanical merge. |
| `codex/smart-audit-streaming`, `5fc17cc` | 877 changed files, including a whole Builder copy under community; two additional audit domains and nine capabilities, not merely streaming. | Split Harness-only domain/provider/contracts from Builder changes; review input bounds, owner/resource binding, existing audit overlap, and current Host integration. Do not import a second Builder implementation into Harness. |
| Fork PR9 `feat/guardrail-jiuwen`, `01ce3d3` | Separate HTTP service, policies, NLP model payloads and runtime hooks. `services/guardrail-jiuwen/adapters/jiuwen_adapter/rail.py` defaults unavailable/error handling to fail-open. It is not wired to current financial authorization/audit contracts. | Detection rules can be assessed separately after failure semantics, data boundary, dependency/license and Host integration review. Do not substitute this for current mandatory controls. |

No branch, worktree, historical release, Agent or evidence directory is deleted.
The original main repository worktree has pre-existing uncommitted changes and
is deliberately not reset or used for integration.

## Already covered; no duplicate merge

- Remote `feat/customer-manager-previsit-mvp-v01` and
  `feat/customer-manager-streaming-products-v01`: ancestors of the baseline.
- Remote/local `feat/customer-manager-closed-loop-v01`: both unique commits
  are patch-equivalent to changes already in main.
- Local `docs/developer-guide-v10` and `fix/manager-validator-r41`:
  patch-equivalent commits already in main.
- Local `feat/harness-spending-readonly-v01` and
  `feat/cashflow-kit-integration-v01`: main has the cashflow contracts,
  mapping, methods and preserved coverage/comparability gaps. CashflowMapper
  and CashflowConsumer are byte-identical to the latter branch; current tests
  additionally cover source observation. Preserve the newer implementation.
- Other 64 local branches were already ancestors of baseline main. Their names
  are historical pointers, not 64 outstanding product versions.

## Verification and release boundary

Run combined-tree checks, not the original branch's historical green status:

```sh
python engineering/verify_contracts.py
python engineering/verify_component_assembly.py
python engineering/verify_observation_assembly.py
python -m unittest discover -s engineering -p 'test_*.py' -v
mvn -B -ntp verify
mvn -B -ntp -f integrations/openjiuwen-java-otel/pom.xml test
mvn -B -ntp -f packages/java/observation-analysis/pom.xml test
H1_MAVEN_REPO="$HOME/.m2/repository" python engineering/verify_quotation_reference.py --report /tmp/quotation-component-report.json
python engineering/verify_reproducible_jars.py --report /tmp/reproducibility/report.json
```

Python requires `engineering/requirements-conformance.txt`; using an unprepared
system interpreter is not a test of the code. The controlled wealth-facts-kit
entrypoint and public guide CI commands are also included in local verification.
No real model, real bank, 85-product qualification matrix or production service
is invoked by this consolidation.

GitHub branch protection/rulesets and actual check annotations must be rechecked
at merge. A workflow failed before jobs started is not a test PASS. Historical
PR84 annotations explicitly report payment/spending-limit blockage; the final
consolidation PR must retain its own actual status and annotations.

Source merge does not authorize enabling new scopes, connections, optional
observation or Windows support in production. A later deployment needs the
actual Builder/generated Host dependency-impact gate and final installation
qualification. Previously installed Agent locks and permissions are unchanged.

## Branch workflow

New work starts from current main on a short-lived feature branch. A normal
reviewed PR returns it to main; release manifests pin exact commits. Do not use
historical feature branch names as a permanent integration target. Remote branch
deletion and dirty-worktree cleanup are separate from source consolidation.


## PR44 follow-up (2026-10-29)

The PR44 repair is based on main e911c71. It retains both fraud operations and the
managed factory connection kind, and maps the existing current-session supplier
onto every assess/evidence HTTP request. The rc standalone pack remains QUERY-only;
its fraud:read scope agrees with the current factory. Existing evidence pack and
fraud-risk:read requirements remain. Candidate admission, host-session rotation and
revocation require the regression evidence attached to PR44; this is not a release
or production-installation claim. The historical retained-work table above records
the state at consolidation, before this repair.
