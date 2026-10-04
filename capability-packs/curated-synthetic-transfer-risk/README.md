# Synthetic transfer-risk input boundary

Candidate1.1.0 is readonly and has two different resource roles:

- `curated_account_balance_query.reference`: the explicit payer account.
- `curated_fraud_transfer_risk_assess.reference`: the explicit beneficiary.

Both inputs are required and no longer advertise a default business target.
Missing or ambiguous input requires clarification. The existing Runtime query
validator enforces the required field; the existing Provider still enforces the
server resource allowlist. Neither check proves a supplied allowed object was
what the user intended, so real-model object matching is a separate evaluation.

This is not an alias for the original `risk-review-basics` bank contract, whose
risk tool uses accountRef/customerRef. Migrating its method requires reconciling
the parameter meanings, not only renaming tools. Preserve the source method and
publish the synthetic target as a different immutable candidate version.

The source returns RISK_HIT, NO_KNOWN_HIT or UNKNOWN. A no-hit is not a safety
guarantee, UNKNOWN is not a no-hit, and this package cannot execute transfers.
No existing generated Agent or prior Lock is changed by this directory update.
