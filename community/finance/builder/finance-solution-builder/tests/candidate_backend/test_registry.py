"""Deterministic Backend selection tests."""

from dataclasses import dataclass

import pytest

from finance_solution_builder.candidate_backend import (
    BackendCapability,
    BackendIdentity,
    BackendRegistry,
    BackendSelectionError,
    LifecycleOperation,
    supports_target,
)

from .support import ALL_OPERATIONS, target


@dataclass
class StubBackend:
    identity: BackendIdentity
    capability: BackendCapability


def backend(backend_id: str, *, language: str = "java") -> StubBackend:
    identity = BackendIdentity(backend_id=backend_id, version="1.0")
    return StubBackend(
        identity=identity,
        capability=BackendCapability(
            backend=identity,
            languages=(language,),
            runtime_families=("openjiuwen",),
            artifact_kinds=("jar" if language == "java" else "wheel",),
            invocation_protocols=("a2a",),
            operations=ALL_OPERATIONS,
        ),
    )


def test_registry_selects_only_compatible_backend() -> None:
    java = backend("openjiuwen-java")
    python = backend("openjiuwen-python", language="python")
    registry = BackendRegistry((python, java))

    assert registry.select(target()) is java
    assert supports_target(java.capability, target()) is True
    assert supports_target(python.capability, target()) is False


@pytest.mark.parametrize(
    ("backends", "explicit_id", "code"),
    [
        ((), None, "candidate.backend.no_match"),
        ((backend("java-a"), backend("java-b")), None, "candidate.backend.ambiguous"),
        ((backend("java-a"),), "missing", "candidate.backend.explicit_missing"),
        ((backend("python", language="python"),), "python", "candidate.backend.explicit_unsupported"),
    ],
)
def test_registry_rejects_fallback_and_ambiguity(
    backends: tuple[StubBackend, ...], explicit_id: str | None, code: str
) -> None:
    registry = BackendRegistry(backends)

    with pytest.raises(BackendSelectionError) as caught:
        registry.select(target(), backend_id=explicit_id)

    assert caught.value.detail.code == code


def test_registry_rejects_duplicate_identity() -> None:
    registry = BackendRegistry((backend("java"),))

    with pytest.raises(BackendSelectionError) as caught:
        registry.register(backend("java"))

    assert caught.value.detail.code == "candidate.backend.duplicate"


def test_registry_rejects_capability_from_another_backend() -> None:
    item = backend("java")
    item.capability = item.capability.model_copy(
        update={"backend": BackendIdentity(backend_id="other", version="1.0")}
    )

    with pytest.raises(BackendSelectionError) as caught:
        BackendRegistry((item,))

    assert caught.value.detail.code == "candidate.backend.identity_mismatch"


def test_registry_checks_all_required_lifecycle_operations() -> None:
    item = backend("java")
    item.capability = item.capability.model_copy(
        update={
            "operations": tuple(
                operation
                for operation in ALL_OPERATIONS
                if operation is not LifecycleOperation.STOP
            )
        }
    )

    assert supports_target(item.capability, target()) is False
