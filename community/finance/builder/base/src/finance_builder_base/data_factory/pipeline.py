"""Caller-composed DataFactory processing units."""

from __future__ import annotations

from collections.abc import Iterable
from typing import Any, Protocol

from finance_builder_base.data_factory.models import (
    GenerationMode,
    GenerationSpec,
    ProcessingResult,
    ProcessingStatus,
)


class ProcessingUnit(Protocol):
    name: str

    def process(self, value: Any, spec: GenerationSpec) -> ProcessingResult[Any]: ...


def default_step_names(mode: GenerationMode) -> tuple[str, ...]:
    """Return the replaceable reference composition for one generation mode."""

    if mode is GenerationMode.DIRECT:
        generation_steps = ("synthesis",)
    elif mode is GenerationMode.BACKGROUND_FIRST:
        generation_steps = ("background", "synthesis")
    elif mode is GenerationMode.STRUCTURED_RULE:
        generation_steps = ("structured-generation",)
    elif mode is GenerationMode.TEXT_MODEL:
        generation_steps = ("text-generation",)
    else:
        raise ValueError(f"unsupported generation mode: {mode}")
    return (*generation_steps, "quality", "split", "package")


class ProcessingPipeline:
    """Executes an order supplied by the caller; it is not a template method."""

    def __init__(self, units: Iterable[ProcessingUnit]) -> None:
        self._units = {unit.name: unit for unit in units}

    def run(
        self,
        spec: GenerationSpec,
        initial: Any = None,
        *,
        steps: tuple[str, ...] | None = None,
    ) -> ProcessingResult[Any]:
        value = initial
        for step in steps or default_step_names(spec.mode):
            unit = self._units.get(step)
            if unit is None:
                return ProcessingResult.unsupported(step)
            result = unit.process(value, spec)
            if result.status is not ProcessingStatus.COMPLETED:
                return result
            value = result.output
        return ProcessingResult.completed(value)
