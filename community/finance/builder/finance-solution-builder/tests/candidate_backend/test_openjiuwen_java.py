"""OpenJiuwen Java build adapter tests."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import zipfile
from dataclasses import replace
from pathlib import Path

import pytest
from finance_builder_base import ResourceKind

from finance_solution_builder.assets import ResolvedAsset
from finance_solution_builder.candidate_backend import (
    AssemblyRequest,
    BuildRequest,
    CandidateResourceRef,
    LifecycleStatus,
    OpenJiuwenJavaBackend,
    PreflightRequest,
    verify_candidate_artifact,
)
from tests.candidate_backend.conformance import ConformanceCase, run_successful_lifecycle


DIGEST = "a" * 64


def _asset(root: Path, asset_id: str, source: str) -> ResolvedAsset:
    path = root / asset_id
    path.mkdir(parents=True, exist_ok=True)
    return ResolvedAsset(
        id=asset_id,
        version="1.0",
        source=source,
        status="resolved",
        digest=DIGEST,
        path=path,
        root_path=root,
    )


def _backend(
    tmp_path: Path,
    *,
    provider_has_capability: bool = True,
    runnable_artifact: bool = False,
    artifact_mode: str = "valid",
) -> OpenJiuwenJavaBackend:
    delivery = tmp_path / "delivery"
    candidate = delivery / "candidate"
    candidate.mkdir(parents=True)
    root_pom = """<project xmlns="http://maven.apache.org/POM/4.0.0">
      <modelVersion>4.0.0</modelVersion>
      <groupId>example</groupId><artifactId>root</artifactId><version>1.2.3</version>
      <properties><openjiuwen.runtime.version>0.1.1.post1</openjiuwen.runtime.version></properties>
    </project>"""
    declared_main_class = "CandidateFixture" if runnable_artifact else "example.Main"
    module_pom = f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
      <modelVersion>4.0.0</modelVersion>
      <parent><groupId>example</groupId><artifactId>root</artifactId><version>1.2.3</version></parent>
      <artifactId>domain-runtime</artifactId>
      <build><plugins><plugin><configuration><mainClass>{declared_main_class}</mainClass></configuration></plugin></plugins></build>
    </project>"""
    (candidate / "pom.xml").write_text(root_pom, encoding="utf-8")
    module = candidate / "agents" / "domain-runtime"
    module.mkdir(parents=True)
    (module / "pom.xml").write_text(module_pom, encoding="utf-8")

    assets_root = tmp_path / "assets"
    profile = _asset(assets_root, "employee-assisted", "repo://profiles/employee-assisted")
    domain = _asset(
        assets_root, "wealth-employee-assisted", "repo://domain-packs/wealth/employee-assisted"
    )
    (domain.path / "pack.yaml").write_text(
        "capabilities:\n  - wealth.previsit-context.query\n"
        "skills:\n  - directory: wealth-previsit-diagnosis\n",
        encoding="utf-8",
    )
    provider = _asset(assets_root, "employee-agent-reference", "repo://providers/employee-agent")
    provider_text = (
        'class Provider { String capability = "wealth.previsit-context.query"; }'
        if provider_has_capability
        else "class Provider {}"
    )
    (provider.path / "Provider.java").write_text(provider_text, encoding="utf-8")
    evaluation = _asset(assets_root, "wealth-management", "repo://eval-packs/wealth")
    template = _asset(assets_root, "openjiuwen-java-template", "repo://templates/java")
    (template.root_path / "pom.xml").write_text(root_pom, encoding="utf-8")
    integration = _asset(
        assets_root, "runtime-integration", "repo://integrations/employee-agent-java"
    )

    def runner(command: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
        assert command[-1] == "package"
        assert cwd == candidate
        artifact = module / "target" / "domain-runtime-1.2.3.jar"
        artifact.parent.mkdir(parents=True, exist_ok=True)
        if runnable_artifact:
            if shutil.which("javac") is None or shutil.which("java") is None:
                pytest.skip("Java and javac are required for Java Backend conformance")
            source = module / "CandidateFixture.java"
            source.write_text(
                """
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
public final class CandidateFixture {
  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getenv("SERVER_PORT"));
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext("/actuator/health", exchange -> {
      byte[] body = "{\\\"status\\\":\\\"UP\\\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    server.createContext("/a2a", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] body = "{\\\"result\\\":{\\\"status\\\":\\\"COMPLETED\\\"}}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    server.start();
  }
}
""".strip()
                + "\n",
                encoding="utf-8",
            )
            subprocess.run(["javac", str(source)], cwd=module, check=True, capture_output=True)
            with zipfile.ZipFile(artifact, "w") as archive:
                archive.writestr(
                    "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\r\nMain-Class: CandidateFixture\r\n\r\n",
                )
                archive.write(module / "CandidateFixture.class", "CandidateFixture.class")
        elif artifact_mode == "invalid":
            artifact.write_bytes(b"candidate")
        else:
            with zipfile.ZipFile(artifact, "w") as archive:
                if artifact_mode != "missing-manifest":
                    manifest_main = "other.Main" if artifact_mode == "mismatch" else "example.Main"
                    archive.writestr(
                        "META-INF/MANIFEST.MF",
                        f"Manifest-Version: 1.0\r\nMain-Class: {manifest_main}\r\n\r\n",
                    )
                if artifact_mode != "missing-class":
                    class_name = "other/Main.class" if artifact_mode == "mismatch" else "example/Main.class"
                    archive.writestr(class_name, b"fixture-class")
        return subprocess.CompletedProcess(command, 0, "", "")

    solution = {
        "solutionId": "wealth-manager-previsit-demo",
        "profileRef": {"id": "employee-assisted"},
        "runtime": {
            "family": "openjiuwen",
            "language": "java",
            "integrationRef": "repo://integrations/employee-agent-java",
        },
    }
    return OpenJiuwenJavaBackend(
        project_root=tmp_path,
        delivery_root=delivery,
        candidate_dir=candidate,
        solution=solution,
        assets=[profile, domain, provider, evaluation],
        runtime_assets={"template": template, "integration": integration},
        assemble_action=lambda: None,
        command_runner=runner,
    )


def _assemble_and_build(backend: OpenJiuwenJavaBackend):
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
    return backend.build(
        BuildRequest(
            task_id="task-1",
            candidate_id=assembly.candidate_id,
            lock_digest=DIGEST,
            assembly_refs=assembly.assembly_refs,
        )
    )


def test_preflight_requires_provider_capability_coverage(tmp_path: Path) -> None:
    backend = _backend(tmp_path, provider_has_capability=False)
    target = backend.target()

    result = backend.preflight(
        PreflightRequest(
            task_id="task-1",
            target=target,
            resolved_asset_ids=target.required_asset_ids,
        )
    )

    assert result.outcome.status is LifecycleStatus.BLOCKED
    assert next(check for check in result.checks if check.check_id == "capability-coverage").status.value == "blocked"


def test_build_uses_exact_maven_artifact_and_writes_complete_descriptor(tmp_path: Path) -> None:
    backend = _backend(tmp_path)

    result = _assemble_and_build(backend)

    assert result.outcome.status is LifecycleStatus.PASSED
    assert result.descriptor is not None
    descriptor = result.descriptor
    assert descriptor.artifact.resource.locator.endswith(
        "candidate/agents/domain-runtime/target/domain-runtime-1.2.3.jar"
    )
    assert descriptor.runtime.version == "0.1.1.post1"
    assert descriptor.installed_capability_ids == ("wealth.previsit-context.query",)
    assert descriptor.provider_bindings[0].provider_id == "employee-agent-reference"
    assert descriptor.invocation.path == "/a2a"
    assert descriptor.health.path == "/actuator/health"
    verification = verify_candidate_artifact(descriptor, backend.delivery_root)
    assert verification.descriptor_digest == descriptor.sha256()
    assert verification.artifact_digest == descriptor.artifact.resource.sha256


@pytest.mark.parametrize(
    "artifact_mode",
    ["invalid", "missing-manifest", "mismatch", "missing-class"],
)
def test_build_rejects_non_executable_jar(tmp_path: Path, artifact_mode: str) -> None:
    backend = _backend(tmp_path, artifact_mode=artifact_mode)

    result = _assemble_and_build(backend)

    assert result.outcome.status is LifecycleStatus.FAILED
    assert result.outcome.error is not None
    assert result.outcome.error.code == "candidate.java.build_failed"
    assert result.descriptor is None


def test_tampered_artifact_is_rejected_before_start(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    result = _assemble_and_build(backend)
    assert result.descriptor is not None
    artifact = backend.delivery_root / result.descriptor.artifact.resource.locator
    artifact.write_bytes(b"tampered")

    with pytest.raises(ValueError, match="digest_mismatch"):
        verify_candidate_artifact(result.descriptor, backend.delivery_root)


def test_build_fails_closed_when_maven_coordinates_are_incomplete(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    pom = backend.candidate_dir / "agents/domain-runtime/pom.xml"
    pom.write_text("<project><modelVersion>4.0.0</modelVersion></project>", encoding="utf-8")
    target = backend.target()
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

    result = backend.build(
        BuildRequest(
            task_id="task-1",
            candidate_id=assembly.candidate_id,
            lock_digest=DIGEST,
            assembly_refs=assembly.assembly_refs,
        )
    )

    assert result.outcome.status is LifecycleStatus.FAILED
    assert result.descriptor is None


def test_build_fails_closed_when_executable_main_class_is_missing(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    pom = backend.candidate_dir / "agents/domain-runtime/pom.xml"
    pom.write_text(
        """<project>
          <modelVersion>4.0.0</modelVersion>
          <parent><groupId>example</groupId><artifactId>root</artifactId><version>1.2.3</version></parent>
          <artifactId>domain-runtime</artifactId>
        </project>""",
        encoding="utf-8",
    )
    target = backend.target()
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

    result = backend.build(
        BuildRequest(
            task_id="task-1",
            candidate_id=assembly.candidate_id,
            lock_digest=DIGEST,
            assembly_refs=assembly.assembly_refs,
        )
    )

    assert result.outcome.status is LifecycleStatus.FAILED
    assert result.descriptor is None


def test_target_fails_closed_when_runtime_version_is_not_declared(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    (backend.runtime_assets["template"].root_path / "pom.xml").unlink()

    with pytest.raises(ValueError, match="Runtime version is missing"):
        backend.target()


def test_target_uses_the_pinned_runtime_coordinate_when_template_falls_back(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    backend.solution["runtime"]["templateRef"] = "maven://com.openjiuwen:agent-core-java:0.1.15"
    (backend.runtime_assets["template"].root_path / "pom.xml").unlink()

    assert backend._runtime_version() == "0.1.15"


def test_target_deduplicates_required_asset_ids(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    backend.assets.append(backend.assets[0])

    target = backend.target()

    assert len(target.required_asset_ids) == len(set(target.required_asset_ids))


def test_internal_control_profile_uses_its_declared_smart_audit_host_wiring(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    backend.solution["profileRef"]["id"] = "internal-control"
    backend.solution["runtime"]["integrationRef"] = "repo://integrations/openjiuwen-java"

    assert backend.host_wiring_ids == ("in-process-smart-audit.v1",)


def test_preflight_accepts_the_controlled_template_fallback(tmp_path: Path) -> None:
    backend = _backend(tmp_path)
    backend.runtime_assets["template"] = replace(
        backend.runtime_assets["template"], status="fallback"
    )
    target = backend.target()

    result = backend.preflight(
        PreflightRequest(
            task_id="fallback-template",
            target=target,
            resolved_asset_ids=target.required_asset_ids,
        )
    )

    assert result.checks[0].status.value == "passed"


def test_openjiuwen_java_backend_passes_public_conformance(tmp_path: Path) -> None:
    backend = _backend(tmp_path, runnable_artifact=True)
    payload_path = backend.delivery_root / "evaluation" / "request.json"
    payload_path.parent.mkdir(parents=True)
    payload = json.dumps({"jsonrpc": "2.0", "id": "probe", "method": "SendMessage"}).encode()
    payload_path.write_bytes(payload)
    target = backend.target()

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
                locator=payload_path.relative_to(backend.delivery_root).as_posix(),
                sha256=hashlib.sha256(payload).hexdigest(),
            ),
            delivery_root=backend.delivery_root,
        ),
    )

    assert handle.candidate_id == "wealth-manager-previsit-demo"
