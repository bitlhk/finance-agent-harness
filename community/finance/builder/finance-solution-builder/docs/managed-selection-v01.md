# Managed component selection V0.1

Historical entry contract: version 0.8 remains compatible. The unified creation
page now uses the additive [0.9 contract](unified-creation-v01.md); it does not
require a separate Component mode. Shared TASK support and its controlled
acceptance are documented separately in `shared-task-operator-v01.md`.

Candidate developer entry. The existing WebUI selection versions and legacy
recipes remain compatible; this CLI is not yet a claim that all WebUI choices
have been generalized. Shared TASK and deployment are separate gates.

## Workbench API (candidate 0.8)

The existing owner-protected `/api/v1/preflight` and `/api/v1/builds` now accept
`finance-workbench.selection` version `0.8`. This is an additive API, not a
replacement for existing selection versions or private method releases.
The catalog exposes `managedSelectionSchema` and `managedDirectory`; choose
explicit Pack and Provider identities from its declaration-qualified entries.
The server resolves them again before creating any job. `capabilityIds` must
exactly match the complete selected Packs, and cannot grant permissions.

Required fields are `extension_type`, `schema_version`, `runtimeId`,
`applicationTemplateId`, `bankConnectionId`, `capabilityIds`, `packBindings`,
`selectedModelRefs`, `defaultModelRef`, and the current `modelCatalogRevision`.
Use the same managed Java Runtime, template and connection identifiers as the
existing workbench. `packBindings` has the same shape as the CLI example.
Model references come from the current server catalog, never credentials or URLs.
An optional `presentation` is `MOBILE_WEB`, `DESKTOP_WEB`, or `ATM_WEB`, with
the existing `interfaceConfig` contract (ATM voice remains unsupported).

Version 0.8 does not accept private `methodRef`, custom code, arbitrary paths,
Python or TASK. Private methods keep their existing version 0.7 owner-scoped
path until the combined contract is implemented and tested. The WebUI now has
explicit Scenario and Component entry modes. The component path uses 0.8;
the scenario path preserves Python, private SOP and the existing seven query
combinations. Unsupported choices block building; there is no silent fallback.
The managed template's `selection-support.v0.1.json` provides UI compatibility,
while component admission checks actual Host support before generating code.
The managed build uses the same queue, owner isolation, retention and build
pipeline. Startup checks frozen model configuration and the original artifact;
it does not rebuild an old selection from a changed current component catalog.

Run `pytest tests/test_managed_workbench.py` for selection, binding, preflight,
job creation, managed pipeline dispatch and frozen-start routing checks. These
tests mock process building/startup; the separate native Candidate tests remain
necessary for actual compilation, tool registration and business results.

`engineering/verify_managed_workbench.py` starts an isolated workbench with a
controlled model and source endpoint, selects an additional managed component
in Chromium, invokes the real build API, and verifies the generated native service.
Supply `--harness`, a new `--output` directory, `--maven`, and `--chromium`.
This is not real-model or independent-human acceptance. It does not connect to
or stop production services. Failed runs retain their evidence directories.

## Build

Use the paired Harness branch containing managed factories and the qualified
`wealth-customer-context` companion. With the documented JDK21/Maven/Python setup:

```sh
PYTHONPATH=src:../base/src python -m finance_solution_builder.managed_selection \
  --harness-repo /path/to/finance-agent-harness \
  --input examples/managed-selection/wealth-context.json \
  --output-root /path/to/new-builds
```

This resolves explicit Pack/Provider identities from the managed catalog, validates
compatibility, generates and compiles the existing Host, and checks its Assembler
plan. Input cannot supply a source path, class name, Host support or permissions.
Empty, duplicate, unknown or incompatible selections fail before build output.
The model and trusted simulator access policy are still external Host configuration.

The example adds the original `wealth.customer-context.query` tool, previously
unavailable in the local four-capability recipes. It obtains source wealth context;
it does not substitute the common `wealth.facts.describe` projection or invent a
new bank API. Existing Provider implementation and independently granted
`wealth:customer:read` permission are required.

## Construction and extension

The shared Host uses the managed factory SPI. Its configured dependencies contain
the reference bank and wealth factories in their owning artifacts. Source mapping
and requirements are not duplicated in Builder construction branches. Only selected
factories and their dependencies are constructed; construction gets no bank session
or Grant. Current authorization is still checked at the original boundaries.

For an additional capability on a packaged Provider, contribute its Pack, companion,
Provider support declaration and business tests, then select its identities. If its
source operation is new, implement that operation in the Provider, not Builder.
For a different Provider artifact, a maintainer must first package/review the
dependency and its factory; this CLI is not arbitrary Maven/plugin installation.
No selection automatically adds source permissions or qualifies TASK support.

## Verify

```sh
PYTHONPATH=src:../base/src python -m pytest tests/test_managed_selection.py -q
python engineering/verify_local_service.py /path/to/new-builds/managed-wealth-context/candidate
python engineering/verify_component_installation.py \
  --candidate /path/to/new-builds/managed-wealth-context/candidate \
  --output /path/to/new-installation-negatives
```

The service verifier runs real native SDK calls against controlled HTTP model and
bank endpoints. Inspect `toolResults[].data.capabilityId`, source snapshots,
registered QUERY tools, rejected selectors/grants and zero business writes. It
does not prove live model quality. Keep the generated lock and report together.

The new Host compares the manifest capabilities with the locked QUERY definitions,
not a list of business names. Actual Pack atomicity and Provider binding are also
checked by the Assembler. Old generated artifacts are never rewritten by this change.
