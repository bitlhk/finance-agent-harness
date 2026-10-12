"""OpenJiuwen Python Candidate Backend tests."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

from finance_builder_base import ResourceKind

from finance_solution_builder.assets import (
    HarnessArchiveAnchor,
    ResolvedAsset,
    archive_tree_digest,
    tree_digest,
    write_archive_integrity,
)
from finance_solution_builder.candidate_backend import (
    AssemblyRequest,
    BuildRequest,
    CandidateResourceRef,
    LifecycleStatus,
    OpenJiuwenPythonBackend,
    OpenJiuwenPythonZipAppBackend,
    PreflightRequest,
)
from tests.candidate_backend.conformance import ConformanceCase, run_successful_lifecycle


DIGEST = "a" * 64
FIXTURE = Path(__file__).parent / "python_candidate_fixture"


def _asset(root: Path, asset_id: str, source: str) -> ResolvedAsset:
    path = root / asset_id
    path.mkdir(parents=True, exist_ok=True)
    return ResolvedAsset(
        id=asset_id,
        version="1.0",
        source=source,
        status="resolved",
        digest=tree_digest(path),
        path=path,
        root_path=root,
    )


def _backend(
    tmp_path: Path,
    *,
    provider_has_capability: bool = True,
    dependencies_match: bool = True,
    environment: dict[str, str] | None = None,
) -> OpenJiuwenPythonZipAppBackend:
    assets_root = tmp_path / "assets"
    profile = _asset(assets_root, "employee-assisted", "repo://profiles/employee-assisted")
    domain = _asset(
        assets_root,
        "wealth-employee-assisted",
        "repo://domain-packs/wealth/employee-assisted",
    )
    (domain.path / "pack.yaml").write_text(
        "capabilities:\n  - wealth.previsit-context.query\n"
        "skills:\n  - directory: wealth-previsit-diagnosis\n",
        encoding="utf-8",
    )
    provider = _asset(assets_root, "bank-provider", "repo://providers/bank")
    provider_text = (
        '{"capabilityId":"wealth.previsit-context.query"}'
        if provider_has_capability
        else "{}"
    )
    (provider.path / "provider.json").write_text(provider_text, encoding="utf-8")
    evaluation = _asset(assets_root, "wealth-management", "repo://eval-packs/wealth")
    template_path = assets_root / "openjiuwen-python-template"
    shutil.copytree(FIXTURE, template_path)
    template = ResolvedAsset(
        id="openjiuwen-python-template",
        version="pinned",
        source="repo://templates/python",
        status="resolved",
        digest=tree_digest(template_path),
        path=template_path,
        root_path=assets_root,
    )
    integration = _asset(
        assets_root, "runtime-integration", "repo://integrations/openjiuwen-python"
    )
    (integration.path / "python-harness-integration.json").write_text(
        json.dumps({"harnessTransport": "canonical-json-http.v1"}),
        encoding="utf-8",
    )

    def runner(command: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
        assert command[:2] == [sys.executable, "-c"]
        assert cwd == tmp_path
        versions = {
            "python": f"{sys.version_info.major}.{sys.version_info.minor}",
            "openjiuwen": "0.1.16" if dependencies_match else "0.1.15",
            "openjiuwen-runtime-service": "0.1.0",
        }
        return subprocess.CompletedProcess(command, 0, json.dumps(versions), "")

    return OpenJiuwenPythonZipAppBackend(
        project_root=tmp_path,
        delivery_root=tmp_path / "delivery",
        candidate_dir=tmp_path / "delivery" / "candidate",
        solution={
            "solutionId": "wealth-manager-previsit-demo",
            "profileRef": {"id": "employee-assisted"},
            "runtime": {
                "family": "openjiuwen",
                "language": "python",
                "integrationRef": "repo://integrations/openjiuwen-python",
            },
        },
        assets=[profile, domain, provider, evaluation],
        runtime_assets={"template": template, "integration": integration},
        python=sys.executable,
        command_runner=runner,
        health_timeout_seconds=10,
        request_timeout_seconds=5,
        environment=environment,
    )


def _assemble_and_build(backend: OpenJiuwenPythonZipAppBackend):
    target = backend.target()
    preflight = backend.preflight(
        PreflightRequest(
            task_id="task-1",
            target=target,
            resolved_asset_ids=target.required_asset_ids,
        )
    )
    assert preflight.outcome.status is LifecycleStatus.PASSED
    assembly = backend.assemble(
        AssemblyRequest(
            task_id="task-1",
            target=target,
            lock_digest=DIGEST,
            workspace=CandidateResourceRef(
                kind=ResourceKind.WORKSPACE_PATH, locator="candidate"
            ),
            asset_refs=(),
        )
    )
    assert assembly.outcome.status is LifecycleStatus.PASSED
    return backend.build(
        BuildRequest(
            task_id="task-1",
            candidate_id=assembly.candidate_id,
            lock_digest=DIGEST,
            assembly_refs=assembly.assembly_refs,
        )
    )


def test_finance_service_and_zipapp_backends_have_distinct_contracts(
    tmp_path: Path,
) -> None:
    finance_backend = OpenJiuwenPythonBackend(
        builder=SimpleNamespace(),
        solution={"solutionId": "finance-service"},
        output=tmp_path / "finance-service",
    )
    zipapp_backend = _backend(tmp_path / "zipapp")

    assert finance_backend.identity.backend_id == "openjiuwen-python-finance"
    assert finance_backend.capability.artifact_kinds == ("directory-manifest",)
    assert finance_backend.capability.invocation_protocols == ("finance-chat",)
    assert zipapp_backend.identity.backend_id == "openjiuwen-python"
    assert zipapp_backend.capability.artifact_kinds == ("python-zipapp",)
    assert zipapp_backend.capability.invocation_protocols == ("a2a",)


def test_preflight_requires_pinned_dependencies_and_provider_coverage(tmp_path: Path) -> None:
    backend = _backend(tmp_path, provider_has_capability=False, dependencies_match=False)
    target = backend.target()

    result = backend.preflight(
        PreflightRequest(
            task_id="task-1",
            target=target,
            resolved_asset_ids=target.required_asset_ids,
        )
    )

    assert result.outcome.status is LifecycleStatus.BLOCKED
    blocked = {check.check_id for check in result.checks if check.status.value == "blocked"}
    assert blocked == {"python-dependencies", "capability-coverage"}


def test_preflight_rejects_incompatible_harness_integration(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    integration = backend.runtime_assets["integration"]
    (integration.path / "python-harness-integration.json").write_text(
        json.dumps({"harnessTransport": "unsupported.v1"}), encoding="utf-8"
    )
    target = backend.target()

    result = backend.preflight(
        PreflightRequest(
            task_id="task-1",
            target=target,
            resolved_asset_ids=target.required_asset_ids,
        )
    )

    assert result.outcome.status is LifecycleStatus.BLOCKED
    assert next(
        check for check in result.checks if check.check_id == "harness-integration"
    ).status.value == "blocked"


def test_preflight_rejects_invalid_template_entrypoint(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    template = backend.runtime_assets["template"]
    manifest = template.path / "python-candidate-template.json"
    document = json.loads(manifest.read_text(encoding="utf-8"))
    document["moduleEntrypoint"] = "missing-callable"
    manifest.write_text(json.dumps(document), encoding="utf-8")
    target = backend.target()

    result = backend.preflight(
        PreflightRequest(
            task_id="task-1",
            target=target,
            resolved_asset_ids=target.required_asset_ids,
        )
    )

    assert result.outcome.status is LifecycleStatus.BLOCKED
    assert next(
        check for check in result.checks if check.check_id == "python-template"
    ).status.value == "blocked"


def test_build_is_deterministic_and_descriptor_is_language_neutral(tmp_path: Path) -> None:
    first = _backend(tmp_path / "first")
    second = _backend(tmp_path / "second")

    first_result = _assemble_and_build(first)
    second_result = _assemble_and_build(second)

    assert first_result.descriptor is not None
    assert second_result.descriptor is not None
    first_bytes = (
        first.delivery_root / first_result.descriptor.artifact.resource.locator
    ).read_bytes()
    second_bytes = (
        second.delivery_root / second_result.descriptor.artifact.resource.locator
    ).read_bytes()
    assert hashlib.sha256(first_bytes).digest() == hashlib.sha256(second_bytes).digest()
    descriptor = first_result.descriptor
    assert descriptor.language == "python"
    assert descriptor.runtime.version == "0.1.16"
    assert descriptor.artifact.artifact_kind == "python-zipapp"
    assert descriptor.invocation.path == "/a2a"
    assert descriptor.health.path == "/health"
    assert descriptor.extensions[0].payload["runtimeServiceVersion"] == "0.1.0"
    provider_dir = first.delivery_root / "candidate" / "assets" / "bank-provider"
    assert (provider_dir / "asset-lock.json").is_file()
    assert not (provider_dir / "provider.json").exists()
    integration_dir = first.delivery_root / "candidate" / "assets" / "runtime-integration"
    assert (integration_dir / "python-harness-integration.json").is_file()


def test_python_backend_passes_shared_lifecycle_conformance(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    target = backend.target()
    request_path = backend.delivery_root / "runtime" / "requests" / "request.json"
    request_path.parent.mkdir(parents=True)
    payload = b'{"jsonrpc":"2.0","id":"probe","method":"SendMessage"}'
    request_path.write_bytes(payload)

    handle = run_successful_lifecycle(
        backend,
        ConformanceCase(
            task_id="task-1",
            target=target,
            resolved_asset_ids=target.required_asset_ids,
            lock_digest=DIGEST,
            workspace=CandidateResourceRef(
                kind=ResourceKind.WORKSPACE_PATH, locator="candidate"
            ),
            asset_refs=(),
            invocation_payload_ref=CandidateResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator=request_path.relative_to(backend.delivery_root).as_posix(),
                sha256=hashlib.sha256(payload).hexdigest(),
            ),
            delivery_root=backend.delivery_root,
        ),
    )

    assert handle.backend.backend_id == "openjiuwen-python"


def test_solution_builder_runs_python_candidate_and_reports_selected_backend(
    tmp_path: Path,
    monkeypatch,
) -> None:
    from tests.test_builder import _builder_fixture

    builder, solution_path, assets = _builder_fixture(
        tmp_path,
        monkeypatch,
        profile_id="employee-assisted",
    )
    template_path = tmp_path / "python-template"
    shutil.copytree(FIXTURE, template_path)
    assets["openjiuwen-python-template"] = ResolvedAsset(
        id="openjiuwen-python-template",
        version="pinned",
        source="repo://templates/python",
        status="resolved",
        digest=tree_digest(template_path),
        path=template_path,
        root_path=tmp_path,
    )
    integration = assets["runtime-integration"]
    (integration.path / "python-harness-integration.json").write_text(
        json.dumps({"harnessTransport": "canonical-json-http.v1"}),
        encoding="utf-8",
    )
    provider = assets["employee-agent-reference"]
    (provider.path / "provider.json").write_text(
        json.dumps({"capabilityId": "wealth.previsit-context.query"}),
        encoding="utf-8",
    )

    catalog_path = builder.harness_repo / "catalog/finance-assets.v1.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    catalog["runtimeIntegrations"][0]["compatibility"]["runtimeLanguages"] = ["python"]
    catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
    anchor = builder.harness_archive_anchor
    assert anchor is not None
    write_archive_integrity(
        builder.harness_repo,
        revision=anchor.revision,
        source=anchor.source,
    )
    builder.harness_archive_anchor = HarnessArchiveAnchor(
        source=anchor.source,
        revision=anchor.revision,
        tree_digest=archive_tree_digest(builder.harness_repo),
    )

    solution = json.loads(solution_path.read_text(encoding="utf-8"))
    solution["runtime"].update(
        {
            "language": "python",
            "templateRef": "repo://templates/python",
        }
    )
    solution_path.write_text(json.dumps(solution), encoding="utf-8")
    monkeypatch.setattr(
        OpenJiuwenPythonZipAppBackend,
        "_inspect_interpreter",
        lambda _self: {
            "python": f"{sys.version_info.major}.{sys.version_info.minor}",
            "openjiuwen": "0.1.16",
            "openjiuwen-runtime-service": "0.1.0",
        },
    )

    result = builder.build(solution_path, builder.project_root / "build/python", verify=True)
    report = json.loads(result.report_path.read_text(encoding="utf-8"))

    assert report["candidateBackend"]["backendId"] == "openjiuwen-python"
    assert report["candidateLanguage"] == "python"
    assert report["candidateRuntime"] == {"family": "openjiuwen", "version": "0.1.16"}
    assert report["verification"]["candidateStarted"] is True
    assert report["verification"]["candidateProtocolInvocationExecuted"] is True
