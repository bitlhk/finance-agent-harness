"""Finance Harness solution builder."""

from finance_solution_builder.candidate_backend import (
    BackendCapability,
    BackendRegistry,
    BackendSelectionError,
    CandidateBackend,
    CandidateDescriptor,
    CandidateTarget,
    LifecycleOperation,
    LifecycleStatus,
)

__all__ = [
    "BackendCapability",
    "BackendRegistry",
    "BackendSelectionError",
    "CandidateBackend",
    "CandidateDescriptor",
    "CandidateTarget",
    "LifecycleOperation",
    "LifecycleStatus",
]

__version__ = "0.2.0"
