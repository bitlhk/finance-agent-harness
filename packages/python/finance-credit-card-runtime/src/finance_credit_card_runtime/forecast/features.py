"""只从RuntimeView提取白名单数值特征；不接受标签或完整Dataset。"""

from datetime import timedelta
from ..contracts import RuntimeView, BusinessError

FEATURE_NAMES = tuple(
    f"{kind}_{days}_{stat}"
    for days in (7, 14, 30)
    for kind in ("consumption", "repayment")
    for stat in ("sum", "count", "max")
) + ("failed_30", "available_minor", "tier", "weekday", "known_expiry_7")


def extract(view: RuntimeView) -> tuple[float, ...]:
    view = RuntimeView.model_validate(view.model_dump())
    if "ledger" not in view.case.visible_sources or view.account.available_minor is None:
        raise BusinessError("insufficient-data")
    now = view.case.observed_at
    values = []
    for days in (7, 14, 30):
        for kind in ("consumption", "repayment"):
            amounts = [
                f.amount_minor
                for f in view.facts
                if f.data_type == "fact"
                and f.kind == kind
                and now - timedelta(days=days) < f.event_at <= now
            ]
            values.extend((sum(amounts), len(amounts), max(amounts, default=0)))
    attempts = {
        f.purchase_id or f.record_id
        for f in view.facts
        if f.kind == "authorization-failed" and now - timedelta(days=30) < f.event_at <= now
    }
    expiry = sum(
        f.amount_minor
        for f in view.facts
        if f.kind == "temporary-limit-expiry" and now < f.event_at <= now + timedelta(days=7)
    )
    values.extend(
        (len(attempts), view.account.available_minor, view.customer.tier, now.weekday(), expiry)
    )
    return tuple(float(x) for x in values)
