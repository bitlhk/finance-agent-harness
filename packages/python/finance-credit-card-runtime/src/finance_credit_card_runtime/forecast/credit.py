"""显式条件路径的额度推演；不接触任何私有未来真值。"""

from typing import Literal
from pydantic import AwareDatetime, StrictInt, Field
from ..contracts import Frozen, Id, Minor, RuntimeView


class ScenarioEvent(Frozen):
    event_id: Id
    event_at: AwareDatetime
    kind: Literal[
        "consumption",
        "repayment",
        "authorization-failed",
        "preauthorization",
        "settlement",
        "release",
        "reversal",
        "temporary-limit-expiry",
    ]
    amount_minor: Minor
    origin: Literal["arrangement", "prediction", "assumption"]
    sequence: StrictInt | None = Field(default=None, ge=0)
    related_id: Id | None = None
    basis_ref: Id | None = None


def simulate(view: RuntimeView, events: tuple[ScenarioEvent, ...], *, scenario_id="explicit-path"):
    """条件缺口只对已声明路径成立；不推断分位数的精确交易时刻。"""
    view = RuntimeView.model_validate(view.model_dump())
    events = tuple(ScenarioEvent.model_validate(e.model_dump()) for e in events)

    def unknown(reason):
        return {
            "status": "indeterminate",
            "scenario_id": scenario_id,
            "reason": reason,
            "first_shortage_at": None,
            "shortage_minor": None,
            "trace": [],
        }

    if view.account.available_minor is None or "ledger" not in view.case.visible_sources:
        return unknown("missing-current-balance")
    if len({e.event_id for e in events}) != len(events):
        return unknown("duplicate-event")
    stamps = {}
    for e in events:
        if e.event_at <= view.case.observed_at:
            return unknown("non-future-scenario-event")
        stamps.setdefault(e.event_at, []).append(e.sequence)
        if e.origin == "arrangement":
            if not any(
                f.record_id == e.basis_ref
                and f.data_type == "arrangement"
                and f.kind == e.kind
                and f.event_at == e.event_at
                and f.amount_minor == e.amount_minor
                for f in view.facts
            ):
                return unknown("unverified-arrangement")
    if any(len(v) > 1 and (None in v or len(set(v)) != len(v)) for v in stamps.values()):
        return unknown("ambiguous-event-order")
    holds = {}
    for f in sorted(view.facts, key=lambda f: (f.event_at, f.record_id)):
        if f.event_at > view.case.observed_at:
            continue
        if f.kind == "preauthorization":
            holds[f.record_id] = f.amount_minor
        elif f.kind in ("release", "reversal"):
            holds[f.related_id] = holds.get(f.related_id, 0) - f.amount_minor
        elif f.kind == "settlement":
            holds[f.related_id] = 0
    available = view.account.available_minor
    trace = []
    first = None
    gap = None
    for e in sorted(events, key=lambda e: (e.event_at, e.sequence or 0)):
        before = available
        if e.kind in ("consumption", "preauthorization"):
            if e.amount_minor > available:
                if first is None:
                    first = e.event_at.isoformat()
                    gap = e.amount_minor - available
            else:
                available -= e.amount_minor
                if e.kind == "preauthorization":
                    holds[e.event_id] = e.amount_minor
        elif e.kind == "repayment":
            available += e.amount_minor
        elif e.kind == "temporary-limit-expiry":
            available = max(0, available - e.amount_minor)
        elif e.kind in ("release", "reversal", "settlement"):
            if e.related_id not in holds or holds[e.related_id] <= 0:
                return unknown("unknown-hold")
            held = holds[e.related_id]
            if e.kind == "settlement":
                if e.amount_minor > available + held:
                    if first is None:
                        first = e.event_at.isoformat()
                        gap = e.amount_minor - available - held
                else:
                    available += held - e.amount_minor
                    holds[e.related_id] = 0
            else:
                if e.amount_minor > held:
                    return unknown("over-release")
                available += e.amount_minor
                holds[e.related_id] -= e.amount_minor
        trace.append(
            {
                "event_id": e.event_id,
                "kind": e.kind,
                "origin": e.origin,
                "event_at": e.event_at.isoformat(),
                "before_minor": before,
                "after_minor": available,
            }
        )
    return {
        "status": "available",
        "scenario_id": scenario_id,
        "reason": "conditional-path-only",
        "first_shortage_at": first,
        "shortage_minor": gap if gap is not None else 0,
        "trace": trace,
    }
