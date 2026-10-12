"""Candidate Backend lifecycle SPI."""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from finance_builder_base.candidate_backend.models import (
    AssemblyRequest,
    AssemblyResult,
    BackendCapability,
    BackendIdentity,
    BuildRequest,
    BuildResult,
    EvidenceRequest,
    EvidenceResult,
    HealthRequest,
    HealthResult,
    InvocationRequest,
    InvocationResult,
    PreflightRequest,
    PreflightResult,
    StartRequest,
    StartResult,
    StopRequest,
    StopResult,
)


@runtime_checkable
class CandidateBackend(Protocol):
    """Language-neutral lifecycle implemented by concrete Candidate runtimes."""

    @property
    def identity(self) -> BackendIdentity:
        """Return the stable Backend identity and implementation version."""

    @property
    def capability(self) -> BackendCapability:
        """Describe supported targets before any side effect occurs."""

    def preflight(self, request: PreflightRequest) -> PreflightResult:
        """Check assets, tools, capability coverage and Host Wiring."""

    def assemble(self, request: AssemblyRequest) -> AssemblyResult:
        """Assemble a Candidate workspace from the locked assets."""

    def build(self, request: BuildRequest) -> BuildResult:
        """Build the Candidate and return its validated descriptor."""

    def start(self, request: StartRequest) -> StartResult:
        """Start a Candidate whose delivery artifact verification is bound to the request."""

    def health(self, request: HealthRequest) -> HealthResult:
        """Check whether the managed instance is ready for Evaluation."""

    def invoke(self, request: InvocationRequest) -> InvocationResult:
        """Invoke the Candidate through its declared public protocol."""

    def collect_evidence(self, request: EvidenceRequest) -> EvidenceResult:
        """Return public references to Backend-owned evidence."""

    def stop(self, request: StopRequest) -> StopResult:
        """Idempotently stop an instance created by this Backend."""
