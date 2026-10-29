# R54 offline reliability candidate

## Scope

- Base: `8056ab2`
- Branch: `codex/r54-offline-reliability`
- Offline only: no deployment, service restart, authorization change, online Agent creation, Provider call, or model call.
- Frozen R53 evidence remains unchanged for the customer-manager stream failure, audit authenticity wording, and post-loan freshness wording.

## Changes

1. Model-stream diagnostics record only observable, redacted termination signals. They distinguish iterator exhaustion, socket read timeout, timeout with unspecified source, cancellation, interruption, malformed payload, EOF, socket failure, and generic I/O/runtime failure. Unknown HTTP status, upstream request ID, send count, and downstream disconnect remain explicitly unobserved.
2. Audit results now express `authenticityStatus=NOT_VERIFIED` separately from an explicit `FAILED` result. The explanation guard rejects failure or fraud wording when any result is still not verified.
3. Post-loan facts now carry a separate freshness assessment. Query completeness does not imply freshness. Without a versioned freshness rule, the status is `NOT_EVALUATED` and the report may only state the data timestamp and pending verification.

## First candidate failure retained

The first targeted reactor run failed in `EnterpriseCreditMaterialAuditCasesTest.frozenCasesMatchDeterministicTruth` for frozen case `rule-01-expired`: the unavailable-rule branch did not project `authenticityStatus=NOT_VERIFIED`. The implementation was corrected by applying the same decision-boundary projection to every completed branch. The subsequent targeted reactor passed.

This was a candidate-test defect, not an online business execution. It is retained here rather than hidden by the successful rerun.

## Verification

- Targeted reactor after the projection fix: passed.
- Full Maven reactor before the final timeout-classification tightening: passed all 26 modules; live tests requiring external systems were skipped by their existing conditions.
- Final affected-module reactor after all changes: passed all 13 selected/dependent modules.
- Final full Maven reactor after all changes: passed all 26 modules in 81 seconds. Existing live tests that require external systems remained skipped by their normal conditions.

## Evidence limits

- The R53 stream failure root cause is **not fixed or established**. Existing evidence does not distinguish an upstream SSE failure from an SDK/downstream cancellation. This candidate adds future causal diagnostics; it cannot retroactively recover missing HTTP or gateway evidence.
- SDK retry configuration is not recorded as an HTTP-send count.
- No professional-quality, public SSO, installation-qualification, real-model, or live Provider acceptance was performed in this offline round.
- Existing generated Agents do not automatically receive these Host/library changes; later acceptance requires rebuilt candidate artifacts.
