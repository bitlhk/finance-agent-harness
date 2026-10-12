# Customer Method Assets V0.1

## Implementation Record

Baseline: Builder `ee49898366359d1c06ce16e777988f641a52bb90`; clean Harness
`b55d0722cac2905388f57dc4a17b85d346a0e470`. Development branch:
`feat/studio-private-method-assets-v01`. Shanghai deployment and its separate
acceptance results are recorded in the release report; implementation alone does not imply deployment.

This is a Finance Solution Builder extension, not a new Base contract, Runtime,
central asset service or permission engine. Customer method text stays in the
host's private data directory and is never committed to Harness public assets.

- [x] Fixed baseline and preserved the existing UI and user work.
- [x] Owner-scoped drafts, bounded import, revision checks and immutable releases.
- [x] Materialized private method and required reference text through repo://.
- [x] Real controlled Java trial and report-bound publication (not SOP quality).
- [x] Asset-only reassembly with unchanged compatible Host/Harness JAR hashes.
- [x] Asset library, editor, trial comparison and explicit Agent application.
- [x] Negative and existing workflow regression; isolated local environment.
- [x] Delivery report, pushed review branch and non-author onboarding instructions.
- [x] Configured model connectivity/tool preview (professional quality NOT accepted).
- [ ] Independent human acceptance (not signed by AI).

## Accepted Boundaries

The current UI previews a new/example/imported method without persistence. Only
explicit Save creates a draft. The original direct-create/import API remains
compatible for existing clients. Neither preview nor import creates an executable
tool or a permission grant. Publication
freezes content, capability dependencies, selected reference text and the trial
report for the same revision. Applying a published version creates a new Agent
artifact; old artifacts and in-flight sessions are not mutated. Revocation stays
effective at existing authorization boundaries.

V1 executes no uploaded scripts and fetches no URLs from method text. Terminology
may be stored/validated as pending integration but cannot be reported as active.
Required plain-text references must be visible in the installed method, with
source hashes, not merely present on disk. Evaluation expected answers remain
outside runtime prompt resources. Publishing and applying are separate actions.

No changes to Core, bank adapters, Runtime SDK source or public financial
permissions are planned. Only versioned Builder selection and local host method
composition evolve. Existing 0.2-0.6 selections keep their original semantics.

## Phase A API

All routes reuse the workbench owner session, Origin/Host and CSRF checks.
They are not unauthenticated upload endpoints. The HTTP client never supplies
a trial report, `runtimeVerified`, identity, granted scopes or a command.

| Route | Body / outcome |
| --- | --- |
| GET `/api/v1/assets` | Owner's summaries; published versions separate from `hasUnpublishedChanges` |
| POST `/api/v1/assets` | `{data: DraftContent}` creates revision 1 |
| POST `/api/v1/assets/copy-default` | `{capabilityId, name}` copies a shipped host method into a private draft |
| POST `/api/v1/assets/import` | `{filename, base64, name}` imports `SKILL.md` or a safely named ZIP |
| POST `/api/v1/assets/import-preview` | Same three fields; bounded parsing/validation only, no files written |
| POST `/api/v1/assets/example-preview` | `{capabilityId, name}`; reads a shipped example, no private draft created |
| GET `/api/v1/assets/{id}` | Editable draft, validation, current trial and immutable version references |
| POST `.../{id}/save` | `{revision, data}` requires the current integer revision |
| POST `.../{id}/validate` | `{revision}`; returns content digest and structural/dependency checks |
| POST `.../{id}/trial` | `{revision}`; queues explicit controlled Java/HTTP execution |
| GET `.../{id}/trial` | Server job state and revision-bound report; unfinished restart jobs are interrupted |
| POST `.../{id}/publish` | `{revision, version, trialId}`; fresh successful server trial required |
| POST `.../{id}/apply` | `{version, releaseDigest, selection}`; creates a new version-0.7 selection/build |
| POST `.../{id}/delete` | `{revision}`; owner-scoped unreferenced unpublished draft without trial/preview evidence only |
| POST `.../{id}/archive` | `{revision}`; retains all immutable versions/evidence, excludes new build admission |
| POST `.../{id}/restore` | `{revision}`; restores editing, increments revision; requires new trial for publication |

`import` and `copy-default` additionally accept optional `data` for the user's
edits after preview. Original archive bytes are validated again on Save and kept
privately; client-supplied provenance/report/permissions are not accepted. Old
three-field import and two-field copy calls still create a draft immediately.

Archive is not deletion or revocation of old generated Agents. Their frozen
release remains readable, including already-admitted queued builds. New builds
recheck active status under the same Jobs lock used for removal, even if their
release snapshot was read before archival. Ongoing trials block removal. Any
trial/preview evidence requires archival rather than deletion. Archived records
can be shown using the list filter and restored; they are not offered for a new
Agent. The default list API still returns them with `status=ARCHIVED` so clients
must filter selection lists. Store format remains backward-readable: missing
`archivedAt` means active. This is single-workbench process locking, not a
multi-node asset service or Packager lifecycle contract.

`data` has `name`, `kind`, `maintainer`, `content`, `resources`,
`requiredResources`, `capabilityIds`, and `cases`. Each case has `name`, `query`
and optional `expectedTerms`. Capability dependencies are limited to the two
verified read-only capabilities. Adding a dependency does not install it or grant
access: the build selection must already include it, otherwise application fails.

