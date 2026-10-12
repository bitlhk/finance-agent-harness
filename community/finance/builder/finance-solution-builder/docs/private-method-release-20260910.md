# Private Method Assets: Shanghai Delivery, 2026-09-10

## Versions And Status

| Component | Actual version / status |
| --- | --- |
| Builder Phase A | `0754ca85`: private lifecycle and native controlled checks |
| Builder Phase B | `963a475fadf4efab3e4d2894e4706455e317d375`: UI, selection and model preview |
| Deployed Builder | `b1e557720cf3b169d92c352b70edb0fbf166f7a8`: preview-click fix, dependency installer and user guide |
| Harness | `b55d0722cac2905388f57dc4a17b85d346a0e470`, clean dedicated source |
| Runtime | OpenJiuwen Java `0.1.13`; SDK source unchanged |
| Simulator JAR SHA-256 | `e40f43df54b4f54b86cf552554c3e4cd65728073a6fa71cc71cab2108681807e` |
| Model | Configured `model-ea-002`, `deepseek-v4-flash`; secrets remain server-side |
| GitCode branch | `feat/studio-private-method-assets-v01`, pushed; upstream acceptance/merge NOT completed |
| Public guide | V0.6 HTML/PDF deployed separately; existing two architecture diagrams preserved |

The running backend implementation is unchanged between `963a475f` and `b1e55772`.
The latter changes the browser blur/click behavior, its test and generated bundle,
plus installation/documentation files. Existing Candidate provenance is not rewritten
to pretend it was built at the newer browser commit.

## What Is Delivered

Business modules remain navigation over verified capabilities. Module detail and
card selection agree; pending modules/cashflow remain unavailable. Private methods
are imported or copied, edited with resources/dependencies/cases, checked by the
server, published immutably and explicitly selected for a new Agent. Old artifacts
are not updated in place. Authentication and the existing phone/desktop/ATM UI remain.

See [operator and contributor instructions](private-method-user-guide.md) and
[API/runtime boundaries](private-method-assets-v01.md). This is a Builder extension,
not a new Core authorization layer or arbitrary uploaded-script execution facility.

## Actual Verification

| Check | Observed outcome |
| --- | --- |
| Phase A old-code negative probes | Four added tests failed before their fixes |
| Clean Phase A acceptance | `0754ca85` clean checkout; real Java default/draft/released method checks and existing 33 controlled-service checks passed |
| Final local Builder Python | 428 passed, 0 failed, 11.97 s; includes four Node editor-state assertions |
| Final server Builder Python | 428 passed, 0 failed, 10.07 s |
| Local asset browser | Four checks, no page errors; dirty publish and cancelled navigation covered |
| Online terminal preview | 131 checks, no page errors; phone/desktop/ATM at desktop/mobile widths, no builds or model calls in this check |
| Online access | Unauthenticated asset API 401; public guide 200; existing login retained |
| Online method lifecycle | Browser ZIP import, saved dependency/resource/case fields through API, browser controlled-check/publish/version-selection/build |
| Native controlled comparison | Default/draft each: 2 model requests, 1 tool result, 2 synthetic source reads; current method observed; zero write attempts |
| Actual generated Agent | Selected private method `1.0.0` in runtime Lock; only `wealth.facts.describe` installed as QUERY |
| Full simulator plus actual model | One wealth conversation, 2 recorded business GETs, source facts/amount/clock returned; 11.15 s Host elapsed time |
| Actual-model preview | Default/draft each: 2 actual configured-model requests and 1 wealth tool call, readable outputs/source/Lock; separate controlled HTTP source |
| Old artifact preservation | 8 previous service Locks unchanged; all 1,078 files referenced by those Locks retain matching hashes |
| Public reader | Local 51 checks and anonymous online 52 checks; no page errors or extra subresource/API requests |
| Human independent onboarding | NOT_RUN; no AI signature substituted |
| Real bank authorization / true Provider substitution | NOT_RUN; all financial data in this acceptance is synthetic |

The controlled comparison measured 31,736 ms. New Agent asset reassembly recorded
309 ms with compatible previously verified binaries; this is a stage duration, not
complete deployment time or a claim of improvement versus a measured old baseline.

The generated Agent is `agent-b8353d0315504342`, built from clean Builder `963a475f`
and Harness `b55d072`. Private method is `method-e623b6a9fef64466@1.0.0`, release
digest `sha256:a1156573082a2e9809b8aa6d76e7130b61eeaf441170360afbf8c6d94b92e522`.
Its acceptance instance was stopped; the artifact and private evidence are retained.
No old Agent was deleted. Service restart did interrupt existing running instances;
retaining artifacts is not a claim that sessions continued uninterrupted.

