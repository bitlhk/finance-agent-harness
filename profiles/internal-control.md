# Internal control profile

This profile describes an authenticated bank employee performing internal review
with explicitly installed, read-only capabilities. It does not grant access to a
customer, material set, rule set, or approval action.

The Host must bind the current employee identity, task, session, trusted business
date, and material-set scope. Providers must recheck those values on every call.
Material submission and a successful deterministic check do not establish document
authenticity, credit approval, rejection, lending, or disbursement authority.

Human review remains required for unresolved, incomparable, or decision-bearing
outcomes. Runtime and model output cannot widen the installed capability set or the
current authorization.
