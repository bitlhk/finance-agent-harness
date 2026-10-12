# Studio navigation release candidate

Extends `b0cb6c34`; the full release gate remains open until actual artifacts and
deployment are qualified. This document is not a deployment record.

## Changes

- Persistent desktop sidebar collapse, explicit names on icon buttons, mobile
  drawer with focus containment, Escape, inert background, and scroll lock.
- Sidebar uses 14px labels and 20px stroke icons. Gray navigation and fine borders
  follow the existing EA-style workbench convention.
- Pointer focus uses a quiet input border; keyboard navigation retains a visible
  outline. Native domain selection keeps keyboard behavior and 14px arrow inset.
- Directory v0.1.2 records transaction-detail querying as `NOT_INTEGRATED` metadata
  under account service. Business details and module drawer consume the same
  metadata. It is not a capability ID in the installed tool set; falsified
  available status or existing executable IDs in this metadata fail validation.
- No change to published SOP files, customer versions, old Agent artifacts, or
  readonly execution authorization.

## Verification to date

Full candidate runs at loopback 18588 using its own workspace, not a browser bundle
overlay. Tests used the authorized synthetic bank connection. No bank writes.

- Full Builder regression: 775 passed, 1 live Codex test skipped before the directory
  display-version increment; directory/business regression rerun for that change.
- Browser: stable navigation, five retained navigation items, correct active page,
  desktop collapse retained after reload, pointer/keyboard focus, native select
  inset, mobile drawer focus/Escape/overflow, disabled transaction-detail entry.
- Full backend: business-derived private SOP, edit/save, source association,
  generated Java controlled trial, immutable publish, and creating an Agent from
  the published version passed. This does not overwrite any existing method.
- First live Java two-turn synthetic-bank dialogue completed and recorded its
  installation Lock. Stronger exact tool/source checks and final regenerated
  Java/Python artifacts remain part of release qualification.

Evidence is under `/home/ubuntu/outputs/chinese-sop-ui-v01`: `full-ui-tests.xml`,
`full-navigation-checks.json`, navigation screenshots, `ux-progress.json`,
`ux-trial-status.json`, `ux-build-report.json`, and `ux-live-checks.json`.

The first screenshot exposed an active-item bug caused by the brand button sharing
the create navigation ID. Rendering now matches only `.nav-item` nodes. Browser
checks explicitly assert all five nav items and the current assets selection.

Standalone generated Python icons, all device surfaces, exact Chinese instruction
installation with real models, release/rollback and postdeploy checks must be
reported separately before merge/deployment is announced. Java transfer remains a
separate candidate, not enabled by this UI change.
