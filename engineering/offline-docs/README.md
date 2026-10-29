# Public developer guide

`docs/public/start.md`, `first-agent.md` and `developer-guide.md` are the reviewed public sources; `edition.json`
beside it fixes the version, date and implementation baseline. Three SVGs in
`docs/public/diagrams/` and two reviewed actual UI screenshots in
`docs/public/screenshots/` are embedded into the self-contained HTML and PDF. Do
not publish an internal source archive or a private configuration alongside it.

From the repository root:

```bash
npm ci --prefix engineering/offline-docs
BUILDER_ROOT="$BUILDER_ROOT" node --test engineering/offline-docs/test-public-content.mjs engineering/offline-docs/public-link-policy.test.mjs engineering/offline-docs/test-public-diagrams.mjs engineering/offline-docs/test-public-pages.mjs
BUILDER_ROOT="$BUILDER_ROOT" TUTORIAL_PYTHON="$TUTORIAL_PYTHON" node engineering/offline-docs/verify-tutorial.mjs "$SNIPPET_EVIDENCE_DIR"
node engineering/offline-docs/build-public.mjs "$OUTPUT_DIR"
PLAYWRIGHT_MODULE="$PLAYWRIGHT_MODULE" CHROMIUM_PATH="$CHROMIUM_PATH" node engineering/offline-docs/verify-public.mjs "$OUTPUT_DIR"
```

`OUTPUT_DIR` must not exist before the build. The verifier checks five viewport
sizes, offline resources, diagrams and navigation, then writes `handbook.pdf`.
The reviewed publishable files are `index.html`, `handbook.pdf` and
`first-agent-cases.json` (unexecuted manual acceptance records); the manifest,
screenshots and verification JSON are release evidence. Run the same verifier
against the anonymous live URL after switching the versioned public release.

`BUILDER_ROOT` is the fixed Builder project directory, not its monorepo root.
`TUTORIAL_PYTHON` is its already provisioned Python interpreter. The snippet
verifier creates only small JSON/log files in a new `SNIPPET_EVIDENCE_DIR` and
executes the actual solution and compatibility CLI from the tutorial, including
no-overwrite and unknown-impact rejection. It does not build or start an Agent.
Source links show reviewed paths and commits in a dialog, not private contents.
The original reference page ID and all original heading IDs remain supported.

The manifest records the Git source commit, dirty flag, and content/image
hashes. Publish only from a clean, fixed commit after review; do not edit the
deployed HTML independently. Source commit and implementation commit are
different: a documentation release does not upgrade the running SDK or Host.

The internal guide is a separate authenticated asset. This repository does not
build it, and publication must not point its route at the public guide output.
