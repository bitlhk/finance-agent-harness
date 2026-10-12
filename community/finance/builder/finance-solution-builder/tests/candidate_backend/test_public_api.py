"""Stable package import surface tests."""

import finance_solution_builder as public_api
from finance_solution_builder.candidate_backend import CandidateBackend


def test_top_level_package_exposes_only_portable_candidate_concepts() -> None:
    expected = {
        "BackendCapability",
        "BackendRegistry",
        "BackendSelectionError",
        "CandidateBackend",
        "CandidateDescriptor",
        "CandidateTarget",
        "LifecycleOperation",
        "LifecycleStatus",
    }

    assert set(public_api.__all__) == expected
    assert public_api.CandidateBackend is CandidateBackend
    assert not hasattr(public_api, "MavenBuildRequest")
