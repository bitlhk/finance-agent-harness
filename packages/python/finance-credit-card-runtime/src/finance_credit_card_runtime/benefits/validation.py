"""保留原始错误诊断，再从候选真源重建有效推荐。"""

from .models import EngineResult, RecommendationItem


def bind_choices(context, choices):
    """模型只选择ID；定位由已授权候选确定，未知ID无法获得引用。"""
    from .models import Ranking

    refs = {x.benefit.benefit_id:x.reference.ref_id for x in context.candidates}
    return Ranking(items=[{'benefit_id':x.benefit_id,'reason':x.reason,
        'evidence_refs':[refs[x.benefit_id]] if x.benefit_id in refs else []} for x in choices.items])


def validate_ranking(context, ranking, *, engine, elapsed_ms, provenance, calls=()):
    candidates = {x.benefit.benefit_id: x for x in context.candidates}
    seen, items, rejected = set(), [], []
    for raw in ranking.items:
        candidate = candidates.get(raw.benefit_id)
        code = "duplicate-id" if raw.benefit_id in seen else ""
        seen.add(raw.benefit_id)
        if candidate is None:
            code = code or "not-in-authorized-candidates"
        elif any(ref != candidate.reference.ref_id for ref in raw.evidence_refs):
            code = code or "invalid-reference"
        elif not raw.evidence_refs:
            code = code or "missing-reference"
        if code:
            rejected.append({"benefit_id": raw.benefit_id, "code": code,
                             "supplied_refs": list(raw.evidence_refs)})
            continue
        if len(items) < context.top_k:
            items.append(RecommendationItem(
                benefit=candidate.benefit,
                reference=candidate.reference,
                matching_explanation=raw.reason,
            ))
    return EngineResult(
        engine=engine, status="complete" if items else "failed",
        code="" if items else "invalid-ranking", items=tuple(items),
        rejected=tuple(rejected), raw_count=len(ranking.items), elapsed_ms=elapsed_ms,
        provenance=provenance, calls=calls,
    )
