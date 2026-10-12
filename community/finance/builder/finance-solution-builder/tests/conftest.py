import shutil
from pathlib import Path

import pytest
import yaml

from finance_solution_builder.service import solution_for
from tests.component_fixture import copy_admission_metadata


PROJECT = Path(__file__).resolve().parents[1]


@pytest.fixture
def root(tmp_path: Path) -> Path:
    """Provide the shared component-assembly Harness fixture."""
    destination = tmp_path / "harness"
    shutil.copytree(PROJECT / "tests/fixtures/component-assembly", destination)
    return destination


@pytest.fixture
def harness(tmp_path: Path) -> Path:
    root = tmp_path / "harness"
    shutil.copytree(PROJECT / "tests/fixtures/business-assets", root)
    copy_admission_metadata(root)
    capabilities = (
        ("account.balance.query", "account_balance_query", ["account:read"]),
        ("wealth.facts.describe", "wealth_facts_describe", ["wealth:facts:read"]),
    )
    for capability_id, tool_name, scopes in capabilities:
        recipe = solution_for([capability_id], "fixture")
        refs = [
            recipe["profileRef"],
            *recipe["domainPackRefs"],
            *recipe["capabilityPackRefs"],
            recipe["evaluationPackRef"],
        ]
        for ref in refs:
            path = root / ref["source"].removeprefix("repo://")
            if ref in recipe["capabilityPackRefs"]:
                path.mkdir(parents=True, exist_ok=True)
                document = {
                    "schemaVersion": "finance-capability-pack.v1",
                    "systemPrompt": "Controlled source fixture",
                    "packId": ref["id"],
                    "version": ref["version"],
                    "capabilities": [
                        {
                            "capabilityId": capability_id,
                            "toolName": tool_name,
                            "name": "Controlled query",
                            "description": "Controlled query",
                            "operation": "QUERY",
                            "inputSchema": {"type": "object"},
                            "requiredScopes": scopes,
                        }
                    ],
                }
                (path / "pack.yaml").write_text(yaml.safe_dump(document))
            elif ref in recipe["domainPackRefs"]:
                path.mkdir(parents=True, exist_ok=True)
                document = {
                    "schemaVersion": "finance-domain-pack.v1",
                    "packId": ref["id"],
                    "version": ref["version"],
                    "profile": "local-read-only",
                    "capabilities": [capability_id],
                    "scope": ["Describe facts"],
                    "outOfScope": ["Writes"],
                    "skills": [],
                }
                (path / "pack.yaml").write_text(yaml.safe_dump(document))
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.touch(exist_ok=True)
    return root