## Failures Found And Corrected During Deployment

1. The newly prepared venv omitted the Harness-declared date-time format validator.
   The first controlled check failed at `harness_contracts`, before model/bank use.
   Installed the fixed Harness `requirements-conformance.txt`, retained the failure
   record, and reran successfully. Both the release prepare script and the checked-in
   `deploy/server/install-python-dependencies.sh` now install/check these dependencies.
   No format assertion or build stage was removed.
2. Editing the preview question then clicking Run lost the first click because blur
   rebuilt the button DOM. A browser probe observed 0 requests before the fix, and
   exactly 1 after it, with no real model call in the probe. A Node assertion also
   failed on the old code, then passed. Query/version text blur now keeps its action
   target; model selection still refreshes its enabled state. Full 428 tests reran.
3. An early screenshot preceded external SVG painting. Independently checked
   `icons.svg` HTTP 200, image/svg+xml, and nonzero icon geometry; final screenshots
   wait for painting. No authentication bypass or public icon exception was added.

The bundle source is a shallow Git history. Its shallow boundary metadata was
retained on the deployment copy after Git's parent traversal warning; final checkout
is clean and explicitly shallow, not represented as complete upstream history.

## Quality And Scope Limits

Real-model connectivity is accepted; professional output quality is not. The local
preview previously introduced unsupported customer-declared assets and C3 meaning.
The online current-method preview still described SOURCE_REPORTED_UNSPECIFIED as
customer-declared assets despite the instruction. Retain this as a bad case with
`REQUIRES_HUMAN_REVIEW`. No method was silently promoted to a default public method.

The live generated Agent conversation used the full synthetic simulator. The
default/draft previews used the controlled HTTP fixture with the actual selected
model. These are different evidence classes. Neither proves real customer consent,
production bank authorization, investment suitability or stable professional gain.

## Deployment And Rollback

Entry: `https://finance-harness.demo.linggan.top/` (management still authenticated).
Server release directory retains its initial name `20260910-methods-963a475f`; its
actual final Git HEAD is `b1e55772`, checked after the static-browser update.
No second central service, public upload endpoint or new bank operation was added.

Before switching, configuration/drop-ins and the full builds directory were backed
up under `/opt/finance-studio/rollback/20260910-private-methods`. Its `rollback.sh`
returns the Builder symlink to `20260910-device-283e6d52`, removes only this release's
drop-in and restarts the owned service. New private data remains; an old UI may not
understand new selection versions. Use the recorded backup for a separately reviewed
data rollback, never blindly replace current user data.

The public guide edge changed only `public-current`, from `20260909-public-v05` to
`20260910-public-v06`. Old files remain available for rollback; internal guide and
workbench authentication rules were not changed.

## Maintainer Evidence And Handoff

Private verification files reside under `/home/ubuntu/outputs/` on the development
machine. Do not publish complete private drafts, model requests, tokens or credentials.

| Relative directory | Key evidence |
| --- | --- |
| `private-method-phase-a-20260910-clean` | Clean-commit acceptance report and controlled Host records |
| `private-method-live-preview-20260910` | Local actual-model preview and retained bad cases |
| `private-method-online-20260910` | controlled-trial.json, built-agent.json, session-installation.json, real-model-chat.json, observed-installation.json, configured-model-preview.json, old-artifacts-verification.json |
| `private-method-online-20260910` | module-selection.png, published-method.png, model-preview-desktop.png, model-preview-mobile.png, icons-probe.png, actual-installation-lock.png |
| `private-method-online-devices-20260910` | 131-check report and terminal screenshots |
| `private-method-public-guide-20260910-final` | Public HTML/PDF, manifest, local/online reader verification, unchanged diagrams |
| `private-method-release-20260910` | Prepare/switch/rollback scripts, browser acceptance/probes, bundles, public-guide-v06.patch |

Public documentation source had pre-existing uncommitted Harness documentation work.
Only the public guide and its exporter/verifier were changed, preserving that work
and the diagrams. A scoped patch against their pre-change files is supplied; this
does not claim those earlier Harness documentation changes were committed here.
Builder operation instructions are committed in this branch.

The GitCode branch push succeeded. Its server warned that repository LFS usage
exceeds quota; the owner should address it before later pushes become blocked.
No existing MR was merged or silently rebased, and upstream acceptance remains separate.
