"""Construction Agent Backend control SPI."""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from finance_solution_builder.construction_agent_backend.models import (
    ApprovalDecision,
    CancelRequest,
    ConstructionBackendCapability,
    ConstructionBackendIdentity,
    ConstructionEventBatch,
    ConstructionResult,
    ConstructionTaskHandle,
    ConstructionTaskRequest,
    EventBatchRequest,
    ResultRequest,
    ResumeRequest,
)


@runtime_checkable
class ConstructionAgentBackend(Protocol):
    """Provider-neutral control surface implemented by a construction agent adapter."""

    @property
    def identity(self) -> ConstructionBackendIdentity:
        """Return a stable Backend identity."""

    @property
    def capability(self) -> ConstructionBackendCapability:
        """Describe the supported public contract before task execution."""

    def start(self, request: ConstructionTaskRequest) -> ConstructionTaskHandle:
        """Start one idempotent, boundary-constrained construction task."""

    def resume(self, request: ResumeRequest) -> ConstructionTaskHandle:
        """Recover existing task facts without starting new side effects."""

    def events(self, request: EventBatchRequest) -> ConstructionEventBatch:
        """Read ordered public events after an acknowledged sequence."""

    def approve(self, request: ApprovalDecision) -> ConstructionTaskHandle:
        """Apply one decision to the currently pending approval request."""

    def cancel(self, request: CancelRequest) -> ConstructionResult:
        """Idempotently cancel a non-terminal task or return its existing result."""

    def result(self, request: ResultRequest) -> ConstructionResult:
        """Return the unique terminal result, failing while the task is active."""
