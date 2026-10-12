# Business assets final polish

This revision retains parent module names from the template directory. Child
capabilities continue to use shared capability metadata; IDs and cashflow pairing
are unchanged. Method reading aids now keep source/version and package details
collapsed, and explicitly describe the built-in method rather than the draft.

An unsaved draft's continuation command explicitly saves before changing stages.
Stage tabs only browse. A failed save or a concurrent edit prevents advancement;
duplicate clicks cannot duplicate writes. Advancing never replaces the existing
current-revision trial and publication gates.

Verification: `npm run build:ui`, `node engineering/test_method_state.mjs`, and
`python -m pytest -q` completed; 761 tests passed and one was skipped. The Node
state suite includes six new continuation checks (navigation without writes,
duplicate suppression, awaited success, already saved, failure, concurrent edit).

This is a source revision, not a production deployment record. Final merged
source qualification, deployment and generated-agent verification are reported
separately. Existing agents and published methods are not rewritten.

## Observation-enabled Python installation

Production acceptance exposed that observation-enabled Assembler plans use
`finance-installation-plan.v0.2`, while unassessed plans retain `v0.1`. Both keep
the same method and capability structure. The installation detail projection
now accepts these two explicit versions, without accepting future versions or
treating an observation assessment as authorization. Build identity, registered
Python tools, QUERY operations and private-body filtering remain required.

The new version-matrix regression first reproduced the v0.2 false-negative;
wrong build/tools, writes, unregistered tools and unknown versions remain rejected.
This correction does not modify generated agent code or rewrite old evidence.
