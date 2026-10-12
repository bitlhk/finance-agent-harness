import importlib.util
import json
from pathlib import Path

import pytest

from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.service import solution_for

PROJECT = Path(__file__).resolve().parents[1]


def test_external_storage_is_explicit_and_confined_to_service_recipe(tmp_path, monkeypatch):
    source, storage = tmp_path / "source", tmp_path / "storage"
    source.mkdir()
    storage.mkdir()
    solution = solution_for(["account.balance.query"], "agent-one")
    path = tmp_path / "input.json"
    path.write_text(json.dumps(solution))
    with pytest.raises(ValueError, match="child of"):
        SolutionBuilder(source, source).build(path, storage / "agent-one", verify=False)
    builder = SolutionBuilder(source, source, service_output_root=storage)
    calls = []
    monkeypatch.setattr(builder, "_load_and_validate", lambda path: solution)
    monkeypatch.setattr("finance_solution_builder.service.build_service", lambda *a, **kw: calls.append(a[2]))
    builder.build(path, storage / "agent-one", verify=False)
    assert calls == [storage / "agent-one"]
    for destination in (storage, tmp_path / "escape"):
        with pytest.raises(ValueError):
            builder.build(path, destination, verify=False)
    (storage / "link").symlink_to(tmp_path / "escape", target_is_directory=True)
    with pytest.raises(ValueError):
        builder.build(path, storage / "link/agent-one", verify=False)
    solution["profileRef"]["id"] = "shared-read-only"
    with pytest.raises(ValueError, match="EXTERNAL_OUTPUT_ONLY"):
        builder.build(path, storage / "agent-two", verify=False)


def test_deployment_render_is_private_explicit_and_non_overwriting(tmp_path):
    spec = importlib.util.spec_from_file_location("prepare", PROJECT / "engineering/prepare_server_deployment.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    output = tmp_path / "deployment"
    module.prepare("studio.example.internal", output)
    assert output.stat().st_mode & 0o777 == 0o700
    for file in output.iterdir():
        assert file.stat().st_mode & 0o777 == 0o600
        assert "@@DOMAIN@@" not in file.read_text()
    token = (output / "proxy-token").read_text().strip()
    assert len(token) == 64 and token in (output / "proxy-header.conf").read_text()
    nginx = (output / "nginx.conf").read_text()
    assert "proxy_set_header X-Finance-User $remote_user;" in nginx
    assert "auth_basic_user_file" in nginx and "proxy_next_upstream off;" in nginx
    with pytest.raises(FileExistsError):
        module.prepare("studio.example.internal", output)
    with pytest.raises(ValueError):
        module.prepare("evil.internal; include /etc/*", tmp_path / "bad")
