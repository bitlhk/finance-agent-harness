# O1 candidate Host observation admission

## Workbench release wiring

The operator may set `FH_OBSERVATION_REQUIREMENTS` to the local Harness selection
asset for newly generated Studio Candidates. `Jobs` forwards it through
`SolutionBuilder.build` to both existing service backends; materialization still
validates containment in the Harness repository. It is not an HTTP request field,
uploaded Skill setting or a source of business grants. Unset preserves the legacy
build shape; unsupported non-service profiles reject the explicit option.

The owned Host environment forwards only the existing qualification file and
SHA-256 keys (`FH_OBSERVATION_QUALIFICATION_FILE`,
`FH_OBSERVATION_QUALIFICATION_SHA256`), in addition to the prior whitelist.
Qualification must be issued for the actual deployed collector identity using
local qualifying evidence. Existing Agents are neither rewritten nor qualified
retroactively. This wiring is Studio-specific; Base contracts are unchanged.

Regression: `pytest tests/test_observation_workbench_wiring.py` checks Java/Python
entry delegation, operator-only selection, legacy opt-out and child environment
isolation. The pre-fix implementation fails five of the six initial cases.

## Original candidate scope

Based on finance9a403538 and Harness1ec961b plus the explicit companion O1 branch.
This is the existing Studio prototype, not a new Base contract or FEAT010 completion.
No production deploy/merge is part of this iteration.

`build_service(..., observation_requirements=<local Harness asset>)` is an explicit
developer opt-in. `build_python_service` forwards the same option to its Java bridge
build. Builder copies the selected requirement plus a digest-bound reference; it
does not synthesize financial requirements or grant collection access. The default
remains legacy-compatible until the candidate completes review.

The new ObservationAdmission Host helper validates both files against service-lock,
checks id/version/digest with the neutral Harness types and probes history storage
with a temporary write/force/delete. REQUIRED disabled/unavailable history fails before
serving queries. The Assembler matches the same requirement via its explicit overload.
Native Java and Python share the helper, archive and Harness matcher; no Python Core
copy was introduced. The plan exporter uses the same wiring and no real bank identity.

Collector descriptors come from trusted Host code, not an uploaded descriptor file.
Hardcoded regression labels no longer confer VERIFIED. The trusted Host can configure
`FH_OBSERVATION_QUALIFICATION_FILE` and `FH_OBSERVATION_QUALIFICATION_SHA256` for a local
qualification record. It must match the collector class digests, candidate Runtime and
locked dependencies, and reference existing digest-checked evidence. The full Builder
source commit remains in the service Lock for traceability but does not invalidate collector
qualification when unrelated Builder code changes. Java and Python have different
identities; Python additionally binds its actual hook files. Without matching evidence,
support remains separate from NOT_CHECKED verification and required admission fails.
Project SNAPSHOT JARs are first verified against their locked SHA-256, then identified by
their contained file digests rather than ZIP metadata. Changed class/resource content still
requires fresh qualification; repackaging identical content does not.
This file is an operator qualification input, not a user-uploaded asset, signature service,
or proof that arbitrary test content is sufficient. The operator owns approval of its scope.

The model-client collector does not claim MODEL_HTTP capture. Body authorization is
separate from enablement. A separate snapshot-directory probe now checks write/force/delete
and current quota without expiring existing evidence. It does not reserve future capacity.
Capture failure after startup must still be reported independently.

Existing runtime archive failure handling remains non-blocking after business execution;
no financial outcome or authorization order is rewritten and no retry is added. The
new startup probe does add file I/O; no zero-overhead or capacity improvement is claimed.

History saves the startup assessment in the immutable request header. The reader only
projects bounded metadata and distinguishes that preflight from actual records. Model
body capture is still inspected separately, not inferred from READY. Legacy history
shows LEGACY_UNASSESSED, remains readable and is never deleted when collection is off.

An explicit Harness `observation-packs/readonly/selection.json` selects a common requirement
and balance/wealth/cashflow unit declarations. Builder performs bounded composition only;
it preserves all selected source files in service-lock and writes a digest-bound composed
requirement. The financial declarations remain Harness-owned and strict Pack V1 is unchanged.
`capability.result` requires capability/provider/request correlation, business status and
observation time, not customer values or a valuation timestamp. No invocation is NOT_INVOKED;
a missing business result is not invented after a thrown execution error.

O1/O2 controlled candidate acceptance is complete at Harness8142208 / Buildere0a1bb02:
two qualifying Candidates plus fourteen admitted Java/Python combinations, independently
checked artifact and per-request display/installation hashes, and new browser evidence.
See [O2 developer/qualification guide](observation-display-o2.md) and
[version-bound report](observation-o1-o2-verification-20260913.md).
Snapshot receipts remain distinct from permission-checked body opening and digest validation.
No independent human or production bank authorization acceptance is claimed. Earlier
matrix-fixed and O1-only results are historical, not the final evidence for these sources.