ZIP accepts `SKILL.md` at root or within one enclosing folder, plus bounded text
under `references/` and inert files under `scripts/`. No network references are
fetched and no scripts run. Required script use blocks validation. ZIP imports
retain original bytes privately. Publication writes the manifest and bound report
atomically; later report/content tampering blocks release retrieval.

## What The Controlled Trial Proves

One compatible Host is compiled with the existing Builder. Default and draft
Candidates reuse verified JAR bytes, then separately start real OpenJiuwen Java
processes against loopback synthetic bank/model endpoints. The server checks the
instance against its Lock, actual installed QUERY tools, source reads, and the
method/reference text in the actual model HTTP request. The runtime installation
asset ID/version/digest must match too. Owner-private request/tool/Lock evidence is
retained with digests; each trial removes its duplicate dependency JAR directory.
The reusable seed and deliberately applied Agent remain available. Operators own
retention/backup of old seeds and private trial evidence; no global automatic
deletion of user Agents is introduced.

The trial comparison is **default versus draft loading and execution**, not
professional quality. Scripted model responses are explicitly `CONTROLLED_TEST`;
`professionalQuality=NOT_EVALUATED`, including when `expectedTerms` is empty.
Terms-in-instructions is a diagnostic, not a claim about real model compliance.
Case expectations are never appended to the Agent method or prompt resources.
Real-model readable answer comparison and the asset WebUI are implemented in Phase B.

Trial installs use provisional version `0.0.0`; publication freezes a separately
named immutable release and digest. The acceptance command additionally installs
the released `1.0.0` in a new Candidate and observes its real runtime Lock/text.
Published assets do not silently update existing Agents or sessions. A balance-only
method leaves the default wealth Domain Pack in a combined Agent unchanged.

## Reproduce Phase A

Use Java 21, Maven, and the same Python environment/dependencies as the Builder.
From this directory:

```bash
PYTHONPATH=src:../base/src python -m pytest tests -q
PYTHONPATH=src:../base/src python engineering/verify_private_methods.py \
  --harness /path/to/clean/finance-agent-harness \
  --data /path/to/new-empty-private-acceptance-directory
```

The second command is explicit and can compile/install local Maven dependencies.
It uses no real bank/model credentials. Inspect `acceptance-report.json`, the
seed build logs, private per-case `method-evidence`, and the released Candidate's
`controlled-service-report.json`. Never publish private method evidence wholesale.

Initial worktree verification on 2026-09-10: Java compilation and Harness reactor
passed; default/draft/released Host checks passed; the released Host's existing
controlled-service verifier passed 33 checks. This is not real-model, full-bank-
simulator, UI, deployment or independent-human acceptance. A clean-commit rerun
and its source/lock identities are recorded separately in the delivery report.

## Phase B Workbench And Model Preview

The `Business assets` page edits private drafts, resources and capability
dependencies. An immutable published version is explicitly selected in the Agent
configuration. The version's own frozen capability dependencies are used, never
those of a later draft. The UI and server both reject publishing an unsaved or
unverified revision. Asynchronous responses are scoped to the editor generation;
late results cannot replace another method's editor/report. Edits made during an
in-flight save are preserved and require another save/check.

The 26 upstream business modules remain navigation, not 26 installed capabilities.
One module selection owns its verified capability group; the detail view and
summary derive from the same set. Unavailable tools remain disabled. Grouped tools
have one combination checkbox, not misleading independent selectors. Dependencies
are visible and cannot be removed while required by the selected method. Core
authorization and evidence controls are not feature switches. The existing mobile,
desktop and ATM previews are retained.

Build pages label selection-derived information `planned assembly`. Actual
installation is displayed separately from recorded runtime Locks. A recorded Lock
belongs to the immutable artifact and does not claim a current authorized session.

POST `.../{id}/preview` accepts exactly `{revision, modelRef, query}`. GET the same
route reads its independent job/result. It requires a configured server-owned
model connection; unavailable/disabled connections are rejected. Both default and
draft methods run with that model against loopback synthetic bank HTTP. Connection
version/state is rechecked before forwarding each model request. Model keys are
not copied to Candidates, responses or evidence; expected case answers are never
included in prompts. The preview shows readable answers, tool facts and source
calls. It does not create the server trial required for publication or an
automatic professional quality approval.

```bash
# Explicit network model use; may consume configured model quota.
PYTHONPATH=src:../base/src python engineering/verify_method_preview.py \
  --harness /path/to/clean/finance-agent-harness \
  --data /path/to/private-preview-directory \
  --model-ref model-ea-002 \
  --environment-file /private/path/to/model.env
node browser/build-ui.mjs
node engineering/test_method_state.mjs
```

Initial real-model preview used the configured `deepseek-v4-flash`: default and
draft each made two model requests and one wealth tool call. The draft and
reference text were observed in the native model request and runtime Lock. This
proves connectivity and installation only. The draft answer introduced unsupported
`customer-declared assets` and `C3 = conservative/stable` interpretations. Those
are retained bad cases; professional quality remains `REQUIRES_HUMAN_REVIEW`.
No user method is silently promoted to a default production method.

Phase A implementation commit: `0754ca85`. Its clean-checkout acceptance report
was generated separately from the initial dirty-worktree run. The public package
must not include private drafts, complete model requests, credentials or local
customer evidence; maintainer verification paths are listed in the release report.

See [Shanghai delivery and limits](private-method-release-20260910.md) for actual
deployment, discovered fixes, retained bad cases and upstream acceptance status.
