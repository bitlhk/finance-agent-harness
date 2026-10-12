"""Managed Java Candidate lifecycle tests."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path

import pytest
from finance_builder_base import ResourceKind

from finance_solution_builder.candidate_backend import (
    BackendIdentity,
    CandidateArtifact,
    CandidateDescriptor,
    CandidateEntrypoint,
    CandidateResourceRef,
    EvidenceRequest,
    HealthRequest,
    InvocationRequest,
    LifecycleStatus,
    ProtocolBinding,
    RuntimeIdentity,
    StartRequest,
    StopRequest,
    verify_candidate_artifact,
)
from finance_solution_builder.candidate_backend.java_runtime import JavaManagedRuntime


DIGEST = "a" * 64


def _compile_fixture(root: Path) -> Path:
    if shutil.which("javac") is None or shutil.which("java") is None:
        pytest.skip("Java and javac are required for the managed Java runtime test")
    source = root / "CandidateFixture.java"
    source.write_text(
        """
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class CandidateFixture {
  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getenv("SERVER_PORT"));
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.createContext("/health", exchange -> {
      byte[] body = "{\\\"status\\\":\\\"UP\\\"}".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    server.createContext("/a2a", exchange -> {
      exchange.getRequestBody().readAllBytes();
      byte[] body = "{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":\\\"probe\\\",\\\"result\\\":{\\\"status\\\":\\\"COMPLETED\\\"}}".getBytes(StandardCharsets.UTF_8);
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
    subprocess.run(["javac", str(source)], cwd=root, check=True, capture_output=True)
    jar_path = root / "candidate-fixture.jar"
    with zipfile.ZipFile(jar_path, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMain-Class: CandidateFixture\r\n\r\n")
        archive.write(root / "CandidateFixture.class", "CandidateFixture.class")
    return jar_path


def _descriptor(delivery: Path, artifact: Path, *, health_path: str = "/health") -> CandidateDescriptor:
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    resource = CandidateResourceRef(
        kind=ResourceKind.WORKSPACE_PATH,
        locator=artifact.relative_to(delivery).as_posix(),
        sha256=digest,
    )
    return CandidateDescriptor(
        task_id="task-1",
        candidate_id="candidate-1",
        backend=BackendIdentity(backend_id="openjiuwen-java", version="1.0"),
        language="java",
        runtime=RuntimeIdentity(family="openjiuwen", version="test"),
        artifact=CandidateArtifact(
            artifact_id="candidate-fixture", artifact_kind="jar", resource=resource
        ),
        entrypoint=CandidateEntrypoint(mode="managed-artifact", resource=resource),
        invocation=ProtocolBinding(protocol="a2a", path="/a2a", media_type="application/json"),
        health=ProtocolBinding(protocol="http", path=health_path),
        lock_digest=DIGEST,
        installed_asset_ids=("runtime", "domain-pack", "provider"),
    )


def _payload(delivery: Path) -> CandidateResourceRef:
    path = delivery / "runtime" / "requests" / "request.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    body = json.dumps({"jsonrpc": "2.0", "id": "probe", "method": "SendMessage"}).encode()
    path.write_bytes(body)
    return CandidateResourceRef(
        kind=ResourceKind.WORKSPACE_PATH,
        locator=path.relative_to(delivery).as_posix(),
        sha256=hashlib.sha256(body).hexdigest(),
    )


def test_managed_java_runtime_completes_lifecycle_and_idempotent_stop(tmp_path: Path) -> None:
    delivery = tmp_path / "delivery"
    candidate = delivery / "candidate"
    candidate.mkdir(parents=True)
    artifact = _compile_fixture(candidate)
    descriptor = _descriptor(delivery, artifact)
    runtime = JavaManagedRuntime(
        backend=descriptor.backend,
        delivery_root=delivery,
        health_timeout_seconds=10,
        request_timeout_seconds=5,
    )

    started = runtime.start(
        StartRequest(
            descriptor=descriptor,
            artifact_verification=verify_candidate_artifact(descriptor, delivery),
        )
    )
    assert started.handle is not None
    handle = started.handle
    assert runtime.health(HealthRequest(handle=handle)).ready is True
    invoked = runtime.invoke(
        InvocationRequest(
            request_id="probe",
            handle=handle,
            protocol="a2a",
            payload_ref=_payload(delivery),
        )
    )
    assert invoked.outcome.status is LifecycleStatus.PASSED
    assert invoked.response_ref is not None
    evidence = runtime.collect_evidence(EvidenceRequest(handle=handle))
    assert evidence.outcome.evidence_refs
    assert runtime.stop(StopRequest(handle=handle)).stopped is True
    assert runtime.stop(StopRequest(handle=handle)).stopped is True


def test_health_failure_cleans_up_managed_process(tmp_path: Path) -> None:
    delivery = tmp_path / "delivery"
    candidate = delivery / "candidate"
    candidate.mkdir(parents=True)
    descriptor = _descriptor(delivery, _compile_fixture(candidate), health_path="/missing")
    runtime = JavaManagedRuntime(
        backend=descriptor.backend,
        delivery_root=delivery,
        health_timeout_seconds=0.5,
    )
    started = runtime.start(
        StartRequest(
            descriptor=descriptor,
            artifact_verification=verify_candidate_artifact(descriptor, delivery),
        )
    )
    assert started.handle is not None

    result = runtime.health(HealthRequest(handle=started.handle))

    assert result.outcome.status is LifecycleStatus.FAILED
    assert runtime.stop(StopRequest(handle=started.handle)).stopped is True


def test_public_evidence_does_not_copy_environment_secrets(tmp_path: Path) -> None:
    delivery = tmp_path / "delivery"
    candidate = delivery / "candidate"
    candidate.mkdir(parents=True)
    descriptor = _descriptor(delivery, _compile_fixture(candidate))
    credential_name = "DEMO_" + "A" + "PI_KEY"
    credential_value = "value" + "-for-test"
    runtime = JavaManagedRuntime(
        backend=descriptor.backend,
        delivery_root=delivery,
        environment={credential_name: credential_value},
        health_timeout_seconds=10,
    )
    started = runtime.start(
        StartRequest(
            descriptor=descriptor,
            artifact_verification=verify_candidate_artifact(descriptor, delivery),
        )
    )
    assert started.handle is not None
    assert runtime.health(HealthRequest(handle=started.handle)).ready is True
    evidence = runtime.collect_evidence(EvidenceRequest(handle=started.handle))
    evidence_path = delivery / evidence.outcome.evidence_refs[0].locator
    runtime.stop(StopRequest(handle=started.handle))

    assert credential_value not in evidence_path.read_text(encoding="utf-8")
