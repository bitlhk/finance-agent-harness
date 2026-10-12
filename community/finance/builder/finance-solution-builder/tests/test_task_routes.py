import pytest

from finance_solution_builder.task_routes import TASK_ACTION_FIELDS, validate_task_body


@pytest.mark.parametrize("action", TASK_ACTION_FIELDS)
def test_exact_host_control_fields_only(action):
    body = {key: "test-value" for key in TASK_ACTION_FIELDS[action]}
    validate_task_body(action, body)
    for extra in ("confirmed", "principalId", "scopes", "parameters", "trusted"):
        with pytest.raises(ValueError, match="INVALID_TASK_REQUEST"):
            validate_task_body(action, dict(body, **{extra: "forged"}))
    for field in body:
        for invalid in ("", " ", True, {}, "x" * 161):
            with pytest.raises(ValueError, match="INVALID_TASK_REQUEST"):
                validate_task_body(action, dict(body, **{field: invalid}))
