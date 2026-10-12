"""Domain-neutral managed Python Candidate lifecycle tests."""

from __future__ import annotations

import hashlib
import json
import shutil
import sys
from pathlib import Path

import pytest

from finance_builder_base import ResourceKind
from finance_builder_base.candidate_backend import (
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
    PythonZipAppManagedRuntime,
    RuntimeIdentity,
    StartRequest,
    StopRequest,
    verify_candidate_artifact,
)


FIXTURE = Path(__file__).parent / "fixtures" / "candidate_service.py"


def _digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def _resource(locator: str, digest: str) -> CandidateResourceRef:
    return CandidateResourceRef(
        kind=ResourceKind.WORKSPACE_PATH,
        locator=locator,
        sha256=digest,
    )


def _descriptor(delivery_root: Path) -> CandidateDescriptor:
    candidate_dir = delivery_root / "candidate"
    candidate_dir.mkdir(parents=True)
    artifact = candidate_dir / "candidate.py"
    shutil.copy2(FIXTURE, artifact)
    digest = _digest(artifact)
    resource = _resource("candidate/candidate.py", digest)
    return CandidateDescriptor(
        task_id="task-python-runtime",
        candidate_id="candidate-python-runtime",
        backend=BackendIdentity(backend_id="python-managed", version="1.0"),
        language="python",
        runtime=RuntimeIdentity(family="python", version="3.13"),
        artifact=CandidateArtifact(
            artifact_id="candidate-script",
            artifact_kind="script",
            resource=resource,
        ),
        entrypoint=CandidateEntrypoint(mode="managed-artifact", resource=resource),
        invocation=ProtocolBinding(
            protocol="a2a",
            path="/a2a",
            media_type="application/json",
        ),
        health=ProtocolBinding(protocol="http", path="/health"),
        lock_digest="a" * 64,
        installed_asset_ids=("runtime",),
    )


@pytest.mark.parametrize(
    "schema_version",
    (
        "",
        " ",
        "candidate evidence.v1",
        "https://example.test/schema.v1",
        "api_" + "key=fixture-value",
        "a" * 129,
    ),
)
def test_managed_python_runtime_rejects_unsafe_evidence_schema_version(
    tmp_path: Path,
    schema_version: str,
) -> None:
    descriptor = _descriptor(tmp_path)

    with pytest.raises(ValueError, match="public-safe schema identifier"):
        PythonZipAppManagedRuntime(
            backend=descriptor.backend,
            delivery_root=tmp_path,
            python=sys.executable,
            evidence_schema_version=schema_version,
        )


def test_managed_python_runtime_completes_lifecycle_and_idempotent_stop(
    tmp_path: Path,
) -> None:
    descriptor = _descriptor(tmp_path)
    payload = tmp_path / "probe.json"
    payload.write_text('{"jsonrpc":"2.0","id":"probe"}', encoding="utf-8")
    runtime = PythonZipAppManagedRuntime(
        backend=descriptor.backend,
        delivery_root=tmp_path,
        python=sys.executable,
        health_timeout_seconds=10,
        request_timeout_seconds=5,
        evidence_schema_version="candidate-python-lifecycle-evidence.v1",
    )

    started = runtime.start(
        StartRequest(
            descriptor=descriptor,
            artifact_verification=verify_candidate_artifact(descriptor, tmp_path),
        )
    )
    assert started.outcome.status is LifecycleStatus.PASSED
    assert started.handle is not None
    assert runtime.health(HealthRequest(handle=started.handle)).ready is True

    invoked = runtime.invoke(
        InvocationRequest(
            request_id="probe",
            handle=started.handle,
            protocol="a2a",
            payload_ref=_resource("probe.json", _digest(payload)),
        )
    )
    assert invoked.outcome.status is LifecycleStatus.PASSED
    assert invoked.response_ref is not None
    evidence = runtime.collect_evidence(EvidenceRequest(handle=started.handle))
    assert evidence.outcome.status is LifecycleStatus.PASSED
    assert evidence.outcome.evidence_refs
    evidence_document = json.loads(
        (tmp_path / evidence.outcome.evidence_refs[0].locator).read_text(encoding="utf-8")
    )
    assert evidence_document["schemaVersion"] == "candidate-python-lifecycle-evidence.v1"

    assert runtime.stop(StopRequest(handle=started.handle)).stopped is True
    assert runtime.stop(StopRequest(handle=started.handle)).stopped is True


