# Business observation requirements: candidate O1

Implementation baseline: Harness main1ec961b / Builder finance9a403538.
O1/O2 controlled candidate acceptance is complete at Harness8142208 / Buildere0a1bb02.
See [fixed-source verification](verification/2026-09-13-observation-o1-o2.md).
No production release or financial-compliance certification is implied.

## Separation of responsibilities

An observation event describes an occurrence. A requirement describes evidence that a
delivery needs. Neither an uploaded requirement nor a collector descriptor grants
permission. A trusted Host/Integrator supplies the collector description, including
the specific version and regression evidence references. Arbitrary customer JSON must
not be accepted as that trusted descriptor. References document evidence, not a
cryptographic attestation that the test was executed.

Support, tested coverage, current enablement, current capture authorization, and actual
per-request capture are distinct. An assessment READY means preflight readiness only;
its capture field remains NOT_COLLECTED. A Java capability not tested is NOT_CHECKED
or UNKNOWN, not false, and no Python superiority follows from missing Java evidence.

Core contains neutral requirement/descriptor/matching types. The existing Assembler
remains Java/OpenJiuwen-specific. Python still reaches it through a Java Host bridge.
No second Core, independent Python Harness SDK, or general policy engine is introduced.

## Versioned opt-in and compatibility

Old Pack V1 and Solution V1 JSON shapes are unchanged. A trusted caller explicitly
passes ObservationRequirementsV01.Reference to the new prepare overload, together
with trusted collectors. The local sidecar is bounded to64KiB, strictly parsed and
verified against its required SHA256. Its id/version must match the reference; the
asset digest enters the installation assets/Lock. Remote sources and symlinks are
not resolved. Unknown fields reject rather than silently enabling an extension.

Old calls have LEGACY_UNASSESSED via the Java accessor and preserve the exact v0.1
plan shape. Opt-in calls produce finance-installation-plan.v0.2 including the
assessment. This is explicit migration: old strict consumers cannot accept the new
shape without upgrading. Do not add these fields silently to a v0.1 plan.

The common fixture requires only execution.history metadata at the Host boundary.
Empty capabilityIds means a common requirement for the entire selected plan. Nonempty
sets must refer to installed capabilities. The explicit three-unit selection composes
this common declaration with scoped capability-result requirements. SOP authors do not
write SDK callbacks. Seven combinations have controlled generated-artifact acceptance;
this does not qualify arbitrary future packages.

REQUIRED gaps reject prepare before tool installation or bank reads. OPTIONAL and
AUTHORIZED_DIAGNOSTIC gaps remain explicit. DIAGNOSTIC_BODY always requires explicit
capture authorization, including when the obligation is REQUIRED. Two ambiguous
claims are rejected, not unioned to manufacture end-to-end coverage.

## Host wiring and acceptance

The Host must re-evaluate real archive availability and current authorization at
startup. The companion Builder branch now performs a write/force/delete probe and
checks the locked requirement reference before admission. This matcher alone does
not establish that startup behavior.
Enabled flags alone do not prove writable storage. Later storage failure must leave
the original business outcome intact and report degraded observation, never trigger
another financial action. Capture/view permissions remain separate.

Implemented in the companion candidate Host: startup probe, immutable per-request
assessment in the request header, and bounded common UI projection. Build and current
runtime assessments remain separate; only environment/capture state is excluded from
the frozen plan comparison, never requirements, versions or capability bindings.
Snapshots have a separate non-destructive directory/quota/write probe; a required
body-snapshot configuration cannot pass just because the history store works.

Three-unit/seven-combination generation and O2 Provider display metadata are verified
at the fixed sources above. New records use immutable component descriptions linked to
installation locks; UI dictionaries remain only for explicitly identified legacy records.
Model receipt projection still does not verify body contents. Opening the body separately
checks permission and digest. See [O2 ownership](observation-display-o2-v01.md).

Negative tests currently cover missing/disabled/unverified/mismatched support,
diagnostic authorization, invalid evidence claims, unknown extensions, uninstalled
capability and ambiguous bindings. Assembler tests cover explicit opt-in, old shape,
digest tamper rejection, locked requirement reference and zero Provider invocation.
These are not production authorization or independent human onboarding acceptance.
## O1 gap-correction status

The candidate now separates SDK support from collector support, hashes collector
declarations in the installation lock, and preserves rejected assessment details.
The strict descriptor schema and Java consume eight shared positive/negative cases.

`observation-packs/readonly/selection.json` is an explicit solution-level composition
entry, not a new field silently added to Capability Pack V1. It combines common history
metadata with balance, wealth and cashflow capability-result requirements. All selected
source bytes and the derived declaration are locked. Business status and observation
timestamp are required metadata; this is not a claim to store customer facts or valuation
timestamps. Selection does not enable a collector or grant access.

Builder Host qualification must now refer to matching current collector/runtime artifacts
and digest-checked local evidence. Supported but unqualified collectors remain NOT_CHECKED.
The qualification workflow and O2 descriptions have fixed-source controlled verification;
earlier matrices are historical, not substitutes for these results. The qualification
record is trusted operator assertion, not a hash proving report adequacy. No merge/deploy
is authorized; production IAM and independent human acceptance remain separate.
