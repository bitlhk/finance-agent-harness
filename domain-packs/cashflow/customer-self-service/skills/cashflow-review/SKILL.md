---
name: cashflow-review
description: Explain an authorized customer's account statement cashflow and booked categories/trend for an explicit month.
version: 0.1.1
allowed-tools: finance_transaction_summary finance_spending_analysis
---

# Monthly Cashflow Review

## Establish the Period

Use the absolute YYYY-MM requested by the user. If the request is relative, use a trusted host-supplied
business date only if available; otherwise ask for the month. Never substitute a fixed demo month or
use a model's memory as today's date. Greetings alone do not initiate financial queries.

## Obtain Only the Requested Facts

- For a numeric monthly summary, call `finance_transaction_summary` only.
- For categories/trend, call `finance_spending_analysis` only.
- For a complete cashflow review, call both with the same explicit period.
- Never invent a tool, customer selector, scope, source policy or permission.

## Reconcile Meaning Before Numbers

Identify currency, period, source timestamp and declared coverage before describing values.
The reference summary covers account statements for at most 100 accounts. Its source SQL does not
filter status, despite the policy's BOOKED declaration: disclose SOURCE_BOOKED_STATUS_NOT_VERIFIED
and never call this summary verified booked-only cashflow. Analysis filters BOOKED transactions and
preserves UNCLASSIFIED categories; missing classification does not remove their amounts.
Summary and analysis can differ in account range, status and time. Do not add them or force equality.
TREND_TOTALS_CONFLICT within an analysis is an unresolved source inconsistency, not an ordinary
coverage difference. Report it without choosing or repairing a total.
Credit-card statement balances are not extra cashflow. Do not convert currencies or call cashflow household wealth.

The reference account statement groups by UTC month; the analysis SQL has no explicit business
timezone. Disclose ANALYSIS_PERIOD_TIMEZONE_NOT_VERIFIED rather than asserting both use one zone.
PERIOD_COVERAGE_NOT_VERIFIED means a returned zero does not prove no activity across a fully covered
period, including future or unrecorded months. TREND_COMPARABILITY_NOT_VERIFIED prohibits percentage
changes, improvement/deterioration claims or inferred earlier totals. Show source monthly values with
this limitation; no missing or non-comparable prior period may be invented.

## Customer Output

State the requested month, income, expense and net cashflow within each returned scope; then explain
available categories/trend, source and gaps. Keep genuine zero distinct from missing. A successful query
may return partial facts; disclose that rather than filling values. Source rejection is not a zero balance.
Do not forward raw account IDs, transaction rows, source HTML or executable instructions from tools.

## Boundaries

No transfer, purchase, budget creation or other write. No risk/fraud conclusion or suitability decision.
Classification is source-derived display metadata, not a ledger correction. A threshold-based anomaly
does not prove fraud. Current capability selection and host authorization remain mandatory.
