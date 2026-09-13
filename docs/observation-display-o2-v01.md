# Component-owned observation display V0.1 (candidate)

O2 adds presentation metadata, not an execution engine or an authorization rule.
`ObservationDisplayV01` is runtime-neutral. `CapabilityLabel` consumes existing
Capability Pack `name`, ID and version. `DescribedFinanceProvider.observationLabels()`
defaults to empty and supplies versioned source operations: provider ID, stable operation
ID, exact method, allowlisted route template, title and description. The source-label
version versions the description; actual Provider artifacts are identified in the Lock.
Do not include URLs,
query values, customer identifiers, secrets or response bodies in labels.

`BoundFinanceProvider`, `ObservedFinanceProvider` and `WealthFactsProvider` delegate
source labels. Display lookup never calls the authority or business operation. Access
checks and Provider result semantics remain in their existing paths. The reference
non-AI Provider owns `ReferenceObservationLabels`; a new Provider needs no UI dictionary
change. HTTP attempts carry the existing `providerId` link for unambiguous matching.

`PreparedSolution.displayMetadata()` extracts a snapshot separately from the existing
installation-plan/Lock JSON shape. It rejects conflicting source descriptions. The Host
catches display-only failures and records UNAVAILABLE, without changing business outcome.
The Host records immutable per-request labels and an `installation-display` association:
request ID, labels SHA-256 and actual installation Lock SHA-256. Source versions and Pack
versions are inside the labels; actual loaded artifacts remain bound by installation and
service locks. A hash proves byte identity, not correctness of a label or source behavior.

History readers verify both immutable files and association. Missing/corrupt labels use
technical IDs, not the latest component catalog. Old records without a label reference
are explicitly LEGACY_FALLBACK interpretations. Unmapped operations remain unmapped.
Labels describe an operation; only actual events determine attempts, denial and outcomes.

Schema: `contracts/json-schema/observation/observation-display.v0.1.schema.json`.
Shared positive/negative examples are consumed by Schema and Java checks. Duplicate IDs
and method/route pairs have additional semantic Java/reader checks. Text is bounded and
rendered as text, never HTML. This sidecar does not change Pack V1 or require new business
capabilities. Its failure is an observation gap, not a reason to repeat financial actions.

Validation for a new Provider: supply source metadata, verify exact provider/method/route
matching, wrapper delegation, immutable old-version descriptions, no business calls from
history, wrong identity rejection, and safe display of unknown/malicious strings. Run
relevant generated combinations at fixed commits. See O1 requirements for separate
collector qualification: metadata presence alone is not evidence of capture completeness.
