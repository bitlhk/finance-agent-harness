"""Strict contract models shared by Builder implementations."""

from __future__ import annotations

import re
from enum import Enum
from pathlib import PurePosixPath
from typing import Any
from urllib.parse import urlparse

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

def _contains_credential_assignment(value: str) -> bool:
    return re.search(
        r"(?i)(authorization|api[_-]?key|access[_-]?token|password|secret)\s*[:=]",
        value,
    ) is not None


def _redact_credential_assignments(value: str) -> str:
    return re.sub(
        r"(?i)(authorization|api[_-]?key|access[_-]?token|password|secret)\s*[:=]\s*\S+",
        "<redacted-credential>",
        value,
    )
_WINDOWS_ABSOLUTE = re.compile(r"^[a-zA-Z]:[\\/]")
_WINDOWS_PATH_IN_TEXT = re.compile(r"(?<![a-zA-Z0-9])[a-zA-Z]:[\\/]")
_POSIX_HOST_PATH_IN_TEXT = re.compile(
    r"(?i)(?<![a-z0-9:/])/(?:home|users|tmp|var|etc|opt|srv|root)(?:/|$)"
)
_UNC_PATH_IN_TEXT = re.compile(r"(?<!\\)\\\\[^\\\s]+\\[^\\\s]+")


class ContractModel(BaseModel):
    """Base model for stable contracts.

    Unknown fields are rejected and assignment is validated so a valid object
    cannot silently become invalid after construction.
    """

    model_config = ConfigDict(extra="forbid", validate_assignment=True)


class ExtensionModel(ContractModel):
    """Versioned base for typed, scenario-owned extensions."""

    extension_type: str = Field(min_length=1, pattern=r"^[a-z][a-z0-9.-]*$")
    schema_version: str = Field(default="1.0", pattern=r"^\d+\.\d+$")


class ResourceKind(str, Enum):
    WORKSPACE_PATH = "workspace-path"
    HTTP = "http"
    A2A_FILE = "a2a-file"


class ResourceRef(ContractModel):
    """Portable reference; never contains a server-side absolute path."""

    kind: ResourceKind
    locator: str = Field(min_length=1)
    name: str | None = None
    media_type: str | None = None
    sha256: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")

    @model_validator(mode="after")
    def validate_locator(self) -> ResourceRef:
        if self.kind is ResourceKind.WORKSPACE_PATH:
            normalized = self.locator.replace("\\", "/")
            path = PurePosixPath(normalized)
            is_posix_absolute = normalized.startswith("/")
            is_windows_absolute = _WINDOWS_ABSOLUTE.match(self.locator) is not None
            has_parent_reference = ".." in path.parts
            if any((is_posix_absolute, is_windows_absolute, has_parent_reference)):
                raise ValueError("workspace locator must be a safe relative path")
        elif self.kind is ResourceKind.HTTP:
            parsed = urlparse(self.locator)
            if parsed.scheme not in {"http", "https"} or not parsed.netloc:
                raise ValueError("http resource requires an http(s) URL")
        return self


class ErrorDetail(ContractModel):
    """Public-safe, actionable error description."""

    code: str = Field(min_length=1, pattern=r"^[a-z][a-z0-9_.-]*$")
    message: str = Field(min_length=1)
    field: str | None = None
    retryable: bool = False

    @field_validator("message")
    @classmethod
    def reject_sensitive_message(cls, value: str) -> str:
        if _contains_credential_assignment(value):
            raise ValueError("error message may not contain credential material")
        contains_windows_path = _WINDOWS_PATH_IN_TEXT.search(value) is not None
        contains_posix_path = _POSIX_HOST_PATH_IN_TEXT.search(value) is not None
        contains_unc_path = _UNC_PATH_IN_TEXT.search(value) is not None
        if any((contains_windows_path, contains_posix_path, contains_unc_path)):
            raise ValueError("error message may not contain an absolute path")
        return value


def sanitize_public_text(value: str) -> str:
    """Redact absolute paths and credential-like material from public-facing text.

    Use this when wrapping arbitrary exception text for public responses; unlike
    ``ErrorDetail`` it redacts instead of rejecting.
    """

    redacted = _redact_credential_assignments(value)
    redacted = _WINDOWS_PATH_IN_TEXT.sub("<redacted-path>", redacted)
    redacted = _WINDOWS_ABSOLUTE.sub("<redacted-path>", redacted)
    redacted = _POSIX_HOST_PATH_IN_TEXT.sub("<redacted-path>", redacted)
    redacted = _UNC_PATH_IN_TEXT.sub("<redacted-path>", redacted)
    return redacted


def extension_dump(extension: ExtensionModel | None) -> dict[str, Any] | None:
    """Serialize a typed extension without weakening its validation boundary."""

    return None if extension is None else extension.model_dump(mode="json")
