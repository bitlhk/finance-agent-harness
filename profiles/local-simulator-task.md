# Local Simulator Controlled Tasks V0.1

CUSTOMER self-service using synthetic bank data only. QUERY and TASK components
may be combined when the Host supports their declared lifecycle requirements.
This profile grants no permissions. Current simulator identity, independent local
policy and source step-up must be verified by the trusted Host and Provider.

Only the bank defines preflight, confirmation, submission, reconciliation and
business outcomes. A model cannot approve a task. Trusted confirmation binds the
frozen task and parameters. Unknown outcomes resume the original task; they never
authorize a replacement submission. The Host uses private persisted checkpoints,
owner/build isolation and exclusive single-process state. Source-session expiry
blocks pending writes rather than silently manufacturing new write authority.

This is not production IAM, multi-node recovery or financial suitability approval.
