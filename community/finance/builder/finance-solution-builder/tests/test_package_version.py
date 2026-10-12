from pathlib import Path
import tomllib

import finance_solution_builder


def test_package_and_runtime_versions_match_release_scope():
    project = Path(__file__).resolve().parents[1]
    metadata = tomllib.loads((project / "pyproject.toml").read_text(encoding="utf-8"))

    assert metadata["project"]["version"] == "0.2.0"
    assert finance_solution_builder.__version__ == metadata["project"]["version"]
