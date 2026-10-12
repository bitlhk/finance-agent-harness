import re
from pathlib import Path

import pytest

from finance_solution_builder.builder import SolutionBuilder


def test_example_contains_only_environment_model_reference() -> None:
    root = Path(__file__).parents[1]
    text = (root / "examples/wealth-manager-previsit/solution.yaml").read_text(encoding="utf-8")
    assert "env://FINANCE_AGENT_MODEL" in text
    for forbidden in ("api_key", "password", "INTERNAL_RUNTIME_TOKEN_SECRET:", "Bearer "):
        assert forbidden not in text


def test_template_does_not_contain_a_secret_value() -> None:
    root = Path(__file__).parents[1]
    env = (root / "src/finance_solution_builder/builder.py").read_text(encoding="utf-8")
    matches = re.findall(r'INTERNAL_RUNTIME_TOKEN_SECRET=([^"\\n]*)', env)
    assert all(not value.strip() for value in matches)


def test_builder_refuses_to_delete_output_outside_project(tmp_path: Path) -> None:
    project = tmp_path / "builder"
    project.mkdir()
    harness = tmp_path / "harness"
    harness.mkdir()
    builder = SolutionBuilder(project, harness)

    with pytest.raises(ValueError, match="child of the Builder project root"):
        builder.build(tmp_path / "solution.yaml", tmp_path / "outside", verify=False)
