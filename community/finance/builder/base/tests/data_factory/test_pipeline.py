from __future__ import annotations

from typing import Any

from finance_builder_base.contracts import ResourceKind, ResourceRef
from finance_builder_base.data_factory import (
    DeliveryPackage,
    GenerationMode,
    GenerationSpec,
    ProcessingPipeline,
    ProcessingResult,
    ProcessingStatus,
    default_step_names,
)


class RecordingUnit:
    def __init__(self, name: str) -> None:
        self.name = name

    def process(self, value: Any, spec: GenerationSpec) -> ProcessingResult[Any]:
        history = list(value or [])
        history.append(self.name)
        return ProcessingResult.completed(history)


def _pipeline() -> ProcessingPipeline:
    names = {
        "background",
        "synthesis",
        "structured-generation",
        "text-generation",
        "quality",
        "split",
        "package",
    }
    return ProcessingPipeline(RecordingUnit(name) for name in names)


def test_default_composition_completes_delivery_steps() -> None:
    spec = GenerationSpec(
        task_id="T1", mode=GenerationMode.BACKGROUND_FIRST, sample_count=10
    )
    result = _pipeline().run(spec, [])
    assert result.status is ProcessingStatus.COMPLETED
    assert result.output == list(default_step_names(spec.mode))


def test_caller_can_recompose_the_same_units() -> None:
    spec = GenerationSpec(task_id="T2", mode=GenerationMode.DIRECT, sample_count=2)
    result = _pipeline().run(spec, [], steps=("synthesis", "split", "quality", "package"))
    assert result.output == ["synthesis", "split", "quality", "package"]


def test_missing_unit_is_explicitly_unsupported() -> None:
    spec = GenerationSpec(task_id="T3", mode=GenerationMode.TEXT_MODEL, sample_count=1)
    result = ProcessingPipeline([RecordingUnit("quality")]).run(spec)
    assert result.status is ProcessingStatus.UNSUPPORTED
    assert result.issues[0].code == "data_factory.unsupported"


def test_generation_modes_choose_explicit_units() -> None:
    assert default_step_names(GenerationMode.DIRECT)[0] == "synthesis"
    assert default_step_names(GenerationMode.BACKGROUND_FIRST)[:2] == (
        "background",
        "synthesis",
    )
    assert default_step_names(GenerationMode.STRUCTURED_RULE)[0] == "structured-generation"
    assert default_step_names(GenerationMode.TEXT_MODEL)[0] == "text-generation"


def test_package_step_produces_delivery_package() -> None:
    class PackagingUnit:
        name = "package"

        def process(self, value: Any, spec: GenerationSpec) -> ProcessingResult[Any]:
            items = list(value or [])
            package = DeliveryPackage(
                task_id=spec.task_id,
                resources=(
                    ResourceRef(
                        kind=ResourceKind.WORKSPACE_PATH,
                        locator=f"testsets/{spec.task_id}.jsonl",
                    ),
                ),
                item_count=len(items),
            )
            return ProcessingResult.completed(package)

    spec = GenerationSpec(task_id="T4", mode=GenerationMode.DIRECT, sample_count=2)
    pipeline = ProcessingPipeline([RecordingUnit("synthesis"), PackagingUnit()])
    result = pipeline.run(spec, ["sample-a"], steps=("synthesis", "package"))

    assert result.status is ProcessingStatus.COMPLETED
    package = result.output
    assert isinstance(package, DeliveryPackage)
    assert package.task_id == "T4"
    assert package.item_count == 2
    assert package.resources[0].locator == "testsets/T4.jsonl"
