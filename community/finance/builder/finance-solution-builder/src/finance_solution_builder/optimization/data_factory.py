"""Thin optimization-data gateway over the shared DataFactory pipeline."""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from typing import Callable

from finance_builder_base.data_factory import (
    DeliveryPackage,
    GenerationSpec,
    ProcessingPipeline,
    ProcessingStatus,
)

from .models import (
    CorrectionExecution,
    CorrectionTarget,
    CorrectionTargetKind,
    OptimizationDataVersion,
)


@dataclass(frozen=True)
class OptimizationDataRequest:
    spec: GenerationSpec
    source_digest: str
    steps: tuple[str, ...] | None = None

    def __post_init__(self) -> None:
        if re.fullmatch(r"[0-9a-f]{64}", self.source_digest) is None:
            raise ValueError("source_digest must be a sha256 value")
        if self.steps is not None and not self.steps:
            raise ValueError("steps may be omitted but not empty")


class OptimizationDataGateway:
    """Generate only versioned optimization data; frozen datasets remain inputs."""

    def __init__(self, pipeline: ProcessingPipeline) -> None:
        self._pipeline = pipeline

    def generate(self, request: OptimizationDataRequest) -> OptimizationDataVersion | None:
        result = self._pipeline.run(request.spec, steps=request.steps)
        if result.status is ProcessingStatus.UNSUPPORTED:
            return None
        if result.status is not ProcessingStatus.COMPLETED:
            raise RuntimeError("optimization.data_factory.failed")
        if not isinstance(result.output, DeliveryPackage):
            raise ValueError("optimization.data_factory.invalid_package")
        package = result.output
        if package.task_id != request.spec.task_id:
            raise ValueError("optimization.data_factory.task_mismatch")
        payload = package.model_dump(mode="json", by_alias=True, exclude_none=True)
        output_digest = hashlib.sha256(
            json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
        ).hexdigest()
        return OptimizationDataVersion(
            version_id=f"{request.spec.task_id}:{output_digest[:12]}",
            task_id=request.spec.task_id,
            source_digest=request.source_digest,
            output_digest=output_digest,
            resource_refs=package.resources,
            item_count=package.item_count,
        )


class DataFactoryCorrectionExecutor:
    """Adapt an optimization-data target to the shared DataFactory gateway."""

    def __init__(
        self,
        gateway: OptimizationDataGateway,
        request_factory: Callable[[CorrectionTarget, int], OptimizationDataRequest],
    ) -> None:
        self._gateway = gateway
        self._request_factory = request_factory

    def execute(self, target: CorrectionTarget, round_index: int) -> CorrectionExecution:
        if target.target_kind is not CorrectionTargetKind.OPTIMIZATION_DATA:
            raise ValueError("optimization.data_factory.target_mismatch")
        version = self._gateway.generate(self._request_factory(target, round_index))
        if version is None:
            raise RuntimeError("optimization.data_factory.unsupported")
        return CorrectionExecution(
            change_digest=version.sha256(),
            evidence_refs=version.resource_refs,
            duration_ms=0,
            cost_microunits=0,
            data_version=version,
        )
