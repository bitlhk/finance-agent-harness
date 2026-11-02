"""预测协议、严格回执与显式缺失语义。"""

from datetime import datetime, timezone
from typing import Annotated, Literal
from pydantic import AwareDatetime, Field, StrictInt, model_validator
from ..contracts import Frozen, Minor, Id

Hash = Annotated[str, Field(pattern=r"^[0-9a-f]{64}$")]
Status = Literal["available", "insufficient-data", "untrained", "failed", "stale", "unsupported"]


class ForecastProtocol(Frozen):
    schema_version: Literal["1.0"] = "1.0"
    business_start: AwareDatetime = datetime(2026, 1, 1, tzinfo=timezone.utc)
    train_days: tuple[StrictInt, ...] = tuple(range(30, 100))
    validation_days: tuple[StrictInt, ...] = tuple(range(115, 136))
    test_days: tuple[StrictInt, ...] = tuple(range(151, 179))
    history_days: StrictInt = Field(default=30, ge=1, le=90)
    maturity_hours: StrictInt = Field(default=24, ge=24)
    k: StrictInt = Field(default=20, ge=1)
    min_samples: StrictInt = Field(default=100, ge=2)
    seed: StrictInt = 42
    rounds: StrictInt = Field(default=100, ge=1, le=1000)
    probability_threshold: float = Field(default=0.2, ge=0, le=1, allow_inf_nan=False)
    feature_version: Literal["known-history-v1"] = "known-history-v1"
    label_version: Literal["successful-spend-limit-failure-v1"] = (
        "successful-spend-limit-failure-v1"
    )
    scorer_version: Literal["forecast-scorer-v1"] = "forecast-scorer-v1"

    @model_validator(mode="after")
    def split_order(self):
        groups = (self.train_days, self.validation_days, self.test_days)
        if any(not g or tuple(sorted(set(g))) != g or min(g) < self.history_days for g in groups):
            raise ValueError("invalid observation grid")
        if any(max(a) + 7 + self.maturity_hours / 24 >= min(b) for a, b in zip(groups, groups[1:])):
            raise ValueError("overlapping label windows")
        return self


class HorizonForecast(Frozen):
    horizon_days: Literal[1, 3, 7]
    status: Status
    reason: str
    currency: Literal["CNY"] = "CNY"
    p10_minor: Minor | None = None
    p50_minor: Minor | None = None
    p90_minor: Minor | None = None
    shortage_probability: float | None = Field(default=None, ge=0, le=1, allow_inf_nan=False)
    postprocess_corrections: StrictInt = Field(default=0, ge=0)

    @model_validator(mode="after")
    def values(self):
        vals = (self.p10_minor, self.p50_minor, self.p90_minor, self.shortage_probability)
        if self.status == "available":
            if (
                any(x is None for x in vals)
                or not self.p10_minor <= self.p50_minor <= self.p90_minor
            ):
                raise ValueError("available forecast needs ordered finite values")
        elif any(x is not None for x in vals):
            raise ValueError("unavailable forecast must not fabricate numbers")
        return self


class ForecastReceipt(Frozen):
    schema_version: Literal["1.0"] = "1.0"
    receipt_id: Hash
    dataset_version: Hash
    case_id: Id
    customer_id: Id
    group_id: Id
    snapshot_revision: StrictInt = Field(ge=0)
    input_digest: Hash
    model_digest: Hash
    protocol_digest: Hash
    observed_at: AwareDatetime
    expires_at: AwareDatetime
    method: Literal["model", "baseline"]
    results: tuple[HorizonForecast, ...]
    evidence_refs: tuple[Id, ...]
    limitation: str = "Synthetic reference; successful spending is policy-constrained; probabilities are not credit approvals."

    @model_validator(mode="after")
    def windows(self):
        if (
            tuple(r.horizon_days for r in self.results) != (1, 3, 7)
            or self.expires_at <= self.observed_at
        ):
            raise ValueError("invalid receipt windows or expiry")
        return self
