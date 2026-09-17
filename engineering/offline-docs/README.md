# Public developer guide

`docs/public/developer-guide.md` is the reviewed public source. The two SVGs in
`docs/public/diagrams/` are embedded into the self-contained HTML and PDF. Do
not publish an internal source archive or a private configuration alongside it.

From the repository root:

```bash
npm ci --prefix engineering/offline-docs
node --test engineering/offline-docs/test-public-content.mjs engineering/offline-docs/public-link-policy.test.mjs engineering/offline-docs/test-public-diagrams.mjs
node engineering/offline-docs/build-public.mjs "$OUTPUT_DIR"
PLAYWRIGHT_MODULE="$PLAYWRIGHT_MODULE" CHROMIUM_PATH="$CHROMIUM_PATH" node engineering/offline-docs/verify-public.mjs "$OUTPUT_DIR"
```

`OUTPUT_DIR` must not exist before the build. The verifier checks four viewport
sizes, offline resources, diagrams and navigation, then writes `handbook.pdf`.
The reviewed publishable files are `index.html` and `handbook.pdf`; the manifest,
screenshots and verification JSON are release evidence. Run the same verifier
against the anonymous live URL after switching the versioned public release.

The internal guide is a separate authenticated asset. This repository does not
build it, and publication must not point its route at the public guide output.
