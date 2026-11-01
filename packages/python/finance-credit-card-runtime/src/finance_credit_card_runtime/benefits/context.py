"""Build recommendations only from an explicitly supplied verified snapshot."""
from ..contracts import RuntimeView, Reference, BusinessError, digest
from .models import Candidate, RecommendationContext


def model_history(history):
    """Project integer fen as explicitly labelled yuan for language models only.

    Stored records and OneRec inputs retain their original integer contracts.
    Missing amounts stay missing; no floating-point money conversion is used.
    """
    rows = []
    for raw in history:
        row = dict(raw)
        if 'amount_minor' in row:
            amount = row.pop('amount_minor')
            if amount is not None and (type(amount) is not int or amount < 0):
                raise BusinessError('invalid-history-amount')
            row['amount_cny'] = None if amount is None else f'{amount//100}.{amount%100:02d}'
            row['amount_unit'] = 'CNY yuan'
        rows.append(row)
    return rows


def build_context(view, request):
    view = RuntimeView.model_validate(view.model_dump())
    if "benefits" not in view.case.visible_sources:
        raise BusinessError("source-not-visible")
    candidates, excluded = [], []
    now, snapshot = view.case.observed_at, digest(view)
    for benefit in view.benefits:
        code = ("benefit-not-current" if not benefit.valid_from <= now < benefit.valid_until
                else "benefit-ineligible" if view.customer.tier < benefit.minimum_tier
                else "benefit-exhausted" if benefit.remaining == 0 else "")
        if code:
            excluded.append({"benefit_id":benefit.benefit_id,"code":code})
            continue
        reference = Reference(ref_id="benefits:"+benefit.benefit_id, customer_id=view.customer.customer_id,
            source_id="benefits",record_id=benefit.benefit_id,version=view.dataset_version,
            snapshot_digest=snapshot,observed_at=now)
        candidates.append(Candidate(benefit=benefit,reference=reference))
    payload = dict(customer_id=view.customer.customer_id,case_id=view.case.case_id,
        observed_at=now,dataset_version=view.dataset_version,snapshot_digest=snapshot,
        tier=view.customer.tier,candidates=tuple(candidates),
        history=tuple(x.model_dump(mode="json") for x in view.facts[-100:] if x.source_id=="ledger"),
        exclusions=tuple(excluded),goal=request.goal,top_k=request.top_k)
    # Canonicalize datetimes and nested models before hashing.
    ctx = RecommendationContext(**payload,comparison_fingerprint="0"*64)
    canonical = ctx.model_dump(mode="json",exclude={"comparison_fingerprint"})
    return ctx.model_copy(update={"comparison_fingerprint":digest({"input":canonical,
        "eligibility":"benefits-v1","protocol":"rec-v1"})})
