"""Typed DataFactory inputs and outputs."""

from __future__ import annotations

from enum import Enum
from typing import Any, Generic, TypeVar

from pydantic import Field, SerializeAsAny

from finance_builder_base.contracts import ContractModel, ErrorDetail, ExtensionModel, ResourceRef

OutputT = TypeVar("OutputT")


class GenerationMode(str, Enum):
    DIRECT = "direct"
    BACKGROUND_FIRST = "background-first"
    STRUCTURED_RULE = "structured-rule"
    TEXT_MODEL = "text-model"


class ProcessingStatus(str, Enum):
    COMPLETED = "completed"
    UNSUPPORTED = "unsupported"
    FAILED = "failed"


class GenerationSpec(ContractModel):
    task_id: str = Field(min_length=1)
    mode: GenerationMode
    sample_count: int = Field(gt=0)
    seed: int | None = None
    source: ResourceRef | None = None
    extension: SerializeAsAny[ExtensionModel] | None = None


class DeliveryPackage(ContractModel):
    task_id: str = Field(min_length=1)
    resources: tuple[ResourceRef, ...]
    item_count: int = Field(ge=0)
    extension: SerializeAsAny[ExtensionModel] | None = None


class ProcessingResult(ContractModel, Generic[OutputT]):
    status: ProcessingStatus
    output: OutputT | None = None
    issues: tuple[ErrorDetail, ...] = ()

    @classmethod
    def completed(cls, output: OutputT) -> ProcessingResult[OutputT]:
        return cls(status=ProcessingStatus.COMPLETED, output=output)

    @classmethod
    def unsupported(cls, unit_name: str) -> ProcessingResult[Any]:
        return cls(
            status=ProcessingStatus.UNSUPPORTED,
            issues=(
                ErrorDetail(
                    code="data_factory.unsupported",
                    field="unit",
                    message=f"processing unit '{unit_name}' is not available",
                ),
            ),
        )
