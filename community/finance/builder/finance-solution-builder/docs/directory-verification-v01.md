# Directory UI V0.1 Verification

Date: 2026-09-09. Implementation: `d05c9ede`; stream-observation test correction: `debd1e86`.
Based on `ca71f04b`, scoped to Finance Solution Builder. Base and generated Java host are unchanged.
This is a local contribution branch, not evidence of upstream acceptance or standardized FEAT-003/005 completion.

## Reproduction

Install the Builder/Base Python dependencies and the Harness `engineering/requirements-conformance.txt`.
Plain `jsonschema` is insufficient: the required date-time format validator must be installed.
Use Python 3.12, JDK 21, Maven, Node 22, and Playwright/Chromium for the following observed run.

```bash
python -m pytest tests ../base/tests -q
python -m pytest tests/test_directory.py -q
npm run build:ui
python engineering/verify_multi_model.py --harness <harness-checkout> --output <new-output> --directory --browser
```

The optional browser requires `@playwright/test`; set `NODE_PATH` when installed outside this project.
Do not change the Builder commit during an active build: source consistency checks intentionally reject that build.

Run `engineering/verify_directory_ui.mjs` against the working URL with a new output directory.
Default mode checks metadata only; `build` compiles a wealth-only component selection;
`live` explicitly creates, starts, converses with and stops two new Agents using the configured model and local simulator.
Live mode is not part of ordinary unit tests. Protected deployments use `STUDIO_TEST_USER` and
`STUDIO_TEST_PASSWORD` only in the verifier environment; never store these values in artifacts or Git.

## Observed Results

| Scope | Result |
| --- | --- |
| Builder and Base unit tests | 288 passed, 2 skipped; repeated in the staged server environment |
| Directory regressions, included above | 23 passed |
| Local directory browser | 22 checks, no browser errors; 1440/820/390/320px |
| Three compositions, native controlled endpoints | 61 checks; model A 8 requests, B 11, unselected C 0 |
| Controlled browser lifecycle | 46 checks; real builds, model switch, AG-UI, revoke, narrowed new instance and delete |
| Deployed page with configured model and local simulator | 37 checks; component wealth and template balance builds, three dialogue turns, both instances stopped |

The configured-model run observed financial tools and source calls for both entries. The second wealth-session
turn was an explicit greeting-only request and made zero financial tool/source calls. This is a smoke result,
not a general model-quality guarantee. Runtime installation Locks contained only the selected QUERY tools.

## Corrections During Verification

- Legacy single-model environment injection cannot start an artifact frozen with the new model registry.
  The registry-aware verifier uses actual configured bindings; no placeholder key was added.
- An initial browser attempt lacked Playwright test dependencies.
- Removing an inline selected component removes its card. The old uncheck/recheck locator was migrated to
  removal plus re-addition through the shared component picker, retaining the behavior assertion.
- A commit made during an earlier build caused a legitimate source-change rejection; the fixed-commit rerun passed.
- A fresh server environment lacked the required date-time format dependency; installing the existing requirements fixed it.
- Chromium evicted streamed response bodies from its debugger cache. The test now observes the real Fetch
  response clone, preserving standard event-schema checks without replacing application data or the model.

Failed attempts remain separate evidence; they are not silently relabeled as successful runs.

## Limits

Employee is an inspect-only view of shared IDs; changing a directory flag cannot enable the CUSTOMER-only host.
Engineering remains disabled. No new Runtime, transfer capability, online catalog editor, real-bank IAM acceptance,
same-authorization production Provider substitution, human independent onboarding or stable business-quality claim.
Existing old requests remain compatible; new builds freeze directory/model selections and installed asset identities separately.
