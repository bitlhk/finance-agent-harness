"""受管任务合同拒绝伪造身份、未确认执行和任意调用参数。"""
from pathlib import Path
import json
import pytest
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "contracts/json-schema/task-interaction"
CASES = json.loads((ROOT / "conformance/original-task-managed/schema-cases.v1.json").read_text(encoding="utf8"))


@pytest.mark.parametrize("name", ["support", "snapshot", "action", "receipt", "source-release", "authority"])
def test_schema_is_strict_and_valid(name):
    schema = json.loads((SCHEMAS / f"{name}.v1.schema.json").read_text(encoding="utf8"))
    Draft202012Validator.check_schema(schema)
    assert schema["additionalProperties"] is False
    assert schema["required"]


@pytest.mark.parametrize("case", CASES["positive"])
def test_supported_actions(case):
    schema = json.loads((SCHEMAS / "action.v1.schema.json").read_text(encoding="utf8"))
    Draft202012Validator(schema, format_checker=FormatChecker()).validate(case)


@pytest.mark.parametrize("case", CASES["negative"])
def test_unsafe_or_incomplete_action_is_rejected(case):
    schema = json.loads((SCHEMAS / "action.v1.schema.json").read_text(encoding="utf8"))
    assert list(Draft202012Validator(schema).iter_errors(case))
