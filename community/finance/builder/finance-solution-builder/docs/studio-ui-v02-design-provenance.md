# Studio V0.2 Design Adaptation

The accepted `finance-builder-ui-v02` handoff is the visual and interaction input,
not a second deployed application. The five supplied files were read directly;
the existing Builder `examples/local-workbench` is the only implementation entry.

| Design file | SHA256 | Adaptation in this repository |
| --- | --- | --- |
| index.html | d605f6e9d1588cce2df81627dfdef027995ed7ae6e2b2491174b50baae37c568 | Existing index shell, real API application, accessible dialog root |
| styles.css | 69259a7000c5385fd45bdf9ae17552d9f837e0a24269e365f65514a7e34ad104 | Imported into style.css; formatting and live.css responsive constraints |
| catalog.css | ec889edc79dc5a20c8ae8d82506dffb01f925d8a1b9098b73ff1ed44e1a8c026 | Imported into catalog.css; live dialog and real status adaptations |
| app.js | 12b64e85dabff3c63c2139c047cdc11cbd3ccb3167a6998d2e2ec62d13d46c61 | Three-step layout, sidebar, templates, selected lists, drawer drafts and default selection retained in live app.js |
| catalog.js | 4753ac1b528defb390e070191812982a64402d4c430dc0973d2a8ada96ab94e4 | Static arrays replaced by /api/v1/catalog; no concept capabilities registered |

The original prototype's `makeAnswer`, scheduled build successes and predefined
financial replies are not deployed. Real request results, server stages, locked
allowlists and native sessions replace them. Red/white/gray styling and concise
copy remain; required configuration/revocation failures are visible. The existing
Lucide sprite is reused rather than adding another icon system. The live page
uses the accepted design's phone/desktop Web presentations, not native packaging.

## Feature and Compatibility Record

This is a scenario-owned extension above Base's existing task/manifest contracts.
Selection 0.3 adds a bounded multi-model allowlist; selection 0.2 and old requests
keep their single-connection behavior. Model files are additional locked assets,
not changes to the four Harness public contracts or Runtime source.

Acceptance maps to `test_model_registry`, `test_ea_model_import`,
`test_model_switch_generations`, the existing lifecycle/authorization tests,
`verify_multi_model` and `verify_studio_ui`. No paid-model, real-bank, upstream
acceptance or independent-human result is inferred from controlled checks.

Editing a selection creates a new build. Reverting this branch restores the old
page/API without rewriting existing generated artifacts. Old artifacts retain
their own host code; template fixes require regeneration and verification.
