from __future__ import annotations

import pytest
from pydantic import ValidationError

from finance_builder_base.contracts import (
    ContractModel,
    ErrorDetail,
    ExtensionModel,
    ResourceKind,
    ResourceRef,
    sanitize_public_text,
)


class ExampleExtension(ExtensionModel):
    extension_type: str = "example"
    label: str


def test_contract_rejects_unknown_and_invalid_assignment() -> None:
    class Count(ContractModel):
        value: int

    with pytest.raises(ValidationError):
        Count(value=1, unexpected=True)
    count = Count(value=1)
    with pytest.raises(ValidationError):
        count.value = "invalid"  # type: ignore[assignment]


def test_resource_refs_cover_public_transport_kinds() -> None:
    ResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="testsets/a.csv")
    ResourceRef(kind=ResourceKind.HTTP, locator="https://example.test/a.csv")
    ResourceRef(kind=ResourceKind.A2A_FILE, locator="file-part-1")
    with pytest.raises(ValidationError):
        ResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator="../outside")


@pytest.mark.parametrize(
    "message",
    [
        r"failed at C:\private\file.txt",
        "failed at /home/service/private.csv",
        r"failed at \\server\share\private.csv",
    ],
)
def test_public_error_rejects_host_absolute_paths(message: str) -> None:
    with pytest.raises(ValidationError):
        ErrorDetail(code="bad.path", message=message)


def test_public_error_rejects_secret() -> None:
    with pytest.raises(ValidationError):
        ErrorDetail(code="bad.secret", message="api_key=customer-secret")


def test_public_error_allows_url_and_logical_api_path() -> None:
    detail = ErrorDetail(
        code="remote.failed",
        message="request to https://example.test/api/v1 failed at endpoint /api/v1",
    )
    assert detail.code == "remote.failed"


def test_typed_extension_keeps_its_schema() -> None:
    extension = ExampleExtension(label="future-builder")
    assert extension.model_dump() == {
        "extension_type": "example",
        "schema_version": "1.0",
        "label": "future-builder",
    }


def test_sanitize_public_text_redacts_windows_posix_unc_paths_and_secrets() -> None:
    raw = (
        r"failed to open C:\Users\alice\secret.txt and /home/bob/config.yaml; "
        r"also \\nas01\share\file.txt with api_key=abc123"
    )
    cleaned = sanitize_public_text(raw)
    assert r"C:\Users" not in cleaned
    assert "/home/bob" not in cleaned
    assert r"\\nas01" not in cleaned
    assert "abc123" not in cleaned
    assert "<redacted-path>" in cleaned


def test_sanitize_public_text_keeps_relative_and_url_text() -> None:
    raw = "workspace/agents/solution missing; see https://example.test/docs"
    assert sanitize_public_text(raw) == raw
