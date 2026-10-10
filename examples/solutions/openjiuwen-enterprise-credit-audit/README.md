# Enterprise Credit Material Audit Candidate

This isolated candidate installs the two deterministic QUERY capabilities and the constrained review
method into OpenJiuwen Java. It reads host-created synthetic material sets only.

Required environment:

- `FH_CREDIT_AUDIT_MATERIAL_ROOT`
- `FH_CREDIT_AUDIT_RULE_FILE`
- `FINANCE_AGENT_MODEL_API_KEY`, `FINANCE_AGENT_MODEL_BASE_URL`, `FINANCE_AGENT_MODEL`

It writes a result JSON and an installation Lock. A model failure leaves the deterministic findings
in the output and marks only `explanationStatus` as `FAILED`.
