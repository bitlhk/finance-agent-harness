"""推荐输入、候选与输出；业务事实和模型匹配说明分开。"""

from typing import Annotated, Literal

from pydantic import AwareDatetime, Field, StrictBool, StrictInt, model_validator

from ..contracts import Benefit, Frozen, Id
from ..contracts import Reference

Engine = Literal["onerec", "llm"]
Sha = Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class RecommendationRequest(Frozen):
    operation_key: Id
    engines: tuple[Engine, ...] = ("llm",)
    top_k: Annotated[StrictInt, Field(ge=1, le=5)] = 3
    goal: str = Field(default="推荐适合当前客户的权益", min_length=1, max_length=1000)
    marketing_copy: StrictBool = False
    conversation: StrictBool = False
    parent_run_id: Id | None = None

    @model_validator(mode="after")
    def engine_set(self):
        if not self.engines or len(set(self.engines)) != len(self.engines):
            raise ValueError("engines must be nonempty and unique")
        if self.marketing_copy and "onerec" not in self.engines:
            raise ValueError("marketing requires an OneRec ranking")
        if self.parent_run_id and not self.conversation:
            raise ValueError("a parent run requires conversation mode")
        return self


class Candidate(Frozen):
    benefit: Benefit
    reference: Reference


class RecommendationContext(Frozen):
    customer_id: Id
    case_id: Id
    observed_at: AwareDatetime
    dataset_version: Sha
    snapshot_digest: Sha
    tier: StrictInt
    candidates: tuple[Candidate, ...]
    history: tuple[dict, ...] = ()
    exclusions: tuple[dict, ...] = ()
    goal: str
    top_k: Annotated[StrictInt, Field(ge=1, le=5)]
    comparison_fingerprint: Sha


class RankedItem(Frozen):
    benefit_id: Id
    reason: str = Field(min_length=1, max_length=1500)
    evidence_refs: tuple[str, ...] = ()


class Ranking(Frozen):
    items: tuple[RankedItem, ...] = Field(max_length=100)


class ModelChoice(Frozen):
    benefit_id: Id
    reason: str = Field(min_length=1, max_length=1500)


class ModelChoices(Frozen):
    items: tuple[ModelChoice, ...] = Field(max_length=100)


class RecommendationItem(Frozen):
    benefit: Benefit
    reference: Reference
    matching_explanation: str
    explanation_kind: Literal["model-matching-not-business-fact"] = (
        "model-matching-not-business-fact"
    )


class EngineResult(Frozen):
    engine: Engine
    status: Literal["complete", "failed", "cancelled", "no-candidates"]
    code: str = ""
    items: tuple[RecommendationItem, ...] = ()
    rejected: tuple[dict, ...] = ()
    raw_count: StrictInt = 0
    elapsed_ms: float = Field(ge=0)
    provenance: dict
    calls: tuple[dict, ...] = ()


class BenefitsConfig(Frozen):
    enabled: bool = False
    dataset_root: str | None = None
    worker_url: str = "http://127.0.0.1:8824"
    worker_token_env: str = "CREDIT_BENEFITS_WORKER_TOKEN"
    seconds: Annotated[float, Field(gt=0, le=180)] = 120

    @model_validator(mode="after")
    def local_worker(self):
        from urllib.parse import urlsplit

        u = urlsplit(self.worker_url)
        if (
            u.scheme != "http" or u.hostname != "127.0.0.1"
            or u.username or u.password or u.path not in ("", "/")
            or u.query or u.fragment or not u.port
        ):
            raise ValueError("worker must use an explicit loopback HTTP port")
        return self
