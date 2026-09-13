# Local Read-only Reference Host V0.1

This profile describes a local CUSTOMER self-service demonstration with synthetic bank data.
The finite installation units are `wealth.facts.describe`, `account.balance.query`, and
the pair `finance.transaction-summary.query` + `finance.spending-analysis.query`.
The seven non-empty combinations reuse the existing wealth projection and bank reference
Provider bindings. Multiple Providers do not confer additional scopes or identity grants.
Neither a selected
capability nor this profile grants authority. The host must separately verify the simulator session
and its provisioned, revocable access policy before model invocation and at the tool boundary.
Revocation clears protected conversation context. No business TASK capability is permitted.
