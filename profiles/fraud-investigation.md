# Fraud Investigation Assistance Profile V0.1

This profile binds an authenticated employee to an explicit deployment-owned allowlist of
synthetic fraud-source CINO values. The allowlist, source credential and current owner are
Host-controlled. A profile, capability declaration, administrator role or model output never
grants access or permits the model to infer an account, recipient or customer identifier.

The installed scenario is read-only evidence verification. It distinguishes a known-record
match, a complete query with no known match, and an incomplete verification. None of these
states is a transaction decision. A no-match result does not establish safety or permission to
transfer; a match does not establish that the subject committed fraud. Human review remains
required and the scenario cannot execute, approve, block or release a transaction.
