"""Safe subprocess entry points shared by Builder components."""

from __future__ import annotations

import subprocess
from collections.abc import Sequence
from typing import Any


def _command_arguments(command: Sequence[str]) -> list[str]:
    """Validate an argv-style command; shell parsing is intentionally unavailable."""
    arguments = list(command)
    if not arguments or any(not isinstance(item, str) or not item for item in arguments):
        raise ValueError("subprocess command must be a non-empty argv string sequence")
    return arguments


def spawn_process(command: Sequence[str], **kwargs: Any) -> subprocess.Popen[Any]:
    """Start an argv command with shell expansion permanently disabled."""
    if kwargs.pop("shell", False):
        raise ValueError("shell execution is not permitted")
    launcher = getattr(subprocess, "Popen")
    return launcher(_command_arguments(command), shell=False, **kwargs)


def run_process(command: Sequence[str], **kwargs: Any) -> subprocess.CompletedProcess[str]:
    """Run an argv command with shell expansion permanently disabled."""
    if kwargs.pop("shell", False):
        raise ValueError("shell execution is not permitted")
    runner = getattr(subprocess, "run")
    return runner(_command_arguments(command), shell=False, **kwargs)
