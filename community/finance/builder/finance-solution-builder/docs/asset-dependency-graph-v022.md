# Asset Dependency Graph V0.2.2

The default composition view projects the existing versioned `composition.nodes`
and `relations`. It displays methods, capabilities, Provider references and their
direct contracts. Package containers and other source nodes remain in the index.
ELK 0.10.0 supplies layout only. No edge editing, execution planning or admission
logic is introduced.

## Semantics

- Provider -> capability means implements; Skill -> capability means declared use.
- A declaration file may contain distinct input definitions for multiple capabilities.
  Edges say `输入定义位于`, not that those inputs share a contract.
- Independent schema references retain their contract relationship. The generic
  result envelope is not a complete financial domain schema.
- Inbound edge counts do not prove reuse. There are no automatic shared badges.
- Node and file identities retain their original versions. Focus reports hidden
  objects; the full index preserves other references.

Capability explanations may use the existing current catalog presentation metadata,
explicitly labeled as such. Original source descriptions and file bytes remain
available, not overwritten or translated. YAML source marks locate the selected
capability in its declaration file; different capabilities in the same file keep
separate locations.

## Interaction And Compatibility

Desktop shows directed labeled edges, a read-only inspector, zoom and fit controls.
The existing CSP is unchanged; dimensions are set by trusted script after render.
Mobile shows source, verb and target from the same relation set and preserves the
selection/scroll position when returning from the inspector.

Leaving assets clears the selected catalog component along with its detail body.
Generation checks reject stale loads; this fixes a previous title/body mismatch.
File viewing, draft/edit/publish, Packager and Builder remain unchanged.

## Verification

`node engineering/test_asset_graph.mjs` checks generic projection, direction,
version identity, missing references, shared nodes and geometry.

`node engineering/verify_asset_graph.mjs URL OUTPUT` checks real catalog relations,
1440/1366/1280/390/320 layouts, selection, file targeting, navigation races and zoom.
Use `PLAYWRIGHT_MODULE`, `CHROMIUM_PATH` and optional `FH_UI_AUTH_FILE` as in the
existing browser checks. This read-only verification does not call business tools.

Run the existing `verify_package_studio_lifecycle.py` separately for immutable
publication, Java/Python builds, actual model and synthetic-bank execution. Diagram
tests are not a substitute for lifecycle, authorization or professional evaluation.
