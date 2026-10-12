"""Legacy compatibility wrapper for the Base-owned managed Python runtime."""

from pathlib import Path

from finance_builder_base.candidate_backend import BackendIdentity
from finance_builder_base.candidate_backend.python_runtime import (
    PythonZipAppManagedRuntime as BasePythonZipAppManagedRuntime,
)


class PythonZipAppManagedRuntime(BasePythonZipAppManagedRuntime):
    """Preserve the historical OpenJiuwen evidence wire identifier."""

    def __init__(
        self,
        *,
        backend: BackendIdentity,
        delivery_root: Path,
        python: str,
        health_timeout_seconds: float = 60.0,
        request_timeout_seconds: float = 120.0,
        environment: dict[str, str] | None = None,
        evidence_schema_version: str = "openjiuwen-python-lifecycle-evidence.v1",
    ) -> None:
        super().__init__(
            backend=backend,
            delivery_root=delivery_root,
            python=python,
            health_timeout_seconds=health_timeout_seconds,
            request_timeout_seconds=request_timeout_seconds,
            environment=environment,
            evidence_schema_version=evidence_schema_version,
        )

__all__ = ["PythonZipAppManagedRuntime"]
