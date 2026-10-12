from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator


def load_solution(path: Path) -> dict[str, Any]:
    text = path.read_text(encoding="utf-8")
    data = json.loads(text) if path.suffix.lower() == ".json" else yaml.safe_load(text)
    if not isinstance(data, dict):
        raise ValueError("solution input must be an object")
    return data


def validate_solution(solution: dict[str, Any], schema_path: Path) -> None:
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    errors = sorted(Draft202012Validator(schema).iter_errors(solution), key=lambda item: list(item.path))
    if errors:
        messages = [f"{'/'.join(map(str, item.path)) or '<root>'}: {item.message}" for item in errors]
        raise ValueError("solution schema validation failed:\n" + "\n".join(messages))
