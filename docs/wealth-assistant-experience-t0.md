# Wealth assistant experience: T0 baseline

This change is based on Harness candidate
`0422795d0a6aa0179eebc0b36a5c5343b536016f`. The paired Builder candidate at T0 is
`9f4e4dd8c9dea9bb7acad5c399e7d1ea1f76babb`.

The real development workbench uses the standard Assistant UI and the managed Host path.
The Host accepts both the current `manager-conversation.v2` contract and the compatibility
chat request. Direct product and customer tasks must be reachable through both paths.

This change keeps task state inside the existing customer-manager ScenarioExtension. A
direct catalog or customer task abandons an unfinished product-screening execution so a
later short answer cannot resume the stale task. Ordinary journey references, provider
authorization, installation locks, audit receipts, stop and history mechanisms are not
replaced.

The change does not modify Harness Core, introduce a workflow runtime, expand permissions,
or alter a formal deployment. Rollback is a normal revert of this commit; persisted Agent,
installation and history records are unchanged.
