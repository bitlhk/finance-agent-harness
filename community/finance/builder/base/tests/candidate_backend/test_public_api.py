"""Public ownership checks for the Candidate Backend contract."""

from pathlib import Path

from finance_builder_base import candidate_backend


def test_candidate_backend_public_api_is_owned_by_base() -> None:
    assert candidate_backend.CandidateDescriptor.__module__.startswith(
        "finance_builder_base.candidate_backend"
    )
    assert candidate_backend.CandidateBackend.__module__.startswith(
        "finance_builder_base.candidate_backend"
    )
    assert candidate_backend.PythonZipAppManagedRuntime.__module__.startswith(
        "finance_builder_base.candidate_backend"
    )


def test_base_candidate_backend_does_not_import_a_concrete_builder() -> None:
    source_root = Path(__file__).parents[2] / "src" / "finance_builder_base" / "candidate_backend"

    for path in source_root.glob("*.py"):
        assert "finance_solution_builder" not in path.read_text(encoding="utf-8")
