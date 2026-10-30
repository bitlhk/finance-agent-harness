# H1a evidence-only selection: installation still blocked

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
