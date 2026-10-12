# Vertical creation homepage

This UI-only revision uses the existing unified catalog and assembly API. It
does not add an execution engine or change generated Agent templates.

Creation proceeds through horizontal template and business-module strips,
Runtime/model/source configuration, terminal selection and build. Both strips
support continuous mouse drag, touch swipe, horizontal trackpad movement and
smooth arrow scrolling; their browser scrollbars are visually hidden. Vertical
wheel movement keeps its native behavior, and a completed gesture does not
activate a template or capability card. Business modules use two rows with four
visible columns on desktop, two on tablet and one on phone while retaining every
filtered result in the same accessible strip. An available card opens the
existing transactional capability drawer as a whole pointer or keyboard target;
pending cards remain non-interactive.
The actual catalog supplies all module identities, capability bindings and
icons. Search and scrolling change visibility, not selected bindings.
Template and module icon names are business metadata in
`template-modules.v1.json`; the renderer contains no per-business icon map.
Workbench controls use cached inline Lucide geometry so dynamic scrolling and
search do not depend on reloading external SVG symbol instances. Unknown icon
names use the generic Blocks fallback; no business-module lookup is added to
the renderer.

Device previews are illustrative layouts. The phone, laptop and ATM retain
their distinct physical frames and inner layouts. Switching terminal inside
the modal only changes a draft; **use this shape** applies it. Cancel, Escape,
close and backdrop dismiss the draft without changing the Agent selection.
Previewing never invokes a model, bank or SOP.

The Runtime, model/source and interface settings are three visually separated
columns. Each column has its own bounded vertical overflow so additional
catalog entries do not resize the section. Runtime/model headings use the same
typography and color. Terminal choices fill the available row as three equal
buttons; the preview command remains a separate command.

The creation page no longer has a separate SOP selector. A capability detail
can reference a compatible immutable published method release. The Agent has
one private method override, not independent overrides per capability. Its
declared capability dependencies must already be installed in the draft.
Default directory methods, selected overrides and actual installation records
are separate concepts. Closing a module drawer cancels both capability and
method changes; confirming commits both.

Old precise method, Pack and Provider references remain visible in edit drafts.
Unavailable or incompatible references block a new build rather than silently
falling back. TASK/private-method and Python/TASK gates are unchanged. A
template change immediately loads that template's defaults without a browser confirmation.
The create page states that the current draft selection is replaced; saved Agent
versions and their Locks remain unchanged.

## Verification

```sh
node browser/build-ui.mjs
node engineering/test_unified_creation.mjs
PYTHONPATH=src:../base/src python engineering/verify_homepage_vertical.py \
  --harness /path/to/finance-agent-harness --chrome /path/to/chromium \
  --output /path/to/new/evidence
```

The isolated API/browser test uses real catalog and storage APIs with synthetic
legacy records and release metadata. Its inert model connections cannot call a
provider. It verifies UI transactions, not method quality or live authorization.
The shared-task workbench regression separately generates and starts a real
synthetic-bank Candidate to check submitted selection and installed tools.

Deployment may replace only workbench static assets. Such a release has a new
UI commit but keeps the backend/template commit and existing qualification.
Existing Agent installation Locks, identities, configuration, histories and
running processes are not upgraded by a UI release.
