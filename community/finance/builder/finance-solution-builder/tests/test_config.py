import json
from pathlib import Path

import pytest
import yaml

from finance_solution_builder.config import load_solution, validate_solution
from finance_solution_builder.builder import SolutionBuilder


def minimal_solution() -> dict:
    asset = {"id": "asset", "version": "1", "source": "repo://asset"}
    return {
        "schemaVersion": "finance-agent-solution-build.v1",
        "solutionId": "wealth-demo",
        "displayName": "Wealth Demo",
        "runtime": {
            "family": "openjiuwen",
            "language": "java",
            "templateRef": "x",
            "integrationRef": "y",
        },
        "profileRef": asset,
        "domainPackRefs": [asset],
        "providerRefs": [asset],
        "modelRef": "env://MODEL",
        "evaluationPackRef": asset,
    }


def schema(tmp_path: Path) -> Path:
    path = tmp_path / "schema.json"
    path.write_text(
        json.dumps(
            {
                "$schema": "https://json-schema.org/draft/2020-12/schema",
                "type": "object",
                "required": ["solutionId"],
                "properties": {"solutionId": {"pattern": "^[a-z][a-z0-9-]+$"}},
            }
        ),
        encoding="utf-8",
    )
    return path


def test_load_yaml(tmp_path: Path) -> None:
    path = tmp_path / "solution.yaml"
    path.write_text(yaml.safe_dump(minimal_solution()), encoding="utf-8")
    assert load_solution(path)["solutionId"] == "wealth-demo"


def test_load_json(tmp_path: Path) -> None:
    path = tmp_path / "solution.json"
    path.write_text(json.dumps(minimal_solution()), encoding="utf-8")
    assert load_solution(path)["runtime"]["language"] == "java"


def test_validation_reports_path(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="solutionId"):
        validate_solution({"solutionId": "BAD"}, schema(tmp_path))


def test_general_compositions_are_not_decided_by_config_hardcoding() -> None:
    solution = minimal_solution()
    solution["profileRef"] = {
        "id": "customer-self-service",
        "version": "1",
        "source": "repo://profile",
    }
    solution["domainPackRefs"] = [
        {"id": "transfer-anti-fraud", "version": "1", "source": "repo://pack"}
    ]
    solution["providerRefs"] = [
        {"id": "fraud-mining-reference", "version": "1", "source": "repo://provider"}
    ]

    SolutionBuilder._validate_assembly_support(solution)
