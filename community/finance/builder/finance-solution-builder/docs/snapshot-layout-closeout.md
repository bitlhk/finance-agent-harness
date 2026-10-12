# Snapshot detail layout closeout

The reviewer found model snapshot detail confined to the timeline's first grid
column. The detail now spans all columns. Captured JSON bodies are parsed for
indented display, with bounded vertical and horizontal scrolling; malformed or
truncated JSON remains readable as captured text. Metadata is collapsed. Only
one copy of the selected body is rendered, in its model span when available.
State labels are translated; original codes remain in metadata and tooltips.

This is presentation only: capture position, authorization, evidence content,
and claims about HTTP dispatch are unchanged. Static assets were rebuilt in the
isolated reviewer workbench without restarting it or changing its owner.
Stable deployment and old candidate locks were not changed.

Validation on the saved synthetic slow request
`c431078a-3888-4de6-b46d-edbff6f00c21`:

- `node engineering/test_snapshot_summary.mjs`: passed, including parsed and
  malformed bodies and localized state labels.
- `python -m pytest tests/test_model_snapshots.py -q`: 5 passed.
- `engineering/verify_snapshot_layout.py`: 9 checks passed. Request and response
  each render once, with collapsed metadata, full timeline width at 1440, 768,
  and 390 pixels, and bounded scrolling. Browser history inspection issued only
  GET requests and no Agent conversation endpoint request.
- Screenshots inspected at
  `/home/ubuntu/outputs/model-snapshot-release-v01/layout-fix-2/`.

The initial layout probe misclassified the static `ag-ui.js` asset as an Agent
endpoint. The corrected assertion checks endpoint paths rather than substrings;
the initial failed run is retained as `layout-fix` and not counted as passing.
The separate page-build verifier now uses CSP-compatible locator assertions.
This layout test does not prove a new candidate build, real-model quality, or
independent human acceptance.

Follow-up: actual `FILTERED` and `BODY_NOT_VERIFIED` values now display as
filtered/redacted and body not yet verified, without changing their technical
codes. Long text wraps within the full-width panel. The same nine browser checks
passed again, additionally asserting no horizontal body overflow at all three
widths. `layout-fix-3` contains screenshots of the complete model step, including
its timing row, request/response controls, selected body, and collapsed metadata,
rather than only cropped body text. Static resources were hot-updated only.
