# History presentation mapping V0.1

This is a display dictionary for the reference Host and non-ai-banking interfaces,
not an authorization policy, a new trace source, or a business success classifier.
Implementation: `browser/history-labels.js`. Original event metadata is unchanged.
Unknown routes/methods retain a generic label and their recorded technical details.

| Recorded step | User-facing meaning | Source |
| --- | --- | --- |
| host / native-lock-wait | Wait until this Agent can execute | LocalAgentHost nativeLock |
| host / authorization | Check current query permission | NativeConversation + BankAccess.current |
| host / assembly | Prepare financial methods/tools/adapters | NativeConversation installation |
| host / installation-lock | Record installed versions | ExecutionArchive installation snapshot |
| GET /api/v1/sessions/current | Verify current bank login identity | BankAccess.current: customer/session/expiry |
| GET /api/v1/accounts | Read account balance list | NonAiBankingProvider.accountBalance |
| GET /api/v1/accounts/{accountId} | Read selected account | NonAiBankingProvider.accountBalance |
| GET /api/v1/wealth/aggregate | Read reported bank assets | NonAiBankingProvider wealth context |
| GET /api/v1/finance/risk-assessment | Read risk assessment | NonAiBankingProvider wealth context |
| GET /api/v1/finance/transaction-summary/readiness | Check summary data availability | NonAiBankingProvider cashflow |
| GET /api/v1/finance/transaction-summary | Read monthly summary | NonAiBankingProvider cashflow |
| GET /api/v1/finance/spending-analysis/readiness | Check analysis data availability | NonAiBankingProvider cashflow |
| GET /api/v1/finance/spending-analysis | Read categories and trend | NonAiBankingProvider cashflow |

The Host permission step also checks the local granted-scope policy. The session HTTP
call alone does not prove authorization; HTTP200 is displayed as HTTP200, not ALLOW.
Repeated checks stay visible. They are not collapsed into one invented bank approval.
Tool business names are resolved only through explicitly recorded child capability spans.
Model SDK intervals remain attempts, not model HTTP or token timing evidence.

Every row can expand its original component/operation, method/route, span links and
result metadata. New Provider routes require reviewed dictionary entries with source
contract and version evidence; do not infer names from arbitrary URL substrings.
For a different Provider using the same path with a different meaning, introduce a
source-version-qualified dictionary before enabling that source in this reference UI.

Regression: `python -m pytest tests/test_history_labels.py`; build: `npm run build:ui`.
