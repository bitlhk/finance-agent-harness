"""Credit-card reference runtime contracts; no Builder dependency or private labels."""
from __future__ import annotations
import hashlib
import json
from typing import Annotated, Literal
from pydantic import BaseModel, AwareDatetime, ConfigDict, Field, StrictInt, model_validator

Id = Annotated[str, Field(pattern=r"^[a-z][a-z0-9_-]{0,63}$")]
Minor = Annotated[StrictInt, Field(ge=0)]
class Frozen(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, validate_assignment=True)


class BusinessError(Exception):
    """只携带公开安全的稳定错误，不转发任意底层异常内容。"""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)

def canonical(value: BaseModel | dict | list) -> bytes:
    if isinstance(value, BaseModel):
        value = value.model_dump(mode="json")
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")

def digest(value: BaseModel | dict | list) -> str:
    return hashlib.sha256(canonical(value)).hexdigest()

class Customer(Frozen):
    customer_id: Id
    group_id: Id
    name: str
    tier: Annotated[StrictInt, Field(ge=2, le=8)]
    synthetic: Literal[True] = True

class Account(Frozen):
    account_id: Id
    customer_id: Id
    card_id: Id
    group_id: Id
    currency: Literal["CNY"] = "CNY"
    available_minor: Minor | None
    credit_limit_minor: Minor | None = None
    used_limit_minor: Minor | None = None

    @model_validator(mode="after")
    def credit_snapshot(self):
        if (
            self.credit_limit_minor is not None
            and self.used_limit_minor is not None
            and self.available_minor is not None
            and self.available_minor != max(0, self.credit_limit_minor - self.used_limit_minor)
        ):
            raise ValueError("inconsistent credit snapshot")
        return self

class Fact(Frozen):
    record_id: Id
    customer_id: Id
    account_id: Id
    group_id: Id
    source_id: Id
    version: Id = "source-v1"
    kind: Literal[
        "balance",
        "consumption",
        "repayment",
        "authorization-failed",
        "preauthorization",
        "settlement",
        "release",
        "reversal",
        "temporary-limit-expiry",
        "benefit",
        "card-state",
    ]
    event_at: AwareDatetime
    known_at: AwareDatetime
    data_type: Literal["fact", "arrangement"] = "fact"
    amount_minor: Minor = 0
    currency: Literal["CNY"] = "CNY"
    related_id: Id | None = None
    purchase_id: Id | None = None
    value: str = ""
    synthetic: Literal[True] = True

    @model_validator(mode="after")
    def time_semantics(self):
        if self.data_type == "fact" and self.known_at < self.event_at:
            raise ValueError("actual facts cannot be known before occurrence")
        if self.data_type == "arrangement" and self.kind != "temporary-limit-expiry":
            raise ValueError("unsupported arrangement kind")
        return self

class Benefit(Frozen):
    benefit_id: Id
    title: str
    known_at: AwareDatetime
    valid_from: AwareDatetime
    valid_until: AwareDatetime
    minimum_tier: Annotated[StrictInt, Field(ge=2, le=8)]
    remaining: Minor
    synthetic: Literal[True] = True

    @model_validator(mode="after")
    def dates(self):
        if self.valid_until <= self.valid_from:
            raise ValueError("invalid benefit validity")
        return self

class CaseContext(Frozen):
    case_id: Id
    customer_id: Id
    account_id: Id
    group_id: Id
    observed_at: AwareDatetime
    visible_sources: tuple[Id, ...] = ("ledger", "benefits")

class RuntimeView(Frozen):
    schema_version: Literal["1.0"] = "1.0"
    dataset_version: str = Field(pattern=r"^[0-9a-f]{64}$")
    case: CaseContext
    customer: Customer
    account: Account
    related_accounts: tuple[Account, ...] = ()
    facts: tuple[Fact, ...]
    benefits: tuple[Benefit, ...]
    revision: Annotated[StrictInt, Field(ge=0)] = 0

    @model_validator(mode="after")
    def scope(self):
        if type(self.case) is not CaseContext:
            raise ValueError("runtime context must exclude evaluation tags")
        c, a, u = self.case, self.account, self.customer
        if (c.customer_id, c.account_id, c.group_id) != (u.customer_id, a.account_id, a.group_id):
            raise ValueError("invalid case subject")
        if a.customer_id != u.customer_id or a.group_id != u.group_id:
            raise ValueError("invalid account subject")
        accounts = {a.account_id, *(x.account_id for x in self.related_accounts)}
        if any(
            x.customer_id != u.customer_id or x.group_id != u.group_id
            for x in self.related_accounts
        ):
            raise ValueError("invalid shared account")
        for f in self.facts:
            if (f.customer_id, f.group_id) != (
                c.customer_id,
                c.group_id,
            ) or f.account_id not in accounts:
                raise ValueError("fact outside subject")
            if f.source_id not in c.visible_sources or f.known_at > c.observed_at:
                raise ValueError("fact outside visible scope")
            if f.data_type == "fact" and f.event_at > c.observed_at:
                raise ValueError("future actual fact")
        if any(b.known_at > c.observed_at for b in self.benefits):
            raise ValueError("future benefit knowledge")
        if self.benefits and "benefits" not in c.visible_sources:
            raise ValueError("benefits outside source scope")
        return self

