# Parallel Work Overlap

Baselines checked on 2026-10-09:

- Harness main: `884947047fca4bf6563c0068f7cf11656ae964d3`
- Customer manager MVP: `4c1b5a2220f802438cf6ff6be6013d8aa84961d1`
- Customer manager streaming: `6a8f13f5b95345c0aba65369b2c1e98dc9751c17`
- Builder finance: `1da29a4bed618d403b56df9f34f7532a217a98c1`
- Customer manager Builder MVP: `cc31e7e59f9cb2e0be8075628447ba110bfad2dd`
- Customer manager Builder streaming: `f31f24cb001748df6fefcc936ee02951ecbfd210`

## Shared integration files

The audit candidate may eventually need coordinated edits to:

- Harness `pom.xml`
- Harness `catalog/finance-assets.v1.json`
- Builder `template-modules.v1.json` and `unified-catalog.v1.json`
- Builder navigation and workbench routing

Customer manager branches already change those files. This branch keeps audit business logic in new
paths. Shared registration changes are isolated commits and must be reconciled against the fixed
customer-manager commit before unified integration.

## Files deliberately not modified

- customer manager integrations and providers
- shared streaming/model snapshot code
- common report and workbench implementation
- existing local-readonly host files
- any running instance, release directory, reverse proxy or production data

