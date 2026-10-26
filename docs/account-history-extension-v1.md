# Account-history extension candidate

Branch: feat/capability-extension-boundaries-v1. Not a production release.

## Source contract

Audited non-ai-banking commit: 162ef4e587c9e9b781bab60bf89591cf4255e579.
BankingController, BankingFacade and BankingContracts provide these existing
read-only routes:

- GET /api/v1/accounts/{accountId}/statement, explicit period (YYYY-MM).
- GET /api/v1/accounts/{accountId}/transaction-page, UTC date range and cursor.

The extension deliberately narrows the source inputs. It requires an explicit
account and absolute period/range; it does not implement guessed dates, account
selection, legal statement documents, automatic pagination or business writes.
Page size defaults to 10 and is limited to 1..100. Only the current page is
reported. Missing monetary fields remain absent, never zero. Source account,
period, currency, dates and decimal shapes are checked before exposing facts.
Unrelated source fields are not forwarded.

## Ownership

- Business contracts: capability-packs/account-statement-readonly and
  capability-packs/account-transactions-readonly.
- Bank-specific parameter validation and projection: AccountHistoryQueries.
- Transport: existing NonAiBankingProvider Client and HostSession binding.
- Permissions: existing account:read in the managed Provider factory; packs do
  not grant scopes or choose an identity.
- Display: each pack's optional presentation.v0.1.json; a bounded record-view
  descriptor, not executable code or a new public Harness contract.
- Runtime: existing OpenJiuwen Java integration, unchanged by this extension.
- Catalog: two pack entries and Provider supports metadata. AgentScope's source
  reference digest changes because the shared Provider declaration changes;
  its supported capability list is NOT expanded.

## Verification scope

AccountHistoryQueriesTest covers invalid inputs, missing facts, exact decimals,
cross-account and out-of-period source records, empty pages and cursor scope.
NonAiBankingProviderTest covers the actual two HTTP routes with a synthetic local
server, existing Host identity, no automatic pagination and zero HTTP for invalid
parameters. Existing query/TASK tests remain mandatory.

Builder engineering/verify_account_history_extension.py builds a candidate with
both packs plus balance. engineering/verify_account_history_chat.py exercises
the native generated Host using a CONTROLLED_TEST model and synthetic source.
This is integration evidence, not real-model quality or a live-bank acceptance.

Do not deploy or migrate existing artifacts from this branch without the paired
Builder acceptance report. Existing deployed images and historical locks are
unchanged. Restarting the process with new SNAPSHOT JARs is not a migration plan.
