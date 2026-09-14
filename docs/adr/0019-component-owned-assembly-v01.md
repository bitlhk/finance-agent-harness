# Component-owned assembly V0.1 (candidate)

Builder understands Harness assembly contracts, not concrete financial business.
This increment does not introduce a workflow engine or change bank authorization.

## Decision

Keep existing Catalog and Pack V1 contracts. A component may publish a strict,
versioned `assembly.v0.1.json` companion in its catalog-registered source directory.
Absence means not qualified for declaration-driven assembly, not unrestricted support.
Capability Pack companions declare installation requirements and default methods.
Provider companions declare tested Pack versions, operations and connection kinds.
Host templates declare supported protocol features, runtime and profile separately.
The resolver checks their intersection, catalog identities and actual Pack contents.
There is no central business-name-to-recipe registry and no execution of asset code.

Bindings are explicit host configuration. An ambiguous provider is never selected
by name or discovery order. Required scopes remain requirements; companions do not
grant identity, customer access or permission. Bank/host grant checks remain mandatory.

## Compatibility and evidence

Old requests and generated agents retain their original recipe, Lock and assets.
The initial resolver is opt-in preflight; qualifying its output does not mean a
new Provider factory or TASK UI exists. Companion files join the asset tree digest,
so newly built locks change while old artifact contents are never rewritten.
Compare all seven readonly combinations with frozen legacy recipes before switching
new builds. Reject missing declarations/dependencies, unsupported features and
version mismatches before download, generation or registration.

## Subsequent gates

Provider factory wiring and shared TASK integration are separate gates. Python does
not declare TASK support. Reuse existing trusted confirmation and checkpoint APIs.
The candidate second business is `channel.preferences.update`: the reference bank
Provider already uses the bank task lifecycle for preference updates. Its source,
frozen parameters and distinct business result must be verified before qualification;
it is not yet enabled by these declarations. Human independent onboarding remains
a separate acceptance item.
