import pytest

from finance_solution_builder.workbench_api import public_error


@pytest.mark.parametrize("source,expected", [
    ("SNAPSHOT_VIEW_NOT_GRANTED", "SNAPSHOT_VIEW_NOT_GRANTED"),
    ("SNAPSHOT_UNAVAILABLE", "SNAPSHOT_READ_REJECTED"),
    ("private-owner /private/file token=secret", "ACCESS_REJECTED"),
    ("SNAPSHOT_REFERENCE_MISMATCH", "ACCESS_REJECTED"),
])
def test_snapshot_view_errors_only_publish_explicit_safe_codes(source, expected):
    status, value = public_error(PermissionError(source))
    assert status == 403
    assert value == {"code": expected, "retryable": False}


@pytest.mark.parametrize("code", ["INVALID_HISTORY_CURSOR", "INVALID_HISTORY_FILTER", "INVALID_HISTORY_LIMIT", "INVALID_SNAPSHOT_REFERENCE"])
def test_history_validation_codes_are_safe_and_actionable(code):
    assert public_error(ValueError(code)) == (400, {"code": code, "retryable": False})
