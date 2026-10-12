# Business asset contents V0.1

## Developer workflow

Open **Business assets**, select a component, then inspect:

1. **Composition and files**: actual text, purpose, version and SHA-256. A directory component is a composition of referenced assets, not an invented ZIP.
2. **Tools and dependencies**: capability, input declaration, output contract and Provider reference. Inline input schemas remain inside their actual declaration file.
3. **Validation and usage**: recorded validation scope, requirements and owner-scoped Agent references. Each Agent has its own installation Lock; a package does not have one universal Lock.

For a published private method, open **View published package contents** in the method editor. The package files match the existing download endpoint. Referenced files are listed separately and are not claimed to be included in the ZIP. Download or create an Agent using that exact published version from this view.

## What developers change

| Change | Workflow |
| --- | --- |
| Explanation or SOP | Existing method customization, dependency binding, trial and new publication |
| Reference material | Existing import, validation and publication |
| Capability declaration or schema | Development environment, contract and compatibility tests, managed admission |
| Provider implementation | Development environment, compile/test and managed artifact installation |
| Published package, report or Lock | Read only; never overwrite historical evidence |

Adding a tool reference to an SOP requires renewed binding and validation. Previous validation cannot approve a changed method.

The packager creates a verifiable asset archive. Java compilation creates executable JARs. Builder creates the Agent application. Assembler checks and installs its assets. No additional financial compiler or arbitrary online code execution is introduced.

## Read model and version boundaries

- `GET /api/v1/assets/components/{componentId}/{directoryRevision}/contents`
- `GET /api/v1/assets/{assetId}/packages/{version}/{releaseDigest}/contents`

Both return `finance-asset-contents.v0.1`. Existing authentication and owner checks apply. The API accepts asset identities, not filesystem paths.

Published bytes are obtained through the existing immutable release store and trusted Harness packager. The release digest is checked before export. Draft edits do not change the published files. External references are resolved only for the declared ID/version in the pinned local Harness checkout, with the actual commit recorded separately. This does not prove an earlier Agent used that dependency: inspect its installation Lock for that claim. Unavailable versions are not replaced by other versions.

Directory files are read from Git blobs at the captured commit and compared with the local projection. Dirty, missing or symlinked content fails closed rather than being presented as committed content. This implementation requires an intact Git checkout for referenced-file browsing; a source archive alone is insufficient.

Provider source references are not represented as executable JAR digests. Actual executable versions and digests remain in per-Agent build/installation evidence. Usage links mean a matching declared selection, not proof of successful installation. Only the current owner's records are returned.

## Limits and safety

Maximum 128 files, 1 MiB total inspected content, 64 KiB text preview per file. Binary and unsupported file types expose metadata only. Reads are limited to declared capability/domain pack roots and the explicit common contract. Absolute paths, traversal, symlinks and worktree drift are rejected. HTML is escaped. Viewing does not enqueue builds, invoke tools, call models or contact enterprise systems.

There is no general editor, automatic execution of package code, new publication mechanism or automatic upgrade of existing Agents.

## Verification

From the project directory:

```sh
PYTHONPATH=.:src:../base/src ../.venv/bin/pytest tests/test_asset_contents.py tests/test_business_packages.py tests/test_method_api.py tests/test_unified_catalog.py -q
npm run build:ui
node engineering/verify_asset_contents.mjs http://127.0.0.1:18168 /tmp/asset-contents-browser
```

The browser verifier uses `PLAYWRIGHT_MODULE` and `CHROMIUM_PATH` when provided. It checks all admitted components, desktop/mobile layout, text download digest, contract navigation, keyboard tabs and absence of mutation requests. Published-package tests separately compare every package file against the ZIP, test owner isolation, reject invalid versions, and verify that draft changes leave published bytes unchanged.

AI-run verification is not independent developer acceptance. Real developer onboarding, production deployment and any untested private-package browser flow must be reported separately.
