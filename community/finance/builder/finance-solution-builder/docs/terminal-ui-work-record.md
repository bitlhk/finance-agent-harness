# Terminal UI V1 Work Record

## Delivery Status (2026-09-10)

The terminal implementation has been committed, pushed and deployed to the protected
Finance Studio demo. This record supersedes the earlier stale "uncommitted/not deployed"
status. Human phone input and independent onboarding remain separate acceptance items.
Design: Builder terminal UI V1 handoff, 2026-09-09. Cashflow candidates are unchanged.

## Verified Baseline

- Builder branch: `feat/studio-terminal-ui-v01`, base `c0f086de` (finance).
- Previous production Builder: `dc9f2cff15dd279da462df628be1eed71e06bcd1`.
- Terminal implementation: `4ba651f3e34704a421a5439e23a87afdc2ba9066`, deployed and tested.
- Upstream finance documentation merged in `35efb827`; no terminal implementation changes.
- Decimal display fix: `a8064117996cd7ebb591550959d018ce9607a933`, deployed;
  `finance-studio.service` active. It changes the generated display projection and its tests only.
- Harness build baseline: `b55d0722`, unchanged by this UI task.
- Branch: `feat/studio-terminal-ui-v01`, pushed to the colleague's Builder repository.
  Maintainer review/merge is not claimed complete.

## Checklist

- [x] Recover design, verify worktree and production baseline.
- [x] Session cancellation/expiry and stale-response regressions.
- [x] Legacy selection tests and frozen resource checks; old record hashes retained.
- [x] Builder Python, Harness Java and generated artifact regressions.
- [x] Mobile, desktop, ATM and alternative balance-card browser checks.
- [x] Standalone exported artifact outside Builder/Harness repositories.
- [x] Actual XFYUN ASR using server-only credentials and recorded reference audio.
- [x] Actual model checks recorded separately from controlled HTTP fixtures.
- [x] Customization tutorial, screenshots and itemized verification report.
- [x] Implementation branch pushed; protected deployment has a retained rollback version.
- [x] False missing balances reproduced on old implementation and corrected in display projection.
- [ ] Builder maintainer acceptance/merge.
- [ ] Human phone microphone/keyboard and independent developer acceptance.

Details and evidence boundaries: [verification report](terminal-ui-verification-20260910.md).

## External Acceptance

Physical phone keyboard/accessibility testing and a human independent developer
run are not performed by AI browser tests. Record these separately. ATM is a
browser simulation, not certified hardware. Existing model and ASR services are
external dependencies; configuration alone is not an acceptance result.
