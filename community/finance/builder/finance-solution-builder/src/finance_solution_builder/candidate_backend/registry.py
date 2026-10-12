"""Compatibility exports for Base-owned Candidate Backend selection."""

from finance_builder_base.candidate_backend.registry import (
    BackendRegistry,
    BackendSelectionError,
    supports_target,
)

__all__ = ["BackendRegistry", "BackendSelectionError", "supports_target"]