def test_health_failure_cleans_up_managed_python_process(tmp_path: Path) -> None:
    descriptor = _descriptor(tmp_path).model_copy(
        update={"health": ProtocolBinding(protocol="http", path="/missing")}
    )
    runtime = PythonZipAppManagedRuntime(
        backend=descriptor.backend,
        delivery_root=tmp_path,
        python=sys.executable,
        health_timeout_seconds=0.5,
    )
    started = runtime.start(
        StartRequest(
            descriptor=descriptor,
            artifact_verification=verify_candidate_artifact(descriptor, tmp_path),
        )
    )
    assert started.handle is not None

    failed = runtime.health(HealthRequest(handle=started.handle))

    assert failed.outcome.status is LifecycleStatus.FAILED
    assert runtime.stop(StopRequest(handle=started.handle)).stopped is True


def test_start_rejects_artifact_changed_after_request_validation(tmp_path: Path) -> None:
    descriptor = _descriptor(tmp_path)
    request = StartRequest(
        descriptor=descriptor,
        artifact_verification=verify_candidate_artifact(descriptor, tmp_path),
    )
    (tmp_path / descriptor.artifact.resource.locator).write_text(
        "tampered", encoding="utf-8"
    )
    runtime = PythonZipAppManagedRuntime(
        backend=descriptor.backend,
        delivery_root=tmp_path,
        python=sys.executable,
    )

    result = runtime.start(request)

    assert result.outcome.status is LifecycleStatus.FAILED
    assert result.handle is None


def test_protocol_mismatch_stops_instance_and_remains_idempotent(tmp_path: Path) -> None:
    descriptor = _descriptor(tmp_path)
    payload = tmp_path / "probe.json"
    payload.write_text('{"jsonrpc":"2.0","id":"probe"}', encoding="utf-8")
    runtime = PythonZipAppManagedRuntime(
        backend=descriptor.backend,
        delivery_root=tmp_path,
        python=sys.executable,
        health_timeout_seconds=10,
    )
    started = runtime.start(
        StartRequest(
            descriptor=descriptor,
            artifact_verification=verify_candidate_artifact(descriptor, tmp_path),
        )
    )
    assert started.handle is not None
    assert runtime.health(HealthRequest(handle=started.handle)).ready is True

    failed = runtime.invoke(
        InvocationRequest(
            request_id="probe",
            handle=started.handle,
            protocol="unsupported",
            payload_ref=_resource("probe.json", _digest(payload)),
        )
    )

    assert failed.outcome.status is LifecycleStatus.FAILED
    assert runtime.stop(StopRequest(handle=started.handle)).stopped is True
    assert runtime.stop(StopRequest(handle=started.handle)).stopped is True


def test_public_evidence_excludes_environment_values_and_host_path(tmp_path: Path) -> None:
    descriptor = _descriptor(tmp_path)
    marker = "private-fixture-value"
    runtime = PythonZipAppManagedRuntime(
        backend=descriptor.backend,
        delivery_root=tmp_path,
        python=sys.executable,
        environment={"DEMO_CREDENTIAL": marker},
    )
    started = runtime.start(
        StartRequest(
            descriptor=descriptor,
            artifact_verification=verify_candidate_artifact(descriptor, tmp_path),
        )
    )
    assert started.handle is not None

    evidence = runtime.collect_evidence(EvidenceRequest(handle=started.handle))
    evidence_path = tmp_path / evidence.outcome.evidence_refs[0].locator
    runtime.stop(StopRequest(handle=started.handle))
    public_text = evidence_path.read_text(encoding="utf-8")

    assert marker not in public_text
    assert str(tmp_path) not in public_text
