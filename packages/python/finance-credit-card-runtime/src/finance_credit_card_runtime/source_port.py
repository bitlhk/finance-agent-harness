"""Host-injected snapshots. No default mock provider is constructed by runtime."""
import os
from typing import Annotated, Literal, Protocol
from pydantic import AwareDatetime, Field, StrictInt
from .contracts import Frozen, Id, RuntimeView, BusinessError, Fact, QueryResult, canonical, digest

class SourceBinding(Frozen):
    tenant_id: Id
    principal_id: Id
    customer_id: Id
    case_id: Id
    observed_at: AwareDatetime
    visible_source_ids: tuple[Id, ...]
    snapshot_digest: str = Field(pattern=r"^[a-f0-9]{64}$")

class SnapshotSource(Protocol):
    def read(self, binding: SourceBinding) -> RuntimeView: ...


def read_current(source: SnapshotSource, binding: SourceBinding) -> RuntimeView:
    view = RuntimeView.model_validate(source.read(binding).model_dump())
    if (view.customer.customer_id != binding.customer_id or view.case.case_id != binding.case_id
            or view.case.observed_at != binding.observed_at
            or not set(view.case.visible_sources) <= set(binding.visible_source_ids)):
        raise BusinessError("source-binding-mismatch")
    if digest(view) != binding.snapshot_digest:
        raise BusinessError("source-snapshot-changed")
    return view


class SnapshotProjection:
    """Read-only projection of the injected snapshot; reauthorize on each read."""

    def __init__(self, get_current):
        self.get_current = get_current

    def query(self, source_id):
        view = RuntimeView.model_validate(self.get_current().model_dump())
        if source_id not in view.case.visible_sources:
            raise BusinessError('scope-denied')
        records = tuple(f for f in view.facts if f.source_id == source_id)
        if source_id == 'benefits':
            records += tuple(Fact(record_id=b.benefit_id, customer_id=view.customer.customer_id,
                account_id=view.account.account_id, group_id=view.account.group_id, source_id='benefits',
                kind='benefit', event_at=b.known_at, known_at=b.known_at,
                version=f'benefits-r{view.revision}', value=canonical(b).decode()) for b in view.benefits)
        if source_id == 'ledger' and view.account.available_minor is None:
            return QueryResult(status='insufficient', records=records, code='insufficient-data')
        return QueryResult(status='ok' if records else 'empty', records=records)

    def resolve(self, record_id, expected_version):
        view = RuntimeView.model_validate(self.get_current().model_dump())
        for source_id in view.case.visible_sources:
            for fact in self.query(source_id).records:
                if fact.record_id == record_id:
                    if fact.version != expected_version:
                        raise BusinessError('version-mismatch')
                    return fact
        raise BusinessError('not-found')

# Managed model configuration; credentials are read only at call time.
class ServiceConfig(Frozen):
    schema_version: Literal["1.0"] = "1.0"
    provider: Literal["DeepSeek-official"] = "DeepSeek-official"
    api_base: Literal["https://api.deepseek.com"] = "https://api.deepseek.com"
    model: Literal["deepseek-flash"] = "deepseek-flash"
    api_key_env: str = Field(default="CREDIT_SERVICE_MODEL_API_KEY", pattern=r"^[A-Z][A-Z0-9_]*$")
    max_model_calls: Annotated[StrictInt, Field(ge=1, le=6)] = 6
    max_tool_calls: Annotated[StrictInt, Field(ge=1, le=12)] = 12
    max_iterations: Annotated[StrictInt, Field(ge=1, le=5)] = 5
    turn_seconds: Annotated[float, Field(gt=0, le=120)] = 120
    model_seconds: Annotated[float, Field(gt=0, le=30)] = 30
    max_tokens: Annotated[StrictInt, Field(ge=256, le=4096)] = 2048

    def credential(self):
        key = os.environ.get(self.api_key_env, "").strip()
        if not key or "\n" in key or "\r" in key:
            raise BusinessError("model-configuration-unavailable")
        return key
