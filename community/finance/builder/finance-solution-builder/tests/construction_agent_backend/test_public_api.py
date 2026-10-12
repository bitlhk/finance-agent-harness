import finance_solution_builder.construction_agent_backend as api


def test_public_api_contains_only_provider_neutral_names() -> None:
    assert "ConstructionAgentBackend" in api.__all__
    assert "ConstructionTaskRequest" in api.__all__
    assert "ConstructionEventBatch" in api.__all__
    assert not any("codex" in name.lower() or "opencode" in name.lower() for name in api.__all__)