class QueryResult(Frozen):
    status: Literal["ok", "empty", "unavailable", "insufficient"]
    records: tuple[Fact, ...] = ()
    code: str = ""


class Reference(Frozen):
    ref_id: str
    customer_id: Id
    source_id: Id
    record_id: Id
    version: str
    snapshot_digest: str
    observed_at: AwareDatetime


# Read-only Agent proposal and verified plan contracts.
Text = Annotated[str, Field(min_length=1, max_length=2000)]

Revision = Annotated[StrictInt, Field(ge=0)]

IntentOrigin = Literal['manager-request', 'customer-report', 'unspecified']

Topic = Literal['benefits', 'travel', 'repayment', 'limit-application', 'forecast', 'other']

class IntentChange(Frozen):
    topic: Topic
    state: Literal['confirmed', 'rejected', 'unverified']
    quote: Text
    message_index: Revision
    origin: IntentOrigin = Field(default='unspecified', description='关注项出处：经理分析指令manager-request；经理明确转述客户表达customer-report；未知unspecified。分类不等于客户授权。')

class Intent(Frozen):
    topic: Topic
    state: Literal['confirmed', 'rejected', 'unverified']
    quote: Text
    message_index: Revision
    origin: IntentOrigin = Field(default='unspecified', description='关注项出处：经理分析指令manager-request；经理明确转述客户表达customer-report；未知unspecified。分类不等于客户授权。')

class Recommendation(Frozen):
    kind: Literal['benefit', 'repayment', 'limit-application', 'forecast']
    target_id: Id
    evidence_ref: str = Field(min_length=1, max_length=160)
    intent_topic: Topic
    amount_minor: Minor = 0

class Draft(Frozen):
    schema_version: Literal['1.0'] = '1.0'
    status: Literal['complete', 'needs-input', 'degraded']
    intent_changes: tuple[IntentChange, ...] = ()
    recommendations: tuple[Recommendation, ...] = ()
    questions: tuple[Text, ...] = ()

    @model_validator(mode='after')
    def state(self):
        if self.status == 'needs-input' and (not self.questions):
            raise ValueError('missing actionable clarification')
        if self.status == 'complete' and (self.questions or not self.recommendations):
            raise ValueError('incomplete plan')
        if self.status == 'degraded' and self.questions:
            raise ValueError('system gaps must not become customer questions')
        return self

class CandidateAction(Frozen):
    action_id: Id
    kind: Literal['repayment', 'benefit-use']
    amount_minor: Minor = 0
    benefit_id: Id | None = None
    requires_confirmation: Literal[True] = True

class PlanItem(Frozen):
    kind: Literal['benefit', 'repayment', 'limit-application', 'forecast']
    target_id: Id
    title: str
    details: dict
    reference: Reference
    intent: Intent
    action: CandidateAction | None = None

class PlanGap(Frozen):
    topic: Topic
    code: str
    message: str
    kind: Literal['source', 'unresolved'] = 'source'

class Plan(Frozen):
    schema_version: Literal['1.0'] = '1.0'
    plan_id: Id
    source_revision: Revision
    source_context_digest: str
    items: tuple[PlanItem, ...]
    intents: tuple[Intent, ...]
    questions: tuple[str, ...]
    gaps: tuple[PlanGap, ...] = ()
    suggested_message: str
    manager_brief: str = ''
    customer_authorization_status: Literal['not-collected'] = 'not-collected'
    synthetic: Literal[True] = True

class CallReceipt(Frozen):
    kind: Literal['model', 'tool']
    name: str
    status: Literal['complete', 'failed', 'timeout', 'cancelled']
    elapsed_ms: Annotated[float, Field(ge=0)]
    input_tokens: Revision | None = None
    output_tokens: Revision | None = None
    code: str = ''

class ExecutionReceipt(Frozen):
    framework: Literal['openjiuwen.ReActAgent'] = 'openjiuwen.ReActAgent'
    framework_version: str = '0.1.16'
    provider: str
    model: str
    status: Literal['complete', 'failed', 'timeout', 'cancelled']
    calls: tuple[CallReceipt, ...]
    elapsed_ms: Annotated[float, Field(ge=0)]
    budget: dict
