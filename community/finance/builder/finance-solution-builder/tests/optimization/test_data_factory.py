from finance_builder_base import ResourceKind, ResourceRef
from finance_builder_base.data_factory import (
    DeliveryPackage,
    GenerationMode,
    GenerationSpec,
    ProcessingPipeline,
    ProcessingResult,
)
from finance_solution_builder.optimization import (
    ActionDisposition,
    CorrectionTarget,
    CorrectionTargetKind,
    DataFactoryCorrectionExecutor,
    OptimizationDataGateway,
    OptimizationDataRequest,
)

from . import SHA


class PackageUnit:
    name = "package"

    def process(self, value, spec):
        del value
        return ProcessingResult.completed(
            DeliveryPackage(
                task_id=spec.task_id,
                resources=(ResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="data/new.jsonl"),),
                item_count=3,
            )
        )


def test_gateway_produces_versioned_optimization_data() -> None:
    gateway = OptimizationDataGateway(ProcessingPipeline((PackageUnit(),)))
    work = OptimizationDataRequest(
        spec=GenerationSpec(task_id="data-1", mode=GenerationMode.DIRECT, sample_count=3),
        source_digest=SHA,
        steps=("package",),
    )
    first = gateway.generate(work)
    assert first == gateway.generate(work)
    assert first.purpose == "optimization"
    assert first.item_count == 3


def test_missing_processing_unit_is_stably_unsupported() -> None:
    gateway = OptimizationDataGateway(ProcessingPipeline(()))
    work = OptimizationDataRequest(
        spec=GenerationSpec(task_id="data-1", mode=GenerationMode.DIRECT, sample_count=1),
        source_digest=SHA,
        steps=("package",),
    )
    assert gateway.generate(work) is None


def test_data_target_is_adapted_to_a_versioned_correction() -> None:
    gateway = OptimizationDataGateway(ProcessingPipeline((PackageUnit(),)))
    work = OptimizationDataRequest(
        spec=GenerationSpec(task_id="data-1", mode=GenerationMode.DIRECT, sample_count=3),
        source_digest=SHA,
        steps=("package",),
    )
    executor = DataFactoryCorrectionExecutor(gateway, lambda _target, _round: work)
    target = CorrectionTarget(
        finding_id="finding-1",
        target_kind=CorrectionTargetKind.OPTIMIZATION_DATA,
        target_ref="datasets/optimization",
        action="generate-cases",
        disposition=ActionDisposition.AUTO,
        reason="Data Finding has an approved optimization-data target.",
        owner_id="data-owner",
        validation_refs=(ResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="evidence/rule.json"),),
    )
    result = executor.execute(target, 1)
    assert result.data_version is not None
    assert result.overlay is None
