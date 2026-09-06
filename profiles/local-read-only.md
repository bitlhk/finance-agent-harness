# Local Read-only Reference Host V0.1

This profile describes a local CUSTOMER self-service demonstration with synthetic bank data.
Only `wealth.facts.describe` and `account.balance.query` may be installed. Neither a selected
capability nor this profile grants authority. The host must separately verify the simulator session
and its provisioned, revocable access policy before model invocation and at the tool boundary.
Revocation clears protected conversation context. No business TASK capability is permitted.
