"""Deterministic Candidate Backend selection."""

from __future__ import annotations

from collections.abc import Iterable

from finance_builder_base.contracts import ErrorDetail

from finance_builder_base.candidate_backend.models import (
    BackendCapability,
    CandidateTarget,
)
from finance_builder_base.candidate_backend.spi import CandidateBackend


class BackendSelectionError(ValueError):
    """Stable, public-safe selection failure."""

    def __init__(self, code: str, message: str) -> None:
        self.detail = ErrorDetail(code=code, message=message)
        super().__init__(f"{code}: {message}")


def supports_target(capability: BackendCapability, target: CandidateTarget) -> bool:
    """Return whether one Backend capability fully covers the target."""

    return all(
        (
            target.language in capability.languages,
            target.runtime_family in capability.runtime_families,
            target.artifact_kind in capability.artifact_kinds,
            target.invocation_protocol in capability.invocation_protocols,
            set(target.required_operations).issubset(capability.operations),
        )
    )


class BackendRegistry:
    """Select an explicit Backend or the only fully compatible Backend."""

    def __init__(self, backends: Iterable[CandidateBackend] = ()) -> None:
        self._backends: dict[str, CandidateBackend] = {}
        for backend in backends:
            self.register(backend)

    def register(self, backend: CandidateBackend) -> None:
        backend_id = backend.identity.backend_id
        if backend.capability.backend != backend.identity:
            raise BackendSelectionError(
                "candidate.backend.identity_mismatch",
                "Backend capability identity does not match the registered Backend.",
            )
        if backend_id in self._backends:
            raise BackendSelectionError(
                "candidate.backend.duplicate",
                "Backend identity is already registered.",
            )
        self._backends[backend_id] = backend

    def all(self) -> tuple[CandidateBackend, ...]:
        return tuple(self._backends[key] for key in sorted(self._backends))

    def select(
        self, target: CandidateTarget, *, backend_id: str | None = None
    ) -> CandidateBackend:
        if backend_id is not None:
            backend = self._backends.get(backend_id)
            if backend is None:
                raise BackendSelectionError(
                    "candidate.backend.explicit_missing",
                    "The explicitly selected Backend is not registered.",
                )
            if not supports_target(backend.capability, target):
                raise BackendSelectionError(
                    "candidate.backend.explicit_unsupported",
                    "The explicitly selected Backend does not support the requested target.",
                )
            return backend

        matches = [
            backend for backend in self.all() if supports_target(backend.capability, target)
        ]
        if not matches:
            raise BackendSelectionError(
                "candidate.backend.no_match",
                "No registered Backend supports the requested target.",
            )
        if len(matches) > 1:
            raise BackendSelectionError(
                "candidate.backend.ambiguous",
                "Multiple Backends support the target; select one explicitly.",
            )
        return matches[0]
