# Employee-assisted Service Preparation V0.1

This profile binds an authenticated employee principal to an explicitly allowed synthetic-customer set.
The effective read scope is the intersection of deployment-owned employee mapping and a current CRM
assignment. Neither the profile, an administrator role, a capability declaration nor model output grants
customer access. Missing, conflicting, expired or revoked authority fails closed before facts are returned.

The first managed scenario is read-only customer-manager service preparation. It may query CRM facts,
apply an installed method, persist an immutable draft and record manager review. It cannot write CRM,
send messages, recommend products, execute transactions or use a customer's own banking session.
