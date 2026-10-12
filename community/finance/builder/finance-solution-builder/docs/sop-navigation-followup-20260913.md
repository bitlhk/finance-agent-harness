# SOP navigation follow-up (candidate)

Baseline: Builder `13f86168cbcb0b7af70ddacb5993c86176c3e95a` on
`feat/chinese-sop-ui-v01`. This change does not deploy or upgrade existing Agents.

## Implemented

- Rename the private method entry to My SOP (Chinese UI); add all, draft/change,
  and published filters. Archived records remain hidden unless explicitly included.
  A published method with unpublished edits appears in both relevant filters.
- Expose the server-owned component ID in the existing owner-scoped list response.
  Business details link matching, non-archived private methods and show their
  published versions. No source document or additional owner data is exposed.
- Retain the sidebar and its SVG nodes on workbench renders. Agent experience
  remains a separate page. Existing delegated navigation and dirty-state checks
  are unchanged; content rendering still follows the previous mechanism.

Compatibility: `componentId` is an additive, nullable field in the local workbench
asset-list API, not a change to the Finance Harness core contracts. Existing
records without a business binding stay visible under My SOP but are not guessed
into a business detail page.

## Verification

- `npm run build:ui`: passed; generated browser bundle checked in.
- `python -m pytest tests -q`: 771 passed, 1 explicit live Codex test skipped.
- Browser baseline: switching to business assets disconnected the old sidebar.
- Candidate browser bundle, existing local read-only API: desktop 1440x1000 and
  mobile 390x844 retained the same sidebar and SVG nodes, selected the draft
  filter, and had no horizontal overflow. Screenshots inspected.
- No model or banking business requests were made by the browser check.
- Browser evidence: `/home/ubuntu/outputs/chinese-sop-ui-v01/sop-navigation-checks.json`
  and `sop-navigation-{1440,390}.png`. This is a bundle-overlay test, not a claim
  that the full candidate backend or a deployed release was tested.

The first full test run exposed a mock event matching every selector; the filter
handler now accepts only known filter IDs. All pre-existing method dirty-state
and asynchronous tests pass without removing assertions.

## Still pending in the larger delivery

Sidebar collapse/mobile drawer, remaining focus/select polish, standalone device
icon verification, planned transaction-detail metadata, Java simulated transfer
Host/UI and isolated ledger validation, real-model qualification, and controlled
release remain separate pending work. The upstream Skill feasibility table is
not implemented by this change. No merge or production acceptance is claimed.
