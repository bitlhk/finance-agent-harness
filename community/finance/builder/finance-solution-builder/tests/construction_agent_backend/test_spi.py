from finance_solution_builder.construction_agent_backend import ConstructionAgentBackend
from tests.construction_agent_backend.support import FakeConstructionAgentBackend


def test_fake_backend_satisfies_runtime_protocol() -> None:
    assert isinstance(FakeConstructionAgentBackend(), ConstructionAgentBackend)
