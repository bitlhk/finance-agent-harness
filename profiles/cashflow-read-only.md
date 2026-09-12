# Cashflow Read-only Reference Host V0.1

CUSTOMER self-service with synthetic bank data. Install only
`finance.transaction-summary.query` and `finance.spending-analysis.query`.
Purpose: describe known monthly account statement cashflow, booked analysis and information gaps.

The host must supply a currently verified bank session and separately provisioned `cashflow:read`
grant for the subject. Required scopes and this profile never grant authority. Recheck before
model calls and tools; revoke protected history on loss of access. No TASK tool is permitted.

This candidate profile is not a bank IAM policy, a fraud decision or suitability approval.
