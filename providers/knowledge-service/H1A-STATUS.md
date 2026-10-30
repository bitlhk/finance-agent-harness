# H1a evidence-only selection: installation still blocked

## Latest Host implementation checkpoint

The earlier rejection/evidence below remains historical. The current independent
Profile is `knowledge-read-only`; the new `integrations/knowledge-java` scenario
implements the existing preparation/open/session SPI and uses the managed
Knowledge Provider with an independent private Host authority. It cannot grant
Core permissions, activate sources, publish, or use a bank session.

The Host supplies currently qualified collectors through Context, whose older
constructors remain supported. Knowledge startup refuses an empty collector list.
Build preparation never reads the private authority file or issues a session.
Runtime leases have a maximum 15-minute life and are deleted on session close;
this is not evidence that all OS resources have been cleaned.

The actual Builder build now passes contracts, Harness tests and packaging,
then fails `OBSERVATION_REQUIREMENTS_UNMET`. REQUIRED Provider collection stays
enabled. There is no accepted Agent Installation Lock or UI invocation yet.
Six new authority tests pass; these do not constitute Agent acceptance.

Use Builder `engineering/build_knowledge_h1a.py` for current admission attempts.
The older `probe_h1a.py` is tied to the earlier Host rejection and is not the
current installation acceptance runner. Refer to Knowledge Factory
`docs/H1A-HOST-CANDIDATE.md` for exact boundaries and retained failed attempts.

---

2026-10-30. This is a follow-up candidate to H0 / PR #95, not a deployment.

## Delivered

`knowledge-evidence-readonly@0.1.0` is an independently selectable, atomic pack
containing only `knowledge.evidence.search`. It requires only `knowledge:read`;
the synthetic rule tool and `knowledge:rule` are not installed by this pack.
The original two-tool probe pack and its old results remain unchanged. The same
Knowledge Provider has an additional declarative support entry, not a second
business-specific implementation. REQUIRED Provider observation is retained.

Checks: four new asset assertions, 31 schemas / 132 fixtures and state probes,
38 component declarations. These are offline checks, not Agent acceptance.

## Real Builder attempt

`probe_h1a.py` calls the actual `build_service(..., verify=True, managed=True)`
from Builder `9d33295f74ff663c16579febd120b586c791280f`, without modifying any Host
declaration. It rejects before creating a candidate:

`ASSEMBLY_PROVIDER_CONNECTION_MISMATCH`

See `evidence/h1a/admission.json`: input solution, selected pack, Host source
digests, fixed Builder SHA and rejection. Installation Lock is null, no JVM or
Agent was started, no model was called. We did not switch connection kinds or
remove `knowledge-binding.v0.1` to manufacture an installation.

## Additional code-confirmed gaps

All Builder paths below are relative to
`community/finance/builder/finance-solution-builder/` at the SHA above.

| Boundary | Evidence | Minimum adaptation |
| --- | --- | --- |
| Build-time Provider identity | `templates/local-readonly-service/.../ReadOnlyProviders.java:37` constructs `http://127.0.0.1:1` with `create(..., empty expected configuration)`. Knowledge Factory requires a pinned configuration digest and `/query` connection. | Materialize trusted non-secret binding/connection configuration into the build Lock, then use a metadata-only preparation path with no authority acquisition. Do not suppress digest checks. |
| Scenario preparation | `NativeRuntime.java:15` and `ObservationAdmission.java:93` still call `ReadOnlyProviders.forPlan`. `ManagedScenarioExtensionV01.installationProviders` exists in Harness but is not used by these preparation paths. | Connect the existing SPI to preparation, with regression for the bank and existing managed scenarios; do not introduce another Runtime. |
| Runtime authority | `ReadOnlyProviders.java:26` derives SourceSession and Grant from BankAccess. | A knowledge-specific trusted supplier must bind principal, purpose, source access, session and current revocation independently. A server-owned test identity is acceptable for the isolated candidate, but not a bank session or browser-supplied identity. |
| Core wire adapter | Core's current M2 UI endpoint accepts runId/query; H0 Provider uses authenticated `knowledge-query.v0.1` with correlated receipt. | Implement the bounded adapter with fixed Manifest/context, current Core permissions before and after retrieval, and session validation. HTTP 200 business errors must remain rejection. |
| Agent surface and admission | Existing Agent experience loader has bank/customer-manager/fraud paths, no knowledge session path. | Thin existing-surface adapter, actual install/collector qualification, tool invocation and release evidence. A handcrafted separate query page is not H1a. |

These are development gaps, not a request to weaken access controls. We have
not changed Builder, Host, public authorization semantics, collector qualification,
production services or any parallel worktree. H1b remains a separate mixed-provider
acceptance gate after H1a; one successful knowledge query cannot stand in for it.

## Reproduction

Use a Python environment containing the pinned Builder dependencies:

```sh
python providers/knowledge-service/probe_h1a.py \
  --builder /path/to/pinned-builder \
  --output /path/to/new-isolated-evidence
python -m unittest engineering.test_knowledge_evidence_pack -v
python engineering/verify_contracts.py
python engineering/verify_component_assembly.py
```

The reproduction intentionally stops at the real admission error. Do not call
this an installed Agent or an end-to-end Knowledge Factory/Harness result.

## Subsequent isolated implementation (not H1a acceptance)

The admission input is now the explicit `h1a/solution.json`, with its independent
`conformance/knowledge-evidence-h1a` test set. It contains no bank domain pack or
wealth evaluation reference. Original admission evidence remains unchanged.

`InstalledKnowledgeProvider` reads fixed per-Provider configuration from Builder
service file-Lock entries and checks exact evidence-only selection. It uses the
existing `createReferenced` managed registry. Preparation acquires no SourceSession
or Grant and makes no HTTP call; runtime suppliers are explicit Host inputs.
This reader does not install an Agent, create authority, or qualify a collector.

Local Maven result: 27 knowledge tests passed, including 6 new installed-binding
tests. The Maven repository was isolated at
`/home/ubuntu/outputs/knowledge-h1a-maven-cache`; shared SNAPSHOTs were not replaced.
Knowledge Core's real fixed M2 product was separately queried through a bounded
consumption adapter, not through an installed Java Agent.

Still required: reviewed Host/knowledge connection admission, runtime authority
supplier, real scenario implementation, qualified installation, existing Agent
UI invocation and same-instance stop/history receipts. The new configuration
reader and Builder opt-in SPI cannot substitute for any of these gates. H1a is
still blocked; no claim of full compatibility or production deployment is made.
