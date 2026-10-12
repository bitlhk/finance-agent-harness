"""Task-local cancellation and reference checkpoint storage."""

from __future__ import annotations

from threading import Event, Lock

from finance_builder_base.solution_build.models import BuildCheckpoint


class CancellationToken:
    """Thread-safe signal checked before each new build stage."""

    def __init__(self) -> None:
        self._event = Event()

    def cancel(self) -> None:
        self._event.set()

    @property
    def requested(self) -> bool:
        return self._event.is_set()


class InMemoryCheckpointStore:
    """Small reference store for tests and single-process hosts."""

    def __init__(self) -> None:
        self._checkpoints: dict[str, BuildCheckpoint] = {}
        self._lock = Lock()

    def load(self, task_id: str) -> BuildCheckpoint | None:
        with self._lock:
            checkpoint = self._checkpoints.get(task_id)
            return None if checkpoint is None else checkpoint.model_copy(deep=True)

    def save(self, checkpoint: BuildCheckpoint) -> None:
        with self._lock:
            self._checkpoints[checkpoint.task_id] = checkpoint.model_copy(deep=True)
